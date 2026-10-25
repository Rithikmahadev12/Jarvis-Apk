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

import com.jarvis.assist.PhoneControlService;
import com.jarvis.assist.R;
import com.jarvis.assist.network.JarvisClient;
import com.jarvis.assist.storage.SessionStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * VoiceOverlayActivity — Google-Assistant-style bottom sheet.
 *
 * FIX 1 — Overlay no longer covers nav bar.
 * FIX 2 — Speed: warmUpRender fires a silent ping; typewriter at 8ms.
 * FIX 3 — Phone control plan JSON correctly unwrapped from server {ok,plan} wrapper.
 * FIX 4 — All Jarvis commands work from voice via normaliseCommand().
 */
public class VoiceOverlayActivity extends AppCompatActivity {

    private static final String TAG        = "JarvisOverlay";
    private static final int    PERM_MIC   = 101;
    private static final int    AUTO_CLOSE = 3500;

    // ═══════════════════════════════════════════════════════════════════════
    // COMMAND NORMALISER
    // ═══════════════════════════════════════════════════════════════════════
    private static final LinkedHashMap<Pattern, String> CMD_MAP = new LinkedHashMap<>();
    static {
        // ── WALLET ──────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(show|what.s|give me|tell me|my).{0,10}(wallet|solana).{0,10}(address|addr|addy)?"),
                "show me my wallet address");
        CMD_MAP.put(pat("(wallet balance|how much sol|check (wallet|balance|sol)|my balance)"),
                "check my wallet balance");
        CMD_MAP.put(pat("(generate|create|make).{0,10}(new.{0,5})?(wallet|solana wallet)"),
                "generate a new solana wallet");
        CMD_MAP.put(pat("(send|transfer).{0,20}(sol|solana|crypto|coin)"),
                "send crypto");
        CMD_MAP.put(pat("(private key|secret key|wallet key)"),
                "show me my wallet private key");
        CMD_MAP.put(pat("(refresh|update|new).{0,10}owner wallet"),
                "refresh owner wallet");

