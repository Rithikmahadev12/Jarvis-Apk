package com.jarvis.assist.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
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
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
 * LandscapeHudActivity — Iron Man / JARVIS landscape HUD.
 *
 * Layout: fixed RIGHT panel (spinning orb + data) + swipeable LEFT pages.
 * LEFT pages (swipe left/right to cycle):
 *   0 — Status    (greeting, date, memory count, system rows)
 *   1 — Phone     (number pad UI like reference image 5)
 *   2 — WiFi      (network info panel like reference image 6)
 *   3 — Memory    (last 8 memories)
 *   4 — Reminders (upcoming reminders list)
 *
 * All sizes are computed from actual screen dimensions at runtime.
 */
public class LandscapeHudActivity extends AppCompatActivity {

    private SessionStore mSession;
    private final Handler mClockHandler = new Handler(Looper.getMainLooper());

    private float SS;   // screen scale (sp)
    private float SD;   // dp density

    // Right panel views
    private TextView mTvClock;
    private TextView mTvGreetingLine;
    private TextView mTvUserNameBig;
    private TextView mTvDateLine;
    private TextView[] mLeftVals = new TextView[5];
    private TextView mTvWalletBig;
    private TextView mTvWalletSub;
    private TextView mTvBattNum;
    private TextView mTvMsg;
    private TextView mTvCal;
    private TextView mTvDone;

    // Swipeable left panel state
    private static final int PAGE_COUNT = 5;
    private int mCurrentPage = 0;
    private LinearLayout[] mPages = new LinearLayout[PAGE_COUNT];
    private FrameLayout mLeftPanelContainer;
    private TextView mTvPageIndicator;
    private GestureDetector mGestureDetector;

    // Page label (top-left corner of left area)
    private static final String[] PAGE_LABELS = {
        "STATUS", "PHONE", "WIFI / NET", "MEMORY", "REMINDERS"
    };
    private static final int[] PAGE_LABEL_NUMS = {1, 2, 12, 3, 4};
    private TextView mTvPageLabel;
    private TextView mTvPageNum;

    // Memory & reminders content containers (built lazily, filled from network)
    private LinearLayout mMemoryList;
    private LinearLayout mRemindersList;

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

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        SS = shortSide / 100f / dm.scaledDensity;
        SD = dm.density;

        mGestureDetector = new GestureDetector(this, new PageSwipeListener());

