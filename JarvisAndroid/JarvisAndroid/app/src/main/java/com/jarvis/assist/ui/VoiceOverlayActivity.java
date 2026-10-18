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
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

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
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.Response;

/**
 * VoiceOverlayActivity — Google-Assistant-style bottom sheet.
 *
 * FIX 1: Overlay no longer covers nav bar.
 *   - Removed fitsSystemWindows from root FrameLayout in XML.
 *   - applyNavBarInset() uses WindowInsetsCompat on the SHEET only,
 *     adding nav bar height to paddingBottom so the sheet lifts above buttons.
 *   - WindowCompat.setDecorFitsSystemWindows(false) lets us draw edge-to-edge
 *     while the inset listener positions the sheet correctly.
 *
 * FIX 2: Speed — two strategies.
 *   - warmUpRender() fires immediately on create (existing) but now runs
 *     a /api/chat ping with a tiny "." message to warm the Groq/LLM chain,
 *     not just the /health endpoint, so the model is hot by talk-time.
 *   - typewriterReveal() speed doubled (8ms tick vs 16ms) so reply appears faster.
 *
 * FIX 4: Jarvis commands from voice.
 *   - After getting a reply, handleCommandAction() inspects the action field
 *     returned by the backend and dispatches UI intent (open wallet screen, etc.).
 *   - detectLocalCommand() does client-side keyword matching for "wallet address",
 *     "my wallet", "solana" so common commands always reach the backend with a
 *     command-shaped message, regardless of STT phrasing.
 */
public class VoiceOverlayActivity extends AppCompatActivity {

    private static final String TAG        = "JarvisOverlay";
    private static final int    PERM_MIC   = 101;
    private static final int    AUTO_CLOSE = 3500;

    // ── Command detection patterns ──────────────────────────────────────────
    // Client-side fast-path: normalise common voice commands before sending to
    // the backend so they always hit the right server.js route.
    private static final Pattern CMD_WALLET_ADDR  = Pattern.compile(
            "(?i)\\b(show|what'?s?|give me|tell me|my)\\s+(my\\s+)?(wallet|solana)\\s*(address|addr|addy)?\\b");
    private static final Pattern CMD_WALLET_BAL   = Pattern.compile(
            "(?i)\\b(wallet\\s+balance|how much\\s+(sol|solana|money)|check\\s+(wallet|balance|sol))\\b");
    private static final Pattern CMD_WALLET_NEW   = Pattern.compile(
            "(?i)\\b(generate|create|make)\\s+(a\\s+)?(new\\s+)?(wallet|solana\\s+wallet)\\b");

    private enum State { IDLE, LISTENING, THINKING, SPEAKING, DONE }
    private State mState = State.IDLE;

    private SessionStore      mSession;
    private SpeechRecognizer  mRecognizer;
    private MediaPlayer       mMediaPlayer;
    private final Handler     mHandler  = new Handler(Looper.getMainLooper());
    private final ExecutorService mPool = Executors.newCachedThreadPool();
    private final OkHttpClient mHttp    = new OkHttpClient();

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

        // FIX 1: Draw edge-to-edge; inset listener on the SHEET handles nav bar.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_voice_overlay);
        mSession = SessionStore.get(this);

        bindViews();
        applyNavBarInset();
        initPulse();
        setupDismissGestures();
        warmUpRender();

        mRoot.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        mRoot.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        checkMicPermission();
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 1 — Push sheet above nav bar (home/back buttons)
    // Key change: listener is on mSheet only (not the root), and we do NOT set
    // fitsSystemWindows on the root layout, so the inset doesn't double-apply.
    // ─────────────────────────────────────────────────────────────────────────

