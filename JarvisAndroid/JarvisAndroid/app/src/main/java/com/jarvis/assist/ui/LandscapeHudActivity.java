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
import android.util.DisplayMetrics;
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
 * All sizes are computed from actual screen dimensions at runtime,
 * so the layout fits any phone — small 5" up to large 6.8" displays.
 */
public class LandscapeHudActivity extends AppCompatActivity {

    private SessionStore mSession;
    private final Handler mClockHandler = new Handler(Looper.getMainLooper());

    // Responsive size tokens — set once in onCreate from screen height
    private float SS;   // "screen scale" base unit = screenHeight / 100
                        //   used for text sizes (sp-ish but screen-relative)
    private float SD;   // dp scale from density

    // Views
    private TextView mTvClock;
    private TextView mTvGreetingLine;
    private TextView mTvUserNameBig;
    private TextView mTvDateLine;
    private TextView mTvSubtitle;
    private TextView[] mLeftVals = new TextView[5];
    private TextView mTvWalletBig;
    private TextView mTvWalletSub;
    private TextView mTvBattNum;
    private TextView mTvMsg;
    private TextView mTvCal;
    private TextView mTvDone;

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

        // Compute screen-relative scale tokens
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // In landscape the SHORT side is the "height" — that's what fills vertically
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        SS = shortSide / 100f / dm.scaledDensity;  // gives sp that fills ~1% screen height
        SD = dm.density;

