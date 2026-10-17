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

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Locale;

public class VoiceOverlayActivity extends AppCompatActivity {

    private static final String TAG        = "JarvisOverlay";
    private static final int    PERM_MIC   = 101;
    private static final int    AUTO_CLOSE = 3000;

    private enum State { IDLE, LISTENING, THINKING, SPEAKING, DONE }
    private State mState = State.IDLE;

    private SessionStore     mSession;
    private SpeechRecognizer mRecognizer;
    private TextToSpeech     mTTS;
    private final Handler    mHandler = new Handler(Looper.getMainLooper());

    private View      mRoot;
    private View      mPulseRing;
    private View      mCornerClose;
    private TextView  mStatusLabel;
    private TextView  mUserText;
    private TextView  mJarvisText;
    private ImageView mJarvisIcon;

    private ObjectAnimator mPulseAnim;
    private boolean        mTTSReady = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

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

        GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (vy > 800) { finishWithFade(); return true; }
                        return false;
                    }
                });
        mRoot.setOnTouchListener((v, ev) -> { gd.onTouchEvent(ev); return true; });
        mCornerClose.setOnClickListener(v -> finishWithFade());

        mRoot.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        mRoot.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        checkMicPermission();
                    }
                });
    }

    private void bindViews() {
        mRoot        = findViewById(R.id.root_overlay);
        mPulseRing   = findViewById(R.id.pulse_ring);
        mCornerClose = findViewById(R.id.btn_close);
        mStatusLabel = findViewById(R.id.tv_status);
        mUserText    = findViewById(R.id.tv_user_text);
        mJarvisText  = findViewById(R.id.tv_jarvis_text);
        mJarvisIcon  = findViewById(R.id.iv_jarvis_icon);
    }

    private void initPulse() {
        mPulseAnim = ObjectAnimator.ofFloat(mPulseRing, "alpha", 0.3f, 1f);
        mPulseAnim.setDuration(800);
        mPulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        mPulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        mPulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
    }

    private void initTTS() {
        mTTS = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                mTTS.setLanguage(Locale.US);
                mTTSReady = true;
                mTTS.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) {
                        mHandler.postDelayed(VoiceOverlayActivity.this::finishWithFade, AUTO_CLOSE);
                    }
                    @Override public void onError(String id) {
                        mHandler.postDelayed(VoiceOverlayActivity.this::finishWithFade, AUTO_CLOSE);
                    }
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission
    // ─────────────────────────────────────────────────────────────────────────

    private void checkMicPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERM_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PERM_MIC && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            setStatus("Mic permission denied");
            mHandler.postDelayed(this::finishWithFade, 2000);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Speech recognition
    // ─────────────────────────────────────────────────────────────────────────

    private void startListening() {
        setState(State.LISTENING);
        setStatus("Listening…");
        if (mPulseAnim != null) mPulseAnim.start();

        mRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        mRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { setStatus("Listening…"); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rms) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { setStatus("Processing…"); }
            @Override public void onPartialResults(Bundle partial) {
                ArrayList<String> parts = partial.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (parts != null && !parts.isEmpty()) mUserText.setText(parts.get(0));
            }
            @Override public void onResults(Bundle results) {
                if (mPulseAnim != null) mPulseAnim.cancel();
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String heard = matches.get(0);
                    mUserText.setText(heard);
                    sendToJarvis(heard);
                } else {
                    setStatus("Didn't catch that");
                    mHandler.postDelayed(VoiceOverlayActivity.this::finishWithFade, 2000);
                }
            }
            @Override public void onError(int err) {
                if (mPulseAnim != null) mPulseAnim.cancel();
                setStatus("Mic error — tap to retry");
                mHandler.postDelayed(VoiceOverlayActivity.this::finishWithFade, 2000);
            }
            @Override public void onEvent(int t, Bundle b) {}
        });

        mRecognizer.startListening(intent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Jarvis API call — fetch memories first, then chat
    // ─────────────────────────────────────────────────────────────────────────

    private void sendToJarvis(String userMessage) {
        setState(State.THINKING);
        setStatus("Jarvis is thinking…");

        String url       = mSession.getJarvisUrl();
        String sessionId = mSession.getSessionId();
        String userName  = mSession.getUserName();
        String userTitle = mSession.getUserTitle();

        // Step 1 — fetch memories so Jarvis has full recall, then chat
        JarvisClient.fetchMemories(url, userName, memories -> {
            JarvisClient.chat(
                    url,
                    userMessage,
                    sessionId,
                    userName,
                    userTitle,
                    memories,
                    new JarvisClient.ChatCallback() {
                        @Override
                        public void onSuccess(String reply) {
                            runOnUiThread(() -> {
                                mJarvisText.setText(reply);
                                mJarvisText.setVisibility(View.VISIBLE);
                                typewriterReveal(mJarvisText, reply,
                                        () -> speakReply(reply));
                            });
                        }
                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> {
                                setStatus("Error: " + error);
                                mHandler.postDelayed(
                                        VoiceOverlayActivity.this::finishWithFade, 3000);
                            });
                        }
                    }
            );
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TTS speak
    // ─────────────────────────────────────────────────────────────────────────

    private void speakReply(String text) {
        setState(State.SPEAKING);
        setStatus("Jarvis");
        if (mTTSReady && mTTS != null) {
            String ttsText = text.length() > 500 ? text.substring(0, 500) + "…" : text;
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis_reply");
            mTTS.speak(ttsText, TextToSpeech.QUEUE_FLUSH, params, "jarvis_reply");
        } else {
            mHandler.postDelayed(this::finishWithFade, AUTO_CLOSE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State + UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void setState(State s) { mState = s; }

    private void setStatus(String msg) {
        runOnUiThread(() -> { if (mStatusLabel != null) mStatusLabel.setText(msg); });
    }

    private void typewriterReveal(TextView tv, String text, Runnable onDone) {
        tv.setText("");
        final int[] i = {0};
        Runnable ticker = new Runnable() {
            @Override public void run() {
                if (i[0] <= text.length()) {
                    tv.setText(text.substring(0, i[0]++));
                    mHandler.postDelayed(this, 18);
                } else if (onDone != null) {
                    onDone.run();
                }
            }
        };
        mHandler.post(ticker);
    }

    private void finishWithFade() {
        setState(State.DONE);
        mRoot.animate()
                .alpha(0f)
                .setDuration(300)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) { finish(); }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRecognizer != null) { mRecognizer.destroy(); mRecognizer = null; }
        if (mTTS != null)        { mTTS.stop(); mTTS.shutdown(); mTTS = null; }
        if (mPulseAnim != null)  { mPulseAnim.cancel(); }
        mHandler.removeCallbacksAndMessages(null);
    }
}