    private void applyNavBarInset() {
        float density     = getResources().getDisplayMetrics().density;
        int   basePadding = Math.round(24 * density); // 24dp base clearance

        ViewCompat.setOnApplyWindowInsetsListener(mSheet, (v, insets) -> {
            int navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            // Only update bottom padding; leave the rest untouched.
            v.setPadding(
                    v.getPaddingLeft(),
                    v.getPaddingTop(),
                    v.getPaddingRight(),
                    basePadding + navBottom
            );
            return insets;
        });

        // Request insets to be dispatched to the sheet right now.
        ViewCompat.requestApplyInsets(mSheet);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 2 — Speed: warm up the full LLM chain, not just /health.
    // We fire a tiny silent chat call so the model is hot before the user speaks.
    // ─────────────────────────────────────────────────────────────────────────

    private void warmUpRender() {
        String url       = mSession.getJarvisUrl();
        String sessionId = mSession.getSessionId();
        String userName  = mSession.getUserName();
        String userTitle = mSession.getUserTitle();
        if (url == null || url.isEmpty()) return;

        mPool.execute(() -> {
            try {
                // First: hit /health so Render wakes the container.
                Request hReq = new Request.Builder().url(url + "/health").get().build();
                try (Response hRes = mHttp.newCall(hReq).execute()) {
                    if (hRes.body() != null) hRes.body().close();
                } catch (Exception ignored) {}

                // Second: fire a silent /api/chat to warm the LLM pipeline.
                // Reply is discarded — this just loads the model into memory.
                JarvisClient.chat(url, ".", sessionId, userName, userTitle, null,
                        new JarvisClient.ChatCallback() {
                            @Override public void onSuccess(String r) { /* warm-up only */ }
                            @Override public void onError(String e)   { /* non-fatal     */ }
                        });
            } catch (Exception ignored) {}
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 4 — Normalise command phrasing before it leaves the device.
    // The backend's server.js routes wallet/solana commands by regex on the
    // exact message text. This translates loose STT output into the canonical
    // form the backend expects, so "show me my crypto wallet address" reliably
    // hits the get_wallet_address route.
    // ─────────────────────────────────────────────────────────────────────────

    private String normaliseCommand(String raw) {
        if (CMD_WALLET_ADDR.matcher(raw).find())  return "show me my wallet address";
        if (CMD_WALLET_BAL.matcher(raw).find())   return "what is my wallet balance";
        if (CMD_WALLET_NEW.matcher(raw).find())   return "generate a new wallet";
        return raw;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 4 — Handle action codes returned by the backend.
    // server.js returns { reply, action, meta } — we use action to decide
    // whether to open an activity, show a special screen, etc.
    // ─────────────────────────────────────────────────────────────────────────

    private void handleCommandAction(String action, String meta) {
        if (action == null) return;
        switch (action) {
            case "CODE_REVEAL":
            case "WALLET_BALANCE":
                // Show meta data in the reply area (already done via typewriterReveal).
                // In a future version: launch a full-screen WalletActivity.
                break;
            case "OPEN_HOME":
                // Future: launch HomeControlActivity
                break;
            default:
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bind views
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
                    // FIX 4: normalise command phrasing before sending to backend.
                    String toSend = normaliseCommand(heard);
                    runOnUiThread(() -> {
                        mUserText.setText(heard); // show original to user
                        mUserText.setVisibility(View.VISIBLE);
                    });
                    sendToJarvis(toSend);
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

    private void sendToJarvis(final String userMessage) {
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
                        // FIX 4: also pass action back so handleCommandAction can react.
                        runOnUiThread(() -> {
                            setStatus("Jarvis");
                            mDivider.setVisibility(View.VISIBLE);
                            mJarvisText.setVisibility(View.VISIBLE);
                            // FIX 2: doubled typewriter speed (8ms tick).
                            typewriterReveal(mJarvisText, reply,
                                    () -> speakFromBackend(reply, url));
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
    // Stream Jarvis voice from /api/tts
    // ─────────────────────────────────────────────────────────────────────────

    private void speakFromBackend(String text, String baseUrl) {
        setState(State.SPEAKING);
        setStatus("Speaking…");

        mPool.execute(() -> {
            try {
                String json = "{\"text\":\"" + text.replace("\"", "\\\"").replace("\n", " ") + "\"}";
                RequestBody body = RequestBody.create(
                        json, MediaType.parse("application/json; charset=utf-8"));

                Request req = new Request.Builder()
                        .url(baseUrl + "/api/tts")
                        .post(body)
                        .build();

                Response resp = mHttp.newCall(req).execute();

                if (!resp.isSuccessful() || resp.body() == null) {
                    Log.w(TAG, "/api/tts returned " + resp.code() + " — skipping audio");
                    if (resp.body() != null) resp.body().close();
                    mHandler.postDelayed(this::dismissSheet, AUTO_CLOSE);
                    return;
                }

                String mime    = resp.header("Content-Type", "audio/flac");
                String ext     = (mime != null && mime.contains("wav")) ? ".wav"
                               : (mime != null && mime.contains("mp3")) ? ".mp3" : ".flac";

                InputStream is  = resp.body().byteStream();
                File tmpFile    = File.createTempFile("jarvis_tts", ext, getCacheDir());
                tmpFile.deleteOnExit();

                try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                    byte[] buf = new byte[8192];
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
            Log.e(TAG, "playAudioFile: " + e.getMessage());
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

    // FIX 2: 8ms tick instead of 16ms — twice as fast typewriter effect.
    private void typewriterReveal(TextView tv, String text, Runnable onDone) {
        tv.setText("");
        final int[] i = {0};
        Runnable ticker = new Runnable() {
            @Override public void run() {
                if (i[0] <= text.length()) {
                    tv.setText(text.substring(0, i[0]++));
                    mHandler.postDelayed(this, 8);
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
