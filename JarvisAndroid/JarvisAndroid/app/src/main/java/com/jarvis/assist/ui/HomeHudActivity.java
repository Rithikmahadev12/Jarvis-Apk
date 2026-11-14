package com.jarvis.assist.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
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
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.jarvis.assist.network.JarvisClient;
import com.jarvis.assist.storage.SessionStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * HomeHudActivity — OSO AugmaOS portrait HUD
 * Exact visual match to image 1: wheel right, status left, greeting card bottom-left
 */
public class HomeHudActivity extends AppCompatActivity {

    // ── OSO exact color palette ──────────────────────────────────────
    static final int C_BG      = 0xFF030406;
    static final int C_TEAL    = 0xFF4EA0BA;
    static final int C_ORANGE  = 0xFFFF7A1A;
    static final int C_RED     = 0xFFC8102E;
    static final int C_WHITE   = 0xFFE8F0F2;
    static final int C_DIM     = 0xFF70929C;
    static final int C_LINE    = 0x29C8D6E2;
    static final int C_LINE2   = 0x52C8D6E2;

    // OSO portrait wheel slots (image 1: BRIEF, PHONE, HEALTH, CAMERA, KEY, VAULT, WIFI)
    private static final String[] WH_LABELS = {"BRIEF","PHONE","HEALTH","CAMERA","KEY","VAULT","WIFI"};
    private static final String[] WH_SUBS   = {"INFO","CALL","VIT","CAM","PRIV","VAULT","NET"};
    private static final int[]    WH_COLS   = {
        C_ORANGE,   // BRIEF   - amber/brown
        C_TEAL,     // PHONE   - teal
        C_RED,      // HEALTH  - red
        0xFF3A5A8A, // CAMERA  - dark blue
        0xFFFFD700, // KEY     - gold/yellow
        0xFF7A3A9A, // VAULT   - purple
        0xFF2A7ABF, // WIFI    - blue
    };
    private static final int NW = 7;

    private static Typeface sMono, sBrand, sRaj;
    private SessionStore mSession;
    private final Handler mClockH = new Handler(Looper.getMainLooper());
    private float SD, SS;

    private TextView mTvClock, mTvDate;
    private OsoWheelPortrait mWheel;
    private OsoGreetCard mCard;
    private SoundPool mSoundPool;
    private int mSoundId = -1;
    private boolean mSoundLoaded;
    private OrientationEventListener mOrientationListener;
    private boolean mInLandscape;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        mSession = SessionStore.get(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        SD = dm.density; SS = Math.min(dm.widthPixels, dm.heightPixels) / 100f / dm.scaledDensity;
        loadFonts(); initSound();
        buildUI(); startClock(); loadData(); setupOrientation();
    }

    @Override protected void onResume()  { super.onResume();  mInLandscape = false; if (mOrientationListener != null && mOrientationListener.canDetectOrientation()) mOrientationListener.enable(); }
    @Override protected void onPause()   { super.onPause();   if (mOrientationListener != null) mOrientationListener.disable(); }
    @Override protected void onDestroy() { super.onDestroy(); mClockH.removeCallbacksAndMessages(null); if (mSoundPool != null) mSoundPool.release(); }

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