        // ── WEATHER ─────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(weather|forecast|temperature|how.s the weather|is it (hot|cold|raining|sunny)|what.s it like outside)"),
                "what is the weather");

        // ── TIMER / REMINDER ─────────────────────────────────────────────────
        CMD_MAP.put(pat("set.{0,10}timer"),                        "set a timer");
        CMD_MAP.put(pat("(remind me|set.{0,10}reminder|reminder for)"), "set a reminder");
        CMD_MAP.put(pat("cancel.{0,10}(timer|reminder|alarm)"),    "cancel reminder");

        // ── MUSIC ───────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(play|put on|queue).{0,40}(music|song|artist|album|playlist|track|beats?)"), "play music");
        CMD_MAP.put(pat("(stop|pause).{0,10}(music|song|playing|audio)"),  "stop music");
        CMD_MAP.put(pat("(next|skip).{0,10}(song|track)"),                 "skip song");
        CMD_MAP.put(pat("(spotify|switch.{0,10}platform|change.{0,10}music)"), "change music platform");

        // ── EMAIL ────────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(check|read|open|show).{0,15}(email|inbox|gmail|mail)"), "check my email");
        CMD_MAP.put(pat("(read|open).{0,10}(first|that|the).{0,10}(email|message|one)"), "read the first email");
        CMD_MAP.put(pat("create.{0,15}(agent email|burner email|temp email|inbox)"), "create an agent email");
        CMD_MAP.put(pat("check.{0,10}agent email"), "check agent email");

        // ── CALENDAR ─────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(check|show|what.s on|my).{0,15}(calendar|schedule|agenda|events|appointments?)"), "check my calendar");

        // ── NEWS ─────────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(world news|news dashboard|open.{0,10}news|show.{0,10}news|latest headlines|top headlines|what.s happening|catch me up on the news|what.s going on in the world)"), "show me the news");
        CMD_MAP.put(pat("news widget"), "news widget");

        // ── CAMERA ───────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(hide|close|turn off|disable|exit).{0,10}camera"),     "hide camera");
        CMD_MAP.put(pat("(show|open|turn on|enable|activate|pull up).{0,10}camera"), "show camera");
        CMD_MAP.put(pat("switch camera|camera \\d|change camera|use camera"),   "switch camera");
        CMD_MAP.put(pat("(read|what.s on|analyze|scan).{0,10}screen"),          "read my screen");

        // ── RECORDING ────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(start|begin).{0,15}record(ing)?"),       "start recording");
        CMD_MAP.put(pat("(stop|end|finish).{0,15}record(ing)?"),   "stop recording");
        CMD_MAP.put(pat("(clip|save).{0,15}last.{0,15}(seconds?|secs?)"), "clip last 30 seconds");

        // ── SMART HOME ───────────────────────────────────────────────────────
        CMD_MAP.put(pat("(smart home|home panel|home control|open home|show home)"), "open home control panel");
        CMD_MAP.put(pat("(turn on|turn off|switch on|switch off|dim|brighten).{0,30}(lights?|plug|lamp|fan|thermostat|device)"), "control home lights");
        CMD_MAP.put(pat("(scan home|scan network|devices on network)"), "scan home network");
        CMD_MAP.put(pat("(enable|turn on|activate).{0,10}home talk"),   "enable home talk");
        CMD_MAP.put(pat("(disable|turn off|deactivate).{0,10}home talk"), "disable home talk");

        // ── MUTE / UNMUTE ────────────────────────────────────────────────────
        CMD_MAP.put(pat("(unmute|start talking|speak again)"), "unmute");
        CMD_MAP.put(pat("(mute|stop talking|be quiet|silence jarvis)"), "mute");

        // ── SECURITY ─────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(scan|check).{0,20}(virus|malware|threats?|hacker|infected|security)"), "scan my computer for threats");
        CMD_MAP.put(pat("(neutralize|remove|kill).{0,15}(threat|virus|malware)"), "neutralize threat");

        // ── PC / SANDBOX ─────────────────────────────────────────────────────
        CMD_MAP.put(pat("(close|shut down|power down|kill).{0,10}all.{0,10}(pcs?|computers?|sandboxes?)"), "close all your pcs");
        CMD_MAP.put(pat("(close|shut down|power down|end).{0,15}(your |the |my )?(pc|computer|desktop|sandbox)"), "close your pc");
        CMD_MAP.put(pat("(show|open|pull up|let me see).{0,15}(your )?(pc|computer|desktop|sandbox)"), "show pc");
        CMD_MAP.put(pat("(check|how much).{0,20}(disk|storage|space|drive|hard drive)"), "check disk space");
        CMD_MAP.put(pat("run.{0,30}(on your computer|in the sandbox|in sandbox|on your pc|in your vm)"), "run in sandbox");
        CMD_MAP.put(pat("(run|execute|terminal).{0,30}command"), "run computer command");
        CMD_MAP.put(pat("(open|launch).{0,30}(vs ?code|chrome|firefox|notepad|excel|spotify|teams|app|application|file|folder)"), "open on computer");
        CMD_MAP.put(pat("type (this|it|that)"), "type text");

        // ── RESEARCH / LOOKUP ─────────────────────────────────────────────────
        CMD_MAP.put(pat("(look up|background check|pull everything on|osint|intel on|find info on)"), "look up");
        CMD_MAP.put(pat("(research|open research|pull up research|find.{0,20}ways to (earn|make money)|how can you earn)"), "find ways to earn");

        // ── BOARDS ────────────────────────────────────────────────────────────
        CMD_MAP.put(pat("make.{0,10}(me.{0,5})?a board.{0,20}(on|about|for|of)"), "make a board");
        CMD_MAP.put(pat("(pull up|show|open|bring up).{0,10}board"), "show board");
        CMD_MAP.put(pat("(list|what).{0,10}boards"), "list boards");
        CMD_MAP.put(pat("(delete|forget|remove).{0,10}board"), "forget board");

        // ── MEMORY ────────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(remember that|memorize|save that|note that|i want you to remember)"), "remember that");
        CMD_MAP.put(pat("(forget|delete memory|clear memory|stop remembering|erase that)"), "forget that");

        // ── BUILD / HOLOGRAM / WIREFRAME ──────────────────────────────────────
        CMD_MAP.put(pat("(blueprint|drafting table|cad mode|design table|let.s design|sketch out|draft something)"), "open blueprint mode");
        CMD_MAP.put(pat("(show me a (3d|hologram)|holographic|3d model|3d scan)"), "show me a hologram");
        CMD_MAP.put(pat("wireframe"), "show wireframe");

        // ── HUD ───────────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(show hud|pull up hud|open hud|solve|calculate|annotate screen)"), "show hud");
        CMD_MAP.put(pat("(hide hud|close hud|dismiss hud|hud off)"), "hide hud");

        // ── TEAMS / CALLS ─────────────────────────────────────────────────────
        CMD_MAP.put(pat("(call|ring|dial|video call|voice call).{1,40}on teams"), "call on teams");
        CMD_MAP.put(pat("(message|text|dm).{0,30}on teams"), "message on teams");
        CMD_MAP.put(pat("join.{0,20}teams meeting"), "join teams meeting");
        CMD_MAP.put(pat("(call|ring|dial|book|schedule).{1,30}(appointment|business)"), "call business appointment");

        // ── BRIEFING / DEBRIEF ────────────────────────────────────────────────
        CMD_MAP.put(pat("(daily brief|brief me|morning briefing|today.s briefing|my briefing)"), "daily briefing");
        CMD_MAP.put(pat("(end of day|evening debrief|wrap up my day|debrief me|how did today go)"), "end of day debrief");

        // ── FOCUS MODE ────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(i.m back|focus mode off|stop focus|end focus|resurfacing|coming up for air)"), "focus mode off");
        CMD_MAP.put(pat("(heads down|focus mode on|going dark|deep work mode|start focus)"), "focus mode on");

        // ── SYSTEM ────────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(system status|diagnostics|all systems|health check|are you ok)"), "system status");
        CMD_MAP.put(pat("(log out|logout|sign out|goodbye|shut down jarvis|close session)"), "log out");

        // ── EARNING ───────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(earn status|how.s earning|how much have you earned|earning update)"), "earn status");
        CMD_MAP.put(pat("(improve earning|earn more|earn better)"), "improve earning");
        CMD_MAP.put(pat("(scan github|github bounties|find bounties)"), "scan github bounties");

        // ── INSTAGRAM ─────────────────────────────────────────────────────────
        CMD_MAP.put(pat("(connect|link|set up|track|follow|add).{0,15}instagram"), "connect instagram");
    }

    // ── PHONE CONTROL pattern — matched BEFORE CMD_MAP ───────────────────────
    private static final Pattern PHONE_CTRL_PAT = Pattern.compile(
        "(?i)\\b(?:" +
        "(go on|open|launch|pull up|switch to|jump on|get on)" +
        ".{0,25}" +
        "(instagram|youtube|tiktok|spotify|chrome|maps|google maps|whatsapp|telegram|gmail|settings|" +
        "twitter|reddit|snapchat|netflix|amazon|doordash|uber|lyft|messages|contacts|camera|files|clock|calculator|play store)" +
        "|" +
        "(search for|search|look for|find|browse|watch|scroll through|go look for)" +
        ".{0,40}" +
        "(on|in|using|via|through)" +
        ".{0,15}" +
        "(instagram|youtube|tiktok|spotify|chrome|twitter|reddit|netflix|amazon|maps|whatsapp)" +
        "|" +
        "(go back on (my )?phone|scroll down on (my )?phone|press (back|home) on (my )?phone)" +
        ")\\b"
    );

    private boolean isPhoneControlCommand(String raw) {
        return PHONE_CTRL_PAT.matcher(raw).find();
    }

    private static Pattern pat(String re) {
        return Pattern.compile("(?i)\\b(?:" + re + ")\\b");
    }

    private String normaliseCommand(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        for (Map.Entry<Pattern, String> entry : CMD_MAP.entrySet()) {
            if (entry.getKey().matcher(lower).find()) {
                Log.d(TAG, "CMD normalised: \"" + raw + "\" → \"" + entry.getValue() + "\"");
                return entry.getValue();
            }
        }
        return raw;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private enum State { IDLE, LISTENING, THINKING, SPEAKING, DONE }
    private State mState = State.IDLE;

    private SessionStore      mSession;
    private SpeechRecognizer  mRecognizer;
    private MediaPlayer       mMediaPlayer;
    private final Handler     mHandler  = new Handler(Looper.getMainLooper());
    private final ExecutorService mPool = Executors.newCachedThreadPool();
    private final OkHttpClient mHttp    = new OkHttpClient();

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

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON   |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

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

    private void applyNavBarInset() {
        float density     = getResources().getDisplayMetrics().density;
        int   basePadding = Math.round(24 * density);

        ViewCompat.setOnApplyWindowInsetsListener(mSheet, (v, insets) -> {
            int navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(
                    v.getPaddingLeft(),
                    v.getPaddingTop(),
                    v.getPaddingRight(),
                    basePadding + navBottom
            );
            return insets;
        });
        ViewCompat.requestApplyInsets(mSheet);
    }

    private void warmUpRender() {
        String url       = mSession.getJarvisUrl();
        String sessionId = mSession.getSessionId();
        String userName  = mSession.getUserName();
        String userTitle = mSession.getUserTitle();
        if (url == null || url.isEmpty()) return;

        mPool.execute(() -> {
            try {
                Request hReq = new Request.Builder().url(url + "/health").get().build();
                try (Response hRes = mHttp.newCall(hReq).execute()) {
                    if (hRes.body() != null) hRes.body().close();
                }
            } catch (Exception ignored) {}

            JarvisClient.chat(url, ".", sessionId, userName, userTitle, null,
                    new JarvisClient.ChatCallback() {
                        @Override public void onSuccess(String r) { }
                        @Override public void onError(String e)   { }
                    });
        });
    }

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

    private void initPulse() {
        mPulseAnim = ObjectAnimator.ofFloat(mPulseRing, "alpha", 0.2f, 0.9f);
        mPulseAnim.setDuration(700);
        mPulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        mPulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        mPulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
    }

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
            @Override public void onEndOfSpeech()            { setStatus("Thinking…"); }

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
                    String heard  = matches.get(0);
                    runOnUiThread(() -> {
                        mUserText.setText(heard);
                        mUserText.setVisibility(View.VISIBLE);
                    });

                    if (isPhoneControlCommand(heard) && PhoneControlService.getInstance() != null) {
                        sendToPhoneControl(heard);
                        return;
                    }

                    String toSend = normaliseCommand(heard);
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
    // Phone Control — FIX: properly unwrap { ok: true, plan: { steps: [...] } }
    // ─────────────────────────────────────────────────────────────────────────

    private void sendToPhoneControl(final String command) {
        setState(State.THINKING);
        setStatus("Planning…");

        String url       = mSession.getJarvisUrl();
        String sessionId = mSession.getSessionId();
        String userName  = mSession.getUserName();
        String userTitle = mSession.getUserTitle();
        if (url == null || url.isEmpty()) {
            setStatus("No Jarvis URL set");
            mHandler.postDelayed(this::dismissSheet, 2500);
            return;
        }

        mPool.execute(() -> {
            try {
                okhttp3.OkHttpClient http = new okhttp3.OkHttpClient.Builder()
                        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                        .build();

                org.json.JSONObject bodyJson = new org.json.JSONObject();
                bodyJson.put("command",   command);
                bodyJson.put("sessionId", sessionId);
                bodyJson.put("userName",  userName);
                bodyJson.put("userTitle", userTitle);

                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(url + "/api/phone-control/plan")
                        .post(okhttp3.RequestBody.create(
                                bodyJson.toString(),
                                okhttp3.MediaType.parse("application/json")))
                        .build();

                try (okhttp3.Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        runOnUiThread(() -> sendToJarvis(command));
                        return;
                    }

                    String rawBody = resp.body().string();

                    // ── FIX: SERVER WRAPS IN { ok: true, plan: {...} } ──────
                    // Pull the nested plan object out and re-serialize it so
                    // executePlan() can find { steps: [...] } at the top level.
                    String planJson;
                    try {
                        JSONObject wrapper = new JSONObject(rawBody);
                        if (wrapper.has("plan")) {
                            // Server returned { ok: true, plan: { steps: [...] } }
                            planJson = wrapper.getJSONObject("plan").toString();
                        } else if (wrapper.has("steps")) {
                            // Server returned plan directly at root (future-proof)
                            planJson = rawBody;
                        } else {
                            Log.e(TAG, "sendToPhoneControl: unexpected plan shape — " + rawBody.substring(0, Math.min(200, rawBody.length())));
                            runOnUiThread(() -> sendToJarvis(command));
                            return;
                        }
                    } catch (Exception parseEx) {
                        Log.e(TAG, "sendToPhoneControl: JSON parse failed — " + parseEx.getMessage());
                        runOnUiThread(() -> sendToJarvis(command));
                        return;
                    }

                    PhoneControlService svc = PhoneControlService.getInstance();
                    if (svc == null) {
                        runOnUiThread(() -> {
                            setStatus("Enable Accessibility Service");
                            typewriterReveal(mJarvisText,
                                "I need Accessibility permission to control your phone. " +
                                "Go to Settings → Accessibility → Jarvis and enable it.",
                                () -> mHandler.postDelayed(this::dismissSheet, 5000));
                            mDivider.setVisibility(View.VISIBLE);
                            mJarvisText.setVisibility(View.VISIBLE);
                        });
                        return;
                    }

                    runOnUiThread(() -> setStatus("Executing…"));

                    final String finalPlanJson = planJson;
                    svc.executePlan(finalPlanJson, url, new PhoneControlService.ExecutionCallback() {
                        @Override
                        public void onStepStart(int index, int total, String description) {
                            runOnUiThread(() ->
                                setStatus("Step " + index + "/" + total + ": " + description));
                        }

                        @Override
                        public void onSuccess(String summary) {
                            String reply = "Done, " + (userTitle != null ? userTitle : "boss") +
                                           ". " + summary + " — complete.";
                            runOnUiThread(() -> {
                                setStatus("Jarvis");
                                mDivider.setVisibility(View.VISIBLE);
                                mJarvisText.setVisibility(View.VISIBLE);
                                typewriterReveal(mJarvisText, reply,
                                    () -> speakFromBackend(reply, url));
                            });
                        }

                        @Override
                        public void onFailure(String step, String reason) {
                            String reply = "Ran into trouble at " + step + ", " +
                                           (userTitle != null ? userTitle : "boss") + ". " + reason;
                            runOnUiThread(() -> {
                                setStatus("Jarvis");
                                mDivider.setVisibility(View.VISIBLE);
                                mJarvisText.setVisibility(View.VISIBLE);
                                typewriterReveal(mJarvisText, reply,
                                    () -> speakFromBackend(reply, url));
                            });
                        }
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "sendToPhoneControl: " + e.getMessage());
                runOnUiThread(() -> sendToJarvis(command));
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Send to Jarvis backend
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
                new JarvisClient.ChatCallbackFull() {

                    @Override public void onSuccess(String reply) {
                        runOnUiThread(() -> {
                            setStatus("Jarvis");
                            mDivider.setVisibility(View.VISIBLE);
                            mJarvisText.setVisibility(View.VISIBLE);
                            typewriterReveal(mJarvisText, reply,
                                    () -> speakFromBackend(reply, url));
                        });
                    }

                    @Override public void onPhoneControlPlan(String reply, String planJson) {
                        // ── FIX: also unwrap here in case backend inlines plan ──
                        String resolvedPlan = planJson;
                        try {
                            JSONObject wrapper = new JSONObject(planJson);
                            if (wrapper.has("plan")) {
                                resolvedPlan = wrapper.getJSONObject("plan").toString();
                            }
                        } catch (Exception ignored) { /* already unwrapped */ }

                        final String finalPlan = resolvedPlan;

                        PhoneControlService svc = PhoneControlService.getInstance();
                        if (svc == null) {
                            runOnUiThread(() -> {
                                setStatus("Enable Accessibility Service");
                                typewriterReveal(mJarvisText,
                                    "I need Accessibility permission to control your phone. " +
                                    "Go to Settings → Accessibility → Jarvis and enable it.",
                                    () -> mHandler.postDelayed(
                                            VoiceOverlayActivity.this::dismissSheet, 5000));
                                mDivider.setVisibility(View.VISIBLE);
                                mJarvisText.setVisibility(View.VISIBLE);
                            });
                            return;
                        }

                        runOnUiThread(() -> {
                            setStatus("Executing…");
                            if (!reply.isEmpty() && !reply.equals("On it, " + userTitle + ".")) {
                                mDivider.setVisibility(View.VISIBLE);
                                mJarvisText.setVisibility(View.VISIBLE);
                                mJarvisText.setText(reply);
                            }
                        });

                        svc.executePlan(finalPlan, url, new PhoneControlService.ExecutionCallback() {
                            @Override
                            public void onStepStart(int index, int total, String description) {
                                runOnUiThread(() ->
                                    setStatus("Step " + index + "/" + total + ": " + description));
                            }
                            @Override
                            public void onSuccess(String summary) {
                                String doneReply = "Done, " + (userTitle != null ? userTitle : "boss") +
                                                   ". " + summary + " — complete.";
                                runOnUiThread(() -> {
                                    setStatus("Jarvis");
                                    mDivider.setVisibility(View.VISIBLE);
                                    mJarvisText.setVisibility(View.VISIBLE);
                                    typewriterReveal(mJarvisText, doneReply,
                                        () -> speakFromBackend(doneReply, url));
                                });
                            }
                            @Override
                            public void onFailure(String step, String reason) {
                                String failReply = "Ran into trouble at " + step + ", " +
                                                   (userTitle != null ? userTitle : "boss") +
                                                   ". " + reason;
                                runOnUiThread(() -> {
                                    setStatus("Jarvis");
                                    mDivider.setVisibility(View.VISIBLE);
                                    mJarvisText.setVisibility(View.VISIBLE);
                                    typewriterReveal(mJarvisText, failReply,
                                        () -> speakFromBackend(failReply, url));
                                });
                            }
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
    // TTS via /api/tts
    // ─────────────────────────────────────────────────────────────────────────

    private void speakFromBackend(String text, String baseUrl) {
        setState(State.SPEAKING);
        setStatus("Speaking…");

        mPool.execute(() -> {
            try {
                String json = "{\"text\":\"" + text.replace("\"", "\\\"")
                        .replace("\n", " ") + "\"}";
                RequestBody body = RequestBody.create(
                        json, MediaType.parse("application/json; charset=utf-8"));
                Request req = new Request.Builder()
                        .url(baseUrl + "/api/tts")
                        .post(body)
                        .build();

                Response resp = mHttp.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    if (resp.body() != null) resp.body().close();
                    mHandler.postDelayed(this::dismissSheet, AUTO_CLOSE);
                    return;
                }

                String mime = resp.header("Content-Type", "audio/flac");
                String ext  = (mime != null && mime.contains("wav")) ? ".wav"
                            : (mime != null && mime.contains("mp3")) ? ".mp3" : ".flac";

                File tmpFile = File.createTempFile("jarvis_tts", ext, getCacheDir());
                tmpFile.deleteOnExit();
                try (InputStream is = resp.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(tmpFile)) {
                    byte[] buf = new byte[8192]; int len;
                    while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
                }
                resp.body().close();
                mHandler.post(() -> playAudioFile(tmpFile));

            } catch (Exception e) {
                Log.e(TAG, "speakFromBackend: " + e.getMessage());
                mHandler.postDelayed(this::dismissSheet, AUTO_CLOSE);
            }
        });
    }

    private void playAudioFile(File file) {
        try {
            if (mMediaPlayer != null) { mMediaPlayer.release(); mMediaPlayer = null; }
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mMediaPlayer.setDataSource(file.getAbsolutePath());
            mMediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mMediaPlayer.setOnCompletionListener(mp -> {
                mp.release(); mMediaPlayer = null; file.delete();
                mHandler.postDelayed(this::dismissSheet, AUTO_CLOSE);
            });
            mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                mp.release(); mMediaPlayer = null;
                mHandler.postDelayed(VoiceOverlayActivity.this::dismissSheet, AUTO_CLOSE);
                return true;
            });
            mMediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "playAudioFile: " + e.getMessage());
            mHandler.postDelayed(this::dismissSheet, AUTO_CLOSE);
        }
    }

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
                    mHandler.postDelayed(this, 8);
                } else if (onDone != null) {
                    onDone.run();
                }
            }
        };
        mHandler.post(ticker);
    }

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
