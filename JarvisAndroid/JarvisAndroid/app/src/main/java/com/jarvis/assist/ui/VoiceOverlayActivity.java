package com.jarvis.assist.ui;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.Response;

/**
 * VoiceOverlayActivity — Google-Assistant-style bottom sheet.
 * Streams Jarvis voice audio from Render backend instead of Android TTS.
 * Background keep-alive ping prevents Render cold starts.
 */
public class VoiceOverlayActivity extends AppCompatActivity {

    private static final String TAG        = "JarvisOverlay";
    private static final int    PERM_MIC   = 101;
    private static final int    AUTO_CLOSE = 3000;

    private enum State { IDLE, LISTENING, THINKING, SPEAKING, DONE }
    private State mState = State.IDLE;

    private SessionStore      mSession;
    private SpeechRecognizer  mRecognizer;
    private MediaPlayer       mMediaPlayer;
    private final Handler     mHandler   = new Handler(Looper.getMainLooper());
    private final ExecutorService mPool  = Executors.newCachedThreadPool();
    private final OkHttpClient mHttp     = new OkHttpClient();

    // Views
    private View      mRoot;
    private View      mScrim;
    private View      mSheet;
    private View      mPulseRing;
    private View      mDivider;
    private TextView  mBtnClose;
    private TextView  mStatusLabel;
    private TextView  mUserText;
    private TextView  mJarvisText;
    private ImageView mJarvisIcon;

