package com.jarvis.assist.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.jarvis.assist.network.JarvisClient;
import com.jarvis.assist.storage.SessionStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * HomeHudActivity — the Iron Man portal home screen.
 * Shown immediately after VaultLockActivity grants access.
 *
 * Layout:
 *   - Full-screen dark background
 *   - Top: JARVIS wordmark + status + clock
 *   - Center: spinning arc orb (same SpinningArcView from LandscapeHudActivity)
 *   - Below orb: greeting + memory count
 *   - Horizontal scrollable module tray (Talk · Vault · Wallet · Memory · Reminders · Todos · HUD · Settings)
 *   - Sound effect on every module tap
 *   - Auto-switches to LandscapeHudActivity on phone rotation
 */
public class HomeHudActivity extends AppCompatActivity {

    private SessionStore mSession;
    private final Handler mClockHandler = new Handler(Looper.getMainLooper());

    private float SD;  // dp scale
    private float SS;  // screen-relative sp scale

    // Data views
    private TextView mTvClock;
    private TextView mTvGreeting;
    private TextView mTvName;
    private TextView mTvMemCount;
    private TextView mTvWalletBal;
    private TextView mTvStatusDot;

    // Sound
    private SoundPool mSoundPool;
    private int mClickSoundId = -1;
    private boolean mSoundLoaded = false;

    // Orientation
    private OrientationEventListener mOrientationListener;
    private boolean mInLandscape = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        mSession = SessionStore.get(this);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        SD = dm.density;
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        SS = shortSide / 100f / dm.scaledDensity;

