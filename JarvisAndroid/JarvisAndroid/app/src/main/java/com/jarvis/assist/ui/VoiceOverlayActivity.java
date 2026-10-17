package com.jarvis.assist.ui;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.jarvis.assist.R;
import com.jarvis.assist.network.JarvisClient;
import com.jarvis.assist.storage.SessionStore;

import java.util.ArrayList;
import java.util.Locale;

/**
 * VoiceOverlayActivity
 *
 * The full-screen translucent HUD that appears on home-hold.
 *
 * Flow:
 *   1. Activity opens → mic activates immediately
 *   2. STT listens → shows transcript in real-time
 *   3. On final result → POST to Jarvis /api/chat
 *   4. Response comes back → TTS speaks it, text shown
 *   5. Activity dims and finishes after response
 *
 * State machine:
 *   IDLE → LISTENING → THINKING → SPEAKING → DONE
 */
public class VoiceOverlayActivity extends AppCompatActivity {

    private static final String TAG        = "JarvisOverlay";
    private static final int    PERM_MIC   = 101;
    private static final int    AUTO_CLOSE = 3000; // ms after TTS done

    // ── State ────────────────────────────────────────────────────────────────
    private enum State { IDLE, LISTENING, THINKING, SPEAKING, DONE }
    private State mState = State.IDLE;

    // ── Dependencies ─────────────────────────────────────────────────────────
    private SessionStore     mSession;
    private SpeechRecognizer mRecognizer;
    private TextToSpeech     mTTS;
    private final Handler    mHandler = new Handler(Looper.getMainLooper());

    // ── Views ─────────────────────────────────────────────────────────────────
    private View     mRoot;
    private View     mPulseRing;
    private View     mCornerClose;
    private TextView mStatusLabel;
    private TextView mUserText;
    private TextView mJarvisText;
    private ImageView mJarvisIcon;

    // ── Pulse animation ───────────────────────────────────────────────────────
    private ObjectAnimator mPulseAnim;

