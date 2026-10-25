package com.jarvis.assist.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * LandscapeHudActivity — Iron Man / JARVIS HUD
 * Time-aware greeting: GOOD MORNING / AFTERNOON / EVENING + real user name (BIG)
 */
public class LandscapeHudActivity extends AppCompatActivity {

    private SessionStore mSession;
    private final Handler mClockHandler = new Handler(Looper.getMainLooper());

    // Center
    private TextView mTvClock;
    private SpinningArcView mMainArc;

    // Left panel
    private TextView mTvGreetingLine;   // "GOOD AFTERNOON,"
    private TextView mTvUserNameBig;    // "JACK"  — massive
    private TextView mTvSubtitle;       // "SYSTEM ACTIVE · ONLINE"
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

        // ── TICK RULER ──────────────────────────────────────────────────
        TickRulerView ruler = new TickRulerView(this);
        FrameLayout.LayoutParams rulerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(22));
        rulerLp.gravity = Gravity.TOP;
        root.addView(ruler, rulerLp);

        // ── RED ACCENT LINE ─────────────────────────────────────────────
        View redLine = new View(this);
        redLine.setBackgroundColor(Color.parseColor("#CC2200"));
        FrameLayout.LayoutParams rlLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(2));
        rlLp.gravity = Gravity.TOP;
        root.addView(redLine, rlLp);

        // ── CLOCK — top right ────────────────────────────────────────────
        mTvClock = new TextView(this);
        mTvClock.setText("00:00");
        mTvClock.setTextColor(Color.WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mTvClock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        clockLp.gravity = Gravity.TOP | Gravity.END;
        clockLp.topMargin   = dp(2);
        clockLp.rightMargin = dp(12);
        mTvClock.setLayoutParams(clockLp);
        root.addView(mTvClock);

        // ── MAIN 3-PANEL ROW ─────────────────────────────────────────────
        LinearLayout panels = new LinearLayout(this);
        panels.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams panelsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        panelsLp.topMargin    = dp(22);
        panelsLp.bottomMargin = dp(38);
        panels.setLayoutParams(panelsLp);
        root.addView(panels);

        panels.addView(buildLeftPanel(),   weightLp(0.28f));
        panels.addView(buildCenterPanel(), weightLp(0.44f));
        panels.addView(buildRightPanel(),  weightLp(0.28f));

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
        p.setPadding(dp(14), dp(8), dp(6), dp(8));

        // Mini spinning arc
        MiniArcView miniArc = new MiniArcView(this);
        LinearLayout.LayoutParams arcLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        arcLp.bottomMargin = dp(10);
        miniArc.setLayoutParams(arcLp);
        p.addView(miniArc);

        // ── GREETING LINE — e.g. "GOOD AFTERNOON," ─────────────────────
        mTvGreetingLine = new TextView(this);
        mTvGreetingLine.setTextColor(Color.parseColor("#88FFFFFF"));
        mTvGreetingLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mTvGreetingLine.setLetterSpacing(0.12f);
        mTvGreetingLine.setTypeface(Typeface.DEFAULT_BOLD);
        p.addView(mTvGreetingLine);

        // ── USER NAME — BIG, white, bold ────────────────────────────────
        mTvUserNameBig = new TextView(this);
        String rawName = mSession.getUserName();
        mTvUserNameBig.setText(rawName != null ? rawName.toUpperCase(Locale.US) : "OPERATOR");
        mTvUserNameBig.setTextColor(Color.WHITE);
        mTvUserNameBig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);   // BIG like video
        mTvUserNameBig.setTypeface(null, Typeface.BOLD);
        mTvUserNameBig.setLetterSpacing(0.05f);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nameLp.bottomMargin = dp(2);
        mTvUserNameBig.setLayoutParams(nameLp);
        p.addView(mTvUserNameBig);

        // subtitle — "SYSTEM ACTIVE · ONLINE"
        mTvSubtitle = new TextView(this);
        mTvSubtitle.setText("SYSTEM ACTIVE · ONLINE");
        mTvSubtitle.setTextColor(Color.parseColor("#44FF88"));
        mTvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        mTvSubtitle.setLetterSpacing(0.1f);
        p.addView(mTvSubtitle);

        addDivider(p);

        // Numbered status rows
        String[] labels   = {"MEMORY", "VAULT",  "SESSION", "NETWORK", "SYNC"};
        String[] initVals = {"—",      "LOCKED", "—",       "ONLINE",  "LIVE"};
        int[]    colors   = {0xFF44FF88, 0xFFCC2200, 0xFF4499FF, 0xFF44FF88, 0xFFFFD700};

        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = dp(5);
            row.setLayoutParams(rLp);

            TextView tvNum = new TextView(this);
            tvNum.setText(String.format(Locale.US, "%02d", i + 1));
            tvNum.setTextColor(Color.parseColor("#33FFFFFF"));
            tvNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            tvNum.setTypeface(Typeface.MONOSPACE);
            tvNum.setPadding(0, 0, dp(6), 0);
            row.addView(tvNum);

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

        mMainArc = new SpinningArcView(this);
        p.addView(mMainArc, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // LIVE badge
        TextView tvLive = new TextView(this);
        tvLive.setText("● LIVE");
        tvLive.setTextColor(Color.WHITE);
        tvLive.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvLive.setLetterSpacing(0.1f);
        tvLive.setBackground(createPill(Color.parseColor("#CC2200")));
        tvLive.setPadding(dp(8), dp(3), dp(8), dp(3));
        tvLive.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams liveLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        liveLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        liveLp.topMargin = dp(30);
        tvLive.setLayoutParams(liveLp);
        pulseView(tvLive);
        p.addView(tvLive);

        // LANDSCAPE mode tag
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

        // Blue status dot
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

        // Bottom center: red mic button
        View micBtn = new View(this);
        android.graphics.drawable.GradientDrawable micGd =
                new android.graphics.drawable.GradientDrawable();
        micGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        micGd.setColor(Color.parseColor("#CC2200"));
        micGd.setStroke(dp(2), Color.parseColor("#FF4422"));
        micBtn.setBackground(micGd);
        FrameLayout.LayoutParams micLp = new FrameLayout.LayoutParams(dp(28), dp(28));
        micLp.gravity      = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        micLp.bottomMargin = dp(4);
        micBtn.setLayoutParams(micLp);
        pulseView(micBtn);
        p.addView(micBtn);

        // Arc ring labels (CENTRAL, OFF, NEXT, GRST around ring)
        addRingLabel(p, "CENTRAL", 0f);
        addRingLabel(p, "OFF",     90f);
        addRingLabel(p, "NEXT",    180f);
        addRingLabel(p, "GRST",    270f);

        return p;
    }

    /** Positions a text label around the center arc ring at the given angle (degrees). */
    private void addRingLabel(FrameLayout parent, String text, float angleDeg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#44FFFFFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7);
        tv.setLetterSpacing(0.1f);
        tv.setTypeface(Typeface.MONOSPACE);

        // We position using post() once view has dimensions
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        parent.addView(tv);

        // Defer placement until layout pass
        parent.post(() -> {
            int pw = parent.getWidth();
            int ph = parent.getHeight();
            float cx = pw / 2f;
            float cy = ph / 2f;
            float radius = Math.min(pw, ph) / 2f * 0.72f;
            double rad = Math.toRadians(angleDeg);
            float x = cx + (float)(Math.cos(rad) * radius) - tv.getWidth() / 2f;
            float y = cy + (float)(Math.sin(rad) * radius) - tv.getHeight() / 2f;
            tv.setX(x);
            tv.setY(y);
        });
    }

    // ── RIGHT PANEL ───────────────────────────────────────────────────────

    private LinearLayout buildRightPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(6), dp(8), dp(14), dp(8));
        p.setGravity(Gravity.END);

        MiniArcView miniArcR = new MiniArcView(this);
        LinearLayout.LayoutParams arcLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        arcLp.gravity = Gravity.END;
        arcLp.bottomMargin = dp(8);
        miniArcR.setLayoutParams(arcLp);
        p.addView(miniArcR);

        addSmallLabel(p, "SYSTEM DATA", Gravity.END);
        addDivider(p);

        // BIG balance — "9296346" — 40sp to match video
        mTvWalletBig = new TextView(this);
        mTvWalletBig.setText("—");
        mTvWalletBig.setTextColor(Color.WHITE);
        mTvWalletBig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);   // BIG like video
        mTvWalletBig.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTvWalletBig.setGravity(Gravity.END);
        LinearLayout.LayoutParams wbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wbLp.topMargin = dp(4);
        mTvWalletBig.setLayoutParams(wbLp);
        p.addView(mTvWalletBig);

        mTvWalletSub = new TextView(this);
        mTvWalletSub.setText("SOLANA WALLET");
        mTvWalletSub.setTextColor(Color.parseColor("#44FF88"));
        mTvWalletSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        mTvWalletSub.setGravity(Gravity.END);
        p.addView(mTvWalletSub);

        addDivider(p);

        // BATT · value HUGE  — "BATT 10" style where 10 is big
        buildBattRow(p);

        addDivider(p);

        // MSG pill
        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = dp(6);
        pillRow.setLayoutParams(prLp);

        mTvMsg = makePill("MSG", "0");
        mTvCal = makePill("CAL", "—");

        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pillLp.leftMargin = dp(4);
        pillRow.addView(mTvMsg, pillLp);
        pillRow.addView(mTvCal, pillLp);
        p.addView(pillRow);

        addDivider(p);

        addStatRow(p, "RETURNS", "—",   Gravity.END);
        addStatRow(p, "ALERTS",  "0",   Gravity.END);
        addStatRow(p, "BEAT",    "NOM", Gravity.END);

        addDivider(p);

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
        closeLp.topMargin = dp(8);
        tvClose.setLayoutParams(closeLp);
        p.addView(tvClose);

        return p;
    }

    /**
     * "BATT  10" style row — label small, value huge, like the video right panel.
     */
    private void buildBattRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(6);
        row.setLayoutParams(rLp);

        // Spacer pushes to end
        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView tvBattLabel = new TextView(this);
        tvBattLabel.setText("BATT");
        tvBattLabel.setTextColor(Color.parseColor("#66FFFFFF"));
        tvBattLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvBattLabel.setLetterSpacing(0.1f);
        tvBattLabel.setPadding(0, 0, dp(4), 0);
        row.addView(tvBattLabel);

        mTvBatt = new TextView(this);
        mTvBatt.setText("—");
        mTvBatt.setTextColor(Color.WHITE);
        mTvBatt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);  // huge number like video
        mTvBatt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(mTvBatt);

        parent.addView(row);
        updateBatteryLevel();
    }

    private void updateBatteryLevel() {
        android.content.IntentFilter ifilter =
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
        android.content.Intent status = registerReceiver(null, ifilter);
        if (status != null) {
            int level = status.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = status.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            int pct   = (level * 100) / scale;
            if (mTvBatt != null) mTvBatt.setText(String.valueOf(pct));
        }
    }

    // ── BOTTOM BAR ────────────────────────────────────────────────────────

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#08111A"));
        bar.setPadding(dp(14), 0, dp(14), 0);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(38));
        barLp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(barLp);

        // Top separator line
        View topLine = new View(this);
        // We do this via a compound approach: wrap bar in FrameLayout
        // Actually just add left border accent via padding and background — keep as is

        // Pulsing red dot
        View redDot = new View(this);
        android.graphics.drawable.GradientDrawable rdGd =
                new android.graphics.drawable.GradientDrawable();
        rdGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        rdGd.setColor(Color.parseColor("#CC2200"));
        redDot.setBackground(rdGd);
        LinearLayout.LayoutParams rdLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        rdLp.rightMargin = dp(8);
        redDot.setLayoutParams(rdLp);
        pulseView(redDot);
        bar.addView(redDot);

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

        // Red mic button in center-ish of bottom bar
        View micSmall = new View(this);
        android.graphics.drawable.GradientDrawable msGd =
                new android.graphics.drawable.GradientDrawable();
        msGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        msGd.setColor(Color.parseColor("#CC2200"));
        micSmall.setBackground(msGd);
        LinearLayout.LayoutParams msLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        msLp.leftMargin  = dp(8);
        msLp.rightMargin = dp(8);
        micSmall.setLayoutParams(msLp);
        pulseView(micSmall);
        bar.addView(micSmall);

        // Second spacer
        View spacer2 = new View(this);
        bar.addView(spacer2, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // 01/13 counter
        TextView tvPager = new TextView(this);
        tvPager.setText("01 / 13");
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
        String url  = mSession.getJarvisUrl();
        String name = mSession.getUserName();
        if (url == null || name == null) return;

        JarvisClient.fetchMemories(url, name, memories -> runOnUiThread(() -> {
            if (mLeftVals[0] != null) mLeftVals[0].setText(String.valueOf(memories.length()));
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

    // ── Clock + Greeting ──────────────────────────────────────────────────

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

    /**
     * Time-aware greeting using the device's local clock.
     *   00:00 – 11:59  →  GOOD MORNING,
     *   12:00 – 16:59  →  GOOD AFTERNOON,
     *   17:00 – 20:59  →  GOOD EVENING,
     *   21:00 – 23:59  →  GOOD NIGHT,
     *
     * Then pulls the real user name from SessionStore and shows it big.
     */
    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

        String greeting;
        if (hour < 12) {
            greeting = "GOOD MORNING,";
        } else if (hour < 17) {
            greeting = "GOOD AFTERNOON,";
        } else if (hour < 21) {
            greeting = "GOOD EVENING,";
        } else {
            greeting = "GOOD NIGHT,";
        }

        if (mTvGreetingLine != null) mTvGreetingLine.setText(greeting);

        // Refresh name in case session updated
        if (mTvUserNameBig != null) {
            String name = mSession.getUserName();
            mTvUserNameBig.setText(name != null ? name.toUpperCase(Locale.US) : "OPERATOR");
        }
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
        row.addView(tvL);

        TextView tvV = new TextView(this);
        tvV.setText(val);
        tvV.setTextColor(Color.WHITE);
        tvV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvV.setTypeface(null, Typeface.BOLD);
        row.addView(tvV);

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

    // ═══════════════════════════════════════════════════════════════════════
    // INNER VIEWS
    // ═══════════════════════════════════════════════════════════════════════

    // ── Tick Ruler ────────────────────────────────────────────────────────

    static class TickRulerView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TickRulerView(android.content.Context ctx) { super(ctx); setWillNotDraw(false); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            mPaint.setStrokeWidth(1f);
            mPaint.setTextAlign(Paint.Align.CENTER);
            mPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 6,
                    getResources().getDisplayMetrics()));
            int step = 18;
            for (int x = 0; x < w; x += step) {
                int num = x / step;
                boolean isMajor = num % 5 == 0;
                float tickH = isMajor ? h * 0.7f : h * 0.4f;
                mPaint.setColor(isMajor
                        ? Color.parseColor("#66FFFFFF")
                        : Color.parseColor("#22FFFFFF"));
                mPaint.setStyle(Paint.Style.STROKE);
                c.drawLine(x, h, x, h - tickH, mPaint);
                if (isMajor) {
                    mPaint.setStyle(Paint.Style.FILL);
                    mPaint.setColor(Color.parseColor("#44FFFFFF"));
                    c.drawText(String.valueOf(num), x, h * 0.25f, mPaint);
                }
            }
        }
    }

    // ── Mini Arc ──────────────────────────────────────────────────────────

    static class MiniArcView extends View {
        private final Paint[] mP = new Paint[3];
        private final float[] mAngles = {0f, 45f, 90f};
        private final float[] mSpeeds = {0.8f, -1.2f, 0.5f};
        private final RectF[]  mOvals  = new RectF[3];
        private final Handler  mH      = new Handler(Looper.getMainLooper());
        private boolean mRunning = false;

        MiniArcView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            int[]   colors = {0x80CC2200, 0x6000CCFF, 0x40FFFFFF};
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

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow(); mRunning = true; tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRunning = false; mH.removeCallbacksAndMessages(null); }

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
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(Color.parseColor("#CC2200"));
            c.drawCircle(cx, cy, min * 0.15f, dot);
        }
    }

    // ── Main Spinning Arc ─────────────────────────────────────────────────

    static class SpinningArcView extends View {
        private final Paint[] mP;
        private final float[] mAngles = new float[6];
        private final float[] mSpeeds = {0.3f, -0.5f, 0.7f, -0.4f, 0.2f, -0.9f};
        private final RectF[] mOvals;
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRunning = false;

        // Segmented block angles around inner ring (like video center arc blocks)
        private static final float[] BLOCK_ANGLES = {0f, 30f, 60f, 90f, 120f, 150f, 210f, 240f, 270f, 300f, 330f};
        private float mBlockRotation = 0f;

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

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow(); mRunning = true; tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRunning = false; mH.removeCallbacksAndMessages(null); }

        private void tick() {
            for (int i = 0; i < 6; i++) mAngles[i] = (mAngles[i] + mSpeeds[i]) % 360f;
            mBlockRotation = (mBlockRotation + 0.4f) % 360f;
            invalidate();
            if (mRunning) mH.postDelayed(this::tick, 16);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float min = Math.min(w, h) / 2f;

            // Concentric spinning arcs
            for (int i = 0; i < 6; i++) {
                float r = min * (0.95f - i * 0.11f);
                mOvals[i].set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(mOvals[i], mAngles[i], SWEEPS[i], false, mP[i]);
            }

            // Segmented square blocks on 3rd ring — like video center
            float blockRingR = min * 0.62f;
            Paint blockP = new Paint(Paint.ANTI_ALIAS_FLAG);
            blockP.setStyle(Paint.Style.FILL);
            blockP.setColor(Color.parseColor("#334499FF"));
            float blockSize = min * 0.06f;
            c.save();
            c.rotate(mBlockRotation, cx, cy);
            for (float a : BLOCK_ANGLES) {
                double rad = Math.toRadians(a);
                float bx = cx + (float)(Math.cos(rad) * blockRingR) - blockSize / 2f;
                float by = cy + (float)(Math.sin(rad) * blockRingR) - blockSize / 2f;
                c.drawRect(bx, by, bx + blockSize, by + blockSize, blockP);
            }
            c.restore();

            // Tick marks on outermost ring
            Paint tickP = new Paint(Paint.ANTI_ALIAS_FLAG);
            tickP.setStrokeWidth(1.5f);
            float outerR = min * 0.95f;
            for (int t = 0; t < 72; t++) {
                double angle = Math.toRadians(t * 5.0);
                boolean major = t % 5 == 0;
                tickP.setColor(major
                        ? Color.parseColor("#55FFFFFF")
                        : Color.parseColor("#22FFFFFF"));
                float tickLen = outerR * (major ? 0.05f : 0.025f);
                float sx = cx + (float)(Math.cos(angle) * (outerR - tickLen));
                float sy = cy + (float)(Math.sin(angle) * (outerR - tickLen));
                float ex = cx + (float)(Math.cos(angle) * outerR);
                float ey = cy + (float)(Math.sin(angle) * outerR);
                c.drawLine(sx, sy, ex, ey, tickP);
            }

            // Center orb
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
