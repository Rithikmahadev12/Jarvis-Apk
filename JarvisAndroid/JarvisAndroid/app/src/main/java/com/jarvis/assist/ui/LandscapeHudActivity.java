package com.jarvis.assist.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
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
 * LandscapeHudActivity — Iron Man landscape HUD.
 *
 * RIGHT panel: SpinningOrbWheelView — a radial sector wheel (same Iron Man style as portrait)
 *              with sectors: BRIEF · PHONE · HEALTH · CAMERA · VAULT · KEY · WIFI
 *              Selecting a sector on the right wheel swaps the LEFT panel page.
 *
 * LEFT panel: Swipeable pages (0=STATUS, 1=PHONE, 2=WIFI, 3=MEMORY, 4=REMINDERS)
 *             Matches reference images 4 & 5 exactly.
 */
public class LandscapeHudActivity extends AppCompatActivity {

    private SessionStore mSession;
    private final Handler mClockHandler = new Handler(Looper.getMainLooper());

    float SS;
    float SD;

    // Header refs
    private TextView mTvClock;

    // Left page content refs
    private TextView mTvGreetingLine;
    private TextView mTvUserNameBig;
    private TextView mTvDateLine;
    private TextView[] mLeftVals = new TextView[5];

    // Right panel wallet/battery
    private TextView mTvWalletBig;
    private TextView mTvWalletSub;
    private TextView mTvBattNum;
    private TextView mTvMsg;
    private TextView mTvCal;
    private TextView mTvDone;

    // Swipeable left panel
    private static final int PAGE_COUNT = 5;
    private int mCurrentPage = 0;
    private LinearLayout[] mPages = new LinearLayout[PAGE_COUNT];
    private FrameLayout mLeftPanelContainer;
    private TextView mTvPageLabel;
    private TextView mTvPageNum;
    private GestureDetector mGestureDetector;
    private LinearLayout mMemoryList;
    private LinearLayout mRemindersList;

    private static final String[] PAGE_LABELS    = {"STATUS","PHONE","WIFI / NET","MEMORY","REMINDERS"};
    private static final int[]    PAGE_LABEL_NUMS = {1, 2, 12, 3, 4};

    // Right orb wheel
    private SpinningOrbWheelView mOrbWheel;
    private TextView mTvPageIndicator;

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

    @Override protected void onDestroy() {
        super.onDestroy();
        mClockHandler.removeCallbacksAndMessages(null);
    }

    float sp(float pct) { return SS * pct; }
    int dp(float v) { return Math.round(v * SD); }

    // ── BUILD UI ─────────────────────────────────────────────────────────

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#030508"));