    private ObjectAnimator mPulseAnim;
    private boolean        mDismissing = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON   |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_voice_overlay);
        mSession = SessionStore.get(this);

        bindViews();
        initPulse();
        setupDismissGestures();
        warmUpRender(); // ping backend so it's ready

        mRoot.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        mRoot.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        checkMicPermission();
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keep Render warm — fire a lightweight ping as soon as overlay opens
    // ─────────────────────────────────────────────────────────────────────────

    private void warmUpRender() {
        String url = mSession.getJarvisUrl();
        if (url == null || url.isEmpty()) return;
        mPool.execute(() -> {
            try {
                Request req = new Request.Builder()
                        .url(url + "/health")
                        .get()
                        .build();
                Response res = mHttp.newCall(req).execute();
                if (res.body() != null) res.body().close();
                Log.d(TAG, "Render warm-up ping sent");
            } catch (Exception e) {
                Log.d(TAG, "Warm-up ping failed (ok): " + e.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bind
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        mRoot        = findViewById(R.id.overlay_root);
        mScrim       = findViewById(R.id.scrim);
        mSheet       = findViewById(R.id.sheet);
        mPulseRing   = findViewById(R.id.pulse_ring);
        mDivider     = findViewById(R.id.divider);
        mBtnClose    = findViewById(R.id.btn_close);
        mStatusLabel = findViewById(R.id.tv_status);
        mUserText    = findViewById(R.id.tv_user_text);
        mJarvisText  = findViewById(R.id.tv_jarvis_reply);
        mJarvisIcon  = findViewById(R.id.iv_jarvis_icon);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dismiss gestures
    // ─────────────────────────────────────────────────────────────────────────

    private void setupDismissGestures() {
        mScrim.setOnClickListener(v -> dismissSheet());
        mBtnClose.setOnClickListener(v -> dismissSheet());

        GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (vy > 600) { dismissSheet(); return true; }
                        return false;
                    }
                });
        mSheet.setOnTouchListener((v, ev) -> { gd.onTouchEvent(ev); return false; });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pulse animation
    // ─────────────────────────────────────────────────────────────────────────

    private void initPulse() {
        mPulseAnim = ObjectAnimator.ofFloat(mPulseRing, "alpha", 0.2f, 0.9f);
        mPulseAnim.setDuration(700);
        mPulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        mPulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        mPulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
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
            mHandler.postDelayed(this::dismissSheet, 2000);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Speech recognition
    // ─────────────────────────────────────────────────────────────────────────

    private void startListening() {
        setState(State.LISTENING);
        setStatus("Listening…");
        mPulseRing.setVisibility(View.VISIBLE);
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
            @Override public void onEndOfSpeech() { setStatus("Thinking…"); }
            @Override public void onPartialResults(Bundle partial) {
                ArrayList<String> parts = partial.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (parts != null && !parts.isEmpty()) {
                    runOnUiThread(() -> {
                        mUserText.setText(parts.get(0));
                        mUserText.setVisibility(View.VISIBLE);
                    });
                }
            }
            @Override public void onResults(Bundle results) {
                if (mPulseAnim != null) mPulseAnim.cancel();
                mPulseRing.setAlpha(0f);
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String heard = matches.get(0);
                    runOnUiThread(() -> {
                        mUserText.setText(heard);
                        mUserText.setVisibility(View.VISIBLE);
                    });
                    sendToJarvis(heard);
                } else {
                    setStatus("Didn't catch that");
                    mHandler.postDelayed(VoiceOverlayActivity.this::dismissSheet, 2000);
                }
            }
            @Override public void onError(int err) {
                if (mPulseAnim != null) mPulseAnim.cancel();
                setStatus("Tap mic to retry");
                mHandler.postDelayed(VoiceOverlayActivity.this::dismissSheet, 2000);
            }
            @Override public void onEvent(int t, Bundle b) {}
        });

        mRecognizer.startListening(intent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Jarvis API
    // ─────────────────────────────────────────────────────────────────────────

    private void sendToJarvis(String userMessage) {
        setState(State.THINKING);
        setStatus("Thinking…");

        String url       = mSession.getJarvisUrl();
        String sessionId = mSession.getSessionId();
        String userName  = mSession.getUserName();
        String userTitle = mSession.getUserTitle();

        JarvisClient.fetchMemories(url, userName, memories ->
            JarvisClient.chat(url, userMessage, sessionId, userName, userTitle, memories,
                new JarvisClient.ChatCallback() {
                    @Override public void onSuccess(String reply) {
                        runOnUiThread(() -> {
                            setStatus("Jarvis");
                            mDivider.setVisibility(View.VISIBLE);
                            mJarvisText.setVisibility(View.VISIBLE);
                            typewriterReveal(mJarvisText, reply, () -> speakFromBackend(reply, url));
                        });
                    }
                    @Override public void onError(String error) {
                        runOnUiThread(() -> {
                            setStatus("Error — try again");
                            mHandler.postDelayed(
                                    VoiceOverlayActivity.this::dismissSheet, 3000);
                        });
                    }
                })
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stream Jarvis voice audio from backend /api/speak
    // Falls back to silence + auto-close if endpoint doesn't exist
    // ─────────────────────────────────────────────────────────────────────────

    private void speakFromBackend(String text, String baseUrl) {
        setState(State.SPEAKING);
        setStatus("Speaking…");

        mPool.execute(() -> {
            try {
                // POST to /api/speak — backend returns audio/mpeg bytes (ElevenLabs or similar)
                String json = "{\"text\":\"" + text.replace("\"", "\\\"") + "\"}";
                RequestBody body = RequestBody.create(
                        json, MediaType.parse("application/json; charset=utf-8"));

                Request req = new Request.Builder()
                        .url(baseUrl + "/api/speak")
                        .post(body)
                        .build();

                Response resp = mHttp.newCall(req).execute();

                if (!resp.isSuccessful() || resp.body() == null) {
                    Log.w(TAG, "speak endpoint returned " + resp.code() + " — skipping audio");
                    resp.close();
                    mHandler.postDelayed(this::dismissSheet, AUTO_CLOSE);
                    return;
                }

                // Write audio bytes to temp file then play with MediaPlayer
                InputStream is  = resp.body().byteStream();
                File tmpFile    = File.createTempFile("jarvis_audio", ".mp3", getCacheDir());
                tmpFile.deleteOnExit();

                try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                    byte[] buf = new byte[4096];
                    int    len;
                    while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
                }
                resp.body().close();

                mHandler.post(() -> playAudioFile(tmpFile));

            } catch (Exception e) {
                Log.e(TAG, "speakFromBackend error: " + e.getMessage());
                mHandler.postDelayed(this::dismissSheet, AUTO_CLOSE);
            }
        });
    }

    private void playAudioFile(File file) {
        try {
            if (mMediaPlayer != null) { mMediaPlayer.release(); mMediaPlayer = null; }

            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
            mMediaPlayer.setDataSource(file.getAbsolutePath());
            mMediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mMediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                mMediaPlayer = null;
                file.delete();
                mHandler.postDelayed(this::dismissSheet, AUTO_CLOSE);
            });
            mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what);
                mp.release();
                mMediaPlayer = null;
                mHandler.postDelayed(VoiceOverlayActivity.this::dismissSheet, AUTO_CLOSE);
                return true;
            });
            mMediaPlayer.prepareAsync();

        } catch (Exception e) {
            Log.e(TAG, "playAudioFile error: " + e.getMessage());
            mHandler.postDelayed(this::dismissSheet, AUTO_CLOSE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dismiss
    // ─────────────────────────────────────────────────────────────────────────

    private void dismissSheet() {
        if (mDismissing) return;
        mDismissing = true;
        setState(State.DONE);

        if (mMediaPlayer != null) { mMediaPlayer.stop(); mMediaPlayer.release(); mMediaPlayer = null; }

        mSheet.animate()
                .translationY(mSheet.getHeight())
                .alpha(0f)
                .setDuration(220)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        mScrim.animate().alpha(0f).setDuration(150)
                                .setListener(new AnimatorListenerAdapter() {
                                    @Override public void onAnimationEnd(Animator a2) {
                                        finish();
                                        overridePendingTransition(0, 0);
                                    }
                                });
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
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
                    mHandler.postDelayed(this, 16);
                } else if (onDone != null) {
                    onDone.run();
                }
            }
        };
        mHandler.post(ticker);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRecognizer  != null) { mRecognizer.destroy(); mRecognizer = null; }
        if (mMediaPlayer != null) { mMediaPlayer.release(); mMediaPlayer = null; }
        if (mPulseAnim   != null) { mPulseAnim.cancel(); }
        mPool.shutdownNow();
        mHandler.removeCallbacksAndMessages(null);
    }
}