        buildUI();
        startClock();
        loadData();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        mClockHandler.removeCallbacksAndMessages(null);
    }

    // ── Scale helpers ─────────────────────────────────────────────────────

    /** sp size that is `pct`% of the screen's short side */
    private float sp(float pct) { return SS * pct; }

    /** dp → px */
    private int dp(float v) { return Math.round(v * SD); }

    // ── Build UI ──────────────────────────────────────────────────────────

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Tick ruler — height = 2.5% short side
        TickRulerView ruler = new TickRulerView(this);
        FrameLayout.LayoutParams rulerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(22));
        rulerLp.gravity = Gravity.TOP;
        root.addView(ruler, rulerLp);

        // Red accent line
        View redLine = new View(this);
        redLine.setBackgroundColor(Color.parseColor("#CC2200"));
        FrameLayout.LayoutParams rlLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(2));
        rlLp.gravity = Gravity.TOP;
        root.addView(redLine, rlLp);

        // Clock — top right
        mTvClock = new TextView(this);
        mTvClock.setText("00:00");
        mTvClock.setTextColor(Color.WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(4.2f));
        mTvClock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        clockLp.gravity = Gravity.TOP | Gravity.END;
        clockLp.topMargin   = dp(2);
        clockLp.rightMargin = dp(10);
        mTvClock.setLayoutParams(clockLp);
        root.addView(mTvClock);

        // 3-panel row
        LinearLayout panels = new LinearLayout(this);
        panels.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams panelsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        panelsLp.topMargin    = dp(22);
        panelsLp.bottomMargin = dp(36);
        panels.setLayoutParams(panelsLp);
        root.addView(panels);

        panels.addView(buildLeftPanel(),   wLp(0.28f));
        panels.addView(buildCenterPanel(), wLp(0.44f));
        panels.addView(buildRightPanel(),  wLp(0.28f));

        root.addView(buildBottomBar());
        setContentView(root);
    }

    private LinearLayout.LayoutParams wLp(float w) {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, w);
    }

    // ── LEFT PANEL ────────────────────────────────────────────────────────

    private LinearLayout buildLeftPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(10), dp(6), dp(4), dp(6));

        // Mini arc — size = 12% of short side
        MiniArcView miniArc = new MiniArcView(this);
        int arcSz = dp(48);
        LinearLayout.LayoutParams arcLp = new LinearLayout.LayoutParams(arcSz, arcSz);
        arcLp.bottomMargin = dp(6);
        miniArc.setLayoutParams(arcLp);
        p.addView(miniArc);

        // GOOD AFTERNOON, — sp(2.8) fits ~11sp on a 5" and ~14sp on a 6.7"
        mTvGreetingLine = new TextView(this);
        mTvGreetingLine.setTextColor(Color.parseColor("#88FFFFFF"));
        mTvGreetingLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.8f));
        mTvGreetingLine.setLetterSpacing(0.12f);
        mTvGreetingLine.setTypeface(null, Typeface.BOLD);
        p.addView(mTvGreetingLine);

        // USER NAME — sp(5.5) ≈ 22sp on 5", 28sp on 6.7"
        mTvUserNameBig = new TextView(this);
        String rawName = mSession.getUserName();
        mTvUserNameBig.setText(rawName != null ? rawName.toUpperCase(Locale.US) : "OPERATOR");
        mTvUserNameBig.setTextColor(Color.WHITE);
        mTvUserNameBig.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(5.5f));
        mTvUserNameBig.setTypeface(null, Typeface.BOLD);
        mTvUserNameBig.setLetterSpacing(0.04f);
        // Allow shrink so long names don't clip
        mTvUserNameBig.setMaxLines(1);
        mTvUserNameBig.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.addView(mTvUserNameBig);

        // Date line — "09 APR" style
        mTvDateLine = new TextView(this);
        mTvDateLine.setTextColor(Color.parseColor("#44FF88"));
        mTvDateLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.0f));
        mTvDateLine.setLetterSpacing(0.12f);
        mTvDateLine.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dateLp.bottomMargin = dp(4);
        mTvDateLine.setLayoutParams(dateLp);
        p.addView(mTvDateLine);

        // "SYSTEM ACTIVE · ONLINE"
        mTvSubtitle = new TextView(this);
        mTvSubtitle.setText("SYSTEM ACTIVE · ONLINE");
        mTvSubtitle.setTextColor(Color.parseColor("#335566"));
        mTvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        mTvSubtitle.setLetterSpacing(0.1f);
        p.addView(mTvSubtitle);

        addDivider(p);

        // Status rows
        String[] labels   = {"MEMORY","VAULT","SESSION","NETWORK","SYNC"};
        String[] initVals = {"—","LOCKED","—","ONLINE","LIVE"};
        int[]    colors   = {0xFF44FF88,0xFFCC2200,0xFF4499FF,0xFF44FF88,0xFFFFD700};

        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = dp(4);
            row.setLayoutParams(rLp);

            TextView tvNum = new TextView(this);
            tvNum.setText(String.format(Locale.US, "%02d", i + 1));
            tvNum.setTextColor(Color.parseColor("#33FFFFFF"));
            tvNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
            tvNum.setTypeface(Typeface.MONOSPACE);
            tvNum.setPadding(0, 0, dp(5), 0);
            row.addView(tvNum);

            View dot = new View(this);
            android.graphics.drawable.GradientDrawable dg =
                    new android.graphics.drawable.GradientDrawable();
            dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dg.setColor(colors[i]);
            dot.setBackground(dg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(4), dp(4));
            dotLp.rightMargin = dp(5);
            dot.setLayoutParams(dotLp);
            row.addView(dot);

            TextView tvLbl = new TextView(this);
            tvLbl.setText(labels[i]);
            tvLbl.setTextColor(Color.parseColor("#55FFFFFF"));
            tvLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
            tvLbl.setLetterSpacing(0.06f);
            tvLbl.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvLbl);

            TextView tvVal = new TextView(this);
            tvVal.setText(initVals[i]);
            tvVal.setTextColor(colors[i]);
            tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.0f));
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

        // Spinning arc — fills panel
        SpinningArcView arc = new SpinningArcView(this);
        p.addView(arc, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // LIVE badge
        TextView tvLive = new TextView(this);
        tvLive.setText("● LIVE");
        tvLive.setTextColor(Color.WHITE);
        tvLive.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        tvLive.setLetterSpacing(0.1f);
        tvLive.setBackground(pill(Color.parseColor("#CC2200")));
        tvLive.setPadding(dp(7), dp(2), dp(7), dp(2));
        tvLive.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams liveLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        liveLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        liveLp.topMargin = dp(26);
        tvLive.setLayoutParams(liveLp);
        pulseView(tvLive);
        p.addView(tvLive);

        // Date/time sub-line under LIVE
        TextView tvCenterDate = new TextView(this);
        tvCenterDate.setTextColor(Color.parseColor("#33FFFFFF"));
        tvCenterDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        tvCenterDate.setLetterSpacing(0.1f);
        tvCenterDate.setTypeface(Typeface.MONOSPACE);
        tvCenterDate.setGravity(Gravity.CENTER);
        tvCenterDate.setText(new SimpleDateFormat("EEE dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US));
        FrameLayout.LayoutParams cdLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cdLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cdLp.topMargin = dp(46);
        tvCenterDate.setLayoutParams(cdLp);
        p.addView(tvCenterDate);

        // LANDSCAPE mode tag
        TextView tvMode = new TextView(this);
        tvMode.setText("◈ LANDSCAPE");
        tvMode.setTextColor(Color.parseColor("#CC2200"));
        tvMode.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
        tvMode.setLetterSpacing(0.1f);
        FrameLayout.LayoutParams modeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        modeLp.gravity = Gravity.TOP | Gravity.END;
        modeLp.topMargin   = dp(4);
        modeLp.rightMargin = dp(6);
        tvMode.setLayoutParams(modeLp);
        p.addView(tvMode);

        // Blue status dot
        View sc = new View(this);
        android.graphics.drawable.GradientDrawable scGd =
                new android.graphics.drawable.GradientDrawable();
        scGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        scGd.setColor(Color.parseColor("#4488FF"));
        sc.setBackground(scGd);
        FrameLayout.LayoutParams scLp = new FrameLayout.LayoutParams(dp(9), dp(9));
        scLp.gravity   = Gravity.TOP | Gravity.START;
        scLp.topMargin  = dp(6);
        scLp.leftMargin = dp(6);
        sc.setLayoutParams(scLp);
        pulseView(sc);
        p.addView(sc);

        // Red mic circle — bottom center
        View mic = new View(this);
        android.graphics.drawable.GradientDrawable micGd =
                new android.graphics.drawable.GradientDrawable();
        micGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        micGd.setColor(Color.parseColor("#CC2200"));
        micGd.setStroke(dp(2), Color.parseColor("#FF4422"));
        mic.setBackground(micGd);
        int micSz = dp(26);
        FrameLayout.LayoutParams micLp = new FrameLayout.LayoutParams(micSz, micSz);
        micLp.gravity      = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        micLp.bottomMargin = dp(3);
        mic.setLayoutParams(micLp);
        pulseView(mic);
        p.addView(mic);

        // Ring labels — deferred until layout
        addRingLabel(p, "CENTRAL",   0f);
        addRingLabel(p, "OFF",       90f);
        addRingLabel(p, "NEXT",     180f);
        addRingLabel(p, "GRST",     270f);

        return p;
    }

    private void addRingLabel(FrameLayout parent, String text, float angleDeg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#44FFFFFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        tv.setLetterSpacing(0.1f);
        tv.setTypeface(Typeface.MONOSPACE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        parent.addView(tv);
        parent.post(() -> {
            float cx = parent.getWidth() / 2f;
            float cy = parent.getHeight() / 2f;
            float radius = Math.min(parent.getWidth(), parent.getHeight()) / 2f * 0.70f;
            double rad = Math.toRadians(angleDeg);
            tv.setX(cx + (float)(Math.cos(rad) * radius) - tv.getWidth() / 2f);
            tv.setY(cy + (float)(Math.sin(rad) * radius) - tv.getHeight() / 2f);
        });
    }

    // ── RIGHT PANEL ───────────────────────────────────────────────────────

    private LinearLayout buildRightPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(4), dp(6), dp(10), dp(6));
        p.setGravity(Gravity.END);

        MiniArcView miniArcR = new MiniArcView(this);
        int arcSz = dp(48);
        LinearLayout.LayoutParams arcLp = new LinearLayout.LayoutParams(arcSz, arcSz);
        arcLp.gravity = Gravity.END;
        arcLp.bottomMargin = dp(6);
        miniArcR.setLayoutParams(arcLp);
        p.addView(miniArcR);

        smallLabel(p, "SYSTEM DATA", Gravity.END);
        addDivider(p);

        // BIG wallet number — sp(9) ≈ 36sp on 5", 46sp on 6.7"
        mTvWalletBig = new TextView(this);
        mTvWalletBig.setText("—");
        mTvWalletBig.setTextColor(Color.WHITE);
        mTvWalletBig.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(9.0f));
        mTvWalletBig.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTvWalletBig.setGravity(Gravity.END);
        mTvWalletBig.setMaxLines(1);
        mTvWalletBig.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM);
        mTvWalletBig.setAutoSizeTextTypeUniformWithConfiguration(
        Math.round(sp(4f)), Math.round(sp(9f)), 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams wbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wbLp.topMargin = dp(3);
        mTvWalletBig.setLayoutParams(wbLp);
        p.addView(mTvWalletBig);

        mTvWalletSub = new TextView(this);
        mTvWalletSub.setText("SOLANA WALLET");
        mTvWalletSub.setTextColor(Color.parseColor("#44FF88"));
        mTvWalletSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        mTvWalletSub.setGravity(Gravity.END);
        p.addView(mTvWalletSub);

        addDivider(p);

        // BATT row — label small, number huge
        buildBattRow(p);

        addDivider(p);

        // MSG + CAL + DONE pills
        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = dp(5);
        pillRow.setLayoutParams(prLp);

        mTvMsg  = makePill("MSG",  "0");
        mTvCal  = makePill("CAL",  "—");
        mTvDone = makePill("DONE", "10");

        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pLp.leftMargin = dp(3);
        pillRow.addView(mTvMsg,  pLp);
        pillRow.addView(mTvCal,  pLp);
        pillRow.addView(mTvDone, pLp);
        p.addView(pillRow);

        addDivider(p);

        statRow(p, "RETURNS", "—",   Gravity.END);
        statRow(p, "ALERTS",  "0",   Gravity.END);
        statRow(p, "BEAT",    "NOM", Gravity.END);

        addDivider(p);

        TextView tvClose = new TextView(this);
        tvClose.setText("CLOSE HUD  ◀");
        tvClose.setTextColor(Color.parseColor("#44FF88"));
        tvClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        tvClose.setGravity(Gravity.END);
        tvClose.setClickable(true);
        tvClose.setFocusable(true);
        tvClose.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(7);
        tvClose.setLayoutParams(cLp);
        p.addView(tvClose);

        return p;
    }

    private void buildBattRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(4);
        row.setLayoutParams(rLp);

        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView tvLbl = new TextView(this);
        tvLbl.setText("BATT");
        tvLbl.setTextColor(Color.parseColor("#66FFFFFF"));
        tvLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.2f));
        tvLbl.setLetterSpacing(0.1f);
        tvLbl.setPadding(0, 0, dp(3), 0);
        row.addView(tvLbl);

        mTvBattNum = new TextView(this);
        mTvBattNum.setText("—");
        mTvBattNum.setTextColor(Color.WHITE);
        mTvBattNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(7.5f));
        mTvBattNum.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(mTvBattNum);

        parent.addView(row);
        updateBattery();
    }

    private void updateBattery() {
        android.content.IntentFilter f =
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
        android.content.Intent s = registerReceiver(null, f);
        if (s != null && mTvBattNum != null) {
            int lvl   = s.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = s.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            mTvBattNum.setText(String.valueOf((lvl * 100) / scale));
        }
    }

    // ── BOTTOM BAR ────────────────────────────────────────────────────────

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#08111A"));
        bar.setPadding(dp(12), 0, dp(12), 0);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(36));
        barLp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(barLp);

        // Pulsing red dot
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

        String[] labels = {"THREAT","EVENTS","CTRL","ANGEL","NEXT"};
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("  ·  ");
                sep.setTextColor(Color.parseColor("#22FFFFFF"));
                sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
                bar.addView(sep);
            }
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextColor(Color.parseColor("#55FFFFFF"));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
            tv.setLetterSpacing(0.1f);
            bar.addView(tv);
        }

        // Spacer
        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // Mic circle
        View mic = new View(this);
        android.graphics.drawable.GradientDrawable mGd =
                new android.graphics.drawable.GradientDrawable();
        mGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        mGd.setColor(Color.parseColor("#CC2200"));
        mic.setBackground(mGd);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        mLp.leftMargin = mLp.rightMargin = dp(8);
        mic.setLayoutParams(mLp);
        pulseView(mic);
        bar.addView(mic);

        // Spacer
        View sp2 = new View(this);
        bar.addView(sp2, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // Page counter
        TextView tvPager = new TextView(this);
        tvPager.setText("01 / 13");
        tvPager.setTextColor(Color.parseColor("#44FFFFFF"));
        tvPager.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        tvPager.setTypeface(Typeface.MONOSPACE);
        bar.addView(tvPager);

        return bar;
    }

    // ── Data ─────────────────────────────────────────────────────────────

    private void loadData() {
        String url  = mSession.getJarvisUrl();
        String name = mSession.getUserName();
        if (url == null || name == null) return;

        JarvisClient.fetchMemories(url, name, mem -> runOnUiThread(() -> {
            if (mLeftVals[0] != null) mLeftVals[0].setText(String.valueOf(mem.length()));
        }));

        JarvisClient.fetchWallet(url, name, new JarvisClient.WalletCallback() {
            @Override public void onSuccess(String a, double sol, double usd) {
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
                runOnUiThread(() -> mTvWalletBig.setText("ERR"));
            }
        });

        JarvisClient.fetchReminders(url, new JarvisClient.RemindersCallback() {
            @Override public void onSuccess(List<JarvisClient.JarvisReminder> r) {
                runOnUiThread(() -> {
                    if (mLeftVals[3] != null) mLeftVals[3].setText(r.size() + " SET");
                    if (mTvMsg != null) setPill(mTvMsg, "MSG", String.valueOf(r.size()));
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
                refreshGreeting();
                mClockHandler.postDelayed(this, 30_000);
            }
        });
    }

    private void refreshGreeting() {
        Calendar cal = Calendar.getInstance(); // device local time
        int h = cal.get(Calendar.HOUR_OF_DAY);

        String greet;
        if      (h < 12) greet = "GOOD MORNING,";
        else if (h < 17) greet = "GOOD AFTERNOON,";
        else if (h < 21) greet = "GOOD EVENING,";
        else             greet = "GOOD NIGHT,";

        if (mTvGreetingLine != null) mTvGreetingLine.setText(greet);

        // "24 OCT" date line
        if (mTvDateLine != null)
            mTvDateLine.setText(
                new SimpleDateFormat("dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US));

        // Real user name
        if (mTvUserNameBig != null) {
            String n = mSession.getUserName();
            mTvUserNameBig.setText(n != null ? n.toUpperCase(Locale.US) : "OPERATOR");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private TextView makePill(String label, String val) {
        TextView tv = new TextView(this);
        tv.setText(label + "\n" + val);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(pill(Color.parseColor("#111827")));
        tv.setPadding(dp(6), dp(3), dp(6), dp(3));
        return tv;
    }

    private void setPill(TextView tv, String label, String val) {
        tv.setText(label + "\n" + val);
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

    private void smallLabel(LinearLayout p, String text, int gravity) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#33FFFFFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
        tv.setLetterSpacing(0.18f);
        tv.setGravity(gravity);
        p.addView(tv);
    }

    private void addDivider(LinearLayout p) {
        View d = new View(this);
        d.setBackgroundColor(Color.parseColor("#1AFFFFFF"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin    = dp(6);
        lp.bottomMargin = dp(3);
        d.setLayoutParams(lp);
        p.addView(d);
    }

    private void statRow(LinearLayout p, String label, String val, int gravity) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(4);
        row.setLayoutParams(rLp);

        TextView tvL = new TextView(this);
        tvL.setText(label);
        tvL.setTextColor(Color.parseColor("#44FFFFFF"));
        tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        tvL.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvL);

        TextView tvV = new TextView(this);
        tvV.setText(val);
        tvV.setTextColor(Color.WHITE);
        tvV.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.0f));
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

    // ═══════════════════════════════════════════════════════════════════════
    // INNER VIEWS
    // ═══════════════════════════════════════════════════════════════════════

    static class TickRulerView extends View {
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        TickRulerView(android.content.Context c) { super(c); setWillNotDraw(false); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            mP.setStrokeWidth(1f);
            mP.setTextAlign(Paint.Align.CENTER);
            mP.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 6,
                    getResources().getDisplayMetrics()));
            int step = 18;
            for (int x = 0; x < w; x += step) {
                int num = x / step;
                boolean maj = num % 5 == 0;
                float th = maj ? h * 0.7f : h * 0.4f;
                mP.setStyle(Paint.Style.STROKE);
                mP.setColor(maj ? Color.parseColor("#66FFFFFF") : Color.parseColor("#22FFFFFF"));
                c.drawLine(x, h, x, h - th, mP);
                if (maj) {
                    mP.setStyle(Paint.Style.FILL);
                    mP.setColor(Color.parseColor("#44FFFFFF"));
                    c.drawText(String.valueOf(num), x, h * 0.25f, mP);
                }
            }
        }
    }

    static class MiniArcView extends View {
        private final Paint[] mP = new Paint[3];
        private final float[] mA = {0f, 45f, 90f};
        private final float[] mS = {0.8f, -1.2f, 0.5f};
        private final RectF[]  mO = new RectF[3];
        private final Handler  mH = new Handler(Looper.getMainLooper());
        private boolean mRun;
        MiniArcView(android.content.Context ctx) {
            super(ctx); setWillNotDraw(false);
            int[] cols = {0x80CC2200, 0x6000CCFF, 0x40FFFFFF};
            float d = ctx.getResources().getDisplayMetrics().density;
            for (int i = 0; i < 3; i++) {
                mP[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mP[i].setStyle(Paint.Style.STROKE);
                mP[i].setColor(cols[i]);
                mP[i].setStrokeWidth((2.5f - i * 0.5f) * d);
                mP[i].setStrokeCap(Paint.Cap.ROUND);
                mO[i] = new RectF();
            }
        }
        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow(); mRun = true; tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun = false; mH.removeCallbacksAndMessages(null); }
        private void tick() {
            for (int i = 0; i < 3; i++) mA[i] = (mA[i] + mS[i]) % 360f;
            invalidate(); if (mRun) mH.postDelayed(this::tick, 16);
        }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth()/2f, cy = getHeight()/2f, min = Math.min(getWidth(),getHeight())/2f;
            float[] sw = {120f, 80f, 200f};
            for (int i = 0; i < 3; i++) {
                float r = min * (0.95f - i * 0.22f);
                mO[i].set(cx-r, cy-r, cx+r, cy+r);
                c.drawArc(mO[i], mA[i], sw[i], false, mP[i]);
            }
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(Color.parseColor("#CC2200"));
            c.drawCircle(cx, cy, min * 0.15f, dot);
        }
    }

    static class SpinningArcView extends View {
        private final Paint[] mP;
        private final float[] mA  = new float[6];
        private final float[] mS  = {0.3f, -0.5f, 0.7f, -0.4f, 0.2f, -0.9f};
        private final RectF[] mO;
        private float mBlkRot = 0f;
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;
        private static final int[]   COLS  = {0x33FFFFFF,0x80CC2200,0x6000CCFF,0xFFFFFFFF,0x40CC2200,0x2200FFAA};
        private static final float[] WDS   = {2f,3f,1.5f,1f,4f,1f};
        private static final float[] SWP   = {200f,80f,160f,340f,40f,220f};
        private static final float[] BLKS  = {0f,30f,60f,90f,120f,150f,210f,240f,270f,300f,330f};

        SpinningArcView(android.content.Context ctx) {
            super(ctx); setWillNotDraw(false);
            mP = new Paint[6]; mO = new RectF[6];
            float d = ctx.getResources().getDisplayMetrics().density;
            for (int i = 0; i < 6; i++) {
                mP[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mP[i].setStyle(Paint.Style.STROKE);
                mP[i].setColor(COLS[i]);
                mP[i].setStrokeWidth(WDS[i] * d);
                mP[i].setStrokeCap(Paint.Cap.ROUND);
                mO[i] = new RectF();
            }
        }
        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow(); mRun = true; tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun = false; mH.removeCallbacksAndMessages(null); }
        private void tick() {
            for (int i = 0; i < 6; i++) mA[i] = (mA[i] + mS[i]) % 360f;
            mBlkRot = (mBlkRot + 0.4f) % 360f;
            invalidate(); if (mRun) mH.postDelayed(this::tick, 16);
        }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float cx = w/2f, cy = h/2f, min = Math.min(w,h)/2f;
            for (int i = 0; i < 6; i++) {
                float r = min * (0.95f - i * 0.11f);
                mO[i].set(cx-r, cy-r, cx+r, cy+r);
                c.drawArc(mO[i], mA[i], SWP[i], false, mP[i]);
            }
            // Rotating square blocks on inner ring
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setStyle(Paint.Style.FILL);
            bp.setColor(Color.parseColor("#334499FF"));
            float br = min * 0.60f, bs = min * 0.055f;
            c.save(); c.rotate(mBlkRot, cx, cy);
            for (float a : BLKS) {
                double rad = Math.toRadians(a);
                float bx = cx + (float)(Math.cos(rad)*br) - bs/2f;
                float by = cy + (float)(Math.sin(rad)*br) - bs/2f;
                c.drawRect(bx, by, bx+bs, by+bs, bp);
            }
            c.restore();
            // Outer tick marks
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG); tp.setStrokeWidth(1.5f);
            float or = min * 0.95f;
            for (int t = 0; t < 72; t++) {
                double ang = Math.toRadians(t * 5.0);
                boolean maj = t % 5 == 0;
                tp.setColor(maj ? Color.parseColor("#55FFFFFF") : Color.parseColor("#22FFFFFF"));
                float tl = or * (maj ? 0.05f : 0.025f);
                c.drawLine(cx+(float)(Math.cos(ang)*(or-tl)), cy+(float)(Math.sin(ang)*(or-tl)),
                           cx+(float)(Math.cos(ang)*or),      cy+(float)(Math.sin(ang)*or), tp);
            }
            // Center orb
            Paint op = new Paint(Paint.ANTI_ALIAS_FLAG); op.setStyle(Paint.Style.FILL);
            op.setColor(Color.parseColor("#08003399")); c.drawCircle(cx, cy, min*0.22f, op);
            op.setColor(Color.parseColor("#CC2200"));   c.drawCircle(cx, cy, min*0.06f, op);
            op.setStyle(Paint.Style.STROKE); op.setColor(Color.parseColor("#66FFFFFF")); op.setStrokeWidth(2f);
            c.drawCircle(cx, cy, min*0.12f, op);
        }
    }
}
