package com.jarvis.assist.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
 * LandscapeHudActivity — OSO AugmaOS landscape HUD, pixel-accurate Jarvis edition.
 *
 * Layout:
 *   TOP: 2px red accent line + tick ruler + clock (top-right)
 *   BODY: LEFT 60% swipeable pages | 1px divider | RIGHT 40% SpinningOrbWheelView
 *   BOTTOM: 36dp nav bar with page tabs + mic + page counter
 *
 * Pages: STATUS · PHONE · WIFI · MEMORY · REMINDERS
 * Sector wheel: BRIEF · PHONE · HEALTH · CAMERA · KEY · VAULT · WIFI
 * Every tap plays Voice.wav click.
 * Scan-line overlay on entire root for the OSO CRT aesthetic.
 */
public class LandscapeHudActivity extends AppCompatActivity {

    // ── fonts (shared with HomeHudActivity statics) ──────────────────────
    private static Typeface sMono, sBrand, sRaj;

    private SessionStore mSession;
    private final Handler mClockH = new Handler(Looper.getMainLooper());
    private SoundPool mSoundPool;
    private int mSoundId = -1;
    private boolean mSoundLoaded;

    // density helpers
    private float SD, SS;

    // header
    private TextView mTvClock;

    // left panel pages
    private static final int PAGE_COUNT = 5;
    private static final String[] PAGE_LABELS = {"STATUS","PHONE","WIFI","MEMORY","REMINDERS"};
    private static final int[] PAGE_NUMS = {1,2,12,3,4};
    private LinearLayout[] mPages = new LinearLayout[PAGE_COUNT];
    private int mCurrentPage = 0;
    private FrameLayout mLeftContainer;
    private TextView mTvPageLabel, mTvPageNum, mTvPageIndicator;
    private GestureDetector mGesture;

    // live data refs
    private TextView mTvGreeting, mTvUserName, mTvDateLine;
    private LinearLayout mMemList, mRemList;

    // right orb wheel
    private OrbWheelView mOrbWheel;

    // ── lifecycle ────────────────────────────────────────────────────────

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
        SD = dm.density;
        SS = Math.min(dm.widthPixels, dm.heightPixels) / 100f / dm.scaledDensity;

        loadFonts();
        initSound();
        mGesture = new GestureDetector(this, new SwipeListener());