    private void setupOrientation() {
        mOrientationListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                boolean land = (o > 60 && o < 120) || (o > 240 && o < 300);
                if (land && !mInLandscape) {
                    mInLandscape = true;
                    startActivity(new Intent(HomeHudActivity.this, LandscapeHudActivity.class));
                } else if (!land) mInLandscape = false;
            }
        };
        if (mOrientationListener.canDetectOrientation()) mOrientationListener.enable();
    }

    int dp(float v) { return Math.round(v * SD); }
    float sp(float p) { return SS * p; }

    // ── BUILD UI ──────────────────────────────────────────────────────────

    private void buildUI() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int W = dm.widthPixels, H = dm.heightPixels;

        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setBackgroundColor(C_BG);
        rootScroll.setVerticalScrollBarEnabled(false);
        rootScroll.setFillViewport(true);

        LinearLayout rootCol = new LinearLayout(this);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.setBackgroundColor(C_BG);

        // ── TOP FRAME: OSO-exact header + wheel + greeting card ──────────
        FrameLayout topFrame = new FrameLayout(this);

        // OSO red accent top line (2dp)
        View redLine = new View(this);
        redLine.setBackgroundColor(C_RED);
        topFrame.addView(redLine, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(2)));

        // Tick ruler (OSO)
        LandscapeHudActivity.OsoTickRuler ruler = new LandscapeHudActivity.OsoTickRuler(this);
        FrameLayout.LayoutParams rulerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(20));
        rulerLp.topMargin = dp(2);
        topFrame.addView(ruler, rulerLp);

        // OSO wheel — right side, full height
        mWheel = new OsoWheelPortrait(this);
        mWheel.setOnSlotClickListener(this::onSlotTap);
        FrameLayout.LayoutParams wheelLp = new FrameLayout.LayoutParams(W, W);
        topFrame.addView(mWheel, wheelLp);

        // Header overlay — floats top-left over wheel
        topFrame.addView(buildHeaderOverlay(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // OSO corner status bars (right side)
        topFrame.addView(buildCornerBars(), new FrameLayout.LayoutParams(
                dp(90), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));

        // Greeting card over bottom of wheel (OSO: bleeds over)
        mCard = new OsoGreetCard(this);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(128), Gravity.BOTTOM);
        topFrame.addView(mCard, cardLp);

        // OSO scanline overlay
        LandscapeHudActivity.OsoScanline scan = new LandscapeHudActivity.OsoScanline(this);
        scan.setClickable(false); scan.setFocusable(false);
        topFrame.addView(scan, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        rootCol.addView(topFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, W));

        // ── STATUS INFO STRIP ─────────────────────────────────────────────
        rootCol.addView(buildStatusStrip(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── NEXT CLEAR STRIP ─────────────────────────────────────────────
        rootCol.addView(buildNextClearStrip(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── BOTTOM ACTION BAR ─────────────────────────────────────────────
        rootCol.addView(buildActionBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View spacer = new View(this);
        rootCol.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)));

        rootScroll.addView(rootCol, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(rootScroll);
    }

    // ── HEADER OVERLAY (top-left: clock + date + brand) ──────────────────

    private View buildHeaderOverlay() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(5), dp(10), 0);
        android.graphics.drawable.GradientDrawable hdrBg =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{Color.parseColor("#EE030406"), Color.TRANSPARENT});
        row.setBackground(hdrBg);

        // Left: time + date
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mTvClock = new TextView(this);
        mTvClock.setText("--:--");
        mTvClock.setTextColor(C_WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(3.0f));
        mTvClock.setTypeface(sMono, Typeface.BOLD);
        left.addView(mTvClock);

        mTvDate = new TextView(this);
        mTvDate.setText(new SimpleDateFormat("SAT dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US));
        mTvDate.setTextColor(C_DIM);
        mTvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.3f));
        mTvDate.setTypeface(sMono);
        left.addView(mTvDate);
        row.addView(left);

        // Center: OSO "aOS" brand
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        brandRow.setLayoutParams(brandLp);

        TextView tvA = new TextView(this); tvA.setText("a");
        tvA.setTextColor(C_TEAL);
        tvA.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        tvA.setTypeface(sRaj, Typeface.BOLD);
        brandRow.addView(tvA);

        TextView tvOS = new TextView(this); tvOS.setText("OS");
        tvOS.setTextColor(C_WHITE);
        tvOS.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        tvOS.setTypeface(sBrand, Typeface.BOLD);
        tvOS.setLetterSpacing(0.08f);
        brandRow.addView(tvOS);
        row.addView(brandRow);

        // Right: SYS ACTIVE + ONLINE dot
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvSys = new TextView(this); tvSys.setText("SYS ACTIVE");
        tvSys.setTextColor(C_DIM); tvSys.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.2f));
        tvSys.setTypeface(sMono); tvSys.setLetterSpacing(0.1f);
        right.addView(tvSys);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        View dot = ovalView(C_RED, dp(5));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(5), dp(5));
        dlp.rightMargin = dp(3); dot.setLayoutParams(dlp);
        pulseView(dot); statusRow.addView(dot);
        TextView tvOnline = new TextView(this); tvOnline.setText("ONLINE");
        tvOnline.setTextColor(C_DIM); tvOnline.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.2f));
        tvOnline.setTypeface(sMono); tvOnline.setLetterSpacing(0.1f);
        statusRow.addView(tvOnline);
        right.addView(statusRow);
        row.addView(right);
        return row;
    }

    // ── CORNER BARS (OSO top-right HUD bars) ─────────────────────────────

    private View buildCornerBars() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(36), dp(3), 0);
        col.setGravity(Gravity.END);

        String[][] bars = {{"RUN","62",String.valueOf(C_TEAL),"0.62"},
                {"SLEEP","45",String.valueOf(C_TEAL),"0.45"},
                {"MOVE","78",String.valueOf(C_RED),"0.78"},
                {"VO2","55",String.valueOf(C_TEAL),"0.55"}};

        for (String[] b : bars) {
            LinearLayout brow = new LinearLayout(this);
            brow.setOrientation(LinearLayout.HORIZONTAL);
            brow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(4); brow.setLayoutParams(rlp);

            int color = b[0].equals("MOVE") ? C_RED : C_TEAL;
            int cr=(color>>16)&0xFF, cg=(color>>8)&0xFF, cb=color&0xFF;

            TextView tvL = new TextView(this); tvL.setText(b[0]);
            tvL.setTextColor(Color.argb(80, 255, 255, 255));
            tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.0f));
            tvL.setTypeface(sMono); tvL.setWidth(dp(26)); tvL.setGravity(Gravity.END);
            brow.addView(tvL);

            FrameLayout track = new FrameLayout(this);
            android.graphics.drawable.GradientDrawable trackBg = new android.graphics.drawable.GradientDrawable();
            trackBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            trackBg.setCornerRadius(dp(2)); trackBg.setColor(Color.argb(28, 255, 255, 255));
            track.setBackground(trackBg);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(32), dp(3));
            trackLp.leftMargin = dp(3); track.setLayoutParams(trackLp);

            View fill = new View(this);
            float frac = Float.parseFloat(b[3]);
            android.graphics.drawable.GradientDrawable fillBg = new android.graphics.drawable.GradientDrawable();
            fillBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            fillBg.setCornerRadius(dp(2)); fillBg.setColor(color);
            fill.setBackground(fillBg);
            fill.setLayoutParams(new FrameLayout.LayoutParams((int)(dp(32) * frac), ViewGroup.LayoutParams.MATCH_PARENT));
            track.addView(fill);
            brow.addView(track);

            TextView tvV = new TextView(this); tvV.setText(b[1]);
            tvV.setTextColor(color);
            tvV.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.0f));
            tvV.setTypeface(sMono, Typeface.BOLD);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = dp(3); tvV.setLayoutParams(vlp);
            brow.addView(tvV);
            col.addView(brow);
        }
        return col;
    }

    // ── STATUS STRIP ─────────────────────────────────────────────────────

    private View buildStatusStrip() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF030609);
        bar.setPadding(dp(10), dp(5), dp(10), dp(5));

        // Battery chip (OSO)
        FrameLayout wChip = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable wcBg = new android.graphics.drawable.GradientDrawable();
        wcBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        wcBg.setCornerRadius(dp(4));
        wcBg.setColor(Color.argb(35, 200, 16, 46));
        wcBg.setStroke(dp(1), Color.argb(90, 200, 16, 46));
        wChip.setBackground(wcBg); wChip.setPadding(dp(6), dp(2), dp(6), dp(2));
        TextView tvW = new TextView(this); tvW.setText("100%");
        tvW.setTextColor(C_RED); tvW.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        tvW.setTypeface(sMono, Typeface.BOLD);
        wChip.addView(tvW);
        LinearLayout.LayoutParams wcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wcLp.rightMargin = dp(6); wChip.setLayoutParams(wcLp);
        bar.addView(wChip);

        addStatusPill(bar, "JARVIS",    C_TEAL,  false);
        addStatusSep(bar);
        addStatusPill(bar, "CONNECTED", C_TEAL,  true);
        addStatusSep(bar);
        addStatusPill(bar, "LOCAL",     C_TEAL,  true);

        View spacerV = new View(this);
        bar.addView(spacerV, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        addStatusPill(bar, "JARVIS ACTIVE", C_TEAL, true);
        return bar;
    }

    private void addStatusPill(LinearLayout parent, String text, int color, boolean withDot) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(4); pill.setLayoutParams(lp);
        int cr=(color>>16)&0xFF,cg=(color>>8)&0xFF,cb=color&0xFF;

        if (withDot) {
            View pdot = ovalView(color, dp(4));
            LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(dp(4), dp(4));
            dlp2.rightMargin = dp(3); pdot.setLayoutParams(dlp2);
            pill.addView(pdot);
        }
        TextView tv = new TextView(this); tv.setText(text);
        tv.setTextColor(Color.argb(200, cr, cg, cb));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.1f));
        tv.setTypeface(sMono); tv.setLetterSpacing(0.08f);
        pill.addView(tv);
        parent.addView(pill);
    }

    private void addStatusSep(LinearLayout parent) {
        TextView sep = new TextView(this); sep.setText("|");
        sep.setTextColor(Color.argb(35, 255, 255, 255));
        sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.4f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(2); lp.rightMargin = dp(2); sep.setLayoutParams(lp);
        parent.addView(sep);
    }

    // ── NEXT CLEAR STRIP (OSO: health/air quality bar) ───────────────────

    private View buildNextClearStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setBackgroundColor(0xFF050810);
        strip.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labelGroup = new LinearLayout(this);
        labelGroup.setOrientation(LinearLayout.HORIZONTAL);
        labelGroup.setGravity(Gravity.CENTER_VERTICAL);
        labelGroup.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvIcon = new TextView(this); tvIcon.setText("✕ ");
        tvIcon.setTextColor(C_RED); tvIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        tvIcon.setTypeface(sMono, Typeface.BOLD);
        labelGroup.addView(tvIcon);

        TextView tvLabel = new TextView(this); tvLabel.setText("NEXT CLEAR");
        tvLabel.setTextColor(C_TEAL); tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        tvLabel.setTypeface(sMono, Typeface.BOLD); tvLabel.setLetterSpacing(0.1f);
        labelGroup.addView(tvLabel);
        topRow.addView(labelGroup);

        TextView tvSub = new TextView(this); tvSub.setText("AIR QUALITY");
        tvSub.setTextColor(Color.argb(60, 255, 255, 255));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.3f));
        tvSub.setTypeface(sMono);
        topRow.addView(tvSub);
        strip.addView(topRow);

        // OSO gradient bars
        int W = getResources().getDisplayMetrics().widthPixels;
        View redBar = new View(this);
        android.graphics.drawable.GradientDrawable rbBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{C_RED, Color.argb(50, 200, 16, 46)});
        redBar.setBackground(rbBg);
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams((int)(W * 0.70f), dp(3));
        rbLp.topMargin = dp(6); rbLp.bottomMargin = dp(2); redBar.setLayoutParams(rbLp);
        strip.addView(redBar);

        View cyanBar = new View(this);
        android.graphics.drawable.GradientDrawable cbBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{C_TEAL, Color.argb(20, 78, 160, 186)});
        cyanBar.setBackground(cbBg);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams((int)(W * 0.45f), dp(2));
        cbLp.bottomMargin = dp(4); cyanBar.setLayoutParams(cbLp);
        strip.addView(cyanBar);

        LinearLayout dataRow = new LinearLayout(this);
        dataRow.setOrientation(LinearLayout.HORIZONTAL);
        dataRow.setGravity(Gravity.CENTER_VERTICAL);
        addDataChip(dataRow, "C  —  SLEEP", "--", C_TEAL);
        addDataChip(dataRow, "HEART RATE · LAST 24H", "--", C_RED);
        strip.addView(dataRow);
        return strip;
    }

    private void addDataChip(LinearLayout parent, String label, String val, int color) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setPadding(dp(4), dp(2), dp(10), dp(2));
        int cr=(color>>16)&0xFF, cg=(color>>8)&0xFF, cb=color&0xFF;
        TextView tvL = new TextView(this); tvL.setText(label);
        tvL.setTextColor(Color.argb(60, 255, 255, 255));
        tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.1f)); tvL.setTypeface(sMono);
        chip.addView(tvL);
        TextView tvV = new TextView(this); tvV.setText(val);
        tvV.setTextColor(Color.argb(220, cr, cg, cb));
        tvV.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f)); tvV.setTypeface(sMono, Typeface.BOLD);
        chip.addView(tvV);
        parent.addView(chip);
    }

    // ── ACTION BAR (OSO dock: MSG SEARCH CAMERA VAULT + mic) ─────────────

    private View buildActionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(10));
        bar.setBackgroundColor(C_BG);

        String[][] actions = {{"MSG",C_RED+""}, {"SEARCH",String.valueOf(C_TEAL)},
                {"CAMERA",C_RED+""}, {"VAULT",String.valueOf(C_TEAL)}};

        // Battery % left
        LinearLayout pctBox = new LinearLayout(this);
        pctBox.setOrientation(LinearLayout.VERTICAL);
        pctBox.setGravity(Gravity.CENTER);
        pctBox.setPadding(dp(4), 0, dp(4), 0);
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pctLp.rightMargin = dp(2); pctBox.setLayoutParams(pctLp);
        TextView tvPct = new TextView(this); tvPct.setText("100%");
        tvPct.setTextColor(C_TEAL); tvPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        tvPct.setTypeface(sMono, Typeface.BOLD);
        pctBox.addView(tvPct);
        bar.addView(pctBox);

        // MSG button
        addActionBtn(bar, new String[]{"MSG", String.valueOf(C_RED)});

        // Center mic (OSO: pulse circle)
        FrameLayout micFrame = new FrameLayout(this);
        View micCircle = new View(this);
        android.graphics.drawable.GradientDrawable micBg = new android.graphics.drawable.GradientDrawable();
        micBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        micBg.setColor(Color.argb(18, 78, 160, 186));
        micBg.setStroke(dp(2), Color.argb(120, 78, 160, 186));
        micCircle.setBackground(micBg);
        FrameLayout.LayoutParams micLp = new FrameLayout.LayoutParams(dp(50), dp(50));
        micCircle.setLayoutParams(micLp);
        micFrame.addView(micCircle);
        // Mic icon label
        TextView micLbl = new TextView(this); micLbl.setText("TALK TO JARVIS");
        micLbl.setTextColor(Color.argb(60, 255, 255, 255));
        micLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(0.8f));
        micLbl.setGravity(Gravity.CENTER); micLbl.setTypeface(sMono);
        FrameLayout.LayoutParams mlLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mlLp.bottomMargin = -dp(13); micLbl.setLayoutParams(mlLp);
        micFrame.addView(micLbl);
        LinearLayout.LayoutParams mfLp = new LinearLayout.LayoutParams(0, dp(50), 1.5f);
        mfLp.gravity = Gravity.CENTER_VERTICAL; micFrame.setLayoutParams(mfLp);
        micFrame.setOnClickListener(v -> { click(); startActivity(new Intent(this, VoiceOverlayActivity.class)); });
        pulseView(micCircle);
        bar.addView(micFrame);

        addActionBtn(bar, new String[]{"SEARCH", String.valueOf(C_TEAL)});
        addActionBtn(bar, new String[]{"CAMERA", String.valueOf(C_RED)});
        addActionBtn(bar, new String[]{"VAULT",  String.valueOf(C_TEAL)});
        return bar;
    }

    private void addActionBtn(LinearLayout parent, String[] item) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btn.setLayoutParams(lp);

        int color; try { color = Integer.parseInt(item[1]); } catch(Exception e) { color = C_TEAL; }
        int cr=(color>>16)&0xFF, cg=(color>>8)&0xFF, cb=color&0xFF;

        FrameLayout iconBox = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable boxBg = new android.graphics.drawable.GradientDrawable();
        boxBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        boxBg.setCornerRadius(dp(6));
        boxBg.setColor(Color.argb(25, cr, cg, cb));
        boxBg.setStroke(dp(1), Color.argb(85, cr, cg, cb));
        iconBox.setBackground(boxBg);
        iconBox.setLayoutParams(new FrameLayout.LayoutParams(dp(34), dp(34)));

        TextView tvL = new TextView(this); tvL.setText(item[0]);
        tvL.setTextColor(Color.argb(170, cr, cg, cb));
        tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.2f));
        tvL.setTypeface(sMono, Typeface.BOLD); tvL.setGravity(Gravity.CENTER);
        tvL.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(3); tvL.setLayoutParams(llp);

        btn.addView(iconBox); btn.addView(tvL);
        final String lbl = item[0];
        btn.setOnClickListener(v -> { click(); onActionTap(lbl); });
        parent.addView(btn);
    }

    // ── ACTIONS ───────────────────────────────────────────────────────────

    private void onSlotTap(int idx) {
        click();
        String key = new String[]{"brief","dial","health","camera","vault","vault","wifi"}[idx % 7];
        switch (key) {
            case "brief": case "health": case "wifi":
                startActivity(new Intent(this, LandscapeHudActivity.class)); break;
            case "dial": case "camera":
                Intent ci = new Intent(this, MainActivity.class);
                ci.putExtra("vault_unlocked", true);
                ci.putExtra("scroll_to", key);
                startActivity(ci);
                break;
            case "vault":
                Intent vi = new Intent(this, MainActivity.class);
                vi.putExtra("vault_unlocked", true);
                vi.putExtra("scroll_to", "vault");
                startActivity(vi);
                break;
        }
    }

    private void onActionTap(String label) {
        switch (label.toUpperCase(Locale.US)) {
            case "MSG": startActivity(new Intent(this, VoiceOverlayActivity.class)); break;
            case "VAULT":
                Intent vi = new Intent(this, MainActivity.class);
                vi.putExtra("vault_unlocked", true); vi.putExtra("scroll_to", "vault");
                startActivity(vi);
                break;
            default:
                Intent di = new Intent(this, MainActivity.class);
                di.putExtra("vault_unlocked", true);
                di.putExtra("scroll_to", label.toLowerCase(Locale.US));
                startActivity(di);
                break;
        }
    }

    // ── DATA + CLOCK ──────────────────────────────────────────────────────

    private void loadData() {
        String url = mSession.getJarvisUrl(), name = mSession.getUserName();
        String n = name != null ? name.toUpperCase(Locale.US) : "OPERATOR";
        if (mCard != null) mCard.setData(n, getGreeting());
        if (url == null || name == null) return;
        JarvisClient.fetchMemories(url, name, mems -> runOnUiThread(() -> {
            if (mCard != null) mCard.setSub(mems.length() + " MEMORIES  ·  SYSTEM ACTIVE");
        }));
        JarvisClient.fetchWallet(url, name, new JarvisClient.WalletCallback() {
            @Override public void onSuccess(String addr, double sol, double usd) {
                runOnUiThread(() -> { if (mCard != null) mCard.setWallet(String.format(Locale.US, "◎ %.4f", sol)); });
            }
            @Override public void onNotConfigured() {}
            @Override public void onError(String e) {}
        });
    }

    private void startClock() {
        mClockH.post(new Runnable() {
            @Override public void run() {
                if (mTvClock != null) mTvClock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
                if (mTvDate  != null) mTvDate.setText(new SimpleDateFormat("SAT dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US));
                if (mCard    != null) mCard.setGreeting(getGreeting());
                mClockH.postDelayed(this, 30_000);
            }
        });
    }

    private String getGreeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (h < 12) return "GOOD MORNING,";
        if (h < 17) return "GOOD AFTERNOON,";
        if (h < 21) return "GOOD EVENING,";
        return "GOOD NIGHT,";
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private View ovalView(int color, int size) {
        View v = new View(this);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL); gd.setColor(color);
        v.setBackground(gd);
        return v;
    }

    void pulseView(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.15f);
        a.setDuration(900); a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE); a.start();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OSO PORTRAIT WHEEL  — exact replica of image 1 right panel
    //  7 sectors: BRIEF(top), PHONE, HEALTH, CAMERA, KEY, VAULT, WIFI
    //  Each with its icon-label + sub-label at mid-arc radius
    // ══════════════════════════════════════════════════════════════════════

    public class OsoWheelPortrait extends View {
        public interface OnSlotClickListener { void onSlotClick(int idx); }
        private OnSlotClickListener mListener;
        public void setOnSlotClickListener(OnSlotClickListener l) { mListener = l; }

        private final Paint mFillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrkP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTxtP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mSubP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mArcP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlowP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDotP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTickP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRf    = new RectF();

        private float mArcRot = 0f, mArcRot2 = 160f, mArcRot3 = 300f;
        private float mGlow = 0f, mGlowDir = 1f;
        private int mSel = -1;
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;
        private float mCx, mCy, mRO, mRI;

        OsoWheelPortrait(android.content.Context ctx) {
            super(ctx); setWillNotDraw(false);
            float d = ctx.getResources().getDisplayMetrics().density;
            mFillP.setStyle(Paint.Style.FILL);
            mStrkP.setStyle(Paint.Style.STROKE); mStrkP.setStrokeWidth(1.0f * d);
            mTxtP.setAntiAlias(true); mTxtP.setTextAlign(Paint.Align.CENTER);
            mTxtP.setTypeface(sBrand != null ? sBrand : Typeface.DEFAULT_BOLD);
            mSubP.setAntiAlias(true); mSubP.setTextAlign(Paint.Align.CENTER);
            mSubP.setTypeface(sMono != null ? sMono : Typeface.MONOSPACE);
            mArcP.setStyle(Paint.Style.STROKE); mArcP.setStrokeCap(Paint.Cap.ROUND); mArcP.setAntiAlias(true);
            mGlowP.setStyle(Paint.Style.FILL); mGlowP.setAntiAlias(true);
            mDotP.setStyle(Paint.Style.FILL); mDotP.setAntiAlias(true);
            mTickP.setStyle(Paint.Style.STROKE); mTickP.setAntiAlias(true);

            setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    float tx = e.getX() - mCx, ty = e.getY() - mCy;
                    double dist = Math.sqrt(tx * tx + ty * ty);
                    if (dist >= mRI * 0.90f && dist <= mRO * 1.08f) {
                        double ang = (Math.toDegrees(Math.atan2(ty, tx)) + 360) % 360;
                        float sectorW = 360f / NW;
                        float gap = 2f, span = sectorW - gap;
                        float startOff = -90f - span / 2f - gap / 2f;
                        ang = (ang - startOff + 720) % 360;
                        int sec = (int)(ang / sectorW) % NW;
                        mSel = sec; invalidate();
                        if (mListener != null) mListener.onSlotClick(sec);
                    } else if (dist < mRI * 0.5f) {
                        click();
                        startActivity(new Intent(HomeHudActivity.this, VoiceOverlayActivity.class));
                    }
                }
                return true;
            });
        }

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun = true;  tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun = false; mH.removeCallbacksAndMessages(null); }

        private void tick() {
            mArcRot  = (mArcRot  + 0.5f) % 360f;
            mArcRot2 = (mArcRot2 - 0.7f + 360f) % 360f;
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
            float minR = Math.min(w, h) / 2f;
            mCx = w / 2f; mCy = h / 2f;
            mRO = minR * 0.92f;
            mRI = minR * 0.44f;

            // BG grid
            Paint gridP = new Paint();
            gridP.setColor(Color.argb(5, 180, 220, 230)); gridP.setStrokeWidth(0.5f * d);
            int gs = dp(32);
            for (int x = gs/2; x < w; x += gs) canvas.drawLine(x, 0, x, h, gridP);
            for (int y = gs/2; y < h; y += gs) canvas.drawLine(0, y, w, y, gridP);

            // Outer tick ring
            for (int t = 0; t < 120; t++) {
                double ang = Math.toRadians(t * 3.0);
                boolean maj = t % 5 == 0;
                mTickP.setColor(maj ? 0x55FFFFFF : 0x1EFFFFFF);
                mTickP.setStrokeWidth((maj ? 1.3f : 0.6f) * d);
                float ro = mRO, ri = ro - (maj ? minR * 0.04f : minR * 0.02f);
                canvas.drawLine(mCx + (float)(Math.cos(ang) * ri), mCy + (float)(Math.sin(ang) * ri),
                                mCx + (float)(Math.cos(ang) * ro), mCy + (float)(Math.sin(ang) * ro), mTickP);
            }

            // Concentric rings
            int[] ringAs = {0x14C8D6E2, 0x1EC8D6E2, 0x14C8D6E2};
            float[] ringRs = {mRO * 0.98f, mRI * 1.04f, mRI * 0.60f};
            for (int i = 0; i < ringRs.length; i++) {
                mTickP.setColor(ringAs[i]); mTickP.setStrokeWidth(0.7f * d);
                canvas.drawCircle(mCx, mCy, ringRs[i], mTickP);
            }

            // Spinning arcs
            drawSpinArc(canvas, mRI * 0.95f, mArcRot,  120f, Color.argb(80, 78, 160, 186), 1.6f * d);
            drawSpinArc(canvas, mRI * 0.76f, mArcRot2,  80f, Color.argb(65, 200, 16, 46),  1.3f * d);
            drawSpinArc(canvas, mRI * 0.58f, mArcRot3, 200f, Color.argb(45, 255, 122, 26), 1.1f * d);

            // ── WHEEL SECTORS ───────────────────────────────────────────
            float sectorDeg = 360f / NW;
            float gap = 2f, span = sectorDeg - gap;
            float startOff = -90f - span / 2f - gap / 2f;

            for (int i = 0; i < NW; i++) {
                float sa = startOff + i * sectorDeg;
                boolean sel = (i == mSel);
                int col = WH_COLS[i];
                int cr = (col >> 16) & 0xFF, cg = (col >> 8) & 0xFF, cb = col & 0xFF;

                // Wedge path
                Path path = osoWedge(mCx, mCy, mRI, mRO, sa, sa + span, 4f * d);

                mFillP.setColor(Color.argb(sel ? 65 : 20, cr, cg, cb));
                canvas.drawPath(path, mFillP);

                mStrkP.setColor(Color.argb(sel ? 210 : 110, cr, cg, cb));
                mStrkP.setStrokeWidth((sel ? 1.6f : 0.9f) * d);
                canvas.drawPath(path, mStrkP);

                if (sel) {
                    mArcP.setColor(Color.argb(220, cr, cg, cb)); mArcP.setStrokeWidth(2.2f * d);
                    mRf.set(mCx - mRI, mCy - mRI, mCx + mRI, mCy + mRI);
                    canvas.drawArc(mRf, sa + 1.5f, span - 3f, false, mArcP);
                }

                // Label
                double midA = Math.toRadians(sa + span / 2f);
                float labelR = (mRI + mRO) / 2f;
                float lx = mCx + (float)(Math.cos(midA) * labelR);
                float ly = mCy + (float)(Math.sin(midA) * labelR);

                mSubP.setTextSize(minR * 0.048f);
                mSubP.setColor(Color.argb(sel ? 200 : 125, cr, cg, cb));
                canvas.drawText(WH_SUBS[i], lx, ly - minR * 0.032f, mSubP);

                mTxtP.setTextSize(minR * 0.072f);
                mTxtP.setColor(Color.argb(sel ? 255 : 185, cr, cg, cb));
                mTxtP.setTypeface(sBrand != null ? sBrand : Typeface.DEFAULT_BOLD,
                        sel ? Typeface.BOLD : Typeface.NORMAL);
                canvas.drawText(WH_LABELS[i], lx, ly + minR * 0.054f, mTxtP);
            }

            // ── CENTER ───────────────────────────────────────────────────
            float coreR = mRI * 0.40f;
            mDotP.setColor(C_BG);
            canvas.drawCircle(mCx, mCy, mRI * 0.98f, mDotP);

            RadialGradient tealGlow = new RadialGradient(mCx, mCy, coreR,
                new int[]{Color.argb((int)(75 + mGlow * 55), 78, 160, 186),
                          Color.argb((int)(25 + mGlow * 15), 78, 160, 186),
                          Color.TRANSPARENT},
                new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP);
            mGlowP.setShader(tealGlow);
            canvas.drawCircle(mCx, mCy, coreR, mGlowP);
            mGlowP.setShader(null);

            mTickP.setColor(Color.argb((int)(130 + mGlow * 70), 78, 160, 186));
            mTickP.setStrokeWidth(1.5f * d);
            canvas.drawCircle(mCx, mCy, coreR * 0.70f, mTickP);

            // "aOS" center label
            Paint cnP = new Paint(Paint.ANTI_ALIAS_FLAG);
            cnP.setTextAlign(Paint.Align.CENTER);
            cnP.setColor(Color.argb((int)(150 + mGlow * 55), 78, 160, 186));
            cnP.setTextSize(coreR * 0.28f);
            cnP.setTypeface(sRaj != null ? sRaj : Typeface.DEFAULT, Typeface.BOLD);
            canvas.drawText("a", mCx - coreR * 0.08f, mCy + coreR * 0.1f, cnP);
            cnP.setColor(Color.argb((int)(170 + mGlow * 55), 232, 240, 242));
            cnP.setTypeface(sBrand != null ? sBrand : Typeface.DEFAULT_BOLD, Typeface.BOLD);
            canvas.drawText("OS", mCx + coreR * 0.1f, mCy + coreR * 0.1f, cnP);

            mDotP.setColor(Color.argb((int)(190 + mGlow * 55), 200, 16, 46));
            canvas.drawCircle(mCx, mCy, coreR * 0.07f, mDotP);
        }

        private Path osoWedge(float cx, float cy, float ri, float ro, float sa, float ea, float r) {
            Path p = new Path();
            float span = ea - sa;
            mRf.set(cx - ro, cy - ro, cx + ro, cy + ro);
            p.arcTo(mRf, sa, span, true);
            double a2 = Math.toRadians(ea);
            p.lineTo(cx + (float)(Math.cos(a2) * ri), cy + (float)(Math.sin(a2) * ri));
            mRf.set(cx - ri, cy - ri, cx + ri, cy + ri);
            p.arcTo(mRf, ea, -span);
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
    //  OSO GREET CARD — greeting over wheel bottom (image 1 bottom-left)
    // ══════════════════════════════════════════════════════════════════════

    static class OsoGreetCard extends View {
        private final Paint mBgP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBigP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mNameP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mSubP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mWalP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTagP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect  = new RectF();
        private float d;
        private String mGreeting = "GOOD MORNING,", mName = "OPERATOR",
                       mSub = "0 MEMORIES  ·  SYSTEM ACTIVE", mWallet = "◎ 0.0000";
        private float mPulse = 0f, mPulseDir = 1f;
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;

        OsoGreetCard(android.content.Context ctx) {
            super(ctx); setWillNotDraw(false);
            d = ctx.getResources().getDisplayMetrics().density;
            mBgP.setStyle(Paint.Style.FILL);
            mBigP.setColor(Color.argb(170, 232, 240, 242));
            mBigP.setTypeface(Typeface.DEFAULT_BOLD); mBigP.setLetterSpacing(0.01f);
            mNameP.setColor(0xFFE8F0F2);
            mNameP.setTypeface(Typeface.DEFAULT_BOLD); mNameP.setLetterSpacing(0.02f);
            mSubP.setColor(Color.argb(110, 200, 220, 230));
            mSubP.setTypeface(sMono != null ? sMono : Typeface.MONOSPACE); mSubP.setLetterSpacing(0.06f);
            mWalP.setColor(0xFF4EA0BA);
            mWalP.setTypeface(sMono != null ? sMono : Typeface.MONOSPACE);
            mTagP.setColor(0xFFC8102E);
            mTagP.setTypeface(sMono != null ? sMono : Typeface.MONOSPACE);
        }

        void setData(String name, String greeting) { mName = name; mGreeting = greeting; invalidate(); }
        void setGreeting(String g) { mGreeting = g; invalidate(); }
        void setSub(String s) { mSub = s; invalidate(); }
        void setWallet(String w) { mWallet = w; invalidate(); }

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun = true;  tickCard(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun = false; mH.removeCallbacksAndMessages(null); }
        private void tickCard() {
            mPulse += mPulseDir * 0.025f;
            if (mPulse >= 1f) { mPulse = 1f; mPulseDir = -1f; }
            if (mPulse <= 0f) { mPulse = 0f; mPulseDir =  1f; }
            invalidate(); if (mRun) mH.postDelayed(this::tickCard, 50);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();

            // OSO: fade-from-transparent gradient bg
            LinearGradient bgGrad = new LinearGradient(0, 0, 0, h,
                    Color.TRANSPARENT, Color.parseColor("#EE030406"), Shader.TileMode.CLAMP);
            mBgP.setShader(bgGrad);
            canvas.drawRect(0, 0, w, h, mBgP);
            mBgP.setShader(null);

            float tl = Math.round(14 * d);
            float midY = h * 0.42f;

            // "JARVIS — STANDING BY" tag line in red
            mTagP.setTextSize(h * 0.11f); mTagP.setLetterSpacing(0.08f);
            Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotP.setStyle(Paint.Style.FILL); dotP.setColor(0xFFC8102E);
            canvas.drawCircle(tl - d * 2, midY - h * 0.14f, 4 * d, dotP);
            canvas.drawText("JARVIS — STANDING BY", tl + Math.round(6 * d), midY - h * 0.09f, mTagP);

            mBigP.setTextSize(h * 0.21f);
            canvas.drawText(mGreeting, tl, midY + h * 0.07f, mBigP);

            mNameP.setTextSize(h * 0.52f);
            canvas.drawText(mName, tl, midY + h * 0.06f + h * 0.52f, mNameP);

            mSubP.setTextSize(h * 0.12f);
            canvas.drawText(mSub, tl, h - Math.round(6 * d), mSubP);

            mWalP.setTextSize(h * 0.16f);
            mWalP.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(mWallet, w - tl, midY + h * 0.05f, mWalP);

            // OSO health ring (top-right of card)
            float cr2 = h * 0.16f, ccx = w - tl * 1.4f - cr2, ccy = midY + h * 0.06f + h * 0.28f;
            Paint cirBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            cirBg.setStyle(Paint.Style.STROKE); cirBg.setStrokeWidth(2.5f * d);
            cirBg.setColor(Color.argb(30, 255, 255, 255));
            canvas.drawCircle(ccx, ccy, cr2, cirBg);
            Paint cirFg = new Paint(Paint.ANTI_ALIAS_FLAG);
            cirFg.setStyle(Paint.Style.STROKE); cirFg.setStrokeWidth(2.5f * d);
            cirFg.setColor(Color.argb((int)(155 + mPulse * 70), 78, 160, 186));
            cirFg.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(new RectF(ccx - cr2, ccy - cr2, ccx + cr2, ccy + cr2), -90f, 260f, false, cirFg);
            Paint cirTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
            cirTxt.setTextAlign(Paint.Align.CENTER);
            cirTxt.setTypeface(sMono != null ? sMono : Typeface.MONOSPACE);
            cirTxt.setColor(0xFFE8F0F2); cirTxt.setTextSize(cr2 * 0.55f);
            canvas.drawText("69", ccx, ccy + cr2 * 0.2f, cirTxt);
        }
    }
}