        buildUI();
        startClock();
        loadData();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mClockHandler.removeCallbacksAndMessages(null);
    }

    // ── Scale helpers ──────────────────────────────────────────────────────

    float sp(float pct) { return SS * pct; }
    int dp(float v) { return Math.round(v * SD); }

    // ── Build UI ───────────────────────────────────────────────────────────

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Tick ruler at top
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
        root.addView(mTvClock, clockLp);

        // Main 2-panel row (left swipeable | right fixed)
        LinearLayout panels = new LinearLayout(this);
        panels.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams panelsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        panelsLp.topMargin    = dp(22);
        panelsLp.bottomMargin = dp(36);
        panels.setLayoutParams(panelsLp);

        // LEFT = 0.60 of width (swipeable pages)
        mLeftPanelContainer = buildSwipeableLeftArea();
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.60f);
        mLeftPanelContainer.setLayoutParams(llp);
        panels.addView(mLeftPanelContainer);

        // Vertical divider
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1AFFFFFF"));
        panels.addView(divider, new LinearLayout.LayoutParams(dp(1),
                LinearLayout.LayoutParams.MATCH_PARENT));

        // RIGHT = 0.40 of width (fixed orb)
        View rightPanel = buildRightPanel();
        panels.addView(rightPanel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.40f));

        root.addView(panels);
        root.addView(buildBottomBar());

        setContentView(root);
        showPage(0);
    }

    // ── LEFT — swipeable page container ───────────────────────────────────

    private FrameLayout buildSwipeableLeftArea() {
        FrameLayout container = new FrameLayout(this);

        // Build all 5 pages (hidden until showPage())
        mPages[0] = buildPageStatus();
        mPages[1] = buildPagePhone();
        mPages[2] = buildPageWifi();
        mPages[3] = buildPageMemory();
        mPages[4] = buildPageReminders();

        for (LinearLayout page : mPages) {
            page.setVisibility(View.GONE);
            container.addView(page, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        // Page label + num overlay (top-left of left panel, like reference)
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        labelLp.gravity = Gravity.TOP | Gravity.START;
        labelLp.topMargin  = dp(4);
        labelLp.leftMargin = dp(8);
        labelRow.setLayoutParams(labelLp);

        mTvPageNum = new TextView(this);
        mTvPageNum.setText("01");
        mTvPageNum.setTextColor(Color.parseColor("#CC2200"));
        mTvPageNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(3.0f));
        mTvPageNum.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTvPageNum.setPadding(0, 0, dp(8), 0);
        labelRow.addView(mTvPageNum);

        mTvPageLabel = new TextView(this);
        mTvPageLabel.setText("STATUS");
        mTvPageLabel.setTextColor(Color.parseColor("#AAFFFFFF"));
        mTvPageLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.4f));
        mTvPageLabel.setLetterSpacing(0.12f);
        mTvPageLabel.setTypeface(null, Typeface.BOLD);
        labelRow.addView(mTvPageLabel);

        container.addView(labelRow);

        // LIVE badge
        TextView tvLive = new TextView(this);
        tvLive.setText("● LIVE");
        tvLive.setTextColor(Color.WHITE);
        tvLive.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        tvLive.setBackground(pill(Color.parseColor("#CC2200")));
        tvLive.setPadding(dp(7), dp(2), dp(7), dp(2));
        FrameLayout.LayoutParams liveLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        liveLp.gravity = Gravity.TOP | Gravity.END;
        liveLp.topMargin   = dp(4);
        liveLp.rightMargin = dp(8);
        tvLive.setLayoutParams(liveLp);
        pulseView(tvLive);
        container.addView(tvLive);

        // Swipe arrows
        TextView arrowLeft = new TextView(this);
        arrowLeft.setText("◀");
        arrowLeft.setTextColor(Color.parseColor("#44FFFFFF"));
        arrowLeft.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.5f));
        arrowLeft.setClickable(true);
        arrowLeft.setFocusable(true);
        arrowLeft.setOnClickListener(v -> navigatePage(-1));
        FrameLayout.LayoutParams alLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        alLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        alLp.leftMargin = dp(4);
        container.addView(arrowLeft, alLp);

        TextView arrowRight = new TextView(this);
        arrowRight.setText("▶");
        arrowRight.setTextColor(Color.parseColor("#44FFFFFF"));
        arrowRight.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.5f));
        arrowRight.setClickable(true);
        arrowRight.setFocusable(true);
        arrowRight.setOnClickListener(v -> navigatePage(+1));
        FrameLayout.LayoutParams arLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        arLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        arLp.rightMargin = dp(4);
        container.addView(arrowRight, arLp);

        // Swipe gesture on entire left area
        container.setOnTouchListener((v, event) -> mGestureDetector.onTouchEvent(event));

        return container;
    }

    private void showPage(int idx) {
        if (idx < 0 || idx >= PAGE_COUNT) return;
        for (int i = 0; i < PAGE_COUNT; i++)
            mPages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        mCurrentPage = idx;
        if (mTvPageLabel != null) mTvPageLabel.setText(PAGE_LABELS[idx]);
        if (mTvPageNum  != null) mTvPageNum.setText(String.format(Locale.US, "%02d",
                PAGE_LABEL_NUMS[idx]));
    }

    private void navigatePage(int delta) {
        int next = (mCurrentPage + delta + PAGE_COUNT) % PAGE_COUNT;
        // Slide animation
        float startX = delta > 0 ? mLeftPanelContainer.getWidth() : -mLeftPanelContainer.getWidth();
        mPages[next].setTranslationX(startX);
        mPages[next].setVisibility(View.VISIBLE);
        ObjectAnimator out = ObjectAnimator.ofFloat(mPages[mCurrentPage], "translationX", 0f, -startX);
        ObjectAnimator in  = ObjectAnimator.ofFloat(mPages[next], "translationX", startX, 0f);
        out.setDuration(220);
        in.setDuration(220);
        out.setInterpolator(new DecelerateInterpolator());
        in.setInterpolator(new DecelerateInterpolator());
        int prevPage = mCurrentPage;
        out.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                mPages[prevPage].setVisibility(View.GONE);
                mPages[prevPage].setTranslationX(0f);
            }
        });
        out.start();
        in.start();
        mCurrentPage = next;
        if (mTvPageLabel != null) mTvPageLabel.setText(PAGE_LABELS[next]);
        if (mTvPageNum  != null) mTvPageNum.setText(String.format(Locale.US, "%02d",
                PAGE_LABEL_NUMS[next]));
    }

    class PageSwipeListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 80;
        private static final int SWIPE_VELOCITY  = 100;

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
            if (e1 == null || e2 == null) return false;
            float dX = e2.getX() - e1.getX();
            float dY = e2.getY() - e1.getY();
            if (Math.abs(dX) > Math.abs(dY) && Math.abs(dX) > SWIPE_THRESHOLD
                    && Math.abs(vX) > SWIPE_VELOCITY) {
                navigatePage(dX > 0 ? -1 : +1);
                return true;
            }
            return false;
        }
    }

    // ── PAGE 0 — STATUS ───────────────────────────────────────────────────

    private LinearLayout buildPageStatus() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(14), dp(32), dp(10), dp(6));

        MiniArcView miniArc = new MiniArcView(this);
        int arcSz = dp(48);
        LinearLayout.LayoutParams arcLp = new LinearLayout.LayoutParams(arcSz, arcSz);
        arcLp.bottomMargin = dp(6);
        miniArc.setLayoutParams(arcLp);
        p.addView(miniArc);

        mTvGreetingLine = makeText(p, "", sp(2.8f), Color.parseColor("#88FFFFFF"), Typeface.BOLD);
        mTvGreetingLine.setLetterSpacing(0.12f);

        mTvUserNameBig = makeText(p, "OPERATOR", sp(5.5f), Color.WHITE, Typeface.BOLD);
        mTvUserNameBig.setMaxLines(1);
        mTvUserNameBig.setEllipsize(android.text.TextUtils.TruncateAt.END);

        mTvDateLine = makeText(p, "", sp(2.0f), Color.parseColor("#44FF88"), -1);
        mTvDateLine.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = dp(4);
        mTvDateLine.setLayoutParams(dlp);

        makeText(p, "SYSTEM ACTIVE · ONLINE", sp(1.8f), Color.parseColor("#335566"), -1)
                .setLetterSpacing(0.1f);
        addDivider(p);

        String[] labels   = {"MEMORY","VAULT","SESSION","NETWORK","SYNC"};
        String[] initVals = {"—","LOCKED","—","ONLINE","LIVE"};
        int[]    colors   = {0xFF44FF88,0xFFCC2200,0xFF4499FF,0xFF44FF88,0xFFFFD700};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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

    // ── PAGE 1 — PHONE ────────────────────────────────────────────────────

    private LinearLayout buildPagePhone() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(32), dp(20), dp(6));

        makeText(p, "GOOD AFTERNOON, " + (mSession.getUserName() != null
                ? mSession.getUserName().toUpperCase(Locale.US) : "OPERATOR"),
                sp(2.0f), Color.parseColor("#88FFFFFF"), Typeface.BOLD);

        // Phone number display (like reference image — big monospace)
        LinearLayout numBox = new LinearLayout(this);
        numBox.setOrientation(LinearLayout.HORIZONTAL);
        numBox.setGravity(Gravity.CENTER_VERTICAL);
        numBox.setBackground(rectBorder(Color.parseColor("#0D1520"), Color.parseColor("#1A3355"), dp(6), dp(1)));
        numBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams nblp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nblp.topMargin = dp(8);
        nblp.bottomMargin = dp(10);
        numBox.setLayoutParams(nblp);

        View pDot = new View(this);
        android.graphics.drawable.GradientDrawable pdg = new android.graphics.drawable.GradientDrawable();
        pdg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        pdg.setColor(Color.parseColor("#44FF88"));
        pDot.setBackground(pdg);
        LinearLayout.LayoutParams pdlp = new LinearLayout.LayoutParams(dp(6), dp(6));
        pdlp.rightMargin = dp(8);
        pDot.setLayoutParams(pdlp);
        pulseView(pDot);
        numBox.addView(pDot);

        TextView tvPhoneNum = new TextView(this);
        tvPhoneNum.setText("▶ PHONE");
        tvPhoneNum.setTextColor(Color.parseColor("#44FF88"));
        tvPhoneNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.0f));
        tvPhoneNum.setLetterSpacing(0.1f);
        tvPhoneNum.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        numBox.addView(tvPhoneNum);

        TextView tvPhoneDigits = new TextView(this);
        tvPhoneDigits.setText("--:--");
        tvPhoneDigits.setTextColor(Color.WHITE);
        tvPhoneDigits.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.8f));
        tvPhoneDigits.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        numBox.addView(tvPhoneDigits);
        p.addView(numBox);

        // 3×4 dial grid of red LED-style buttons (reference style)
        addDivider(p);
        String[] dialKeys = {"1","2","3","4","5","6","7","8","9","*","0","#"};
        LinearLayout dialGrid = new LinearLayout(this);
        dialGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dglp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dglp.topMargin = dp(6);
        dialGrid.setLayoutParams(dglp);

        StringBuilder dialInput = new StringBuilder();
        for (int row = 0; row < 4; row++) {
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(4);
            rowL.setLayoutParams(rlp);
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                if (idx >= dialKeys.length) break;
                String key = dialKeys[idx];
                TextView btn = new TextView(this);
                btn.setText(key);
                btn.setTextColor(Color.parseColor("#CC2200"));
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.8f));
                btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                btn.setGravity(Gravity.CENTER);
                btn.setBackground(rectBorder(Color.parseColor("#0D0808"),
                        Color.parseColor("#330800"), dp(4), dp(1)));
                btn.setClickable(true);
                btn.setFocusable(true);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        0, dp(28), 1f);
                blp.rightMargin = col < 2 ? dp(4) : 0;
                btn.setLayoutParams(blp);
                btn.setOnClickListener(v -> {
                    dialInput.append(key);
                    tvPhoneDigits.setText(dialInput.toString());
                });
                rowL.addView(btn);
            }
            dialGrid.addView(rowL);
        }
        p.addView(dialGrid);

        // DIAL + CLEAR row
        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
        arlp.topMargin = dp(6);
        actRow.setLayoutParams(arlp);

        TextView btnCall = new TextView(this);
        btnCall.setText("▶ CALL");
        btnCall.setTextColor(Color.parseColor("#44FF88"));
        btnCall.setGravity(Gravity.CENTER);
        btnCall.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.0f));
        btnCall.setBackground(rectBorder(Color.parseColor("#001A0D"),
                Color.parseColor("#33FF88"), dp(4), dp(1)));
        btnCall.setClickable(true);
        btnCall.setFocusable(true);
        LinearLayout.LayoutParams bclp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        bclp.rightMargin = dp(6);
        btnCall.setLayoutParams(bclp);
        btnCall.setOnClickListener(v -> {
            String num = dialInput.toString().trim();
            if (!num.isEmpty()) {
                Intent dial = new Intent(Intent.ACTION_DIAL,
                        android.net.Uri.parse("tel:" + num));
                startActivity(dial);
            }
        });
        actRow.addView(btnCall);

        TextView btnClear2 = new TextView(this);
        btnClear2.setText("✕ CLEAR");
        btnClear2.setTextColor(Color.parseColor("#CC2200"));
        btnClear2.setGravity(Gravity.CENTER);
        btnClear2.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.0f));
        btnClear2.setBackground(rectBorder(Color.parseColor("#1A0000"),
                Color.parseColor("#331100"), dp(4), dp(1)));
        btnClear2.setClickable(true);
        btnClear2.setFocusable(true);
        btnClear2.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        btnClear2.setOnClickListener(v -> {
            dialInput.setLength(0);
            tvPhoneDigits.setText("--:--");
        });
        actRow.addView(btnClear2);
        p.addView(actRow);

        return p;
    }

    // ── PAGE 2 — WIFI / NETWORK ───────────────────────────────────────────

    private LinearLayout buildPageWifi() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(32), dp(20), dp(6));

        makeText(p, "GOOD AFTERNOON, " + (mSession.getUserName() != null
                ? mSession.getUserName().toUpperCase(Locale.US) : "OPERATOR"),
                sp(2.0f), Color.parseColor("#88FFFFFF"), Typeface.BOLD);

        // Big WIFI label (like reference image)
        LinearLayout wifiBox = new LinearLayout(this);
        wifiBox.setOrientation(LinearLayout.VERTICAL);
        wifiBox.setBackground(rectBorder(Color.parseColor("#070B12"),
                Color.parseColor("#1A3355"), dp(8), dp(1)));
        wifiBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams wblp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wblp.topMargin = dp(8);
        wblp.bottomMargin = dp(10);
        wifiBox.setLayoutParams(wblp);

        // Header row: ◀ WIFI + LIVE badge
        LinearLayout wHdr = new LinearLayout(this);
        wHdr.setOrientation(LinearLayout.HORIZONTAL);
        wHdr.setGravity(Gravity.CENTER_VERTICAL);
        wHdr.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvWifiTitle = new TextView(this);
        tvWifiTitle.setText("◀  WIFI");
        tvWifiTitle.setTextColor(Color.WHITE);
        tvWifiTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(4.5f));
        tvWifiTitle.setTypeface(null, Typeface.BOLD);
        tvWifiTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        wHdr.addView(tvWifiTitle);

        TextView tvWLive = new TextView(this);
        tvWLive.setText("● LIVE");
        tvWLive.setTextColor(Color.WHITE);
        tvWLive.setBackground(pill(Color.parseColor("#CC2200")));
        tvWLive.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        tvWLive.setPadding(dp(6), dp(2), dp(6), dp(2));
        pulseView(tvWLive);
        wHdr.addView(tvWLive);
        wifiBox.addView(wHdr);

        // Net stats (like reference — label: value pairs)
        String[][] netStats = {
            {"SSP", "AES"}, {"IP ADDRESS", getLocalIp()},
            {"GATEWAY", "192.168.1.1"}, {"DNS", "8.8.8.8 / 8.8.4.4"},
            {"STRENGTH", "████░ 80%"}, {"CHANNEL", "6 (2.4GHz)"}
        };
        for (String[] row : netStats) {
            LinearLayout sRow = new LinearLayout(this);
            sRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            srlp.topMargin = dp(4);
            sRow.setLayoutParams(srlp);

            TextView tvLbl = new TextView(this);
            tvLbl.setText(row[0]);
            tvLbl.setTextColor(Color.parseColor("#44FFFFFF"));
            tvLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
            tvLbl.setLetterSpacing(0.06f);
            tvLbl.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0.45f));
            sRow.addView(tvLbl);

            TextView tvVal = new TextView(this);
            tvVal.setText(row[1]);
            tvVal.setTextColor(Color.parseColor("#44FF88"));
            tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
            tvVal.setTypeface(Typeface.MONOSPACE);
            tvVal.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0.55f));
            sRow.addView(tvVal);
            wifiBox.addView(sRow);
        }
        p.addView(wifiBox);

        // Mini 2-gauge strip: TX / RX
        LinearLayout gaugeRow = new LinearLayout(this);
        gaugeRow.setOrientation(LinearLayout.HORIZONTAL);
        gaugeRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        addGauge(gaugeRow, "TX", "██░░ 40%", "#4488FF");
        addGauge(gaugeRow, "RX", "███░ 75%", "#44FF88");
        p.addView(gaugeRow);

        return p;
    }

    private String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface intf = en.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = intf.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address)
                        return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "—";
    }

    private void addGauge(LinearLayout parent, String label, String bar, String color) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(8), dp(4), dp(8), dp(4));
        cell.setBackground(rectBorder(Color.parseColor("#0A0F18"),
                Color.parseColor("#1A3355"), dp(4), dp(1)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        clp.rightMargin = dp(6);
        cell.setLayoutParams(clp);

        TextView tvL = new TextView(this);
        tvL.setText(label);
        tvL.setTextColor(Color.parseColor("#44FFFFFF"));
        tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.5f));
        tvL.setLetterSpacing(0.1f);
        cell.addView(tvL);

        TextView tvB = new TextView(this);
        tvB.setText(bar);
        tvB.setTextColor(Color.parseColor(color));
        tvB.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        tvB.setTypeface(Typeface.MONOSPACE);
        cell.addView(tvB);

        parent.addView(cell);
    }

    // ── PAGE 3 — MEMORY ───────────────────────────────────────────────────

    private LinearLayout buildPageMemory() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(32), dp(20), dp(6));

        makeText(p, "WHAT JARVIS KNOWS", sp(2.4f), Color.WHITE, Typeface.BOLD);
        addDivider(p);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(svlp);

        mMemoryList = new LinearLayout(this);
        mMemoryList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(mMemoryList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvPlaceholder = new TextView(this);
        tvPlaceholder.setText("Loading memories…");
        tvPlaceholder.setTextColor(Color.parseColor("#44FFFFFF"));
        tvPlaceholder.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        mMemoryList.addView(tvPlaceholder);
        p.addView(sv);
        return p;
    }

    // ── PAGE 4 — REMINDERS ────────────────────────────────────────────────

    private LinearLayout buildPageReminders() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(32), dp(20), dp(6));

        makeText(p, "UPCOMING REMINDERS", sp(2.4f), Color.WHITE, Typeface.BOLD);
        addDivider(p);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(svlp);

        mRemindersList = new LinearLayout(this);
        mRemindersList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(mRemindersList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvPl = new TextView(this);
        tvPl.setText("Loading reminders…");
        tvPl.setTextColor(Color.parseColor("#44FFFFFF"));
        tvPl.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        mRemindersList.addView(tvPl);
        p.addView(sv);
        return p;
    }

    // ── RIGHT PANEL (fixed) ────────────────────────────────────────────────

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
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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
        buildBattRow(p);
        addDivider(p);

        // MSG + CAL + DONE pills
        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = dp(5);
        pillRow.setLayoutParams(prLp);
        mTvMsg  = makePill("MSG",  "0");
        mTvCal  = makePill("CAL",  "—");
        mTvDone = makePill("DONE", "0");
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLp.leftMargin = dp(3);
        pillRow.addView(mTvMsg,  pLp);
        pillRow.addView(mTvCal,  pLp);
        pillRow.addView(mTvDone, pLp);
        p.addView(pillRow);

        addDivider(p);
        statRow(p, "ALERTS",  "0",   Gravity.END);
        statRow(p, "BEAT",    "NOM", Gravity.END);
        addDivider(p);

        // Close HUD button
        TextView tvClose = new TextView(this);
        tvClose.setText("CLOSE HUD  ◀");
        tvClose.setTextColor(Color.parseColor("#44FF88"));
        tvClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        tvClose.setGravity(Gravity.END);
        tvClose.setClickable(true);
        tvClose.setFocusable(true);
        tvClose.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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

        android.content.IntentFilter f =
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
        android.content.Intent s = registerReceiver(null, f);
        if (s != null) {
            int lvl   = s.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = s.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            if (lvl >= 0 && scale > 0) mTvBattNum.setText(String.valueOf((lvl * 100) / scale));
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

        // Page dots
        String[] labels = {"STATUS","PHONE","WIFI","MEMORY","REMINDERS"};
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("  ·  ");
                sep.setTextColor(Color.parseColor("#22FFFFFF"));
                sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
                bar.addView(sep);
            }
            final int fi = i;
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextColor(i == 0 ? Color.parseColor("#CC2200") : Color.parseColor("#44FFFFFF"));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
            tv.setLetterSpacing(0.1f);
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setOnClickListener(v -> showPage(fi));
            bar.addView(tv);
        }

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

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
        mic.setClickable(true);
        mic.setOnClickListener(v -> startActivity(new Intent(this, VoiceOverlayActivity.class)));

        View sp2 = new View(this);
        bar.addView(sp2, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        mTvPageIndicator = new TextView(this);
        mTvPageIndicator.setText("01 / 05");
        mTvPageIndicator.setTextColor(Color.parseColor("#44FFFFFF"));
        mTvPageIndicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        mTvPageIndicator.setTypeface(Typeface.MONOSPACE);
        bar.addView(mTvPageIndicator);

        return bar;
    }

    // ── Data ──────────────────────────────────────────────────────────────

    private void loadData() {
        String url  = mSession.getJarvisUrl();
        String name = mSession.getUserName();
        if (url == null || name == null) return;

        JarvisClient.fetchMemories(url, name, mem -> runOnUiThread(() -> {
            if (mLeftVals[0] != null) mLeftVals[0].setText(String.valueOf(mem.length()));
            if (mMemoryList == null) return;
            mMemoryList.removeAllViews();
            if (mem.length() == 0) {
                addMemLine(mMemoryList, "No memories yet.");
                return;
            }
            int limit = Math.min(mem.length(), 10);
            for (int i = 0; i < limit; i++) {
                try {
                    org.json.JSONObject m = mem.getJSONObject(i);
                    String content = m.optString("content",
                            m.optString("memory", m.optString("text", "")));
                    if (!content.isEmpty()) addMemLine(mMemoryList, "• " + content);
                } catch (Exception ignored) {}
            }
        }));

        JarvisClient.fetchWallet(url, name, new JarvisClient.WalletCallback() {
            @Override public void onSuccess(String a, double sol, double usd) {
                runOnUiThread(() -> {
                    long lamps = (long)(sol * 1_000_000_000L);
                    if (mTvWalletBig != null) mTvWalletBig.setText(String.valueOf(lamps));
                    if (mTvWalletSub != null) mTvWalletSub.setText(
                            String.format(Locale.US, "◎ %.4f  ·  $%.2f", sol, usd));
                });
            }
            @Override public void onNotConfigured() {
                runOnUiThread(() -> {
                    if (mTvWalletBig != null) mTvWalletBig.setText("—");
                    if (mTvWalletSub != null) mTvWalletSub.setText("NO WALLET");
                });
            }
            @Override public void onError(String e) {
                runOnUiThread(() -> { if (mTvWalletBig != null) mTvWalletBig.setText("ERR"); });
            }
        });

        JarvisClient.fetchReminders(url, new JarvisClient.RemindersCallback() {
            @Override public void onSuccess(List<JarvisClient.JarvisReminder> rs) {
                runOnUiThread(() -> {
                    if (mLeftVals[3] != null) mLeftVals[3].setText(rs.size() + " SET");
                    if (mTvMsg != null) setPill(mTvMsg, "MSG", String.valueOf(rs.size()));
                    if (mRemindersList == null) return;
                    mRemindersList.removeAllViews();
                    if (rs.isEmpty()) { addMemLine(mRemindersList, "No reminders set."); return; }
                    for (JarvisClient.JarvisReminder r : rs)
                        addMemLine(mRemindersList, (r.isDue ? "⚡ " : "⏰ ") + r.label);
                });
            }
            @Override public void onError(String e) {}
        });
    }

    private void addMemLine(LinearLayout container, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#CCFFFFFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        tv.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        container.addView(tv);
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
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        String greet = h < 12 ? "GOOD MORNING,"
                : h < 17 ? "GOOD AFTERNOON,"
                : h < 21 ? "GOOD EVENING,"
                : "GOOD NIGHT,";
        if (mTvGreetingLine != null) mTvGreetingLine.setText(greet);
        if (mTvDateLine != null)
            mTvDateLine.setText(
                    new SimpleDateFormat("dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US));
        if (mTvUserNameBig != null) {
            String n = mSession.getUserName();
            mTvUserNameBig.setText(n != null ? n.toUpperCase(Locale.US) : "OPERATOR");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private TextView makeText(LinearLayout parent, String text, float textSize,
                               int color, int style) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
        if (style >= 0) tv.setTypeface(null, style);
        parent.addView(tv);
        return tv;
    }

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

    private void setPill(TextView tv, String label, String val) { tv.setText(label + "\n" + val); }

    private android.graphics.drawable.Drawable pill(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(20));
        gd.setColor(color);
        gd.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        return gd;
    }

    private android.graphics.drawable.Drawable rectBorder(int fill, int stroke, float radius, int sw) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radius);
        gd.setColor(fill);
        gd.setStroke(sw, stroke);
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
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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
    // INNER VIEWS — kept static so HomeHudActivity can reference them
    // ═══════════════════════════════════════════════════════════════════════

    public static class TickRulerView extends View {
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        public TickRulerView(android.content.Context c) { super(c); setWillNotDraw(false); }
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

    public static class MiniArcView extends View {
        private final Paint[] mP = new Paint[3];
        private final float[] mA = {0f, 45f, 90f};
        private final float[] mS = {0.8f, -1.2f, 0.5f};
        private final RectF[]  mO = new RectF[3];
        private final Handler  mH = new Handler(Looper.getMainLooper());
        private boolean mRun;
        public MiniArcView(android.content.Context ctx) {
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

    public static class SpinningArcView extends View {
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

        public SpinningArcView(android.content.Context ctx) {
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
            Paint op = new Paint(Paint.ANTI_ALIAS_FLAG); op.setStyle(Paint.Style.FILL);
            op.setColor(Color.parseColor("#08003399")); c.drawCircle(cx, cy, min*0.22f, op);
            op.setColor(Color.parseColor("#CC2200"));   c.drawCircle(cx, cy, min*0.06f, op);
            op.setStyle(Paint.Style.STROKE); op.setColor(Color.parseColor("#66FFFFFF")); op.setStrokeWidth(2f);
            c.drawCircle(cx, cy, min*0.12f, op);
        }
    }
}
