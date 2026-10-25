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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.jarvis.assist.network.JarvisClient;
import com.jarvis.assist.storage.SessionStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * LandscapeHudActivity — full Iron Man / Jarvis HUD matching the video exactly.
 *
 * THREE panel layout (landscape locked):
 *   LEFT   — mini spinning arc + greeting + numbered status rows
 *   CENTER — large spinning arc + clock + LIVE badge
 *   RIGHT  — big wallet number + BATT/MSG/CAL pills + session data
 *
 * TOP    — tick-mark ruler across full width
 * BOTTOM — THREAT · EVENTS bar with red indicators + 01/13 counter
 */
public class LandscapeHudActivity extends AppCompatActivity {

    private SessionStore mSession;
    private final Handler mClockHandler = new Handler(Looper.getMainLooper());

    // Center
    private TextView mTvClock;
    private SpinningArcView mMainArc;

    // Left panel
    private TextView mTvGreeting;
    private TextView mTvUserName;
    private TextView[] mLeftVals = new TextView[5];

    // Right panel
    private TextView mTvWalletBig;
    private TextView mTvWalletSub;
    private TextView mTvBatt;
    private TextView mTvMsg;
    private TextView mTvCal;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        mSession = SessionStore.get(this);
        buildUI();
        startClock();
        loadData();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        mClockHandler.removeCallbacksAndMessages(null);
    }

    // ── Build UI ──────────────────────────────────────────────────────────

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // ── TICK RULER — top of screen, full width ──────────────────────
        TickRulerView ruler = new TickRulerView(this);
        FrameLayout.LayoutParams rulerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(20));
        rulerLp.gravity = Gravity.TOP;
        root.addView(ruler, rulerLp);

        // ── RED TOP ACCENT LINE ──────────────────────────────────────────
        View redLine = new View(this);
        redLine.setBackgroundColor(Color.parseColor("#CC2200"));
        FrameLayout.LayoutParams rlLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(2));
        rlLp.gravity = Gravity.TOP;
        root.addView(redLine, rlLp);

        // ── MAIN 3-PANEL ROW ─────────────────────────────────────────────
        LinearLayout panels = new LinearLayout(this);
        panels.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams panelsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        panelsLp.topMargin    = dp(20);
        panelsLp.bottomMargin = dp(36);
        panels.setLayoutParams(panelsLp);
        root.addView(panels);

        panels.addView(buildLeftPanel(),   weightLp(0.26f));
        panels.addView(buildCenterPanel(), weightLp(0.48f));
        panels.addView(buildRightPanel(),  weightLp(0.26f));

        // ── BOTTOM BAR ───────────────────────────────────────────────────
        root.addView(buildBottomBar());

        setContentView(root);
    }

    private LinearLayout.LayoutParams weightLp(float w) {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, w);
    }

    // ── LEFT PANEL ────────────────────────────────────────────────────────

    private LinearLayout buildLeftPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(14), dp(10), dp(6), dp(10));

        // Mini spinning arc (left side small arc like video)
        MiniArcView miniArc = new MiniArcView(this);
        LinearLayout.LayoutParams arcLp = new LinearLayout.LayoutParams(dp(60), dp(60));
        arcLp.bottomMargin = dp(8);
        miniArc.setLayoutParams(arcLp);
        p.addView(miniArc);

        // Greeting
        mTvGreeting = new TextView(this);
        mTvGreeting.setTextColor(Color.parseColor("#88FFFFFF"));
        mTvGreeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        mTvGreeting.setLetterSpacing(0.1f);
        p.addView(mTvGreeting);

        mTvUserName = new TextView(this);
        mTvUserName.setText(mSession.getUserName() != null
                ? mSession.getUserName().toUpperCase() : "OPERATOR");
        mTvUserName.setTextColor(Color.WHITE);
        mTvUserName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mTvUserName.setTypeface(null, Typeface.BOLD);
        mTvUserName.setLetterSpacing(0.06f);
        p.addView(mTvUserName);

        addDivider(p);

        // Numbered status rows (like "09" items in video left panel)
        String[] labels = {"MEMORY", "VAULT", "SESSION", "NETWORK", "SYNC"};
        String[] initVals = {"—", "LOCKED", "—", "ONLINE", "LIVE"};
        int[]    colors   = {0xFF44FF88, 0xFFCC2200, 0xFF4499FF, 0xFF44FF88, 0xFFFFD700};

        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = dp(6);
            row.setLayoutParams(rLp);

            // Number index like video
            TextView tvNum = new TextView(this);
            tvNum.setText(String.format(Locale.US, "%02d", i + 1));
            tvNum.setTextColor(Color.parseColor("#33FFFFFF"));
            tvNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            tvNum.setTypeface(Typeface.MONOSPACE);
            tvNum.setPadding(0, 0, dp(6), 0);
            row.addView(tvNum);

            // Color indicator dot
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable dotGd =
                    new android.graphics.drawable.GradientDrawable();
            dotGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dotGd.setColor(colors[i]);
            dot.setBackground(dotGd);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(5), dp(5));
            dotLp.rightMargin = dp(6);
            dot.setLayoutParams(dotLp);
            row.addView(dot);

            TextView tvLabel = new TextView(this);
            tvLabel.setText(labels[i]);
            tvLabel.setTextColor(Color.parseColor("#55FFFFFF"));
            tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            tvLabel.setLetterSpacing(0.08f);
            LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvLabel.setLayoutParams(tlLp);
            row.addView(tvLabel);

            TextView tvVal = new TextView(this);
            tvVal.setText(initVals[i]);
            tvVal.setTextColor(colors[i]);
            tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            tvVal.setTypeface(null, Typeface.BOLD);
            row.addView(tvVal);
            mLeftVals[i] = tvVal;

            p.addView(row);
        }

        return p;
    }

    // ── CENTER PANEL ──────────────────────────────────────────────────────

    private FrameLayout buildCenterPanel() {
        FrameLayout p = new FrameLayout(this);

        // Large spinning arc — fills center
        mMainArc = new SpinningArcView(this);
        p.addView(mMainArc, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Center text overlay
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        p.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // LIVE badge — red pill exactly like video
        TextView tvLive = new TextView(this);
        tvLive.setText("● LIVE");
        tvLive.setTextColor(Color.WHITE);
        tvLive.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvLive.setLetterSpacing(0.1f);
        tvLive.setBackground(createPill(Color.parseColor("#CC2200")));
        tvLive.setPadding(dp(8), dp(3), dp(8), dp(3));
        tvLive.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        liveLp.gravity = Gravity.CENTER_HORIZONTAL;
        liveLp.bottomMargin = dp(6);
        tvLive.setLayoutParams(liveLp);
        overlay.addView(tvLive);

        // Clock
        mTvClock = new TextView(this);
        mTvClock.setText("00:00");
        mTvClock.setTextColor(Color.WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        mTvClock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTvClock.setGravity(Gravity.CENTER);
        overlay.addView(mTvClock);

        TextView tvJarvis = new TextView(this);
        tvJarvis.setText("J · A · R · V · I · S");
        tvJarvis.setTextColor(Color.parseColor("#33FFFFFF"));
        tvJarvis.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        tvJarvis.setLetterSpacing(0.2f);
        tvJarvis.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams jLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        jLp.topMargin = dp(4);
        tvJarvis.setLayoutParams(jLp);
        overlay.addView(tvJarvis);

        // LANDSCAPE mode tag — top right of center panel
        TextView tvMode = new TextView(this);
        tvMode.setText("◈ LANDSCAPE");
        tvMode.setTextColor(Color.parseColor("#CC2200"));
        tvMode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        tvMode.setLetterSpacing(0.1f);
        FrameLayout.LayoutParams modeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        modeLp.gravity = Gravity.TOP | Gravity.END;
        modeLp.topMargin   = dp(6);
        modeLp.rightMargin = dp(8);
        tvMode.setLayoutParams(modeLp);
        p.addView(tvMode);

        // Small blue status circle — top right (like video)
        View statusCircle = new View(this);
        android.graphics.drawable.GradientDrawable scGd =
                new android.graphics.drawable.GradientDrawable();
        scGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        scGd.setColor(Color.parseColor("#4488FF"));
        statusCircle.setBackground(scGd);
        FrameLayout.LayoutParams scLp = new FrameLayout.LayoutParams(dp(10), dp(10));
        scLp.gravity   = Gravity.TOP | Gravity.START;
        scLp.topMargin  = dp(8);
        scLp.leftMargin = dp(8);
        statusCircle.setLayoutParams(scLp);
        pulseView(statusCircle);
        p.addView(statusCircle);

        return p;
    }

    // ── RIGHT PANEL ───────────────────────────────────────────────────────

    private LinearLayout buildRightPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(6), dp(10), dp(14), dp(10));
        p.setGravity(Gravity.END);

        // Mini arc right side
        MiniArcView miniArcR = new MiniArcView(this);
        LinearLayout.LayoutParams arcLp = new LinearLayout.LayoutParams(dp(60), dp(60));
        arcLp.gravity = Gravity.END;
        arcLp.bottomMargin = dp(8);
        miniArcR.setLayoutParams(arcLp);
        p.addView(miniArcR);

        addSmallLabel(p, "SYSTEM DATA", Gravity.END);
        addDivider(p);

        // BIG balance number — like "9296346" in video
        mTvWalletBig = new TextView(this);
        mTvWalletBig.setText("—");
        mTvWalletBig.setTextColor(Color.WHITE);
        mTvWalletBig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        mTvWalletBig.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTvWalletBig.setGravity(Gravity.END);
        LinearLayout.LayoutParams wbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wbLp.topMargin = dp(6);
        mTvWalletBig.setLayoutParams(wbLp);
        p.addView(mTvWalletBig);

        mTvWalletSub = new TextView(this);
        mTvWalletSub.setText("SOLANA WALLET");
        mTvWalletSub.setTextColor(Color.parseColor("#44FF88"));
        mTvWalletSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        mTvWalletSub.setGravity(Gravity.END);
        p.addView(mTvWalletSub);

        addDivider(p);

        // BATT · MSG · CAL pill row — matches video right panel exactly
        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = dp(8);
        pillRow.setLayoutParams(prLp);

        mTvBatt = makePill("BATT", "—%");
        mTvMsg  = makePill("MSG",  "0");
        mTvCal  = makePill("CAL",  "—");

        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pillLp.leftMargin = dp(4);

        pillRow.addView(mTvBatt, pillLp);
        pillRow.addView(mTvMsg,  pillLp);
        pillRow.addView(mTvCal,  pillLp);
        p.addView(pillRow);

        addDivider(p);

        // Returns / Alerts / Beat (quick stats)
        addStatRow(p, "RETURNS", "—",    Gravity.END);
        addStatRow(p, "ALERTS",  "0",    Gravity.END);
        addStatRow(p, "BEAT",    "NOM",  Gravity.END);

        addDivider(p);

        // Close button
        TextView tvClose = new TextView(this);
        tvClose.setText("CLOSE HUD  ◀");
        tvClose.setTextColor(Color.parseColor("#44FF88"));
        tvClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvClose.setGravity(Gravity.END);
        tvClose.setClickable(true);
        tvClose.setFocusable(true);
        tvClose.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = dp(10);
        tvClose.setLayoutParams(closeLp);
        p.addView(tvClose);

        return p;
    }

    // ── BOTTOM BAR ────────────────────────────────────────────────────────

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#08111A"));
        bar.setPadding(dp(14), 0, dp(14), 0);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(36));
        barLp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(barLp);

        // Red indicator dot
        View redDot = new View(this);
        android.graphics.drawable.GradientDrawable rdGd =
                new android.graphics.drawable.GradientDrawable();
        rdGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        rdGd.setColor(Color.parseColor("#CC2200"));
        redDot.setBackground(rdGd);
        LinearLayout.LayoutParams rdLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        rdLp.rightMargin = dp(6);
        redDot.setLayoutParams(rdLp);
        pulseView(redDot);
        bar.addView(redDot);

        // THREAT label
        addBottomLabel(bar, "THREAT");
        addBottomSep(bar);
        addBottomLabel(bar, "EVENTS");
        addBottomSep(bar);
        addBottomLabel(bar, "CTRL");
        addBottomSep(bar);
        addBottomLabel(bar, "ANGEL");
        addBottomSep(bar);
        addBottomLabel(bar, "NEXT");

        // Spacer
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // 01/13 page counter
        TextView tvPager = new TextView(this);
        tvPager.setText("01 / 01");
        tvPager.setTextColor(Color.parseColor("#44FFFFFF"));
        tvPager.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvPager.setTypeface(Typeface.MONOSPACE);
        bar.addView(tvPager);

        return bar;
    }

    private void addBottomLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#55FFFFFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        tv.setLetterSpacing(0.1f);
        parent.addView(tv);
    }

    private void addBottomSep(LinearLayout parent) {
        TextView tv = new TextView(this);
        tv.setText("  ·  ");
        tv.setTextColor(Color.parseColor("#22FFFFFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        parent.addView(tv);
    }

    // ── Data load ─────────────────────────────────────────────────────────

    private void loadData() {
        String url = mSession.getJarvisUrl();
        String name = mSession.getUserName();
        if (url == null || name == null) return;

        JarvisClient.fetchMemories(url, name, memories -> runOnUiThread(() -> {
            int c = memories.length();
            mSession.cacheMemoryCount(c);
            if (mLeftVals[0] != null) mLeftVals[0].setText(String.valueOf(c));
        }));

        JarvisClient.fetchWallet(url, name, new JarvisClient.WalletCallback() {
            @Override public void onSuccess(String addr, double sol, double usd) {
                runOnUiThread(() -> {
                    long lamps = (long)(sol * 1_000_000_000L);
                    mTvWalletBig.setText(String.valueOf(lamps));
                    mTvWalletSub.setText(String.format(Locale.US, "◎ %.4f  ·  $%.2f", sol, usd));
                });
            }
            @Override public void onNotConfigured() {
                runOnUiThread(() -> { mTvWalletBig.setText("—"); mTvWalletSub.setText("NO WALLET"); });
            }
            @Override public void onError(String e) {
                runOnUiThread(() -> mTvWalletBig.setText("OFFLINE"));
            }
        });

        JarvisClient.fetchReminders(url, new JarvisClient.RemindersCallback() {
            @Override public void onSuccess(List<JarvisClient.JarvisReminder> r) {
                runOnUiThread(() -> {
                    if (mLeftVals[3] != null) mLeftVals[3].setText(r.size() + " SET");
                    if (mTvMsg != null) updatePillValue(mTvMsg, "MSG", String.valueOf(r.size()));
                });
            }
            @Override public void onError(String e) {}
        });
    }

    // ── Clock ─────────────────────────────────────────────────────────────

    private void startClock() {
        mClockHandler.post(new Runnable() {
            @Override public void run() {
                if (mTvClock != null)
                    mTvClock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
                updateGreeting();
                mClockHandler.postDelayed(this, 30_000);
            }
        });
    }

    private void updateGreeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String g = h < 12 ? "GOOD MORNING," : h < 17 ? "GOOD AFTERNOON," : "GOOD EVENING,";
        if (mTvGreeting != null) mTvGreeting.setText(g);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private TextView makePill(String label, String val) {
        TextView tv = new TextView(this);
        tv.setText(label + "\n" + val);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(createPill(Color.parseColor("#111827")));
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        return tv;
    }

    private void updatePillValue(TextView pill, String label, String val) {
        pill.setText(label + "\n" + val);
    }

    private android.graphics.drawable.Drawable createPill(int color) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(20));
        gd.setColor(color);
        gd.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        return gd;
    }

    private void addSmallLabel(LinearLayout p, String text, int gravity) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#33FFFFFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        tv.setLetterSpacing(0.18f);
        tv.setGravity(gravity);
        p.addView(tv);
    }

    private void addDivider(LinearLayout p) {
        View d = new View(this);
        d.setBackgroundColor(Color.parseColor("#1AFFFFFF"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dlp.topMargin    = dp(8);
        dlp.bottomMargin = dp(4);
        d.setLayoutParams(dlp);
        p.addView(d);
    }

    private void addStatRow(LinearLayout p, String label, String val, int gravity) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(5);
        row.setLayoutParams(rLp);

        TextView tvL = new TextView(this);
        tvL.setText(label);
        tvL.setTextColor(Color.parseColor("#44FFFFFF"));
        tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        tvL.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvL.setLayoutParams(llp);
        if (gravity == Gravity.END) row.addView(tvL); // label left even in right panel

        TextView tvV = new TextView(this);
        tvV.setText(val);
        tvV.setTextColor(Color.WHITE);
        tvV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvV.setTypeface(null, Typeface.BOLD);
        row.addView(tvV);

        if (gravity != Gravity.END) row.addView(tvL);
        p.addView(row);
    }

    private void pulseView(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.2f);
        a.setDuration(900);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.start();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ── Tick Ruler View ───────────────────────────────────────────────────
    // Full width number ruler across top of screen — matches video exactly

    static class TickRulerView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TickRulerView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);
        }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            mPaint.setColor(Color.parseColor("#33FFFFFF"));
            mPaint.setStrokeWidth(1f);
            mPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 6,
                    getResources().getDisplayMetrics()));
            mPaint.setTextAlign(Paint.Align.CENTER);
            // Draw ticks every ~20px, numbers every 5 ticks
            int step = 18;
            for (int x = 0; x < w; x += step) {
                int num = x / step;
                boolean isMajor = num % 5 == 0;
                float tickH = isMajor ? h * 0.7f : h * 0.4f;
                mPaint.setColor(isMajor
                        ? Color.parseColor("#66FFFFFF")
                        : Color.parseColor("#22FFFFFF"));
                c.drawLine(x, h, x, h - tickH, mPaint);
                if (isMajor) {
                    mPaint.setColor(Color.parseColor("#44FFFFFF"));
                    c.drawText(String.valueOf(num), x, h * 0.25f, mPaint);
                }
            }
        }
    }

    // ── Mini Arc View ─────────────────────────────────────────────────────
    // Small spinning arc for left and right panels — matches video side arcs

    static class MiniArcView extends View {
        private final Paint[] mP = new Paint[3];
        private float[] mAngles  = {0f, 45f, 90f};
        private float[] mSpeeds  = {0.8f, -1.2f, 0.5f};
        private final RectF[] mOvals = new RectF[3];
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRunning = false;

        MiniArcView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            int[] colors = {0x80CC2200, 0x6000CCFF, 0x40FFFFFF};
            float[] widths = {3f, 2f, 1.5f};
            for (int i = 0; i < 3; i++) {
                mP[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mP[i].setStyle(Paint.Style.STROKE);
                mP[i].setColor(colors[i]);
                mP[i].setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                        widths[i], ctx.getResources().getDisplayMetrics()));
                mP[i].setStrokeCap(Paint.Cap.ROUND);
                mOvals[i] = new RectF();
            }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            mRunning = true;
            tick();
        }
        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mRunning = false;
            mH.removeCallbacksAndMessages(null);
        }

        private void tick() {
            for (int i = 0; i < 3; i++) mAngles[i] = (mAngles[i] + mSpeeds[i]) % 360f;
            invalidate();
            if (mRunning) mH.postDelayed(this::tick, 16);
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float min = Math.min(getWidth(), getHeight()) / 2f;
            float[] sweeps = {120f, 80f, 200f};
            for (int i = 0; i < 3; i++) {
                float r = min * (0.95f - i * 0.22f);
                mOvals[i].set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(mOvals[i], mAngles[i], sweeps[i], false, mP[i]);
            }
            // Center dot
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(Color.parseColor("#CC2200"));
            c.drawCircle(cx, cy, min * 0.15f, dot);
        }
    }

    // ── Main Spinning Arc ─────────────────────────────────────────────────
    // Large center HUD arc — concentric rings, tick marks, center orb

    static class SpinningArcView extends View {
        private final Paint[] mP;
        private final float[] mAngles = new float[6];
        private final float[] mSpeeds = {0.3f, -0.5f, 0.7f, -0.4f, 0.2f, -0.9f};
        private final RectF[] mOvals;
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRunning = false;

        private static final int[]   COLORS = {
            0x33FFFFFF, 0x80CC2200, 0x6000CCFF, 0xFFFFFFFF, 0x40CC2200, 0x2200FFAA};
        private static final float[] WIDTHS  = {2f, 3f, 1.5f, 1f, 4f, 1f};
        private static final float[] SWEEPS  = {200f, 80f, 160f, 340f, 40f, 220f};

        SpinningArcView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            mP = new Paint[6];
            mOvals = new RectF[6];
            float density = ctx.getResources().getDisplayMetrics().density;
            for (int i = 0; i < 6; i++) {
                mP[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mP[i].setStyle(Paint.Style.STROKE);
                mP[i].setColor(COLORS[i]);
                mP[i].setStrokeWidth(WIDTHS[i] * density);
                mP[i].setStrokeCap(Paint.Cap.ROUND);
                mOvals[i] = new RectF();
            }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            mRunning = true;
            tick();
        }
        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mRunning = false;
            mH.removeCallbacksAndMessages(null);
        }

        private void tick() {
            for (int i = 0; i < 6; i++) mAngles[i] = (mAngles[i] + mSpeeds[i]) % 360f;
            invalidate();
            if (mRunning) mH.postDelayed(this::tick, 16);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float min = Math.min(w, h) / 2f;

            for (int i = 0; i < 6; i++) {
                float r = min * (0.95f - i * 0.11f);
                mOvals[i].set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(mOvals[i], mAngles[i], SWEEPS[i], false, mP[i]);
            }

            // Tick marks on outermost ring
            Paint tickP = new Paint(Paint.ANTI_ALIAS_FLAG);
            tickP.setStrokeWidth(1.5f);
            float outerR = min * 0.95f;
            for (int t = 0; t < 72; t++) {
                double angle = Math.toRadians(t * 5.0);
                boolean major = t % 5 == 0;
                tickP.setColor(major ? Color.parseColor("#55FFFFFF") : Color.parseColor("#22FFFFFF"));
                float tickLen = outerR * (major ? 0.05f : 0.025f);
                float sx = cx + (float)(Math.cos(angle) * (outerR - tickLen));
                float sy = cy + (float)(Math.sin(angle) * (outerR - tickLen));
                float ex = cx + (float)(Math.cos(angle) * outerR);
                float ey = cy + (float)(Math.sin(angle) * outerR);
                c.drawLine(sx, sy, ex, ey, tickP);
            }

            // Center orb — red core
            Paint orb = new Paint(Paint.ANTI_ALIAS_FLAG);
            orb.setStyle(Paint.Style.FILL);
            orb.setColor(Color.parseColor("#08003399"));
            c.drawCircle(cx, cy, min * 0.22f, orb);
            orb.setColor(Color.parseColor("#CC2200"));
            c.drawCircle(cx, cy, min * 0.06f, orb);
            orb.setStyle(Paint.Style.STROKE);
            orb.setColor(Color.parseColor("#66FFFFFF"));
            orb.setStrokeWidth(2f);
            c.drawCircle(cx, cy, min * 0.12f, orb);
        }
    }
}
