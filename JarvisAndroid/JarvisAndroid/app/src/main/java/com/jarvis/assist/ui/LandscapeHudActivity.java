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
import android.graphics.SweepGradient;
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
 * LandscapeHudActivity — exact OSO AugmaOS visual replica
 * BG=#030406  TEAL=#4EA0BA  ORANGE=#FF7A1A  RED=#C8102E  WHITE=#E8F0F2  DIM=#70929C
 */
public class LandscapeHudActivity extends AppCompatActivity {

    // ── OSO exact color palette ─────────────────────────────────────
    static final int C_BG      = 0xFF030406;
    static final int C_BG2     = 0xFF050810;
    static final int C_TEAL    = 0xFF4EA0BA;
    static final int C_ORANGE  = 0xFFFF7A1A;
    static final int C_RED     = 0xFFC8102E;
    static final int C_WHITE   = 0xFFE8F0F2;
    static final int C_DIM     = 0xFF70929C;
    static final int C_LINE    = 0x29C8D6E2;  // 16% white-blue
    static final int C_LINE2   = 0x52C8D6E2;  // 32% white-blue
    static final int C_SEG     = 0xE610141A;  // segment fill
    static final int C_ACT     = 0x294EA0BA;  // active segment fill

    // OSO wheel slots  {key, LABEL, SUB, icon-hint, color}
    static final Object[][] WHEEL = {
        {"health",   "HEALTH",   "VIT",   C_RED},
        {"weather",  "WEATHER",  "ENV",   C_TEAL},
        {"gallery",  "GALLERY",  "MEDIA", C_ORANGE},
        {"calendar", "CALENDAR", "TIME",  C_TEAL},
        {"settings", "SETTINGS", "SYS",   C_DIM},
        {"dial",     "PHONE",    "CALL",  C_TEAL},
        {"sms",      "MSG",      "SMS",   C_ORANGE},
        {"browser",  "BROWSER",  "WEB",   C_TEAL},
        {"camera",   "CAMERA",   "CAM",   C_RED},
    };

    // show 8 slots
    static final int NW = 8;

    private static Typeface sMono, sBrand, sRaj;

    private SessionStore mSession;
    private final Handler mClockH = new Handler(Looper.getMainLooper());
    private SoundPool mSoundPool;
    private int mSoundId = -1;
    private boolean mSoundLoaded;
    private float SD, SS;

    private TextView mTvClock, mTvDate, mTvBattery;
    private TextView mTvGreeting, mTvUserName;
    private LinearLayout mMemList, mRemList;

    private static final String[] PAGE_LABELS = {"STATUS","PHONE","WIFI","MEMORY","REMINDERS"};
    private static final int[] PAGE_NUMS = {1,2,12,3,4};
    private static final int PAGE_COUNT = 5;
    private LinearLayout[] mPages = new LinearLayout[PAGE_COUNT];
    private int mCurrentPage = 0;
    private FrameLayout mLeftContainer;
    private TextView mTvPageLabel, mTvPageNum, mTvPageIndicator;
    private GestureDetector mGesture;