        buildUI();
        startClock();
        loadData();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        mClockH.removeCallbacksAndMessages(null);
        if (mSoundPool != null) mSoundPool.release();
    }

    private void loadFonts() {
        try {
            sMono  = Typeface.createFromAsset(getAssets(), "fonts/sharetechmono_400.ttf");
            sBrand = Typeface.createFromAsset(getAssets(), "fonts/rajdhani_700.ttf");
            sRaj   = Typeface.createFromAsset(getAssets(), "fonts/rajdhani_400.ttf");
        } catch (Exception ignored) {
            sMono  = Typeface.MONOSPACE;
            sBrand = Typeface.DEFAULT_BOLD;
            sRaj   = Typeface.DEFAULT;
        }
    }

    private void initSound() {
        AudioAttributes a = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        mSoundPool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(a).build();
        mSoundPool.setOnLoadCompleteListener((sp, id, s) -> mSoundLoaded = (s == 0));
        try { mSoundId = mSoundPool.load(getAssets().openFd("Voice.wav"), 1); }
        catch (Exception ignored) {}
    }

    private void click() {
        if (mSoundLoaded && mSoundId != -1) mSoundPool.play(mSoundId, 0.5f, 0.5f, 1, 0, 1f);
    }

    int dp(float v) { return Math.round(v * SD); }
    float sp(float p) { return SS * p; }

    // ── BUILD UI ─────────────────────────────────────────────────────────

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#030508"));

        // Scan-line overlay (CRT aesthetic — same as OSO)
        ScanLineView scan = new ScanLineView(this);
        FrameLayout.LayoutParams scanLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        scan.setLayoutParams(scanLp);
        // added after everything else so it draws on top

        // Red accent top line
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
        rulerLp.gravity = Gravity.TOP;
        rulerLp.topMargin = dp(2);
        root.addView(ruler, rulerLp);

        // Clock top-right
        mTvClock = new TextView(this);
        mTvClock.setText("00:00");
        mTvClock.setTextColor(Color.WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(4.2f));
        mTvClock.setTypeface(sMono, Typeface.BOLD);
        FrameLayout.LayoutParams clkLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        clkLp.gravity = Gravity.TOP | Gravity.END;
        clkLp.topMargin = dp(2);
        clkLp.rightMargin = dp(14);
        root.addView(mTvClock, clkLp);

        // JARVIS brand label center-top
        TextView tvBrand = new TextView(this);
        tvBrand.setText("JARVIS");
        tvBrand.setTextColor(Color.parseColor("#CC2200"));
        tvBrand.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.8f));
        tvBrand.setTypeface(sBrand, Typeface.BOLD);
        tvBrand.setLetterSpacing(0.22f);
        FrameLayout.LayoutParams brandLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        brandLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        brandLp.topMargin = dp(3);
        root.addView(tvBrand, brandLp);

        // Main body panels
        LinearLayout panels = new LinearLayout(this);
        panels.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams panelsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        panelsLp.topMargin = dp(24);
        panelsLp.bottomMargin = dp(36);
        root.addView(panels, panelsLp);

        // LEFT 60%
        mLeftContainer = buildLeftArea();
        panels.addView(mLeftContainer, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.60f));

        // Divider
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        panels.addView(div, new LinearLayout.LayoutParams(dp(1),
                LinearLayout.LayoutParams.MATCH_PARENT));

        // RIGHT 40% — orb wheel
        mOrbWheel = new OrbWheelView(this);
        mOrbWheel.setOnSectorClickListener(idx -> {
            click();
            int[] map = {0, 1, 0, 0, 0, 1, 2}; // BRIEF/HEALTH/CAMERA/KEY→0, PHONE→1, VAULT→1, WIFI→2
            navigateTo(idx < map.length ? map[idx] : 0);
        });
        panels.addView(mOrbWheel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.40f));

        root.addView(buildBottomBar());

        // Scan overlay last (draws on top, pointer events pass through)
        root.addView(scan, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        showPage(0);
    }

    // ── LEFT SWIPEABLE AREA ──────────────────────────────────────────────

    private FrameLayout buildLeftArea() {
        FrameLayout c = new FrameLayout(this);

        mPages[0] = buildPageStatus();
        mPages[1] = buildPagePhone();
        mPages[2] = buildPageWifi();
        mPages[3] = buildPageMemory();
        mPages[4] = buildPageReminders();

        for (LinearLayout p : mPages) {
            p.setVisibility(View.GONE);
            c.addView(p, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        // Page label overlay
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        llp.gravity = Gravity.TOP | Gravity.START;
        llp.topMargin = dp(4); llp.leftMargin = dp(10);
        labelRow.setLayoutParams(llp);

        mTvPageNum = new TextView(this);
        mTvPageNum.setText("01");
        mTvPageNum.setTextColor(Color.parseColor("#CC2200"));
        mTvPageNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(3.0f));
        mTvPageNum.setTypeface(sMono, Typeface.BOLD);
        mTvPageNum.setPadding(0, 0, dp(8), 0);
        labelRow.addView(mTvPageNum);

        mTvPageLabel = new TextView(this);
        mTvPageLabel.setText("STATUS");
        mTvPageLabel.setTextColor(Color.parseColor("#AAFFFFFF"));
        mTvPageLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.4f));
        mTvPageLabel.setLetterSpacing(0.12f);
        mTvPageLabel.setTypeface(null, Typeface.BOLD);
        labelRow.addView(mTvPageLabel);
        c.addView(labelRow);

        // LIVE badge
        TextView tvLive = new TextView(this);
        tvLive.setText("● LIVE");
        tvLive.setTextColor(Color.WHITE);
        tvLive.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        tvLive.setBackground(pill(Color.parseColor("#CC2200")));
        tvLive.setPadding(dp(7), dp(2), dp(7), dp(2));
        FrameLayout.LayoutParams liveLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        liveLp.gravity = Gravity.TOP | Gravity.END;
        liveLp.topMargin = dp(4); liveLp.rightMargin = dp(8);
        tvLive.setLayoutParams(liveLp);
        pulseView(tvLive);
        c.addView(tvLive);

        // Swipe arrows
        addArrow(c, "◀", Gravity.CENTER_VERTICAL | Gravity.START, dp(2), v -> { click(); navigatePage(-1); });
        addArrow(c, "▶", Gravity.CENTER_VERTICAL | Gravity.END,   dp(2), v -> { click(); navigatePage(+1); });

        c.setOnTouchListener((v, e) -> mGesture.onTouchEvent(e));
        return c;
    }

    private void addArrow(FrameLayout parent, String txt, int gravity, int margin, View.OnClickListener cl) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(Color.parseColor("#33FFFFFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.5f));
        tv.setClickable(true); tv.setFocusable(true);
        tv.setOnClickListener(cl);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        if ((gravity & Gravity.START) != 0) lp.leftMargin = margin;
        else lp.rightMargin = margin;
        parent.addView(tv, lp);
    }

    private void showPage(int idx) {
        if (idx < 0 || idx >= PAGE_COUNT) return;
        for (int i = 0; i < PAGE_COUNT; i++)
            mPages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        mCurrentPage = idx;
        if (mTvPageLabel != null) mTvPageLabel.setText(PAGE_LABELS[idx]);
        if (mTvPageNum   != null) mTvPageNum.setText(String.format(Locale.US, "%02d", PAGE_NUMS[idx]));
        if (mTvPageIndicator != null)
            mTvPageIndicator.setText(String.format(Locale.US, "%02d / %02d", idx + 1, PAGE_COUNT));
    }

    private void navigatePage(int delta) { navigateTo((mCurrentPage + delta + PAGE_COUNT) % PAGE_COUNT); }

    private void navigateTo(int next) {
        if (next == mCurrentPage) return;
        int delta = next - mCurrentPage;
        float startX = delta > 0 ? mLeftContainer.getWidth() : -mLeftContainer.getWidth();
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
        if (mTvPageNum   != null) mTvPageNum.setText(String.format(Locale.US, "%02d", PAGE_NUMS[next]));
        if (mTvPageIndicator != null)
            mTvPageIndicator.setText(String.format(Locale.US, "%02d / %02d", next + 1, PAGE_COUNT));
    }

    class SwipeListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
            if (e1 == null || e2 == null) return false;
            float dX = e2.getX() - e1.getX(), dY = e2.getY() - e1.getY();
            if (Math.abs(dX) > Math.abs(dY) && Math.abs(dX) > 80 && Math.abs(vX) > 100) {
                click(); navigatePage(dX > 0 ? -1 : +1); return true;
            }
            return false;
        }
    }

    // ── PAGE 0 — STATUS ──────────────────────────────────────────────────

    private LinearLayout buildPageStatus() {
        LinearLayout p = page();

        // Mini spinning arc
        MiniArcView arc = new MiniArcView(this);
        LinearLayout.LayoutParams arcLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        arcLp.bottomMargin = dp(4);
        p.addView(arc, arcLp);

        mTvGreeting = mono(p, "", sp(2.6f), 0xAAFFFFFF, Typeface.BOLD);
        mTvGreeting.setLetterSpacing(0.1f);

        mTvUserName = mono(p, "OPERATOR", sp(5.0f), Color.WHITE, Typeface.BOLD);
        mTvUserName.setMaxLines(1);
        mTvUserName.setEllipsize(android.text.TextUtils.TruncateAt.END);

        mTvDateLine = mono(p, "", sp(1.9f), Color.parseColor("#44FF88"), Typeface.NORMAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = dp(4);
        mTvDateLine.setLayoutParams(dlp);

        mono(p, "SYSTEM ACTIVE  ·  ONLINE", sp(1.7f), Color.parseColor("#224455"), Typeface.NORMAL)
                .setLetterSpacing(0.1f);
        divider(p);

        String[] labels = {"MEMORY","VAULT","SESSION","NETWORK","SYNC"};
        String[] vals   = {"—","LOCKED","—","ONLINE","LIVE"};
        int[] cols      = {0xFF44FF88,0xFFCC2200,0xFF4499FF,0xFF44FF88,0xFFFFD700};

        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(4);
            row.setLayoutParams(rlp);

            TextView tvN = mono(null, String.format(Locale.US, "%02d", i + 1), sp(1.7f), 0x22FFFFFF, Typeface.NORMAL);
            tvN.setPadding(0, 0, dp(5), 0);
            row.addView(tvN);

            View dot = oval(cols[i], dp(5)); oval(dot, dp(6));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(5), dp(5));
            dotLp.rightMargin = dp(6); dotLp.gravity = Gravity.CENTER_VERTICAL;
            dot.setLayoutParams(dotLp);
            row.addView(dot);

            TextView tvL = mono(null, labels[i], sp(1.8f), 0x55FFFFFF, Typeface.NORMAL);
            tvL.setLetterSpacing(0.05f);
            tvL.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvL);

            TextView tvV = mono(null, vals[i], sp(1.9f), cols[i], Typeface.BOLD);
            row.addView(tvV);
            p.addView(row);
        }
        return p;
    }

    // ── PAGE 1 — PHONE ───────────────────────────────────────────────────

    private LinearLayout buildPagePhone() {
        LinearLayout p = page();
        p.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        leftCol.setPadding(0, dp(34), 0, 0);

        String name = mSession.getUserName() != null
                ? mSession.getUserName().toUpperCase(Locale.US) : "OPERATOR";
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greet = h < 12 ? "GOOD MORNING, " : h < 17 ? "GOOD AFTERNOON, " : "GOOD EVENING, ";
        mono(leftCol, greet + name, sp(1.9f), 0x88FFFFFF, Typeface.BOLD);
        divider(leftCol);

        final StringBuilder dial = new StringBuilder();
        TextView tvNum = mono(leftCol, "——————————", sp(3.8f), Color.WHITE, Typeface.BOLD);
        tvNum.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(4); nlp.bottomMargin = dp(6);
        tvNum.setLayoutParams(nlp);
        divider(leftCol);

        String[] keys = {"1","2","3","4","5","6","7","8","9","*","0","#"};
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(4);
        grid.setLayoutParams(glp);

        for (int row = 0; row < 4; row++) {
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(22));
            rlp.bottomMargin = dp(3);
            rowL.setLayoutParams(rlp);
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                if (idx >= keys.length) break;
                String key = keys[idx];
                TextView btn = new TextView(this);
                btn.setText(key);
                btn.setTextColor(Color.parseColor("#CC2200"));
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.5f));
                btn.setTypeface(sMono, Typeface.BOLD);
                btn.setGravity(Gravity.CENTER);
                btn.setBackground(rectBorder(Color.parseColor("#0C0606"), Color.parseColor("#2A0800"), dp(3), dp(1)));
                btn.setClickable(true); btn.setFocusable(true);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                blp.rightMargin = col < 2 ? dp(3) : 0;
                btn.setLayoutParams(blp);
                btn.setOnClickListener(v -> { click(); dial.append(key); tvNum.setText(dial.toString()); });
                rowL.addView(btn);
            }
            grid.addView(rowL);
        }
        leftCol.addView(grid);

        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26));
        arlp.topMargin = dp(5);
        actRow.setLayoutParams(arlp);

        TextView call = new TextView(this); call.setText("▶ CALL");
        call.setTextColor(Color.parseColor("#44FF88")); call.setGravity(Gravity.CENTER);
        call.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        call.setBackground(rectBorder(Color.parseColor("#001A0D"), Color.parseColor("#2299AA"), dp(4), dp(1)));
        call.setClickable(true); call.setFocusable(true);
        LinearLayout.LayoutParams callLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        callLp.rightMargin = dp(6); call.setLayoutParams(callLp);
        call.setOnClickListener(v -> {
            String n = dial.toString().trim();
            if (!n.isEmpty()) startActivity(new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + n)));
        });
        actRow.addView(call);

        TextView clr = new TextView(this); clr.setText("✕ CLR");
        clr.setTextColor(Color.parseColor("#CC2200")); clr.setGravity(Gravity.CENTER);
        clr.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        clr.setBackground(rectBorder(Color.parseColor("#120000"), Color.parseColor("#331100"), dp(4), dp(1)));
        clr.setClickable(true); clr.setFocusable(true);
        clr.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        clr.setOnClickListener(v -> { dial.setLength(0); tvNum.setText("——————————"); });
        actRow.addView(clr);
        leftCol.addView(actRow);
        p.addView(leftCol);
        return p;
    }

    // ── PAGE 2 — WIFI ────────────────────────────────────────────────────

    private LinearLayout buildPageWifi() {
        LinearLayout p = page();
        String name = mSession.getUserName() != null ? mSession.getUserName().toUpperCase(Locale.US) : "OPERATOR";
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        mono(p, (h<12?"GOOD MORNING, ":h<17?"GOOD AFTERNOON, ":"GOOD EVENING, ") + name, sp(1.9f), 0x88FFFFFF, Typeface.BOLD);
        divider(p);

        String[][] stats = {
            {"BPS","——"},{" IP",localIp()},{"GW","192.168.1.1"},
            {"DNS","8.8.8.8 / 8.8.4.4"},{"SIG","████░  80%"},{"CH","6  (2.4GHz)"}
        };
        for (String[] row : stats) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(4);
            r.setLayoutParams(rlp);
            TextView k = mono(null, row[0], sp(1.7f), 0x44FFFFFF, Typeface.NORMAL);
            k.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f));
            r.addView(k);
            r.addView(mono(null, row[1], sp(1.7f), Color.parseColor("#44FF88"), Typeface.NORMAL));
            p.addView(r);
        }
        return p;
    }

    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.util.Enumeration<java.net.InetAddress> addrs = en.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "—";
    }

    // ── PAGE 3 — MEMORY ──────────────────────────────────────────────────

    private LinearLayout buildPageMemory() {
        LinearLayout p = page();
        mono(p, "WHAT JARVIS KNOWS", sp(2.4f), Color.WHITE, Typeface.BOLD);
        divider(p);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        mMemList = new LinearLayout(this);
        mMemList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(mMemList);
        mono(mMemList, "Loading memories…", sp(1.9f), 0x44FFFFFF, Typeface.NORMAL);
        p.addView(sv);
        return p;
    }

    // ── PAGE 4 — REMINDERS ───────────────────────────────────────────────

    private LinearLayout buildPageReminders() {
        LinearLayout p = page();
        mono(p, "UPCOMING REMINDERS", sp(2.4f), Color.WHITE, Typeface.BOLD);
        divider(p);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        mRemList = new LinearLayout(this);
        mRemList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(mRemList);
        mono(mRemList, "Loading reminders…", sp(1.9f), 0x44FFFFFF, Typeface.NORMAL);
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

        View dot = oval(Color.parseColor("#CC2200"), dp(7));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dlp.rightMargin = dp(7);
        dot.setLayoutParams(dlp);
        pulseView(dot);
        bar.addView(dot);

        for (int i = 0; i < PAGE_LABELS.length; i++) {
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("  ·  ");
                sep.setTextColor(0x22FFFFFF);
                sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
                bar.addView(sep);
            }
            final int fi = i;
            TextView tv = new TextView(this);
            tv.setText(PAGE_LABELS[i]);
            tv.setTextColor(i == 0 ? Color.parseColor("#CC2200") : 0x44FFFFFF);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
            tv.setLetterSpacing(0.1f);
            tv.setClickable(true); tv.setFocusable(true);
            tv.setOnClickListener(v -> { click(); navigateTo(fi); });
            bar.addView(tv);
        }

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // Mic button
        View mic = oval(Color.parseColor("#CC2200"), dp(16));
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        mLp.leftMargin = mLp.rightMargin = dp(8);
        mic.setLayoutParams(mLp);
        pulseView(mic);
        mic.setClickable(true);
        mic.setOnClickListener(v -> { click(); startActivity(new Intent(this, VoiceOverlayActivity.class)); });
        bar.addView(mic);

        View sp2 = new View(this);
        bar.addView(sp2, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        mTvPageIndicator = new TextView(this);
        mTvPageIndicator.setText("01 / 05");
        mTvPageIndicator.setTextColor(0x44FFFFFF);
        mTvPageIndicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        mTvPageIndicator.setTypeface(sMono);
        bar.addView(mTvPageIndicator);
        return bar;
    }

    // ── DATA ─────────────────────────────────────────────────────────────

    private void loadData() {
        String url = mSession.getJarvisUrl(), name = mSession.getUserName();
        if (url == null || name == null) return;
        JarvisClient.fetchMemories(url, name, mem -> runOnUiThread(() -> {
            if (mMemList == null) return;
            mMemList.removeAllViews();
            if (mem.length() == 0) { listLine(mMemList, "No memories yet."); return; }
            for (int i = 0; i < Math.min(mem.length(), 10); i++) {
                try {
                    org.json.JSONObject m = mem.getJSONObject(i);
                    String c = m.optString("content", m.optString("memory", m.optString("text", "")));
                    if (!c.isEmpty()) listLine(mMemList, "• " + c);
                } catch (Exception ignored) {}
            }
        }));
        JarvisClient.fetchReminders(url, new JarvisClient.RemindersCallback() {
            @Override public void onSuccess(List<JarvisClient.JarvisReminder> rs) {
                runOnUiThread(() -> {
                    if (mRemList == null) return;
                    mRemList.removeAllViews();
                    if (rs.isEmpty()) { listLine(mRemList, "No reminders set."); return; }
                    for (JarvisClient.JarvisReminder r : rs)
                        listLine(mRemList, (r.isDue ? "⚡ " : "⏰ ") + r.label);
                });
            }
            @Override public void onError(String e) {}
        });
        refreshGreeting();
    }

    private void listLine(LinearLayout c, String txt) {
        TextView tv = new TextView(this);
        tv.setText(txt); tv.setTextColor(0xCCFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f)); tv.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        c.addView(tv);
    }

    // ── CLOCK ────────────────────────────────────────────────────────────

    private void startClock() {
        mClockH.post(new Runnable() {
            @Override public void run() {
                if (mTvClock != null)
                    mTvClock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
                refreshGreeting();
                mClockH.postDelayed(this, 30_000);
            }
        });
    }

    private void refreshGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h<12?"GOOD MORNING,":h<17?"GOOD AFTERNOON,":h<21?"GOOD EVENING,":"GOOD NIGHT,";
        if (mTvGreeting  != null) mTvGreeting.setText(g);
        if (mTvDateLine  != null)
            mTvDateLine.setText(new SimpleDateFormat("dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US));
        if (mTvUserName  != null) {
            String n = mSession.getUserName();
            mTvUserName.setText(n != null ? n.toUpperCase(Locale.US) : "OPERATOR");
        }
    }

    // ── HELPERS ──────────────────────────────────────────────────────────

    private LinearLayout page() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(34), dp(12), dp(6));
        return p;
    }

    private TextView mono(ViewGroup parent, String text, float size, int color, int style) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        tv.setTypeface(sMono, style);
        if (parent != null) parent.addView(tv);
        return tv;
    }

    private void divider(LinearLayout p) {
        View d = new View(this);
        d.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(5); lp.bottomMargin = dp(3);
        d.setLayoutParams(lp);
        p.addView(d);
    }

    private View oval(int color, int size) {
        View v = new View(this);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        v.setBackground(gd);
        return v;
    }

    private void oval(View v, int size) {}

    private android.graphics.drawable.Drawable pill(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(20)); gd.setColor(color);
        return gd;
    }

    private android.graphics.drawable.Drawable rectBorder(int fill, int stroke, float r, int sw) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(r); gd.setColor(fill); gd.setStroke(sw, stroke);
        return gd;
    }

    void pulseView(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.15f);
        a.setDuration(900); a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE); a.start();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  SCAN LINE OVERLAY — CRT vibe matching OSO
    // ═════════════════════════════════════════════════════════════════════

    public static class ScanLineView extends View {
        private final Paint mP = new Paint();
        public ScanLineView(android.content.Context c) {
            super(c);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            mP.setColor(Color.argb(10, 0, 0, 0));
            mP.setStrokeWidth(1f);
        }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            for (int y = 0; y < h; y += 3) c.drawLine(0, y, w, y, mP);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  TICK RULER
    // ═════════════════════════════════════════════════════════════════════

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
                boolean maj = (x / step) % 5 == 0;
                float th = maj ? h * 0.70f : h * 0.40f;
                mP.setStyle(Paint.Style.STROKE);
                mP.setColor(maj ? 0x66FFFFFF : 0x22FFFFFF);
                c.drawLine(x, h, x, h - th, mP);
                if (maj) {
                    mP.setStyle(Paint.Style.FILL);
                    mP.setColor(0x44FFFFFF);
                    c.drawText(String.valueOf(x / step), x, h * 0.25f, mP);
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  MINI ARC VIEW  — spinning arcs for status page corner
    // ═════════════════════════════════════════════════════════════════════

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
                mP[i].setStrokeWidth((2.5f - i * 0.5f) * d); mP[i].setStrokeCap(Paint.Cap.ROUND);
                mO[i] = new RectF();
            }
        }
        @Override protected void onAttachedToWindow()   { super.onAttachedToWindow();  mRun=true;  tick(); }
        @Override protected void onDetachedFromWindow()  { super.onDetachedFromWindow(); mRun=false; mH.removeCallbacksAndMessages(null); }
        private void tick() { for(int i=0;i<3;i++) mA[i]=(mA[i]+mS[i])%360f; invalidate(); if(mRun) mH.postDelayed(this::tick,16); }
        @Override protected void onDraw(Canvas c) {
            float cx=getWidth()/2f, cy=getHeight()/2f, min=Math.min(getWidth(),getHeight())/2f;
            float[] sw={120f,80f,200f};
            for(int i=0;i<3;i++){float r=min*(0.95f-i*0.22f);mO[i].set(cx-r,cy-r,cx+r,cy+r);c.drawArc(mO[i],mA[i],sw[i],false,mP[i]);}
            Paint dot=new Paint(Paint.ANTI_ALIAS_FLAG); dot.setColor(Color.parseColor("#CC2200"));
            c.drawCircle(cx,cy,min*0.15f,dot);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ORB WHEEL VIEW — right panel radial sector wheel
    // ═════════════════════════════════════════════════════════════════════

    public static class OrbWheelView extends View {
        private static final int   N    = 7;
        private static final float GAP  = 3f;
        private static final float SDEG = (360f / N) - GAP;

        private static final String[] LABELS = {"BRIEF","PHONE","HEALTH","CAMERA","KEY","VAULT","WIFI"};
        private static final String[] ICONS  = {"📋","📞","❤","📷","🔑","🔐","📶"};
        private static final int[] COLORS = {
            0xFFCC2200, 0xFF44FF88, 0xFFFF4488, 0xFF4488FF, 0xFFFFD700, 0xFF9945FF, 0xFF00AAFF
        };

        private final Paint[] mFill  = new Paint[N];
        private final Paint[] mStroke= new Paint[N];
        private final Paint   mTxt   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint   mIcon  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint   mTick  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint[] mArcP  = new Paint[4];
        private final float[] mArcA  = {0f,60f,120f,180f};
        private final float[] mArcS  = {0.5f,-0.8f,0.4f,-0.3f};
        private float mGlow=0f, mGlowDir=1f;
        private int mSel=-1;
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;

        public interface OnSectorClickListener { void onSectorClick(int idx); }
        private OnSectorClickListener mListener;
        public void setOnSectorClickListener(OnSectorClickListener l) { mListener=l; }

        public OrbWheelView(android.content.Context ctx) {
            super(ctx); setWillNotDraw(false);
            float d = ctx.getResources().getDisplayMetrics().density;
            for (int i=0;i<N;i++){
                int c=COLORS[i],r=(c>>16)&0xFF,g=(c>>8)&0xFF,b=c&0xFF;
                mFill[i]=new Paint(Paint.ANTI_ALIAS_FLAG); mFill[i].setStyle(Paint.Style.FILL);
                mFill[i].setColor(Color.argb(25,r,g,b));
                mStroke[i]=new Paint(Paint.ANTI_ALIAS_FLAG); mStroke[i].setStyle(Paint.Style.STROKE);
                mStroke[i].setStrokeWidth(1.8f*d); mStroke[i].setColor(Color.argb(150,r,g,b));
            }
            int[] ac={0x55CC2200,0x4400CCFF,0x33FFFFFF,0x44CC2200};
            for(int i=0;i<4;i++){
                mArcP[i]=new Paint(Paint.ANTI_ALIAS_FLAG); mArcP[i].setStyle(Paint.Style.STROKE);
                mArcP[i].setStrokeWidth((2.2f-i*0.3f)*d); mArcP[i].setStrokeCap(Paint.Cap.ROUND);
                mArcP[i].setColor(ac[i]);
            }
            mTxt.setTextAlign(Paint.Align.CENTER);
            mTxt.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            mIcon.setTextAlign(Paint.Align.CENTER);
            mTick.setStyle(Paint.Style.STROKE); mTick.setAntiAlias(true);

            setOnTouchListener((v,e)->{
                if(e.getAction()==MotionEvent.ACTION_UP){
                    float cx=getWidth()/2f,cy=getHeight()/2f;
                    float dx=e.getX()-cx,dy=e.getY()-cy;
                    double dist=Math.sqrt(dx*dx+dy*dy);
                    float minR=Math.min(getWidth(),getHeight())/2f;
                    if(dist>=minR*0.30f&&dist<=minR*0.90f){
                        double ang=Math.toDegrees(Math.atan2(dy,dx));
                        float off=-90f-(SDEG/2f+GAP/2f);
                        ang=(ang-off%360+720)%360;
                        int sec=(int)(ang/(360f/N));
                        if(sec>=0&&sec<N){mSel=sec;invalidate();if(mListener!=null)mListener.onSectorClick(sec);}
                    }
                }
                return false;
            });
        }

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun=true;  tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun=false; mH.removeCallbacksAndMessages(null); }
        private void tick(){
            for(int i=0;i<4;i++) mArcA[i]=(mArcA[i]+mArcS[i])%360f;
            mGlow+=mGlowDir*0.02f; if(mGlow>=1f){mGlow=1f;mGlowDir=-1f;} if(mGlow<=0f){mGlow=0f;mGlowDir=1f;}
            invalidate(); if(mRun) mH.postDelayed(this::tick,16);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d=getResources().getDisplayMetrics().density;
            int w=getWidth(),h=getHeight();
            float cx=w/2f,cy=h/2f,minR=Math.min(w,h)/2f;
            float outerR=minR*0.90f,innerR=minR*0.30f,labelR=minR*0.66f;
            float startOff=-90f-(SDEG/2f+GAP/2f);

            // Tick ring
            for(int t=0;t<120;t++){
                double ang=Math.toRadians(t*3.0); boolean maj=t%5==0;
                mTick.setColor(maj?0x55FFFFFF:0x22FFFFFF); mTick.setStrokeWidth((maj?1.5f:0.8f)*d);
                float ro=outerR,ri=ro-(maj?minR*0.04f:minR*0.02f);
                canvas.drawLine(cx+(float)(Math.cos(ang)*ri),cy+(float)(Math.sin(ang)*ri),
                                cx+(float)(Math.cos(ang)*ro),cy+(float)(Math.sin(ang)*ro),mTick);
            }

            // Sectors
            for(int i=0;i<N;i++){
                float sa=startOff+i*(SDEG+GAP); boolean sel=(i==mSel);
                float rO=sel?outerR+minR*0.04f:outerR;
                Path path=new Path();
                double a1=Math.toRadians(sa),a2=Math.toRadians(sa+SDEG);
                path.moveTo(cx+(float)(Math.cos(a1)*rO),cy+(float)(Math.sin(a1)*rO));
                path.arcTo(new RectF(cx-rO,cy-rO,cx+rO,cy+rO),sa,SDEG,false);
                path.lineTo(cx+(float)(Math.cos(a2)*innerR),cy+(float)(Math.sin(a2)*innerR));
                path.arcTo(new RectF(cx-innerR,cy-innerR,cx+innerR,cy+innerR),sa+SDEG,-SDEG,false);
                path.close();
                int c=COLORS[i],r=(c>>16)&0xFF,g=(c>>8)&0xFF,b=c&0xFF;
                mFill[i].setColor(Color.argb(sel?75:22,r,g,b));
                canvas.drawPath(path,mFill[i]); canvas.drawPath(path,mStroke[i]);
                // divider line
                mTick.setColor(0x22FFFFFF); mTick.setStrokeWidth(0.8f*d);
                canvas.drawLine(cx+(float)(Math.cos(a1)*innerR),cy+(float)(Math.sin(a1)*innerR),
                                cx+(float)(Math.cos(a1)*rO),cy+(float)(Math.sin(a1)*rO),mTick);
                // icon + label
                double midA=Math.toRadians(sa+SDEG/2f);
                float lx=cx+(float)(Math.cos(midA)*labelR),ly=cy+(float)(Math.sin(midA)*labelR);
                mIcon.setTextSize(minR*0.08f); mIcon.setColor(COLORS[i]);
                canvas.drawText(ICONS[i],lx,ly-minR*0.022f,mIcon);
                mTxt.setTextSize(minR*0.048f);
                mTxt.setColor(sel?COLORS[i]:Color.argb(160,255,255,255));
                canvas.drawText(LABELS[i],lx,ly+minR*0.058f,mTxt);
            }

            // Inner spinning arcs
            float[][] ad={{innerR*0.92f,140f},{innerR*0.76f,80f},{innerR*0.60f,200f},{innerR*0.44f,60f}};
            for(int i=0;i<4;i++){float ar=ad[i][0];canvas.drawArc(new RectF(cx-ar,cy-ar,cx+ar,cy+ar),mArcA[i],ad[i][1],false,mArcP[i]);}

            // Core glow
            Paint glow=new Paint(Paint.ANTI_ALIAS_FLAG); glow.setStyle(Paint.Style.FILL);
            RadialGradient rg=new RadialGradient(cx,cy,innerR*0.45f,
                Color.argb((int)((0.3f+mGlow*0.25f)*255),0,100,220),Color.TRANSPARENT,Shader.TileMode.CLAMP);
            glow.setShader(rg); canvas.drawCircle(cx,cy,innerR*0.45f,glow);
            Paint core=new Paint(Paint.ANTI_ALIAS_FLAG); core.setStyle(Paint.Style.FILL);
            core.setColor(Color.parseColor("#050015")); canvas.drawCircle(cx,cy,innerR*0.32f,core);
            Paint ring=new Paint(Paint.ANTI_ALIAS_FLAG); ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1.8f*d); ring.setColor(Color.argb((int)(140+mGlow*80),0,140,255));
            canvas.drawCircle(cx,cy,innerR*0.25f,ring);
            core.setColor(Color.argb((int)(180+mGlow*60),204,34,0));
            canvas.drawCircle(cx,cy,innerR*0.07f,core);
        }
    }
}