        initSoundPool();
        buildUI();
        startClock();
        loadData();
        setupOrientationListener();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mInLandscape = false; // reset so rotation triggers fresh
        if (mOrientationListener != null && mOrientationListener.canDetectOrientation())
            mOrientationListener.enable();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mOrientationListener != null) mOrientationListener.disable();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mClockHandler.removeCallbacksAndMessages(null);
        if (mSoundPool != null) mSoundPool.release();
    }

    // ── Sound ─────────────────────────────────────────────────────────────

    private void initSoundPool() {
        AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        mSoundPool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attr).build();
        mSoundPool.setOnLoadCompleteListener((sp, sampleId, status) -> mSoundLoaded = (status == 0));
        // Load the Voice.wav shipped with the project (used as UI click tone)
        try {
            android.content.res.AssetFileDescriptor afd = getAssets().openFd("Voice.wav");
            mClickSoundId = mSoundPool.load(afd, 1);
        } catch (Exception ignored) {}
    }

    private void playClick() {
        if (mSoundLoaded && mClickSoundId != -1)
            mSoundPool.play(mClickSoundId, 0.6f, 0.6f, 1, 0, 1.0f);
    }

    // ── Orientation ───────────────────────────────────────────────────────

    private void setupOrientationListener() {
        mOrientationListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                boolean nowLandscape = (orientation > 60 && orientation < 120)
                        || (orientation > 240 && orientation < 300);
                if (nowLandscape && !mInLandscape) {
                    mInLandscape = true;
                    startActivity(new Intent(HomeHudActivity.this, LandscapeHudActivity.class));
                } else if (!nowLandscape) {
                    mInLandscape = false;
                }
            }
        };
        if (mOrientationListener.canDetectOrientation()) mOrientationListener.enable();
    }

    // ── Build UI ──────────────────────────────────────────────────────────

    private void buildUI() {
        // Root: FrameLayout for layering orb + content
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#040408"));

        // ── Top scan line (red accent) ────────────────────────────────────
        View redLine = new View(this);
        redLine.setBackgroundColor(Color.parseColor("#CC2200"));
        FrameLayout.LayoutParams rlLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(2));
        rlLp.gravity = Gravity.TOP;
        root.addView(redLine, rlLp);

        // ── Tick ruler ────────────────────────────────────────────────────
        LandscapeHudActivity.TickRulerView ruler = new LandscapeHudActivity.TickRulerView(this);
        FrameLayout.LayoutParams rulerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(22));
        rulerLp.gravity = Gravity.TOP;
        rulerLp.topMargin = dp(2);
        root.addView(ruler, rulerLp);

        // ── Scrollable content (the whole page scrolls) ───────────────────
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(24), 0, dp(60));
        scroll.addView(page, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Header row ────────────────────────────────────────────────────
        page.addView(buildHeaderRow());

        // ── Big spinning orb (center) ─────────────────────────────────────
        page.addView(buildOrbSection());

        // ── Greeting + memory line ────────────────────────────────────────
        page.addView(buildGreetingSection());

        // ── Horizontal module tray ────────────────────────────────────────
        page.addView(buildModuleTray());

        // ── Live data strip ───────────────────────────────────────────────
        page.addView(buildDataStrip());

        setContentView(root);
    }

    private LinearLayout buildHeaderRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(30), dp(20), dp(6));

        // Left: JARVIS + ONLINE
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.setLayoutParams(llp);

        TextView tvJarvis = new TextView(this);
        tvJarvis.setText("JARVIS");
        tvJarvis.setTextColor(Color.WHITE);
        tvJarvis.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(4.5f));
        tvJarvis.setLetterSpacing(0.3f);
        tvJarvis.setTypeface(null, Typeface.NORMAL);
        left.addView(tvJarvis);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        mTvStatusDot = new TextView(this);
        mTvStatusDot.setText("●");
        mTvStatusDot.setTextColor(Color.parseColor("#44FF88"));
        mTvStatusDot.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        mTvStatusDot.setPadding(0, 0, dp(4), 0);
        pulseView(mTvStatusDot);
        statusRow.addView(mTvStatusDot);

        TextView tvOnline = new TextView(this);
        tvOnline.setText("ONLINE");
        tvOnline.setTextColor(Color.parseColor("#44FF88"));
        tvOnline.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.5f));
        tvOnline.setLetterSpacing(0.2f);
        statusRow.addView(tvOnline);
        left.addView(statusRow);

        row.addView(left);

        // Right: clock
        mTvClock = new TextView(this);
        mTvClock.setText("--:--");
        mTvClock.setTextColor(Color.WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(3.5f));
        mTvClock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(mTvClock);

        return row;
    }

    private FrameLayout buildOrbSection() {
        FrameLayout frame = new FrameLayout(this);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260));
        frame.setLayoutParams(flp);

        // Spinning arc fills the frame
        LandscapeHudActivity.SpinningArcView arc = new LandscapeHudActivity.SpinningArcView(this);
        frame.addView(arc, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // LIVE badge overlay
        TextView tvLive = new TextView(this);
        tvLive.setText("● LIVE");
        tvLive.setTextColor(Color.WHITE);
        tvLive.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.0f));
        tvLive.setLetterSpacing(0.1f);
        tvLive.setBackground(pill(Color.parseColor("#CC2200")));
        tvLive.setPadding(dp(10), dp(4), dp(10), dp(4));
        tvLive.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams liveLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        liveLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        liveLp.topMargin = dp(14);
        tvLive.setLayoutParams(liveLp);
        pulseView(tvLive);
        frame.addView(tvLive);

        // Wallet balance overlay — bottom-left of orb
        mTvWalletBal = new TextView(this);
        mTvWalletBal.setText("◎ —");
        mTvWalletBal.setTextColor(Color.parseColor("#9945FF"));
        mTvWalletBal.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.0f));
        mTvWalletBal.setTypeface(Typeface.MONOSPACE);
        FrameLayout.LayoutParams wlLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        wlLp.gravity = Gravity.BOTTOM | Gravity.START;
        wlLp.bottomMargin = dp(8);
        wlLp.leftMargin = dp(20);
        mTvWalletBal.setLayoutParams(wlLp);
        frame.addView(mTvWalletBal);

        // Date — bottom right
        TextView tvDate = new TextView(this);
        tvDate.setText(new SimpleDateFormat("dd MMM", Locale.US)
                .format(new Date()).toUpperCase(Locale.US));
        tvDate.setTextColor(Color.parseColor("#44FF88"));
        tvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        tvDate.setTypeface(Typeface.MONOSPACE);
        FrameLayout.LayoutParams dtLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        dtLp.gravity = Gravity.BOTTOM | Gravity.END;
        dtLp.bottomMargin = dp(8);
        dtLp.rightMargin = dp(20);
        tvDate.setLayoutParams(dtLp);
        frame.addView(tvDate);

        return frame;
    }

    private LinearLayout buildGreetingSection() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(10), dp(24), dp(4));

        mTvGreeting = new TextView(this);
        mTvGreeting.setText(getGreeting());
        mTvGreeting.setTextColor(Color.parseColor("#88FFFFFF"));
        mTvGreeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.2f));
        mTvGreeting.setLetterSpacing(0.12f);
        mTvGreeting.setTypeface(null, Typeface.BOLD);
        col.addView(mTvGreeting);

        mTvName = new TextView(this);
        String rawName = mSession.getUserName();
        mTvName.setText(rawName != null ? "Hello, " + rawName + (mSession.isOwner() ? " ★" : "") : "Hello");
        mTvName.setTextColor(Color.WHITE);
        mTvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(5.5f));
        mTvName.setTypeface(null, Typeface.NORMAL);
        col.addView(mTvName);

        mTvMemCount = new TextView(this);
        mTvMemCount.setText("Loading memories…");
        mTvMemCount.setTextColor(Color.parseColor("#44FF88"));
        mTvMemCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        LinearLayout.LayoutParams mclp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mclp.topMargin = dp(4);
        mTvMemCount.setLayoutParams(mclp);
        col.addView(mTvMemCount);

        return col;
    }

    private View buildModuleTray() {
        // Label
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(14);
        wrapper.setLayoutParams(wlp);

        TextView trayLabel = new TextView(this);
        trayLabel.setText("MODULES");
        trayLabel.setTextColor(Color.parseColor("#33FFFFFF"));
        trayLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        trayLabel.setLetterSpacing(0.22f);
        LinearLayout.LayoutParams tllp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tllp.leftMargin = dp(24);
        tllp.bottomMargin = dp(8);
        trayLabel.setLayoutParams(tllp);
        wrapper.addView(trayLabel);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipToPadding(false);
        hsv.setPadding(dp(20), 0, dp(20), 0);
        wrapper.addView(hsv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout tray = new LinearLayout(this);
        tray.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(tray, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Module definitions: {emoji, title, subtitle, accentColor}
        Object[][] modules = {
            {"🎙", "Talk",      "Speak to Jarvis",    "#9945FF", true},
            {"🔐", "Vault",     "Password manager",   "#4488FF", false},
            {"◎",  "Wallet",    "Solana",             "#9945FF", false},
            {"🧠", "Memory",    "What Jarvis knows",  "#44FF88", false},
            {"⏰", "Reminders", "Upcoming",           "#FFD700", false},
            {"📋", "Todos",     "Research queue",     "#FF8844", false},
            {"◉",  "HUD",       "Landscape view",     "#CC2200", true},
            {"⚙️", "Settings",  "Account & server",   "#666688", false},
        };

        for (int i = 0; i < modules.length; i++) {
            final int idx = i;
            String emoji    = (String)  modules[i][0];
            String title    = (String)  modules[i][1];
            String sub      = (String)  modules[i][2];
            String color    = (String)  modules[i][3];
            boolean accent  = (Boolean) modules[i][4];

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(10), dp(14), dp(10), dp(14));

            // Card background
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(14));
            bg.setColor(accent ? Color.parseColor("#1A0A2A") : Color.parseColor("#0D0D1A"));
            bg.setStroke(dp(1), accent ? Color.parseColor(color + "55") : Color.parseColor("#1A1A2E"));
            card.setBackground(bg);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(110), dp(110));
            cardLp.rightMargin = dp(10);
            card.setLayoutParams(cardLp);
            card.setClickable(true);
            card.setFocusable(true);

            TextView tvEmoji = new TextView(this);
            tvEmoji.setText(emoji);
            tvEmoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(4.0f));
            tvEmoji.setTextColor(accent ? Color.parseColor(color) : Color.WHITE);
            tvEmoji.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            elp.bottomMargin = dp(6);
            tvEmoji.setLayoutParams(elp);
            card.addView(tvEmoji);

            TextView tvTitle = new TextView(this);
            tvTitle.setText(title);
            tvTitle.setTextColor(Color.WHITE);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.0f));
            tvTitle.setTypeface(null, Typeface.BOLD);
            tvTitle.setGravity(Gravity.CENTER);
            card.addView(tvTitle);

            TextView tvSub = new TextView(this);
            tvSub.setText(sub);
            tvSub.setTextColor(Color.parseColor("#66FFFFFF"));
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.4f));
            tvSub.setGravity(Gravity.CENTER);
            tvSub.setMaxLines(1);
            card.addView(tvSub);

            card.setOnClickListener(v -> {
                playClick();
                // Scale-pulse feedback
                ObjectAnimator scaleX = ObjectAnimator.ofFloat(card, "scaleX", 0.92f, 1.0f);
                ObjectAnimator scaleY = ObjectAnimator.ofFloat(card, "scaleY", 0.92f, 1.0f);
                scaleX.setDuration(160);
                scaleY.setDuration(160);
                scaleX.start();
                scaleY.start();
                onModuleTapped(idx);
            });

            tray.addView(card);
        }

        return wrapper;
    }

    private void onModuleTapped(int idx) {
        switch (idx) {
            case 0: // Talk
                startActivity(new Intent(this, VoiceOverlayActivity.class));
                break;
            case 1: // Vault — goes to MainActivity (dashboard, vault card)
                Intent vaultIntent = new Intent(this, MainActivity.class);
                vaultIntent.putExtra("vault_unlocked", true);
                vaultIntent.putExtra("scroll_to", "vault");
                startActivity(vaultIntent);
                break;
            case 2: // Wallet
                Intent walletIntent = new Intent(this, MainActivity.class);
                walletIntent.putExtra("vault_unlocked", true);
                walletIntent.putExtra("scroll_to", "wallet");
                startActivity(walletIntent);
                break;
            case 3: // Memory
                Intent memIntent = new Intent(this, MainActivity.class);
                memIntent.putExtra("vault_unlocked", true);
                memIntent.putExtra("scroll_to", "memory");
                startActivity(memIntent);
                break;
            case 4: // Reminders
                Intent remIntent = new Intent(this, MainActivity.class);
                remIntent.putExtra("vault_unlocked", true);
                remIntent.putExtra("scroll_to", "reminders");
                startActivity(remIntent);
                break;
            case 5: // Todos
                Intent todoIntent = new Intent(this, MainActivity.class);
                todoIntent.putExtra("vault_unlocked", true);
                todoIntent.putExtra("scroll_to", "todos");
                startActivity(todoIntent);
                break;
            case 6: // HUD
                startActivity(new Intent(this, LandscapeHudActivity.class));
                break;
            case 7: // Settings
                Intent settingsIntent = new Intent(this, MainActivity.class);
                settingsIntent.putExtra("vault_unlocked", true);
                settingsIntent.putExtra("scroll_to", "settings");
                startActivity(settingsIntent);
                break;
        }
    }

    private View buildDataStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(dp(20), dp(18), dp(20), dp(10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        strip.setLayoutParams(slp);

        String[] labels = {"MEMORY", "VAULT", "NETWORK", "SYNC"};
        int[]    colors = {0xFF44FF88, 0xFF9945FF, 0xFF44FF88, 0xFFFFD700};

        for (int i = 0; i < labels.length; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cell.setLayoutParams(clp);

            View dot = new View(this);
            android.graphics.drawable.GradientDrawable dg =
                    new android.graphics.drawable.GradientDrawable();
            dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dg.setColor(colors[i]);
            dot.setBackground(dg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(5), dp(5));
            dotLp.bottomMargin = dp(4);
            dot.setLayoutParams(dotLp);
            cell.addView(dot);

            TextView tvL = new TextView(this);
            tvL.setText(labels[i]);
            tvL.setTextColor(Color.parseColor("#44FFFFFF"));
            tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.5f));
            tvL.setLetterSpacing(0.1f);
            tvL.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(tvL);

            strip.addView(cell);
        }
        return strip;
    }

    // ── Data ──────────────────────────────────────────────────────────────

    private void loadData() {
        String url  = mSession.getJarvisUrl();
        String name = mSession.getUserName();
        if (url == null || name == null) return;

        JarvisClient.fetchMemories(url, name, mems -> runOnUiThread(() -> {
            int count = mems.length();
            if (mTvMemCount != null)
                mTvMemCount.setText(count > 0
                    ? "Jarvis remembers " + count + " thing" + (count == 1 ? "" : "s") + " about you"
                    : "No memories yet");
        }));

        JarvisClient.fetchWallet(url, name, new JarvisClient.WalletCallback() {
            @Override public void onSuccess(String addr, double sol, double usd) {
                runOnUiThread(() -> {
                    if (mTvWalletBal != null)
                        mTvWalletBal.setText(String.format(Locale.US, "◎ %.4f", sol));
                });
            }
            @Override public void onNotConfigured() {
                runOnUiThread(() -> { if (mTvWalletBal != null) mTvWalletBal.setText("◎ NO WALLET"); });
            }
            @Override public void onError(String e) {
                runOnUiThread(() -> { if (mTvWalletBal != null) mTvWalletBal.setText("◎ —"); });
            }
        });
    }

    // ── Clock ─────────────────────────────────────────────────────────────

    private void startClock() {
        mClockHandler.post(new Runnable() {
            @Override public void run() {
                if (mTvClock != null)
                    mTvClock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
                if (mTvGreeting != null)
                    mTvGreeting.setText(getGreeting());
                mClockHandler.postDelayed(this, 30_000);
            }
        });
    }

    private String getGreeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if      (h < 12) return "GOOD MORNING,";
        else if (h < 17) return "GOOD AFTERNOON,";
        else if (h < 21) return "GOOD EVENING,";
        else             return "GOOD NIGHT,";
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private float sp(float pct) { return SS * pct; }
    private int   dp(float v)   { return Math.round(v * SD); }

    private void pulseView(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.2f);
        a.setDuration(900);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.start();
    }

    private android.graphics.drawable.Drawable pill(int color) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(20));
        gd.setColor(color);
        gd.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        return gd;
    }
}