    private OsoWheelView mOsoWheel;

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
        SD = dm.density; SS = Math.min(dm.widthPixels, dm.heightPixels) / 100f / dm.scaledDensity;
        loadFonts(); initSound();
        mGesture = new GestureDetector(this, new SwipeListener());
        buildUI(); startClock(); loadData();
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
            sMono = Typeface.MONOSPACE; sBrand = Typeface.DEFAULT_BOLD; sRaj = Typeface.DEFAULT;
        }
    }

    private void initSound() {
        AudioAttributes a = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        mSoundPool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(a).build();
        mSoundPool.setOnLoadCompleteListener((sp, id, s) -> mSoundLoaded = (s == 0));
        try { mSoundId = mSoundPool.load(getAssets().openFd("Voice.wav"), 1); } catch (Exception ignored) {}
    }

    private void click() { if (mSoundLoaded && mSoundId != -1) mSoundPool.play(mSoundId, 0.5f, 0.5f, 1, 0, 1f); }

    int dp(float v) { return Math.round(v * SD); }
    float sp(float p) { return SS * p; }

    // ── BUILD UI ──────────────────────────────────────────────────────────

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);

        // OSO top 2px accent line (RED)
        View redLine = new View(this);
        redLine.setBackgroundColor(C_RED);
        FrameLayout.LayoutParams rlLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(2));
        rlLp.gravity = Gravity.TOP;
        root.addView(redLine, rlLp);

        // OSO tick ruler
        OsoTickRuler ruler = new OsoTickRuler(this);
        FrameLayout.LayoutParams rulerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(20));
        rulerLp.gravity = Gravity.TOP;
        rulerLp.topMargin = dp(2);
        root.addView(ruler, rulerLp);

        // Battery % top-left (OSO style)
        mTvBattery = new TextView(this);
        mTvBattery.setText("100%");
        mTvBattery.setTextColor(C_DIM);
        mTvBattery.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        mTvBattery.setTypeface(sMono);
        FrameLayout.LayoutParams battLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        battLp.gravity = Gravity.TOP | Gravity.START;
        battLp.topMargin = dp(4); battLp.leftMargin = dp(12);
        root.addView(mTvBattery, battLp);

        // Clock top-right (OSO: large white mono)
        mTvClock = new TextView(this);
        mTvClock.setText("00:00");
        mTvClock.setTextColor(C_WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(4.0f));
        mTvClock.setTypeface(sMono, Typeface.BOLD);
        FrameLayout.LayoutParams clkLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        clkLp.gravity = Gravity.TOP | Gravity.END;
        clkLp.topMargin = dp(2); clkLp.rightMargin = dp(14);
        root.addView(mTvClock, clkLp);

        // Date next to clock
        mTvDate = new TextView(this);
        mTvDate.setTextColor(C_DIM);
        mTvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        mTvDate.setTypeface(sMono);
        FrameLayout.LayoutParams dateLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        dateLp.gravity = Gravity.TOP | Gravity.END;
        dateLp.topMargin = dp(22); dateLp.rightMargin = dp(14);
        root.addView(mTvDate, dateLp);

        // OSO "aOS" brand center-top
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams brandLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        brandLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        brandLp.topMargin = dp(3);

        TextView tvA = new TextView(this); tvA.setText("a");
        tvA.setTextColor(C_TEAL);
        tvA.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.5f));
        tvA.setTypeface(sRaj, Typeface.BOLD);
        brandRow.addView(tvA);

        TextView tvOS = new TextView(this); tvOS.setText("OS");
        tvOS.setTextColor(C_WHITE);
        tvOS.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.5f));
        tvOS.setTypeface(sBrand, Typeface.BOLD);
        tvOS.setLetterSpacing(0.08f);
        brandRow.addView(tvOS);

        root.addView(brandRow, brandLp);

        // Main body
        LinearLayout panels = new LinearLayout(this);
        panels.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams panelsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        panelsLp.topMargin = dp(22); panelsLp.bottomMargin = dp(34);
        root.addView(panels, panelsLp);

        // LEFT 60%
        mLeftContainer = buildLeftArea();
        panels.addView(mLeftContainer, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.60f));

        // 1px OSO divider
        View div = new View(this);
        div.setBackgroundColor(C_LINE2);
        panels.addView(div, new LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT));

        // RIGHT 40% — OSO wheel
        mOsoWheel = new OsoWheelView(this);
        mOsoWheel.setOnSlotClickListener(idx -> {
            click();
            String key = (String) WHEEL[idx % WHEEL.length][0];
            handleSlotTap(key);
        });
        panels.addView(mOsoWheel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.40f));

        // Bottom bar
        root.addView(buildBottomBar());

        // OSO scanline overlay
        OsoScanline scan = new OsoScanline(this);
        scan.setClickable(false); scan.setFocusable(false);
        root.addView(scan, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        showPage(0);
    }

    // ── LEFT PANEL ────────────────────────────────────────────────────────

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

        // Page num + label overlay (OSO: "01 STATUS")
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        llp.gravity = Gravity.TOP | Gravity.START;
        llp.topMargin = dp(4); llp.leftMargin = dp(10);

        mTvPageNum = new TextView(this);
        mTvPageNum.setText("01");
        mTvPageNum.setTextColor(C_RED);
        mTvPageNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.8f));
        mTvPageNum.setTypeface(sMono, Typeface.BOLD);
        mTvPageNum.setPadding(0, 0, dp(8), 0);
        labelRow.addView(mTvPageNum);

        mTvPageLabel = new TextView(this);
        mTvPageLabel.setText("STATUS");
        mTvPageLabel.setTextColor(C_WHITE);
        mTvPageLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.2f));
        mTvPageLabel.setLetterSpacing(0.14f);
        mTvPageLabel.setTypeface(sBrand, Typeface.BOLD);
        labelRow.addView(mTvPageLabel);
        c.addView(labelRow);

        // LIVE badge (OSO style - red pill)
        TextView tvLive = new TextView(this);
        tvLive.setText("● LIVE");
        tvLive.setTextColor(C_WHITE);
        tvLive.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        tvLive.setTypeface(sMono);
        android.graphics.drawable.GradientDrawable liveBg = new android.graphics.drawable.GradientDrawable();
        liveBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        liveBg.setCornerRadius(dp(20)); liveBg.setColor(C_RED);
        tvLive.setBackground(liveBg);
        tvLive.setPadding(dp(7), dp(2), dp(7), dp(2));
        FrameLayout.LayoutParams liveLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        liveLp.gravity = Gravity.TOP | Gravity.END;
        liveLp.topMargin = dp(4); liveLp.rightMargin = dp(8);
        tvLive.setLayoutParams(liveLp);
        pulseView(tvLive);
        c.addView(tvLive);

        // Swipe arrows
        addArrow(c, "◀", Gravity.CENTER_VERTICAL | Gravity.START, v -> { click(); navigatePage(-1); });
        addArrow(c, "▶", Gravity.CENTER_VERTICAL | Gravity.END,   v -> { click(); navigatePage(+1); });

        c.setOnTouchListener((v, e) -> mGesture.onTouchEvent(e));
        return c;
    }

    private void addArrow(FrameLayout parent, String txt, int gravity, View.OnClickListener cl) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(Color.argb(50, 255, 255, 255));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.2f));
        tv.setClickable(true); tv.setFocusable(true);
        tv.setOnClickListener(cl);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        if ((gravity & Gravity.START) != 0) lp.leftMargin = dp(2);
        else lp.rightMargin = dp(2);
        parent.addView(tv, lp);
    }

    // ── PAGE 0 — STATUS (OSO exact layout) ───────────────────────────────

    private LinearLayout buildPageStatus() {
        LinearLayout p = page();

        // Mini spinning arc (OSO status page indicator)
        OsoMiniArc arc = new OsoMiniArc(this);
        LinearLayout.LayoutParams arcLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        arcLp.bottomMargin = dp(4);
        p.addView(arc, arcLp);

        mTvGreeting = mono(p, "", sp(2.4f), 0xAAE8F0F2, Typeface.BOLD);
        mTvGreeting.setLetterSpacing(0.1f);

        mTvUserName = mono(p, "OPERATOR", sp(4.8f), C_WHITE, Typeface.BOLD);
        mTvUserName.setMaxLines(1);
        mTvUserName.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView tvDateLine = mono(p, new SimpleDateFormat("dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US),
                sp(1.8f), 0xFF44E894, Typeface.NORMAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = dp(4);
        tvDateLine.setLayoutParams(dlp);

        mono(p, "SYSTEM ACTIVE  ·  ONLINE", sp(1.5f), 0x33C8D6E2, Typeface.NORMAL).setLetterSpacing(0.1f);
        osoDiv(p);

        // OSO-exact status rows with colored dots
        String[] labels = {"MEMORY","VAULT","SESSION","NETWORK","SYNC"};
        String[] vals   = {"—","LOCKED","—","ONLINE","LIVE"};
        int[] cols = {0xFF44E894, C_RED, 0xFF5AA9DC, 0xFF44E894, 0xFFFFD700};

        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(4);
            row.setLayoutParams(rlp);

            TextView tvN = mono(null, String.format(Locale.US, "%02d", i + 1), sp(1.5f), 0x22FFFFFF, Typeface.NORMAL);
            tvN.setPadding(0, 0, dp(5), 0);
            row.addView(tvN);

            View dot = ovalView(cols[i], dp(5));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(5), dp(5));
            dotLp.rightMargin = dp(6); dotLp.gravity = Gravity.CENTER_VERTICAL;
            dot.setLayoutParams(dotLp);
            row.addView(dot);

            TextView tvL = mono(null, labels[i], sp(1.7f), 0x55FFFFFF, Typeface.NORMAL);
            tvL.setLetterSpacing(0.05f);
            tvL.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvL);

            row.addView(mono(null, vals[i], sp(1.8f), cols[i], Typeface.BOLD));
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
        leftCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        leftCol.setPadding(0, dp(30), 0, 0);

        String name = mSession.getUserName() != null ? mSession.getUserName().toUpperCase(Locale.US) : "OPERATOR";
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        mono(leftCol, (h<12?"GOOD MORNING, ":h<17?"GOOD AFTERNOON, ":"GOOD EVENING, ") + name, sp(1.8f), 0x88FFFFFF, Typeface.BOLD);
        osoDiv(leftCol);

        final StringBuilder dial = new StringBuilder();
        final TextView tvNum = mono(leftCol, "——————————", sp(3.6f), C_WHITE, Typeface.BOLD);
        tvNum.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(4); nlp.bottomMargin = dp(4);
        tvNum.setLayoutParams(nlp);
        osoDiv(leftCol);

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
                final String key = keys[idx];
                TextView btn = new TextView(this);
                btn.setText(key);
                btn.setTextColor(C_RED);
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.4f));
                btn.setTypeface(sMono, Typeface.BOLD);
                btn.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
                btnBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                btnBg.setCornerRadius(dp(3));
                btnBg.setColor(Color.argb(20, 200, 16, 46));
                btnBg.setStroke(dp(1), Color.argb(60, 200, 16, 46));
                btn.setBackground(btnBg);
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

        TextView call = osoButton(actRow, "▶ CALL", C_TEAL, 1f, v -> {
            String n = dial.toString().trim();
            if (!n.isEmpty()) startActivity(new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + n)));
        });

        osoButton(actRow, "✕ CLR", C_RED, 1f, v -> { dial.setLength(0); tvNum.setText("——————————"); });
        leftCol.addView(actRow);
        p.addView(leftCol);
        return p;
    }

    private TextView osoButton(LinearLayout parent, String text, int color, float weight, View.OnClickListener cl) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
        btn.setTypeface(sMono, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(4)); bg.setColor(Color.argb(18, r, g, b)); bg.setStroke(dp(1), Color.argb(70, r, g, b));
        btn.setBackground(bg);
        btn.setClickable(true); btn.setFocusable(true);
        btn.setOnClickListener(cl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        lp.rightMargin = dp(4);
        btn.setLayoutParams(lp);
        parent.addView(btn);
        return btn;
    }

    // ── PAGE 2 — WIFI ────────────────────────────────────────────────────

    private LinearLayout buildPageWifi() {
        LinearLayout p = page();
        String name = mSession.getUserName() != null ? mSession.getUserName().toUpperCase(Locale.US) : "OPERATOR";
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        mono(p, (h<12?"GOOD MORNING, ":h<17?"GOOD AFTERNOON, ":"GOOD EVENING, ") + name, sp(1.8f), 0x88FFFFFF, Typeface.BOLD);
        osoDiv(p);
        String[][] stats = {{"BPS","——"},{" IP",localIp()},{"GW","192.168.1.1"},
                {"DNS","8.8.8.8 / 8.8.4.4"},{"SIG","████░  80%"},{"CH","6  (2.4 GHz)"}};
        for (String[] row : stats) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(4);
            r.setLayoutParams(rlp);
            TextView k = mono(null, row[0], sp(1.6f), 0x44FFFFFF, Typeface.NORMAL);
            k.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f));
            r.addView(k);
            r.addView(mono(null, row[1], sp(1.6f), 0xFF44E894, Typeface.NORMAL));
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
        mono(p, "WHAT JARVIS KNOWS", sp(2.2f), C_WHITE, Typeface.BOLD);
        osoDiv(p);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        mMemList = new LinearLayout(this);
        mMemList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(mMemList);
        mono(mMemList, "Loading memories…", sp(1.8f), 0x44FFFFFF, Typeface.NORMAL);
        p.addView(sv);
        return p;
    }

    // ── PAGE 4 — REMINDERS ───────────────────────────────────────────────

    private LinearLayout buildPageReminders() {
        LinearLayout p = page();
        mono(p, "UPCOMING REMINDERS", sp(2.2f), C_WHITE, Typeface.BOLD);
        osoDiv(p);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        mRemList = new LinearLayout(this);
        mRemList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(mRemList);
        mono(mRemList, "Loading reminders…", sp(1.8f), 0x44FFFFFF, Typeface.NORMAL);
        p.addView(sv);
        return p;
    }

    // ── BOTTOM BAR (OSO exact) ────────────────────────────────────────────

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF05080F);
        bar.setPadding(dp(12), 0, dp(12), 0);

        // OSO-style top 1px border on bottom bar
        android.graphics.drawable.LayerDrawable ld = null;
        android.graphics.drawable.GradientDrawable barBorder = new android.graphics.drawable.GradientDrawable();
        barBorder.setColor(0xFF05080F);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(34));
        barLp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(barLp);

        // Red live dot
        View dot = ovalView(C_RED, dp(7));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dlp.rightMargin = dp(7);
        dot.setLayoutParams(dlp);
        pulseView(dot);
        bar.addView(dot);

        // Page tabs
        for (int i = 0; i < PAGE_LABELS.length; i++) {
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("  ·  "); sep.setTextColor(0x22FFFFFF);
                sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
                bar.addView(sep);
            }
            final int fi = i;
            TextView tv = new TextView(this);
            tv.setText(PAGE_LABELS[i]);
            tv.setTextColor(i == 0 ? C_RED : 0x44FFFFFF);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
            tv.setLetterSpacing(0.1f);
            tv.setTypeface(sMono);
            tv.setClickable(true); tv.setFocusable(true);
            tv.setOnClickListener(v -> { click(); navigateTo(fi); });
            bar.addView(tv);
        }

        View sp1 = new View(this);
        bar.addView(sp1, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // OSO center mic dot
        View mic = ovalView(C_RED, dp(14));
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(dp(14), dp(14));
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
        mTvPageIndicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
        mTvPageIndicator.setTypeface(sMono);
        bar.addView(mTvPageIndicator);
        return bar;
    }

    // ── PAGE NAVIGATION ───────────────────────────────────────────────────

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
            float dX = e2.getX() - e1.getX();
            if (Math.abs(dX) > 80 && Math.abs(vX) > 100) { click(); navigatePage(dX > 0 ? -1 : +1); return true; }
            return false;
        }
    }

    // ── SLOT TAP HANDLER ─────────────────────────────────────────────────

    private void handleSlotTap(String key) {
        switch (key) {
            case "dial": navigateTo(1); break;
            case "wifi": navigateTo(2); break;
            default:
                Intent i = new Intent(this, MainActivity.class);
                i.putExtra("vault_unlocked", true);
                i.putExtra("scroll_to", key);
                startActivity(i);
                break;
        }
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f)); tv.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        c.addView(tv);
    }

    // ── CLOCK ─────────────────────────────────────────────────────────────

    private void startClock() {
        mClockH.post(new Runnable() {
            @Override public void run() {
                if (mTvClock != null) mTvClock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
                if (mTvDate  != null) mTvDate.setText(new SimpleDateFormat("SAT dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US));
                refreshGreeting();
                mClockH.postDelayed(this, 30_000);
            }
        });
    }

    private void refreshGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h<12?"GOOD MORNING,":h<17?"GOOD AFTERNOON,":h<21?"GOOD EVENING,":"GOOD NIGHT,";
        if (mTvGreeting != null) mTvGreeting.setText(g);
        if (mTvUserName != null) {
            String n = mSession.getUserName();
            mTvUserName.setText(n != null ? n.toUpperCase(Locale.US) : "OPERATOR");
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private LinearLayout page() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(32), dp(12), dp(6));
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

    private void osoDiv(LinearLayout p) {
        View d = new View(this);
        d.setBackgroundColor(C_LINE2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(5); lp.bottomMargin = dp(3);
        d.setLayoutParams(lp);
        p.addView(d);
    }

    private View ovalView(int color, int size) {
        View v = new View(this);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        v.setBackground(gd);
        return v;
    }

    void pulseView(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.15f);
        a.setDuration(900); a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE); a.start();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OSO EXACT WHEEL VIEW
    //  Renders the exact AugmaOS radial sector wheel from the right panel
    //  Sector geometry: RingMath.even(N, gap, startOffset) → wedge paths
    //  Colors: OSO dark theme palette, per-slot accent colors
    // ══════════════════════════════════════════════════════════════════════

    public class OsoWheelView extends View {

        public interface OnSlotClickListener { void onSlotClick(int idx); }
        private OnSlotClickListener mListener;
        public void setOnSlotClickListener(OnSlotClickListener l) { mListener = l; }

        // OSO: 8 wheel slots, each with LABEL, SUB, accent color
        private static final int NW = 8;
        // Slot data matched to OSO screenshot image 2
        private final String[] LABELS = {"HEALTH","WEATHER","GALLERY","CALENDAR","SETTINGS","PHONE","MSG","BROWSER"};
        private final String[] SUBS   = {"VIT",   "ENV",    "MEDIA",  "TIME",    "SYS",    "CALL","SMS","WEB"};
        private final int[]    COLS   = {
            C_RED,    // HEALTH  - red/coral top
            C_TEAL,   // WEATHER - teal
            C_ORANGE, // GALLERY - orange
            C_TEAL,   // CALENDAR - teal
            C_DIM,    // SETTINGS - grey
            C_TEAL,   // PHONE - teal
            C_ORANGE, // MSG - orange
            C_TEAL,   // BROWSER - teal
        };

        private final Paint mFillP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrkP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTxtP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mSubP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mArcP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlowP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDotP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTickP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRf     = new RectF();
        private final float[] mPt   = new float[2];

        // Animation
        private float mArcRot = 0f, mArcRot2 = 0f, mArcRot3 = 0f;
        private float mGlow = 0f, mGlowDir = 1f;
        private int mSel = -1;
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;

        // Hit rects for touch
        private final float[][] mHits = new float[NW][4]; // {cx,cy,r_inner,r_outer,start,sweep}
        private float mCx, mCy, mRO, mRI;

        OsoWheelView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            float d = ctx.getResources().getDisplayMetrics().density;

            mFillP.setStyle(Paint.Style.FILL);
            mStrkP.setStyle(Paint.Style.STROKE);
            mStrkP.setStrokeWidth(1.2f * d);
            mTxtP.setAntiAlias(true);
            mTxtP.setTextAlign(Paint.Align.CENTER);
            mTxtP.setTypeface(sBrand != null ? sBrand : Typeface.DEFAULT_BOLD);
            mSubP.setAntiAlias(true);
            mSubP.setTextAlign(Paint.Align.CENTER);
            mSubP.setTypeface(sMono != null ? sMono : Typeface.MONOSPACE);
            mArcP.setStyle(Paint.Style.STROKE);
            mArcP.setStrokeCap(Paint.Cap.ROUND);
            mArcP.setAntiAlias(true);
            mGlowP.setStyle(Paint.Style.FILL); mGlowP.setAntiAlias(true);
            mDotP.setStyle(Paint.Style.FILL);  mDotP.setAntiAlias(true);
            mTickP.setStyle(Paint.Style.STROKE); mTickP.setAntiAlias(true);

            setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    float tx = e.getX() - mCx, ty = e.getY() - mCy;
                    double dist = Math.sqrt(tx * tx + ty * ty);
                    if (dist >= mRI * 0.95f && dist <= mRO * 1.05f) {
                        double ang = (Math.toDegrees(Math.atan2(ty, tx)) + 360) % 360;
                        // OSO: sectors evenly spaced, gap = 2°, start offset = -90 - half sector
                        float sectorW = 360f / NW;
                        float gap = 2f;
                        float span = sectorW - gap;
                        float startOff = -90f - span / 2f - gap / 2f;
                        ang = (ang - startOff + 720) % 360;
                        int sec = (int)(ang / sectorW) % NW;
                        mSel = sec;
                        invalidate();
                        if (mListener != null) mListener.onSlotClick(sec);
                    } else if (dist < mRI * 0.5f) {
                        // centre tap → voice
                        click();
                        startActivity(new Intent(LandscapeHudActivity.this, VoiceOverlayActivity.class));
                    }
                }
                return true;
            });
        }

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun = true;  tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun = false; mH.removeCallbacksAndMessages(null); }

        private void tick() {
            mArcRot  = (mArcRot  + 0.5f) % 360f;
            mArcRot2 = (mArcRot2 - 0.8f + 360f) % 360f;
            mArcRot3 = (mArcRot3 + 0.3f) % 360f;
            mGlow += mGlowDir * 0.018f;
            if (mGlow >= 1f) { mGlow = 1f; mGlowDir = -1f; }
            if (mGlow <= 0f) { mGlow = 0f; mGlowDir =  1f; }
            invalidate();
            if (mRun) mH.postDelayed(this::tick, 16);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            int w = getWidth(), h = getHeight();
            mCx = w / 2f; mCy = h / 2f;
            float minR = Math.min(w, h) / 2f;
            mRO = minR * 0.90f;
            mRI = minR * 0.42f;    // OSO: thick ring

            // ── Background grid dots (OSO crosshatch) ──────────────────
            Paint gridP = new Paint(); gridP.setColor(Color.argb(6, 180, 220, 230)); gridP.setStrokeWidth(0.5f * d);
            int step = dp(32);
            for (int x = step/2; x < w; x += step) canvas.drawLine(x, 0, x, h, gridP);
            for (int y = step/2; y < h; y += step) canvas.drawLine(0, y, w, y, gridP);

            // ── Outer tick ring ─────────────────────────────────────────
            for (int t = 0; t < 120; t++) {
                double ang = Math.toRadians(t * 3.0);
                boolean maj = t % 5 == 0;
                mTickP.setColor(maj ? 0x55FFFFFF : 0x1EFFFFFF);
                mTickP.setStrokeWidth((maj ? 1.4f : 0.6f) * d);
                float ro = mRO, ri = ro - (maj ? minR * 0.04f : minR * 0.02f);
                canvas.drawLine(mCx + (float)(Math.cos(ang) * ri), mCy + (float)(Math.sin(ang) * ri),
                                mCx + (float)(Math.cos(ang) * ro), mCy + (float)(Math.sin(ang) * ro), mTickP);
            }

            // ── Concentric helper rings ─────────────────────────────────
            float[] ringRs = {mRO * 0.98f, mRI * 1.05f, mRI * 0.72f, mRI * 0.44f};
            int[]   ringAs = {0x14C8D6E2, 0x1EC8D6E2, 0x14C8D6E2, 0x0EC8D6E2};
            for (int i = 0; i < ringRs.length; i++) {
                mTickP.setColor(ringAs[i]); mTickP.setStrokeWidth(0.8f * d);
                canvas.drawCircle(mCx, mCy, ringRs[i], mTickP);
            }

            // ── Spinning arcs (OSO animated rings) ──────────────────────
            drawSpinArc(canvas, mRI * 0.95f, mArcRot,  120f, Color.argb(90, 78, 160, 186), 1.8f * d);  // teal
            drawSpinArc(canvas, mRI * 0.78f, mArcRot2, 80f,  Color.argb(70, 200, 16, 46),  1.4f * d);  // red
            drawSpinArc(canvas, mRI * 0.60f, mArcRot3, 200f, Color.argb(50, 255, 122, 26), 1.2f * d);  // orange

            // ── WHEEL SECTORS ───────────────────────────────────────────
            float sectorDeg = 360f / NW;
            float gap       = 2f;
            float span      = sectorDeg - gap;
            float startOff  = -90f - span / 2f - gap / 2f;

            for (int i = 0; i < NW; i++) {
                float sa = startOff + i * sectorDeg;
                boolean sel = (i == mSel);
                int col = COLS[i];
                int cr = (col >> 16) & 0xFF, cg = (col >> 8) & 0xFF, cb = col & 0xFF;

                // OSO wedge path with rounded corners via arcTo
                Path path = osoWedge(mCx, mCy, mRI, mRO, sa, sa + span, 4f * d);

                // Fill
                mFillP.setColor(Color.argb(sel ? 60 : 18, cr, cg, cb));
                canvas.drawPath(path, mFillP);

                // Stroke
                mStrkP.setColor(Color.argb(sel ? 200 : 100, cr, cg, cb));
                mStrkP.setStrokeWidth((sel ? 1.6f : 1.0f) * d);
                canvas.drawPath(path, mStrkP);

                // If selected: highlight arc on inner edge
                if (sel) {
                    mArcP.setColor(Color.argb(220, cr, cg, cb));
                    mArcP.setStrokeWidth(2.4f * d);
                    mRf.set(mCx - mRI, mCy - mRI, mCx + mRI, mCy + mRI);
                    canvas.drawArc(mRf, sa + 1.5f, span - 3f, false, mArcP);
                }

                // Label position: mid angle, label radius
                double midA = Math.toRadians(sa + span / 2f);
                float labelR = (mRI + mRO) / 2f;
                float lx = mCx + (float)(Math.cos(midA) * labelR);
                float ly = mCy + (float)(Math.sin(midA) * labelR);

                // SUB text (small, above)
                mSubP.setTextSize(minR * 0.052f);
                mSubP.setColor(Color.argb(sel ? 200 : 130, cr, cg, cb));
                canvas.drawText(SUBS[i], lx, ly - minR * 0.034f, mSubP);

                // LABEL text (larger, below)
                mTxtP.setTextSize(minR * 0.075f);
                mTxtP.setColor(Color.argb(sel ? 255 : 180, cr, cg, cb));
                if (sel) mTxtP.setTypeface(sBrand != null ? sBrand : Typeface.DEFAULT_BOLD, Typeface.BOLD);
                else     mTxtP.setTypeface(sBrand != null ? sBrand : Typeface.DEFAULT_BOLD, Typeface.NORMAL);
                canvas.drawText(LABELS[i], lx, ly + minR * 0.058f, mTxtP);
            }

            // ── CENTER VOID + GLOW ───────────────────────────────────────
            // OSO: dark void with TEAL glow + RED core dot
            float coreR = mRI * 0.40f;
            mDotP.setColor(C_BG);
            canvas.drawCircle(mCx, mCy, mRI * 0.98f, mDotP);

            RadialGradient tealGlow = new RadialGradient(mCx, mCy, coreR,
                new int[]{Color.argb((int)(80 + mGlow * 60), 78, 160, 186),
                          Color.argb((int)(30 + mGlow * 20), 78, 160, 186),
                          Color.TRANSPARENT},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
            mGlowP.setShader(tealGlow);
            canvas.drawCircle(mCx, mCy, coreR, mGlowP);
            mGlowP.setShader(null);

            // OSO inner ring
            mTickP.setColor(Color.argb((int)(140 + mGlow * 80), 78, 160, 186));
            mTickP.setStrokeWidth(1.6f * d);
            canvas.drawCircle(mCx, mCy, coreR * 0.72f, mTickP);

            // OSO brand name in center
            Typeface brand = sBrand != null ? sBrand : Typeface.DEFAULT_BOLD;
            Paint cnP = new Paint(Paint.ANTI_ALIAS_FLAG);
            cnP.setTextAlign(Paint.Align.CENTER); cnP.setTypeface(brand, Typeface.BOLD);
            cnP.setColor(Color.argb((int)(160 + mGlow * 60), 78, 160, 186));
            cnP.setTextSize(coreR * 0.28f);
            canvas.drawText("a", mCx - coreR * 0.08f, mCy + coreR * 0.1f, cnP);
            cnP.setColor(Color.argb((int)(180 + mGlow * 60), 232, 240, 242));
            canvas.drawText("OS", mCx + coreR * 0.1f, mCy + coreR * 0.1f, cnP);

            // RED core dot
            mDotP.setColor(Color.argb((int)(200 + mGlow * 55), 200, 16, 46));
            canvas.drawCircle(mCx, mCy, coreR * 0.08f, mDotP);
        }

        /** Draw OSO-style wedge path with rounded outer corners */
        private Path osoWedge(float cx, float cy, float ri, float ro, float startDeg, float endDeg, float r) {
            Path p = new Path();
            float spanDeg = endDeg - startDeg;
            // Outer arc
            mRf.set(cx - ro, cy - ro, cx + ro, cy + ro);
            p.arcTo(mRf, startDeg, spanDeg, true);
            // Line to inner end
            double a2 = Math.toRadians(endDeg);
            p.lineTo(cx + (float)(Math.cos(a2) * ri), cy + (float)(Math.sin(a2) * ri));
            // Inner arc (reverse)
            mRf.set(cx - ri, cy - ri, cx + ri, cy + ri);
            p.arcTo(mRf, endDeg, -spanDeg);
            p.close();
            return p;
        }

        private void drawSpinArc(Canvas canvas, float r, float startDeg, float sweep, int color, float sw) {
            mArcP.setColor(color); mArcP.setStrokeWidth(sw);
            mRf.set(mCx - r, mCy - r, mCx + r, mCy + r);
            canvas.drawArc(mRf, startDeg, sweep, false, mArcP);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OSO TICK RULER
    // ══════════════════════════════════════════════════════════════════════
    public static class OsoTickRuler extends View {
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        public OsoTickRuler(android.content.Context c) { super(c); setWillNotDraw(false); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            mP.setTextAlign(Paint.Align.CENTER);
            mP.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 6,
                    getResources().getDisplayMetrics()));
            int step = 18;
            for (int x = 0; x < w; x += step) {
                boolean maj = (x / step) % 5 == 0;
                float th = maj ? h * 0.65f : h * 0.38f;
                mP.setStyle(Paint.Style.STROKE); mP.setColor(maj ? 0x55FFFFFF : 0x1EFFFFFF);
                mP.setStrokeWidth(maj ? 1.2f : 0.6f);
                c.drawLine(x, h, x, h - th, mP);
                if (maj) {
                    mP.setStyle(Paint.Style.FILL); mP.setColor(0x44FFFFFF);
                    c.drawText(String.valueOf(x / step), x, h * 0.22f, mP);
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OSO MINI ARC (status page spinner)
    // ══════════════════════════════════════════════════════════════════════
    public static class OsoMiniArc extends View {
        private final Paint[] mP = new Paint[3];
        private final float[] mA = {0f, 45f, 90f};
        private final float[] mS = {0.8f, -1.2f, 0.5f};
        private final RectF[]  mO = new RectF[3];
        private final Handler  mH = new Handler(Looper.getMainLooper());
        private boolean mRun;
        public OsoMiniArc(android.content.Context ctx) {
            super(ctx); setWillNotDraw(false);
            float d = ctx.getResources().getDisplayMetrics().density;
            int[] cols = {C_RED, C_TEAL, 0x40FFFFFF};
            for (int i = 0; i < 3; i++) {
                mP[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mP[i].setStyle(Paint.Style.STROKE); mP[i].setColor(cols[i]);
                mP[i].setStrokeWidth((2.4f - i * 0.5f) * d); mP[i].setStrokeCap(Paint.Cap.ROUND);
                mO[i] = new RectF();
            }
        }
        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun = true;  tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun = false; mH.removeCallbacksAndMessages(null); }
        private void tick() { for (int i=0;i<3;i++) mA[i]=(mA[i]+mS[i])%360f; invalidate(); if(mRun) mH.postDelayed(this::tick,16); }
        @Override protected void onDraw(Canvas c) {
            float cx=getWidth()/2f, cy=getHeight()/2f, min=Math.min(getWidth(),getHeight())/2f;
            float[] sw = {120f,80f,200f};
            for (int i=0;i<3;i++) { float r=min*(0.95f-i*0.22f); mO[i].set(cx-r,cy-r,cx+r,cy+r); c.drawArc(mO[i],mA[i],sw[i],false,mP[i]); }
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG); dot.setColor(C_RED);
            c.drawCircle(cx, cy, min*0.14f, dot);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OSO SCANLINE OVERLAY
    // ══════════════════════════════════════════════════════════════════════
    public static class OsoScanline extends View {
        private final Paint mP = new Paint();
        public OsoScanline(android.content.Context c) {
            super(c); setWillNotDraw(false); setClickable(false); setFocusable(false);
            mP.setColor(Color.argb(8, 0, 0, 0)); mP.setStrokeWidth(1f);
        }
        @Override protected void onDraw(Canvas c) {
            int w=getWidth(), h=getHeight();
            for (int y=0; y<h; y+=3) c.drawLine(0, y, w, y, mP);
        }
    }
}
