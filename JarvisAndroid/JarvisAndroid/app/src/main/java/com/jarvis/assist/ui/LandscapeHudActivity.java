package com.jarvis.assist.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.jarvis.assist.network.JarvisClient;
import com.jarvis.assist.storage.SessionStore;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * LandscapeHudActivity — the Iron Man / Jarvis HUD layout seen in the video.
 *
 * Landscape locked. Three-panel layout:
 *   LEFT  — Greeting, memory count, status items
 *   CENTER — Spinning ring arc + JARVIS orb + live time
 *   RIGHT  — Wallet balance, session data, quick stats
 *
 * Arc spins via Canvas custom view. All data pulled from Jarvis server.
 */
public class LandscapeHudActivity extends AppCompatActivity {

    private SessionStore mSession;
    private Handler      mClockHandler = new Handler(Looper.getMainLooper());
    private TextView     mTvClock;
    private TextView     mTvGreeting;
    private TextView     mTvSubGreeting;
    private TextView     mTvMemoryVal;
    private TextView     mTvWalletBal;
    private TextView     mTvWalletUsd;
    private TextView     mTvSessionId;
    private SpinningArcView mArcView;

    // Status row TextViews (left panel)
    private TextView[] mLeftLabels = new TextView[5];
    private TextView[] mLeftValues = new TextView[5];

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        // Lock landscape + keep screen on
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        mSession = SessionStore.get(this);
        buildUI();
        startClock();
        loadData();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        mClockHandler.removeCallbacksAndMessages(null);
    }

    // ──────────────────────────────────────────────────────────────────────
    // UI Build
    // ──────────────────────────────────────────────────────────────────────

    private void buildUI() {
        // Root
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Scanline overlay (subtle tint)
        View scanlines = new View(this) {
            final Paint p = new Paint();
            @Override protected void onDraw(Canvas c) {
                p.setColor(Color.parseColor("#08002200"));
                for (int y = 0; y < getHeight(); y += 4) {
                    c.drawRect(0, y, getWidth(), y + 2, p);
                }
            }
        };
        scanlines.setWillNotDraw(false);
        root.addView(scanlines, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Three-panel horizontal layout
        LinearLayout panels = new LinearLayout(this);
        panels.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(panels, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ── LEFT PANEL ────────────────────────────────────────────────────
        LinearLayout leftPanel = buildLeftPanel();
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.28f);
        leftPanel.setLayoutParams(leftLp);
        panels.addView(leftPanel);

        // ── CENTER PANEL ──────────────────────────────────────────────────
        FrameLayout centerPanel = buildCenterPanel();
        LinearLayout.LayoutParams centerLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.44f);
        centerPanel.setLayoutParams(centerLp);
        panels.addView(centerPanel);

        // ── RIGHT PANEL ───────────────────────────────────────────────────
        LinearLayout rightPanel = buildRightPanel();
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.28f);
        rightPanel.setLayoutParams(rightLp);
        panels.addView(rightPanel);

        // Red top accent line
        View topLine = new View(this);
        topLine.setBackgroundColor(Color.parseColor("#CC2200"));
        FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(2));
        lineLp.gravity = Gravity.TOP;
        root.addView(topLine, lineLp);

        setContentView(root);
    }

    private LinearLayout buildLeftPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(18), dp(14), dp(10), dp(14));
        p.setBackground(createPanelBg(false));

        // Top label
        addSmallLabel(p, "JARVIS INTERFACE · ALPHA");

        // Greeting
        mTvGreeting = new TextView(this);
        mTvGreeting.setText("AFTERNOON,");
        mTvGreeting.setTextColor(Color.WHITE);
        mTvGreeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        mTvGreeting.setTypeface(null, Typeface.BOLD);
        mTvGreeting.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams greetLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        greetLp.topMargin = dp(8);
        mTvGreeting.setLayoutParams(greetLp);
        p.addView(mTvGreeting);

        // User name (loaded from session)
        mTvSubGreeting = new TextView(this);
        mTvSubGreeting.setText(mSession.getUserName() != null
                ? mSession.getUserName().toUpperCase() : "OPERATOR");
        mTvSubGreeting.setTextColor(Color.parseColor("#CC2200"));
        mTvSubGreeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mTvSubGreeting.setTypeface(null, Typeface.BOLD);
        p.addView(mTvSubGreeting);

        // Sub line
        TextView tvSub2 = new TextView(this);
        tvSub2.setText("ONLINE · ACTIVE");
        tvSub2.setTextColor(Color.parseColor("#44FF88"));
        tvSub2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvSub2.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams s2lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        s2lp.topMargin = dp(2);
        tvSub2.setLayoutParams(s2lp);
        p.addView(tvSub2);

        // Divider
        addDivider(p);

        // Status rows
        String[] labels = {"MEMORY", "VAULT", "SESSION", "NETWORK", "SYNC"};
        String[] vals   = {"—", "LOCKED", "—", "ONLINE", "LIVE"};
        int[]    colors = {0xFF44FF88, 0xFFCC2200, 0xFF4499FF, 0xFF44FF88, 0xFFFFD700};

        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = dp(8);
            row.setLayoutParams(rLp);

            // Color indicator
            View indicator = new View(this);
            android.graphics.drawable.GradientDrawable ind = new android.graphics.drawable.GradientDrawable();
            ind.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            ind.setColor(colors[i]);
            indicator.setBackground(ind);
            LinearLayout.LayoutParams indLp = new LinearLayout.LayoutParams(dp(6), dp(6));
            indLp.rightMargin = dp(8);
            indicator.setLayoutParams(indLp);
            row.addView(indicator);

            TextView tvLabel = new TextView(this);
            tvLabel.setText(labels[i]);
            tvLabel.setTextColor(Color.parseColor("#66FFFFFF"));
            tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            tvLabel.setLetterSpacing(0.1f);
            LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvLabel.setLayoutParams(tlLp);
            row.addView(tvLabel);
            mLeftLabels[i] = tvLabel;

            TextView tvVal = new TextView(this);
            tvVal.setText(vals[i]);
            tvVal.setTextColor(colors[i]);
            tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tvVal.setTypeface(null, Typeface.BOLD);
            row.addView(tvVal);
            mLeftValues[i] = tvVal;

            p.addView(row);
        }

        // Memory count label (updated after load)
        addDivider(p);
        mTvMemoryVal = new TextView(this);
        mTvMemoryVal.setText("Memories: loading…");
        mTvMemoryVal.setTextColor(Color.parseColor("#55FFFFFF"));
        mTvMemoryVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        p.addView(mTvMemoryVal);

        return p;
    }

    private FrameLayout buildCenterPanel() {
        FrameLayout p = new FrameLayout(this);

        // Spinning arc view (background)
        mArcView = new SpinningArcView(this);
        p.addView(mArcView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Center overlay: clock + status
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams ovLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        overlay.setLayoutParams(ovLp);

        TextView tvJarvis = new TextView(this);
        tvJarvis.setText("J A R V I S");
        tvJarvis.setTextColor(Color.parseColor("#33FFFFFF"));
        tvJarvis.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvJarvis.setLetterSpacing(0.3f);
        tvJarvis.setGravity(Gravity.CENTER);
        overlay.addView(tvJarvis);

        mTvClock = new TextView(this);
        mTvClock.setText("00:00");
        mTvClock.setTextColor(Color.WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        mTvClock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTvClock.setGravity(Gravity.CENTER);
        overlay.addView(mTvClock);

        TextView tvLive = new TextView(this);
        tvLive.setText("LIVE");
        tvLive.setTextColor(Color.parseColor("#CC2200"));
        tvLive.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvLive.setLetterSpacing(0.2f);
        tvLive.setGravity(Gravity.CENTER);
        overlay.addView(tvLive);

        // Bottom center: page indicator dots  (01/13 style from video)
        TextView tvPager = new TextView(this);
        tvPager.setText("01 / 01");
        tvPager.setTextColor(Color.parseColor("#44FFFFFF"));
        tvPager.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvPager.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pagerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pagerLp.topMargin = dp(16);
        tvPager.setLayoutParams(pagerLp);
        overlay.addView(tvPager);

        p.addView(overlay);

        // Top right: LANDSCAPE mode tag
        TextView tvMode = new TextView(this);
        tvMode.setText("◈ LANDSCAPE");
        tvMode.setTextColor(Color.parseColor("#CC2200"));
        tvMode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvMode.setLetterSpacing(0.1f);
        FrameLayout.LayoutParams modeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        modeLp.gravity = Gravity.TOP | Gravity.END;
        modeLp.topMargin  = dp(10);
        modeLp.rightMargin = dp(10);
        tvMode.setLayoutParams(modeLp);
        p.addView(tvMode);

        return p;
    }

    private LinearLayout buildRightPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(10), dp(14), dp(18), dp(14));
        p.setBackground(createPanelBg(true));
        p.setGravity(Gravity.END);

        // Clock / date (right-aligned)
        addSmallLabel(p, "SYSTEM DATA");

        addDivider(p);

        // Big balance number (like "9296346" in video)
        mTvWalletBal = new TextView(this);
        mTvWalletBal.setText("—");
        mTvWalletBal.setTextColor(Color.WHITE);
        mTvWalletBal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        mTvWalletBal.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTvWalletBal.setGravity(Gravity.END);
        LinearLayout.LayoutParams balLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        balLp.topMargin = dp(8);
        mTvWalletBal.setLayoutParams(balLp);
        p.addView(mTvWalletBal);

        mTvWalletUsd = new TextView(this);
        mTvWalletUsd.setText("SOLANA WALLET");
        mTvWalletUsd.setTextColor(Color.parseColor("#44FF88"));
        mTvWalletUsd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        mTvWalletUsd.setGravity(Gravity.END);
        p.addView(mTvWalletUsd);

        addDivider(p);

        // Quick stat rows (right-aligned)
        addStatRow(p, "RETURNS",  "—");
        addStatRow(p, "ALERTS",   "0");
        addStatRow(p, "BEAT",     "NOMINAL");

        addDivider(p);

        // Session ID
        mTvSessionId = new TextView(this);
        mTvSessionId.setText("SID: " + mSession.getSessionId());
        mTvSessionId.setTextColor(Color.parseColor("#33FFFFFF"));
        mTvSessionId.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        mTvSessionId.setTypeface(Typeface.MONOSPACE);
        mTvSessionId.setGravity(Gravity.END);
        LinearLayout.LayoutParams sidLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sidLp.topMargin = dp(6);
        mTvSessionId.setLayoutParams(sidLp);
        p.addView(mTvSessionId);

        // Back button (bottom)
        TextView tvBack = new TextView(this);
        tvBack.setText("◀  CLOSE HUD");
        tvBack.setTextColor(Color.parseColor("#44FF88"));
        tvBack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvBack.setGravity(Gravity.END);
        tvBack.setClickable(true);
        tvBack.setFocusable(true);
        tvBack.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        backLp.topMargin = dp(16);
        tvBack.setLayoutParams(backLp);
        p.addView(tvBack);

        return p;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Clock
    // ──────────────────────────────────────────────────────────────────────

    private void startClock() {
        mClockHandler.post(new Runnable() {
            @Override public void run() {
                if (mTvClock != null) {
                    mTvClock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
                    updateGreeting();
                }
                mClockHandler.postDelayed(this, 30_000);
            }
        });
    }

    private void updateGreeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting = hour < 12 ? "MORNING," : hour < 17 ? "AFTERNOON," : "EVENING,";
        if (mTvGreeting != null) mTvGreeting.setText(greeting);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Data loading
    // ──────────────────────────────────────────────────────────────────────

    private void loadData() {
        String url  = mSession.getJarvisUrl();
        String name = mSession.getUserName();
        if (url == null || name == null) return;

        // Memory
        JarvisClient.fetchMemories(url, name, memories -> runOnUiThread(() -> {
            int count = memories.length();
            mSession.cacheMemoryCount(count);
            if (mTvMemoryVal != null) mTvMemoryVal.setText("Memories: " + count);
            if (mLeftValues[0] != null) mLeftValues[0].setText(String.valueOf(count));
        }));

        // Wallet
        JarvisClient.fetchWallet(url, name, new JarvisClient.WalletCallback() {
            @Override public void onSuccess(String address, double sol, double usd) {
                runOnUiThread(() -> {
                    // Show lamports-style big number for HUD feel
                    long lamports = (long)(sol * 1_000_000_000L);
                    mTvWalletBal.setText(String.valueOf(lamports));
                    mTvWalletUsd.setText(String.format(Locale.US, "◎ %.4f  ·  $%.2f", sol, usd));
                });
            }
            @Override public void onNotConfigured() {
                runOnUiThread(() -> {
                    mTvWalletBal.setText("—");
                    mTvWalletUsd.setText("NO WALLET");
                });
            }
            @Override public void onError(String e) {
                runOnUiThread(() -> mTvWalletBal.setText("OFFLINE"));
            }
        });

        // Reminders count → left panel ALERTS
        JarvisClient.fetchReminders(url, new JarvisClient.RemindersCallback() {
            @Override public void onSuccess(List<JarvisClient.JarvisReminder> reminders) {
                runOnUiThread(() -> {
                    if (mLeftValues[3] != null) mLeftValues[3].setText(String.valueOf(reminders.size()) + " SET");
                });
            }
            @Override public void onError(String e) {}
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    // UI helpers
    // ──────────────────────────────────────────────────────────────────────

    private void addSmallLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#33FFFFFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        tv.setLetterSpacing(0.18f);
        parent.addView(tv);
    }

    private void addDivider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(Color.parseColor("#1AFFFFFF"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dlp.topMargin    = dp(10);
        dlp.bottomMargin = dp(4);
        d.setLayoutParams(dlp);
        parent.addView(d);
    }

    private void addStatRow(LinearLayout parent, String label, String val) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(6);
        row.setLayoutParams(rLp);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.parseColor("#44FFFFFF"));
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvLabel.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvLabel.setLayoutParams(tlLp);
        row.addView(tvLabel);

        TextView tvVal = new TextView(this);
        tvVal.setText(val);
        tvVal.setTextColor(Color.WHITE);
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvVal.setTypeface(null, Typeface.BOLD);
        row.addView(tvVal);

        parent.addView(row);
    }

    private android.graphics.drawable.Drawable createPanelBg(boolean rightSide) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setColor(Color.parseColor("#04080F"));
        int stroke = dp(1);
        if (rightSide) {
            // left border only (right panel)
            // Android GradientDrawable doesn't support single-side borders,
            // so we use a LayerDrawable approach
        }
        gd.setStroke(stroke, Color.parseColor("#0A1A2A"));
        return gd;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Spinning Arc Custom View
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Draws the concentric spinning ring + arc system seen in the video.
     * Multiple rings, different speeds, some spinning CW, some CCW.
     */
    static class SpinningArcView extends View {

        private final Paint[] mRingPaints;
        private float[]       mAngles   = new float[6];
        private float[]       mSpeeds   = { 0.3f, -0.5f, 0.7f, -0.4f, 0.2f, -0.9f };
        private final RectF[] mOvals;
        private final Handler mHandler  = new Handler(Looper.getMainLooper());
        private boolean       mRunning  = false;

        // Colours matching the video: red accents, cyan, white, dark rings
        private static final int[] RING_COLORS = {
            0x33FFFFFF,  // faint outer
            0x80CC2200,  // red
            0x6000CCFF,  // cyan
            0xFFFFFFFF,  // solid white thin
            0x40CC2200,  // red mid
            0x2200FFAA,  // faint green inner
        };

        private static final float[] RING_WIDTHS = { 2f, 3f, 1.5f, 1f, 4f, 1f };
        // Arc lengths (sweep degrees)
        private static final float[] ARC_SWEEPS = { 200f, 80f, 160f, 340f, 40f, 220f };

        SpinningArcView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);

            mRingPaints = new Paint[6];
            mOvals      = new RectF[6];

            for (int i = 0; i < 6; i++) {
                mRingPaints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mRingPaints[i].setStyle(Paint.Style.STROKE);
                mRingPaints[i].setColor(RING_COLORS[i]);
                mRingPaints[i].setStrokeWidth(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                        RING_WIDTHS[i], ctx.getResources().getDisplayMetrics()));
                mRingPaints[i].setStrokeCap(Paint.Cap.ROUND);
                mOvals[i] = new RectF();
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            mRunning = true;
            tick();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mRunning = false;
            mHandler.removeCallbacksAndMessages(null);
        }

        private void tick() {
            for (int i = 0; i < mSpeeds.length; i++) {
                mAngles[i] = (mAngles[i] + mSpeeds[i]) % 360f;
            }
            invalidate();
            if (mRunning) mHandler.postDelayed(this::tick, 16); // ~60fps
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float minDim = Math.min(w, h);

            // Draw from outermost ring inward
            for (int i = 0; i < 6; i++) {
                float radius = (minDim / 2f) * (0.95f - i * 0.12f);
                mOvals[i].set(cx - radius, cy - radius, cx + radius, cy + radius);
                c.drawArc(mOvals[i], mAngles[i], ARC_SWEEPS[i], false, mRingPaints[i]);
            }

            // Center glow dot (orb)
            Paint orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            orbPaint.setStyle(Paint.Style.FILL);
            float orbR = minDim * 0.08f;

            // Outer glow
            orbPaint.setColor(Color.parseColor("#0A0044AA"));
            c.drawCircle(cx, cy, orbR * 2.5f, orbPaint);

            // Inner bright core
            orbPaint.setColor(Color.parseColor("#FF0000"));
            c.drawCircle(cx, cy, orbR * 0.5f, orbPaint);

            // Mid ring (solid)
            Paint midPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            midPaint.setStyle(Paint.Style.STROKE);
            midPaint.setColor(Color.parseColor("#55FFFFFF"));
            midPaint.setStrokeWidth(2f);
            c.drawCircle(cx, cy, orbR, midPaint);

            // Tick marks around the outermost ring
            Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            tickPaint.setColor(Color.parseColor("#33FFFFFF"));
            tickPaint.setStrokeWidth(1.5f);
            float outerR = (minDim / 2f) * 0.95f;
            for (int t = 0; t < 36; t++) {
                double angle = Math.toRadians(t * 10.0);
                float tickLen = (t % 3 == 0) ? outerR * 0.04f : outerR * 0.02f;
                float sx = cx + (float)(Math.cos(angle) * (outerR - tickLen));
                float sy = cy + (float)(Math.sin(angle) * (outerR - tickLen));
                float ex = cx + (float)(Math.cos(angle) * outerR);
                float ey = cy + (float)(Math.sin(angle) * outerR);
                c.drawLine(sx, sy, ex, ey, tickPaint);
            }
        }
    }
}