    // ── TTS init flag ─────────────────────────────────────────────────────────
    private boolean mTTSReady = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        // Keep screen on during interaction
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_voice_overlay);
        mSession = SessionStore.get(this);

        bindViews();
        initPulse();
        initTTS();

        // Swipe down to dismiss
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (vy > 600) finishWithFade();
                return true;
            }
        });
        mRoot.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
        mCornerClose.setOnClickListener(v -> finishWithFade());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View wiring
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        mRoot        = findViewById(R.id.overlay_root);
        mPulseRing   = findViewById(R.id.pulse_ring);
        mCornerClose = findViewById(R.id.btn_close);
        mStatusLabel = findViewById(R.id.tv_status);
        mUserText    = findViewById(R.id.tv_user_text);
        mJarvisText  = findViewById(R.id.tv_jarvis_reply);
        mJarvisIcon  = findViewById(R.id.iv_jarvis_icon);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TTS init
    // ─────────────────────────────────────────────────────────────────────────

    private void initTTS() {
        mTTS = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                mTTS.setLanguage(Locale.US);
                mTTS.setPitch(0.92f);
                mTTS.setSpeechRate(0.95f);
                mTTS.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) {
                        runOnUiThread(() -> {
                            setState(State.DONE);
                            mHandler.postDelayed(VoiceOverlayActivity.this::finishWithFade, AUTO_CLOSE);
                        });
                    }
                    @Override public void onError(String id) {
                        runOnUiThread(VoiceOverlayActivity.this::finishWithFade);
                    }
                });
                mTTSReady = true;
                // TTS ready — start listening
                checkPermAndListen();
            } else {
                Log.e(TAG, "TTS init failed: " + status);
                checkPermAndListen(); // still try mic without TTS
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission → STT
    // ─────────────────────────────────────────────────────────────────────────

    private void checkPermAndListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERM_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PERM_MIC && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            setStatus("Mic permission denied");
            mHandler.postDelayed(this::finishWithFade, 1500);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Speech Recognition
    // ─────────────────────────────────────────────────────────────────────────

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition unavailable");
            mHandler.postDelayed(this::finishWithFade, 2000);
            return;
        }

        setState(State.LISTENING);

        mRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        mRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle params) {
                setStatus("Listening…");
                startPulse();
            }

            @Override
            public void onBeginningOfSpeech() {
                setStatus("Hearing you…");
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                // Scale pulse ring with volume
                if (mPulseAnim != null) {
                    float scale = 1f + Math.max(0, rmsdB / 12f) * 0.5f;
                    mPulseRing.setScaleX(scale);
                    mPulseRing.setScaleY(scale);
                }
            }

            @Override
            public void onPartialResults(Bundle partial) {
                ArrayList<String> results = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (results != null && !results.isEmpty()) {
                    mUserText.setText(results.get(0));
                    mUserText.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onResults(Bundle results) {
                stopPulse();
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String spoken = matches.get(0);
                    mUserText.setText(spoken);
                    mUserText.setVisibility(View.VISIBLE);
                    sendToJarvis(spoken);
                } else {
                    setStatus("Didn't catch that…");
                    mHandler.postDelayed(VoiceOverlayActivity.this::startListening, 800);
                }
            }

            @Override public void onError(int error) {
                stopPulse();
                Log.w(TAG, "STT error: " + error);
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    setStatus("Didn't hear anything — tap mic to retry");
                    mJarvisIcon.setOnClickListener(v -> startListening());
                } else {
                    setStatus("Mic error " + error);
                    mHandler.postDelayed(VoiceOverlayActivity.this::finishWithFade, 2500);
                }
            }

            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { setStatus("Processing…"); }
            @Override public void onEvent(int t, Bundle b) {}
        });

        Intent listenIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        listenIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,     RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        listenIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,           Locale.US.toString());
        listenIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,    true);
        listenIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,        1);
        listenIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L);
        mRecognizer.startListening(listenIntent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Jarvis API call
    // ─────────────────────────────────────────────────────────────────────────

    private void sendToJarvis(String userMessage) {
        setState(State.THINKING);
        setStatus("Jarvis is thinking…");

        JarvisClient.sendMessage(
                mSession.getJarvisUrl(),
                userMessage,
                mSession.getSessionId(),
                mSession.getUserName(),
                mSession.getUserTitle(),
                mSession.getTimezone(),
                new JarvisClient.ChatCallback() {
                    @Override
                    public void onSuccess(String reply) {
                        runOnUiThread(() -> {
                            mJarvisText.setText(reply);
                            mJarvisText.setVisibility(View.VISIBLE);
                            typewriterReveal(mJarvisText, reply, () -> speakReply(reply));
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            setStatus("Error: " + error);
                            mHandler.postDelayed(VoiceOverlayActivity.this::finishWithFade, 3000);
                        });
                    }
                }
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TTS speak
    // ─────────────────────────────────────────────────────────────────────────

    private void speakReply(String text) {
        setState(State.SPEAKING);
        setStatus("Jarvis");
        if (mTTSReady && mTTS != null) {
            // Trim to 500 chars for TTS — show full text on screen
            String ttsText = text.length() > 500 ? text.substring(0, 500) + "…" : text;
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis_reply");
            mTTS.speak(ttsText, TextToSpeech.QUEUE_FLUSH, params, "jarvis_reply");
        } else {
            // No TTS — just auto-close after reading time
            int readMs = Math.min(text.length() * 60, 8000);
            mHandler.postDelayed(this::finishWithFade, readMs);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Typewriter reveal
    // ─────────────────────────────────────────────────────────────────────────

    private void typewriterReveal(TextView tv, String text, Runnable onComplete) {
        tv.setText("");
        tv.setVisibility(View.VISIBLE);
        final int[] i = {0};
        Runnable ticker = new Runnable() {
            @Override public void run() {
                if (i[0] <= text.length()) {
                    tv.setText(text.substring(0, i[0]));
                    i[0]++;
                    mHandler.postDelayed(this, 18);
                } else if (onComplete != null) {
                    onComplete.run();
                }
            }
        };
        mHandler.post(ticker);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pulse animation
    // ─────────────────────────────────────────────────────────────────────────

    private void initPulse() {
        mPulseAnim = ObjectAnimator.ofFloat(mPulseRing, "alpha", 0.2f, 0.8f);
        mPulseAnim.setDuration(700);
        mPulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        mPulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        mPulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
    }

    private void startPulse() {
        mPulseRing.setVisibility(View.VISIBLE);
        if (mPulseAnim != null && !mPulseAnim.isRunning()) mPulseAnim.start();
    }

    private void stopPulse() {
        if (mPulseAnim != null) mPulseAnim.cancel();
        mPulseRing.setVisibility(View.INVISIBLE);
        mPulseRing.setScaleX(1f);
        mPulseRing.setScaleY(1f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State machine
    // ─────────────────────────────────────────────────────────────────────────

    private void setState(State s) {
        mState = s;
        switch (s) {
            case IDLE:      setStatus("Hold to speak"); break;
            case LISTENING: setStatus("Listening…");   break;
            case THINKING:  setStatus("Thinking…");    break;
            case SPEAKING:  setStatus("Jarvis");        break;
            case DONE:      setStatus("Done");          break;
        }
    }

    private void setStatus(String msg) {
        if (mStatusLabel != null) mStatusLabel.setText(msg);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dismiss
    // ─────────────────────────────────────────────────────────────────────────

    private void finishWithFade() {
        mRoot.animate()
                .alpha(0f)
                .setDuration(300)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        releaseAll();
                        finish();
                        overridePendingTransition(0, 0);
                    }
                }).start();
    }

    private void releaseAll() {
        mHandler.removeCallbacksAndMessages(null);
        if (mRecognizer != null) { try { mRecognizer.destroy(); } catch (Exception ignored) {} mRecognizer = null; }
        if (mTTS != null)        { try { mTTS.stop(); mTTS.shutdown(); } catch (Exception ignored) {} mTTS = null; }
        mTTSReady = false;
    }
}