        // Red accent line top
        View redLine = new View(this);
        redLine.setBackgroundColor(Color.parseColor("#CC2200"));
        FrameLayout.LayoutParams rlLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(2));
        rlLp.gravity = Gravity.TOP;
        root.addView(redLine, rlLp);

        // Tick ruler
        TickRulerView ruler = new TickRulerView(this);
        FrameLayout.LayoutParams rulerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(22));
        rulerLp.gravity   = Gravity.TOP;
        rulerLp.topMargin = dp(2);
        root.addView(ruler, rulerLp);

        // Clock top-right
        mTvClock = new TextView(this);
        mTvClock.setText("00:00");
        mTvClock.setTextColor(Color.WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(4.2f));
        mTvClock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        clockLp.gravity     = Gravity.TOP | Gravity.END;
        clockLp.topMargin   = dp(2);
        clockLp.rightMargin = dp(12);
        root.addView(mTvClock, clockLp);

        // Main 2-panel row
        LinearLayout panels = new LinearLayout(this);
        panels.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams panelsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        panelsLp.topMargin    = dp(24);
        panelsLp.bottomMargin = dp(36);
        panels.setLayoutParams(panelsLp);

        // LEFT 62% — swipeable pages
        mLeftPanelContainer = buildSwipeableLeftArea();
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.62f);
        mLeftPanelContainer.setLayoutParams(llp);
        panels.addView(mLeftPanelContainer);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        panels.addView(divider, new LinearLayout.LayoutParams(dp(1),
                LinearLayout.LayoutParams.MATCH_PARENT));

        // RIGHT 38% — spinning orb wheel
        mOrbWheel = new SpinningOrbWheelView(this);
        mOrbWheel.setOnSectorClickListener(idx -> {
            // Map right-wheel sector to left page
            int[] sectorToPage = {0, 1, 0, 0, 1, 0, 2}; // BRIEF->0, PHONE->1, WIFI->2, others->0
            int page = idx < sectorToPage.length ? sectorToPage[idx] : 0;
            if (idx == 6) page = 2;  // WIFI
            navigateToPage(page);
        });
        panels.addView(mOrbWheel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.38f));

        root.addView(panels);
        root.addView(buildBottomBar());

        setContentView(root);
        showPage(0);
    }

    // ── LEFT SWIPEABLE AREA ──────────────────────────────────────────────

    private FrameLayout buildSwipeableLeftArea() {
        FrameLayout container = new FrameLayout(this);

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

        // Page label overlay
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        labelLp.gravity    = Gravity.TOP | Gravity.START;
        labelLp.topMargin  = dp(4);
        labelLp.leftMargin = dp(10);
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
        liveLp.gravity    = Gravity.TOP | Gravity.END;
        liveLp.topMargin  = dp(4);
        liveLp.rightMargin = dp(8);
        tvLive.setLayoutParams(liveLp);
        pulseView(tvLive);
        container.addView(tvLive);

        // Swipe arrows
        TextView arrowLeft = new TextView(this);
        arrowLeft.setText("◀");
        arrowLeft.setTextColor(Color.parseColor("#33FFFFFF"));
        arrowLeft.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.5f));
        arrowLeft.setClickable(true);
        arrowLeft.setFocusable(true);
        arrowLeft.setOnClickListener(v -> navigatePage(-1));
        FrameLayout.LayoutParams alLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        alLp.gravity    = Gravity.CENTER_VERTICAL | Gravity.START;
        alLp.leftMargin = dp(2);
        container.addView(arrowLeft, alLp);

        TextView arrowRight = new TextView(this);
        arrowRight.setText("▶");
        arrowRight.setTextColor(Color.parseColor("#33FFFFFF"));
        arrowRight.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.5f));
        arrowRight.setClickable(true);
        arrowRight.setFocusable(true);
        arrowRight.setOnClickListener(v -> navigatePage(+1));
        FrameLayout.LayoutParams arLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        arLp.gravity     = Gravity.CENTER_VERTICAL | Gravity.END;
        arLp.rightMargin = dp(2);
        container.addView(arrowRight, arLp);

        container.setOnTouchListener((v, event) -> mGestureDetector.onTouchEvent(event));
        return container;
    }

    private void showPage(int idx) {
        if (idx < 0 || idx >= PAGE_COUNT) return;
        for (int i = 0; i < PAGE_COUNT; i++)
            mPages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        mCurrentPage = idx;
        if (mTvPageLabel != null) mTvPageLabel.setText(PAGE_LABELS[idx]);
        if (mTvPageNum   != null) mTvPageNum.setText(
                String.format(Locale.US, "%02d", PAGE_LABEL_NUMS[idx]));
        if (mTvPageIndicator != null)
            mTvPageIndicator.setText(
                String.format(Locale.US, "%02d / %02d", idx + 1, PAGE_COUNT));
    }

    private void navigatePage(int delta) {
        int next = (mCurrentPage + delta + PAGE_COUNT) % PAGE_COUNT;
        navigateToPage(next);
    }

    private void navigateToPage(int next) {
        if (next == mCurrentPage) return;
        int delta = next - mCurrentPage;
        float startX = delta > 0 ? mLeftPanelContainer.getWidth() : -mLeftPanelContainer.getWidth();
        mPages[next].setTranslationX(startX);
        mPages[next].setVisibility(View.VISIBLE);
        ObjectAnimator out = ObjectAnimator.ofFloat(mPages[mCurrentPage], "translationX", 0f, -startX);
        ObjectAnimator in  = ObjectAnimator.ofFloat(mPages[next], "translationX", startX, 0f);
        out.setDuration(200); in.setDuration(200);
        out.setInterpolator(new DecelerateInterpolator());
        in.setInterpolator(new DecelerateInterpolator());
        int prev = mCurrentPage;
        out.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                mPages[prev].setVisibility(View.GONE);
                mPages[prev].setTranslationX(0f);
            }
        });
        out.start(); in.start();
        mCurrentPage = next;
        if (mTvPageLabel != null) mTvPageLabel.setText(PAGE_LABELS[next]);
        if (mTvPageNum   != null) mTvPageNum.setText(
                String.format(Locale.US, "%02d", PAGE_LABEL_NUMS[next]));
        if (mTvPageIndicator != null)
            mTvPageIndicator.setText(
                String.format(Locale.US, "%02d / %02d", next + 1, PAGE_COUNT));
    }

    class PageSwipeListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
            if (e1 == null || e2 == null) return false;
            float dX = e2.getX() - e1.getX(), dY = e2.getY() - e1.getY();
            if (Math.abs(dX) > Math.abs(dY) && Math.abs(dX) > 80 && Math.abs(vX) > 100) {
                navigatePage(dX > 0 ? -1 : +1); return true;
            }
            return false;
        }
    }

    // ── PAGE 0 — STATUS ──────────────────────────────────────────────────

    private LinearLayout buildPageStatus() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(34), dp(12), dp(6));

        MiniArcView miniArc = new MiniArcView(this);
        int arcSz = dp(44);
        LinearLayout.LayoutParams arcLp = new LinearLayout.LayoutParams(arcSz, arcSz);
        arcLp.bottomMargin = dp(4);
        miniArc.setLayoutParams(arcLp);
        p.addView(miniArc);

        mTvGreetingLine = makeText(p, "", sp(2.6f), Color.parseColor("#77FFFFFF"), Typeface.BOLD);
        mTvGreetingLine.setLetterSpacing(0.1f);

        mTvUserNameBig = makeText(p, "OPERATOR", sp(5.0f), Color.WHITE, Typeface.BOLD);
        mTvUserNameBig.setMaxLines(1);
        mTvUserNameBig.setEllipsize(android.text.TextUtils.TruncateAt.END);

        mTvDateLine = makeText(p, "", sp(1.9f), Color.parseColor("#44FF88"), -1);
        mTvDateLine.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = dp(4);
        mTvDateLine.setLayoutParams(dlp);

        makeText(p, "SYSTEM ACTIVE  ·  ONLINE", sp(1.7f), Color.parseColor("#224455"), -1)
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
            tvNum.setTextColor(Color.parseColor("#22FFFFFF"));
            tvNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
            tvNum.setTypeface(Typeface.MONOSPACE);
            tvNum.setPadding(0, 0, dp(5), 0);
            row.addView(tvNum);

            View dot = new View(this);
            android.graphics.drawable.GradientDrawable dg =
                    new android.graphics.drawable.GradientDrawable();
            dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dg.setColor(colors[i]);
            dot.setBackground(dg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(5), dp(5));
            dotLp.rightMargin = dp(6);
            dot.setLayoutParams(dotLp);
            row.addView(dot);

            TextView tvLbl = new TextView(this);
            tvLbl.setText(labels[i]);
            tvLbl.setTextColor(Color.parseColor("#55FFFFFF"));
            tvLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
            tvLbl.setLetterSpacing(0.05f);
            tvLbl.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvLbl);

            TextView tvVal = new TextView(this);
            tvVal.setText(initVals[i]);
            tvVal.setTextColor(colors[i]);
            tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
            tvVal.setTypeface(null, Typeface.BOLD);
            row.addView(tvVal);
            mLeftVals[i] = tvVal;
            p.addView(row);
        }
        return p;
    }

    // ── PAGE 1 — PHONE (pixel-accurate from reference images 4&5) ────────

    private LinearLayout buildPagePhone() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);  // 2-column layout like reference
        p.setPadding(dp(16), dp(34), dp(12), dp(6));

        // LEFT sub-column: greeting + display + call history
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        String userName = mSession.getUserName() != null
                ? mSession.getUserName().toUpperCase(Locale.US) : "OPERATOR";

        makeText(leftCol,
            (getHour() < 12 ? "GOOD MORNING, " : getHour() < 17 ? "GOOD AFTERNOON, " : "GOOD EVENING, ")
            + userName, sp(1.9f), Color.parseColor("#88FFFFFF"), Typeface.BOLD);

        addDivider(leftCol);

        // Phone number display box — reference shows big number in a bordered box
        FrameLayout numBoxFrame = new FrameLayout(this);
        numBoxFrame.setBackground(rectBorder(Color.parseColor("#050D18"), Color.parseColor("#1A3355"), dp(6), dp(1)));
        LinearLayout.LayoutParams nbfLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nbfLp.topMargin = dp(6); nbfLp.bottomMargin = dp(6);
        numBoxFrame.setLayoutParams(nbfLp);
        numBoxFrame.setPadding(dp(8), dp(6), dp(8), dp(6));

        LinearLayout numRow = new LinearLayout(this);
        numRow.setOrientation(LinearLayout.HORIZONTAL);
        numRow.setGravity(Gravity.CENTER_VERTICAL);

        View numDot = new View(this);
        android.graphics.drawable.GradientDrawable nDg = new android.graphics.drawable.GradientDrawable();
        nDg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        nDg.setColor(Color.parseColor("#44FF88"));
        numDot.setBackground(nDg);
        LinearLayout.LayoutParams ndLp = new LinearLayout.LayoutParams(dp(6), dp(6));
        ndLp.rightMargin = dp(8);
        numDot.setLayoutParams(ndLp);
        pulseView(numDot);
        numRow.addView(numDot);

        TextView tvPhoneLabel = new TextView(this);
        tvPhoneLabel.setText("▶ PHONE");
        tvPhoneLabel.setTextColor(Color.parseColor("#44FF88"));
        tvPhoneLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        tvPhoneLabel.setLetterSpacing(0.08f);
        tvPhoneLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        numRow.addView(tvPhoneLabel);

        // Live badge
        TextView tvLivePh = new TextView(this);
        tvLivePh.setText("● LIVE");
        tvLivePh.setBackground(pill(Color.parseColor("#CC2200")));
        tvLivePh.setTextColor(Color.WHITE);
        tvLivePh.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.5f));
        tvLivePh.setPadding(dp(5), dp(2), dp(5), dp(2));
        pulseView(tvLivePh);
        numRow.addView(tvLivePh);
        numBoxFrame.addView(numRow);
        leftCol.addView(numBoxFrame);

        // Big number display — like reference image showing "0659296346"
        final StringBuilder dialInput = new StringBuilder();
        TextView tvBigNum = new TextView(this);
        tvBigNum.setText("——————————");
        tvBigNum.setTextColor(Color.WHITE);
        tvBigNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(3.8f));
        tvBigNum.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvBigNum.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams bnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bnLp.topMargin = dp(4); bnLp.bottomMargin = dp(6);
        tvBigNum.setLayoutParams(bnLp);
        leftCol.addView(tvBigNum);

        addDivider(leftCol);

        // 3×4 dial grid — red LED style as in reference
        String[] dialKeys = {"1","2","3","4","5","6","7","8","9","*","0","#"};
        LinearLayout dialGrid = new LinearLayout(this);
        dialGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dglp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dglp.topMargin = dp(4);
        dialGrid.setLayoutParams(dglp);

        for (int row = 0; row < 4; row++) {
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(22));
            rlp.bottomMargin = dp(3);
            rowL.setLayoutParams(rlp);

            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                if (idx >= dialKeys.length) break;
                String key = dialKeys[idx];
                TextView btn = new TextView(this);
                btn.setText(key);
                btn.setTextColor(Color.parseColor("#CC2200"));
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.5f));
                btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                btn.setGravity(Gravity.CENTER);
                btn.setBackground(rectBorder(Color.parseColor("#0C0606"), Color.parseColor("#2A0800"), dp(3), dp(1)));
                btn.setClickable(true); btn.setFocusable(true);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                blp.rightMargin = col < 2 ? dp(3) : 0;
                btn.setLayoutParams(blp);
                btn.setOnClickListener(v -> {
                    dialInput.append(key);
                    tvBigNum.setText(dialInput.toString());
                });
                rowL.addView(btn);
            }
            dialGrid.addView(rowL);
        }
        leftCol.addView(dialGrid);

        // CALL + CLEAR row
        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26));
        arlp.topMargin = dp(5);
        actRow.setLayoutParams(arlp);

        TextView btnCall = new TextView(this);
        btnCall.setText("▶ CALL");
        btnCall.setTextColor(Color.parseColor("#44FF88"));
        btnCall.setGravity(Gravity.CENTER);
        btnCall.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        btnCall.setBackground(rectBorder(Color.parseColor("#001A0D"), Color.parseColor("#2299AA"), dp(4), dp(1)));
        btnCall.setClickable(true); btnCall.setFocusable(true);
        LinearLayout.LayoutParams bclp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        bclp.rightMargin = dp(6);
        btnCall.setLayoutParams(bclp);
        btnCall.setOnClickListener(v -> {
            String num = dialInput.toString().trim();
            if (!num.isEmpty()) startActivity(new Intent(Intent.ACTION_DIAL,
                    android.net.Uri.parse("tel:" + num)));
        });
        actRow.addView(btnCall);

        TextView btnClear = new TextView(this);
        btnClear.setText("✕ CLR");
        btnClear.setTextColor(Color.parseColor("#CC2200"));
        btnClear.setGravity(Gravity.CENTER);
        btnClear.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        btnClear.setBackground(rectBorder(Color.parseColor("#120000"), Color.parseColor("#331100"), dp(4), dp(1)));
        btnClear.setClickable(true); btnClear.setFocusable(true);
        btnClear.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        btnClear.setOnClickListener(v -> {
            dialInput.setLength(0);
            tvBigNum.setText("——————————");
        });
        actRow.addView(btnClear);
        leftCol.addView(actRow);

        p.addView(leftCol);
        return p;
    }

    private int getHour() {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    }

    // ── PAGE 2 — WIFI ────────────────────────────────────────────────────

    private LinearLayout buildPageWifi() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(18), dp(34), dp(12), dp(6));

        String userName = mSession.getUserName() != null
                ? mSession.getUserName().toUpperCase(Locale.US) : "OPERATOR";
        makeText(p,
            (getHour() < 12 ? "GOOD MORNING, " : getHour() < 17 ? "GOOD AFTERNOON, " : "GOOD EVENING, ")
            + userName, sp(1.9f), Color.parseColor("#88FFFFFF"), Typeface.BOLD);

        // WIFI header box
        LinearLayout wifiBox = new LinearLayout(this);
        wifiBox.setOrientation(LinearLayout.VERTICAL);
        wifiBox.setBackground(rectBorder(Color.parseColor("#05090F"), Color.parseColor("#1A3355"), dp(7), dp(1)));
        wifiBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams wblp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wblp.topMargin = dp(7); wblp.bottomMargin = dp(8);
        wifiBox.setLayoutParams(wblp);

        LinearLayout wHdr = new LinearLayout(this);
        wHdr.setOrientation(LinearLayout.HORIZONTAL);
        wHdr.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvNum2 = new TextView(this);
        tvNum2.setText("12");
        tvNum2.setTextColor(Color.parseColor("#CC2200"));
        tvNum2.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(3.0f));
        tvNum2.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvNum2.setPadding(0, 0, dp(6), 0);
        wHdr.addView(tvNum2);

        TextView tvWifiTitle = new TextView(this);
        tvWifiTitle.setText("WIFI");
        tvWifiTitle.setTextColor(Color.WHITE);
        tvWifiTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(4.2f));
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

        // Key=GET row
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams krlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        krlp.topMargin = dp(4);
        keyRow.setLayoutParams(krlp);
        TextView tvKey = new TextView(this); tvKey.setText("KEY");
        tvKey.setTextColor(Color.parseColor("#44FFFFFF"));
        tvKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
        tvKey.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f));
        keyRow.addView(tvKey);
        TextView tvKeyVal = new TextView(this); tvKeyVal.setText("+ GET");
        tvKeyVal.setTextColor(Color.parseColor("#44FF88"));
        tvKeyVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        tvKeyVal.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        keyRow.addView(tvKeyVal);
        wifiBox.addView(keyRow);
        p.addView(wifiBox);

        // Net stats
        String[][] netStats = {
            {"BPS","——"}, {"IP ADDRESS",getLocalIp()},
            {"GATEWAY","192.168.1.1"}, {"DNS","8.8.8.8 / 8.8.4.4"},
            {"STRENGTH","████░ 80%"}, {"CHANNEL","6 (2.4GHz)"},
            {"NPC","——"}, {"REY","——"},
        };
        for (String[] row : netStats) {
            LinearLayout sRow = new LinearLayout(this);
            sRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            srlp.topMargin = dp(3);
            sRow.setLayoutParams(srlp);
            TextView tvL = new TextView(this); tvL.setText(row[0]);
            tvL.setTextColor(Color.parseColor("#44FFFFFF"));
            tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
            tvL.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.45f));
            sRow.addView(tvL);
            TextView tvV = new TextView(this); tvV.setText(row[1]);
            tvV.setTextColor(Color.parseColor("#44FF88"));
            tvV.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
            tvV.setTypeface(Typeface.MONOSPACE);
            sRow.addView(tvV);
            p.addView(sRow);
        }

        // TX/RX gauge mini-strip
        LinearLayout gaugeRow = new LinearLayout(this);
        gaugeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams grlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28));
        grlp.topMargin = dp(6);
        gaugeRow.setLayoutParams(grlp);
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
        cell.setPadding(dp(7), dp(3), dp(7), dp(3));
        cell.setBackground(rectBorder(Color.parseColor("#0A0F18"), Color.parseColor("#1A3355"), dp(4), dp(1)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        clp.rightMargin = dp(5);
        cell.setLayoutParams(clp);
        TextView tvL = new TextView(this); tvL.setText(label);
        tvL.setTextColor(Color.parseColor("#44FFFFFF")); tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.5f));
        tvL.setLetterSpacing(0.1f);
        cell.addView(tvL);
        TextView tvB = new TextView(this); tvB.setText(bar);
        tvB.setTextColor(Color.parseColor(color)); tvB.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        tvB.setTypeface(Typeface.MONOSPACE);
        cell.addView(tvB);
        parent.addView(cell);
    }

    // ── PAGE 3 — MEMORY ──────────────────────────────────────────────────

    private LinearLayout buildPageMemory() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(18), dp(34), dp(12), dp(6));
        makeText(p, "WHAT JARVIS KNOWS", sp(2.4f), Color.WHITE, Typeface.BOLD);
        addDivider(p);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        mMemoryList = new LinearLayout(this);
        mMemoryList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(mMemoryList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView tvPl = new TextView(this); tvPl.setText("Loading memories…");
        tvPl.setTextColor(Color.parseColor("#44FFFFFF")); tvPl.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        mMemoryList.addView(tvPl);
        p.addView(sv);
        return p;
    }

    // ── PAGE 4 — REMINDERS ───────────────────────────────────────────────

    private LinearLayout buildPageReminders() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(18), dp(34), dp(12), dp(6));
        makeText(p, "UPCOMING REMINDERS", sp(2.4f), Color.WHITE, Typeface.BOLD);
        addDivider(p);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        mRemindersList = new LinearLayout(this);
        mRemindersList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(mRemindersList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView tvPl = new TextView(this); tvPl.setText("Loading reminders…");
        tvPl.setTextColor(Color.parseColor("#44FFFFFF")); tvPl.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        mRemindersList.addView(tvPl);
        p.addView(sv);
        return p;
    }

    // ── BOTTOM BAR ────────────────────────────────────────────────────────

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#08101A"));
        bar.setPadding(dp(12), 0, dp(12), 0);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(36));
        barLp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(barLp);

        View redDot = new View(this);
        android.graphics.drawable.GradientDrawable rdGd = new android.graphics.drawable.GradientDrawable();
        rdGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        rdGd.setColor(Color.parseColor("#CC2200"));
        redDot.setBackground(rdGd);
        LinearLayout.LayoutParams rdLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        rdLp.rightMargin = dp(7);
        redDot.setLayoutParams(rdLp);
        pulseView(redDot);
        bar.addView(redDot);

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
            tv.setClickable(true); tv.setFocusable(true);
            tv.setOnClickListener(v -> navigateToPage(fi));
            bar.addView(tv);
        }

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // Mic button
        View mic = new View(this);
        android.graphics.drawable.GradientDrawable mGd = new android.graphics.drawable.GradientDrawable();
        mGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        mGd.setColor(Color.parseColor("#CC2200"));
        mic.setBackground(mGd);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        mLp.leftMargin = mLp.rightMargin = dp(8);
        mic.setLayoutParams(mLp);
        pulseView(mic);
        mic.setClickable(true);
        mic.setOnClickListener(v -> startActivity(new Intent(this, VoiceOverlayActivity.class)));
        bar.addView(mic);

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

    // ── DATA ─────────────────────────────────────────────────────────────

    private void loadData() {
        String url  = mSession.getJarvisUrl();
        String name = mSession.getUserName();
        if (url == null || name == null) return;

        JarvisClient.fetchMemories(url, name, mem -> runOnUiThread(() -> {
            if (mLeftVals[0] != null) mLeftVals[0].setText(String.valueOf(mem.length()));
            if (mMemoryList == null) return;
            mMemoryList.removeAllViews();
            if (mem.length() == 0) { addListLine(mMemoryList, "No memories yet."); return; }
            for (int i = 0; i < Math.min(mem.length(), 10); i++) {
                try {
                    org.json.JSONObject m = mem.getJSONObject(i);
                    String c = m.optString("content", m.optString("memory", m.optString("text", "")));
                    if (!c.isEmpty()) addListLine(mMemoryList, "• " + c);
                } catch (Exception ignored) {}
            }
        }));

        JarvisClient.fetchReminders(url, new JarvisClient.RemindersCallback() {
            @Override public void onSuccess(List<JarvisClient.JarvisReminder> rs) {
                runOnUiThread(() -> {
                    if (mLeftVals[3] != null) mLeftVals[3].setText(rs.size() + " SET");
                    if (mTvMsg != null) mTvMsg.setText("MSG\n" + rs.size());
                    if (mRemindersList == null) return;
                    mRemindersList.removeAllViews();
                    if (rs.isEmpty()) { addListLine(mRemindersList, "No reminders set."); return; }
                    for (JarvisClient.JarvisReminder r : rs)
                        addListLine(mRemindersList, (r.isDue ? "⚡ " : "⏰ ") + r.label);
                });
            }
            @Override public void onError(String e) {}
        });

        refreshGreeting();
    }

    private void addListLine(LinearLayout container, String text) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(Color.parseColor("#CCFFFFFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f)); tv.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        container.addView(tv);
    }

    // ── CLOCK + GREETING ─────────────────────────────────────────────────

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
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greet = h < 12 ? "GOOD MORNING," : h < 17 ? "GOOD AFTERNOON," : h < 21 ? "GOOD EVENING," : "GOOD NIGHT,";
        if (mTvGreetingLine != null) mTvGreetingLine.setText(greet);
        if (mTvDateLine != null)
            mTvDateLine.setText(new SimpleDateFormat("dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US));
        if (mTvUserNameBig != null) {
            String n = mSession.getUserName();
            mTvUserNameBig.setText(n != null ? n.toUpperCase(Locale.US) : "OPERATOR");
        }
    }

    // ── HELPERS ──────────────────────────────────────────────────────────

    private TextView makeText(LinearLayout parent, String text, float textSize, int color, int style) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(color); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
        if (style >= 0) tv.setTypeface(null, style);
        parent.addView(tv);
        return tv;
    }

    android.graphics.drawable.Drawable pill(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(20)); gd.setColor(color);
        gd.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        return gd;
    }

    android.graphics.drawable.Drawable rectBorder(int fill, int stroke, float radius, int sw) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radius); gd.setColor(fill); gd.setStroke(sw, stroke);
        return gd;
    }

    private void addDivider(LinearLayout p) {
        View d = new View(this);
        d.setBackgroundColor(Color.parseColor("#1AFFFFFF"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(5); lp.bottomMargin = dp(3);
        d.setLayoutParams(lp);
        p.addView(d);
    }

    void pulseView(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.15f);
        a.setDuration(900); a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE); a.start();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INNER VIEWS
    // ═══════════════════════════════════════════════════════════════════════

    public static class TickRulerView extends View {
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        public TickRulerView(android.content.Context c) { super(c); setWillNotDraw(false); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
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
                mP[i].setStyle(Paint.Style.STROKE); mP[i].setColor(cols[i]);
                mP[i].setStrokeWidth((2.5f - i * 0.5f) * d);
                mP[i].setStrokeCap(Paint.Cap.ROUND); mO[i] = new RectF();
            }
        }
        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun = true; tick(); }
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

    // ═══════════════════════════════════════════════════════════════════════
    // SpinningArcView — kept for compatibility with HomeHudActivity
    // ═══════════════════════════════════════════════════════════════════════

    public static class SpinningArcView extends View {
        private final Paint[] mP;
        private final float[] mA  = new float[6];
        private final float[] mS  = {0.3f, -0.5f, 0.7f, -0.4f, 0.2f, -0.9f};
        private final RectF[] mO;
        private float mBlkRot = 0f;
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;
        private static final int[]   COLS = {0x33FFFFFF,0x80CC2200,0x6000CCFF,0xFFFFFFFF,0x40CC2200,0x2200FFAA};
        private static final float[] WDS  = {2f,3f,1.5f,1f,4f,1f};
        private static final float[] SWP  = {200f,80f,160f,340f,40f,220f};
        private static final float[] BLKS = {0f,30f,60f,90f,120f,150f,210f,240f,270f,300f,330f};
        public SpinningArcView(android.content.Context ctx) {
            super(ctx); setWillNotDraw(false);
            mP = new Paint[6]; mO = new RectF[6];
            float d = ctx.getResources().getDisplayMetrics().density;
            for (int i = 0; i < 6; i++) {
                mP[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mP[i].setStyle(Paint.Style.STROKE); mP[i].setColor(COLS[i]);
                mP[i].setStrokeWidth(WDS[i] * d); mP[i].setStrokeCap(Paint.Cap.ROUND);
                mO[i] = new RectF();
            }
        }
        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun = true; tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun = false; mH.removeCallbacksAndMessages(null); }
        private void tick() {
            for (int i = 0; i < 6; i++) mA[i] = (mA[i] + mS[i]) % 360f;
            mBlkRot = (mBlkRot + 0.4f) % 360f;
            invalidate(); if (mRun) mH.postDelayed(this::tick, 16);
        }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight(); float cx = w/2f, cy = h/2f, min = Math.min(w,h)/2f;
            for (int i = 0; i < 6; i++) {
                float r = min * (0.95f - i * 0.11f);
                mO[i].set(cx-r, cy-r, cx+r, cy+r);
                c.drawArc(mO[i], mA[i], SWP[i], false, mP[i]);
            }
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG); bp.setStyle(Paint.Style.FILL);
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
                double ang = Math.toRadians(t * 5.0); boolean maj = t % 5 == 0;
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

    // ═══════════════════════════════════════════════════════════════════════
    // SpinningOrbWheelView — RIGHT PANEL — Iron Man radial wheel (landscape)
    // Sectors: BRIEF · PHONE · HEALTH · CAMERA · KEY · VAULT · WIFI
    // ═══════════════════════════════════════════════════════════════════════

    public static class SpinningOrbWheelView extends View {
        private static final int   SECTOR_COUNT = 7;
        private static final float GAP_DEG      = 3f;
        private static final float SECTOR_DEG   = (360f / SECTOR_COUNT) - GAP_DEG;

        private static final String[] LABELS = {"BRIEF","PHONE","HEALTH","CAMERA","KEY","VAULT","WIFI"};
        private static final String[] ICONS  = {"📋","📞","❤","📷","🔑","🔐","📶"};
        private static final int[] BASE_COLORS = {
            0xFFCC2200, 0xFF44FF88, 0xFFFF4488, 0xFF4488FF, 0xFFFFD700, 0xFF9945FF, 0xFF00AAFF
        };

        private final Paint[] mSectorFill   = new Paint[SECTOR_COUNT];
        private final Paint[] mSectorStroke = new Paint[SECTOR_COUNT];
        private final Paint   mTextP        = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint   mIconP        = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint   mTickP        = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint   mArcP[]       = new Paint[4];

        private final float[] mArcAngles = {0f, 60f, 120f, 180f};
        private final float[] mArcSpeeds = {0.5f, -0.8f, 0.4f, -0.3f};
        private float mGlow = 0f, mGlowDir = 1f;
        private int mSelected = -1;

        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;

        public interface OnSectorClickListener { void onSectorClick(int idx); }
        private OnSectorClickListener mListener;
        public void setOnSectorClickListener(OnSectorClickListener l) { mListener = l; }

        public SpinningOrbWheelView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            float d = ctx.getResources().getDisplayMetrics().density;
            for (int i = 0; i < SECTOR_COUNT; i++) {
                mSectorFill[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mSectorFill[i].setStyle(Paint.Style.FILL);
                int c = BASE_COLORS[i];
                mSectorFill[i].setColor(Color.argb(25, (c>>16)&0xFF, (c>>8)&0xFF, c&0xFF));
                mSectorStroke[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mSectorStroke[i].setStyle(Paint.Style.STROKE);
                mSectorStroke[i].setStrokeWidth(1.8f * d);
                mSectorStroke[i].setColor(Color.argb(150, (c>>16)&0xFF, (c>>8)&0xFF, c&0xFF));
            }
            int[] arcCols = {0x55CC2200, 0x4400CCFF, 0x33FFFFFF, 0x44CC2200};
            for (int i = 0; i < 4; i++) {
                mArcP[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mArcP[i].setStyle(Paint.Style.STROKE);
                mArcP[i].setStrokeWidth((2.2f - i * 0.3f) * d);
                mArcP[i].setStrokeCap(Paint.Cap.ROUND);
                mArcP[i].setColor(arcCols[i]);
            }
            mTextP.setTextAlign(Paint.Align.CENTER);
            mTextP.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            mIconP.setTextAlign(Paint.Align.CENTER);
            mTickP.setStyle(Paint.Style.STROKE); mTickP.setAntiAlias(true);

            setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    float cx = getWidth()/2f, cy = getHeight()/2f;
                    float dx = e.getX()-cx, dy = e.getY()-cy;
                    double dist = Math.sqrt(dx*dx + dy*dy);
                    float minR = Math.min(getWidth(), getHeight()) / 2f;
                    float inner = minR * 0.30f, outer = minR * 0.90f;
                    if (dist >= inner && dist <= outer) {
                        double ang = Math.toDegrees(Math.atan2(dy, dx));
                        float offset = -90f - (SECTOR_DEG/2f + GAP_DEG/2f);
                        ang = (ang - offset % 360 + 720) % 360;
                        int sec = (int)(ang / (360f / SECTOR_COUNT));
                        if (sec >= 0 && sec < SECTOR_COUNT) {
                            mSelected = sec; invalidate();
                            if (mListener != null) mListener.onSectorClick(sec);
                        }
                    }
                    return true;
                }
                return false;
            });
        }

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun = true; tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun = false; mH.removeCallbacksAndMessages(null); }

        private void tick() {
            for (int i = 0; i < 4; i++) mArcAngles[i] = (mArcAngles[i] + mArcSpeeds[i]) % 360f;
            mGlow += mGlowDir * 0.02f;
            if (mGlow >= 1f) { mGlow=1f; mGlowDir=-1f; } if (mGlow<=0f) { mGlow=0f; mGlowDir=1f; }
            invalidate(); if (mRun) mH.postDelayed(this::tick, 16);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            int w = getWidth(), h = getHeight();
            float cx = w/2f, cy = h/2f;
            float minR = Math.min(w, h) / 2f;
            float outerR = minR * 0.90f, innerR = minR * 0.30f;
            float labelR = minR * 0.66f;
            float startOff = -90f - (SECTOR_DEG/2f + GAP_DEG/2f);

            // Tick ring
            for (int t = 0; t < 120; t++) {
                double ang = Math.toRadians(t * 3.0);
                boolean maj = t % 5 == 0;
                mTickP.setColor(maj ? 0x55FFFFFF : 0x22FFFFFF);
                mTickP.setStrokeWidth((maj ? 1.5f : 0.8f) * d);
                float ro = outerR, ri = ro - (maj ? minR*0.04f : minR*0.02f);
                canvas.drawLine(cx+(float)(Math.cos(ang)*ri), cy+(float)(Math.sin(ang)*ri),
                                cx+(float)(Math.cos(ang)*ro), cy+(float)(Math.sin(ang)*ro), mTickP);
            }

            // Sectors
            for (int i = 0; i < SECTOR_COUNT; i++) {
                float startAngle = startOff + i * (SECTOR_DEG + GAP_DEG);
                boolean sel = (i == mSelected);
                float rOuter = sel ? outerR + minR*0.04f : outerR;

                Path path = new Path();
                double a1 = Math.toRadians(startAngle);
                double a2 = Math.toRadians(startAngle + SECTOR_DEG);
                path.moveTo(cx+(float)(Math.cos(a1)*rOuter), cy+(float)(Math.sin(a1)*rOuter));
                RectF sRect = new RectF(cx-rOuter, cy-rOuter, cx+rOuter, cy+rOuter);
                path.arcTo(sRect, startAngle, SECTOR_DEG, false);
                path.lineTo(cx+(float)(Math.cos(a2)*innerR), cy+(float)(Math.sin(a2)*innerR));
                RectF iRect = new RectF(cx-innerR, cy-innerR, cx+innerR, cy+innerR);
                path.arcTo(iRect, startAngle+SECTOR_DEG, -SECTOR_DEG, false);
                path.close();

                int c = BASE_COLORS[i]; int r=(c>>16)&0xFF, g=(c>>8)&0xFF, b=c&0xFF;
                mSectorFill[i].setColor(Color.argb(sel ? 75 : 22, r, g, b));
                canvas.drawPath(path, mSectorFill[i]);
                canvas.drawPath(path, mSectorStroke[i]);

                // Divider
                mTickP.setColor(0x22FFFFFF); mTickP.setStrokeWidth(0.8f*d);
                canvas.drawLine(cx+(float)(Math.cos(a1)*innerR), cy+(float)(Math.sin(a1)*innerR),
                                cx+(float)(Math.cos(a1)*rOuter), cy+(float)(Math.sin(a1)*rOuter), mTickP);

                // Icon + label
                double midAng = Math.toRadians(startAngle + SECTOR_DEG/2f);
                float lx = cx + (float)(Math.cos(midAng)*labelR);
                float ly = cy + (float)(Math.sin(midAng)*labelR);
                mIconP.setTextSize(minR * 0.08f); mIconP.setColor(BASE_COLORS[i]);
                canvas.drawText(ICONS[i], lx, ly - minR*0.022f, mIconP);
                mTextP.setTextSize(minR * 0.048f);
                mTextP.setColor(sel ? BASE_COLORS[i] : Color.argb(160, 255, 255, 255));
                canvas.drawText(LABELS[i], lx, ly + minR*0.058f, mTextP);
            }

            // Inner spinning arcs
            float[][] arcDefs = {{innerR*0.92f,140f},{innerR*0.76f,80f},{innerR*0.60f,200f},{innerR*0.44f,60f}};
            for (int i = 0; i < 4; i++) {
                float ar = arcDefs[i][0];
                RectF ar2 = new RectF(cx-ar, cy-ar, cx+ar, cy+ar);
                canvas.drawArc(ar2, mArcAngles[i], arcDefs[i][1], false, mArcP[i]);
            }

            // Core
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG); glow.setStyle(Paint.Style.FILL);
            glow.setColor(Color.argb((int)((0.25f+mGlow*0.25f)*255), 0, 100, 220));
            canvas.drawCircle(cx, cy, innerR*0.40f, glow);
            Paint core = new Paint(Paint.ANTI_ALIAS_FLAG); core.setStyle(Paint.Style.FILL);
            core.setColor(Color.parseColor("#050015"));
            canvas.drawCircle(cx, cy, innerR*0.32f, core);
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG); ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1.8f*d);
            ring.setColor(Color.argb((int)(140+mGlow*80), 0, 140, 255));
            canvas.drawCircle(cx, cy, innerR*0.25f, ring);
            core.setColor(Color.argb((int)(180+mGlow*60), 204, 34, 0));
            canvas.drawCircle(cx, cy, innerR*0.07f, core);
        }
    }
}
