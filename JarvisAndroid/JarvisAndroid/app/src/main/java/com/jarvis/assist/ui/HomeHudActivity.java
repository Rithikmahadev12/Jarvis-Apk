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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.jarvis.assist.network.JarvisClient;
import com.jarvis.assist.storage.SessionStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HomeHudActivity extends AppCompatActivity {

    private SessionStore mSession;
    private final Handler mClockHandler = new Handler(Looper.getMainLooper());
    private float SD, SS;

    private TextView mTvClock;
    private TextView mTvDate;

    private RadarHudView mRadar;
    private GlassCardView mCard;

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
        SD = dm.density;
        SS = Math.min(dm.widthPixels, dm.heightPixels) / 100f / dm.scaledDensity;
        initSound();
        buildUI();
        startClock();
        loadData();
        setupOrientation();
    }

    @Override protected void onResume()  {
        super.onResume(); mInLandscape = false;
        if (mOrientationListener != null && mOrientationListener.canDetectOrientation())
            mOrientationListener.enable();
    }
    @Override protected void onPause()   { super.onPause();   if (mOrientationListener != null) mOrientationListener.disable(); }
    @Override protected void onDestroy() { super.onDestroy(); mClockHandler.removeCallbacksAndMessages(null); if (mSoundPool != null) mSoundPool.release(); }

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
                if (land && !mInLandscape) { mInLandscape = true; startActivity(new Intent(HomeHudActivity.this, LandscapeHudActivity.class)); }
                else if (!land) mInLandscape = false;
            }
        };
        if (mOrientationListener.canDetectOrientation()) mOrientationListener.enable();
    }

    int dp(float v) { return Math.round(v * SD); }
    float sp(float p) { return SS * p; }

    // ─────────────────────────────────────────────────────────────────────
    //  UI BUILD
    // ─────────────────────────────────────────────────────────────────────

    private void buildUI() {
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setBackgroundColor(Color.parseColor("#020406"));
        rootScroll.setVerticalScrollBarEnabled(false);
        rootScroll.setFillViewport(true);

        LinearLayout rootCol = new LinearLayout(this);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.setBackgroundColor(Color.parseColor("#020406"));

        // ── Radar HUD — FULL SCREEN WIDTH, square, NO margins
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int radarSize = dm.widthPixels;

        // FrameLayout: radar + header overlay + greeting overlay + corner bars — all stacked
        FrameLayout topFrame = new FrameLayout(this);
        topFrame.setBackgroundColor(Color.TRANSPARENT);

        mRadar = new RadarHudView(this);
        mRadar.setOnLabelClickListener(this::onRadarLabelTapped);
        topFrame.addView(mRadar, new FrameLayout.LayoutParams(radarSize, radarSize));

        // Header overlay — floats on top of radar top edge
        topFrame.addView(buildHeaderOverlay(), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP));

        // Right-side corner HUD bars — bleed into right edge
        topFrame.addView(buildCornerBars(), new FrameLayout.LayoutParams(
            dp(88), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.END));

        // Greeting card BLEEDS over bottom of radar — not below it
        mCard = new GlassCardView(this);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(130),
            Gravity.BOTTOM);
        topFrame.addView(mCard, cardLp);

        rootCol.addView(topFrame, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, radarSize));

        // ── Status info bar (311° · K.A.R.A · CONNECTED · LOCAL · KARA INACTIVE · 0)
        rootCol.addView(buildStatusInfoBar(), new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── NEXT CLEAR strip
        rootCol.addView(buildNextClearStrip(), buildStripLp());

        // ── Bottom mini orb
        rootCol.addView(buildMiniOrbSection(), buildMiniOrbLp());

        // Spacer
        View spacer = new View(this);
        rootCol.addView(spacer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(16)));

        rootScroll.addView(rootCol, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(rootScroll);
    }

    // ── HEADER OVERLAY (floats over radar top — tight like reference)
    private LinearLayout buildHeaderOverlay() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), 0);
        // Gradient fade: opaque top, transparent bottom
        android.graphics.drawable.GradientDrawable hdrBg =
            new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#EE020406"), Color.TRANSPARENT});
        row.setBackground(hdrBg);

        // Left: time + date stacked
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mTvClock = new TextView(this);
        mTvClock.setText("--:--");
        mTvClock.setTextColor(Color.WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(3.8f));
        mTvClock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        left.addView(mTvClock);

        mTvDate = new TextView(this);
        mTvDate.setText(new SimpleDateFormat("dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US));
        mTvDate.setTextColor(Color.parseColor("#00AAFF"));
        mTvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.4f));
        mTvDate.setTypeface(Typeface.MONOSPACE);
        left.addView(mTvDate);
        row.addView(left);

        // Center: K.A.R.A brand
        TextView tvBrand = new TextView(this);
        tvBrand.setText("K.A.R.A");
        tvBrand.setTextColor(Color.parseColor("#CC2200"));
        tvBrand.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.2f));
        tvBrand.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvBrand.setLetterSpacing(0.25f);
        tvBrand.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvBrand.setLayoutParams(brandLp);
        row.addView(tvBrand);

        // Right: SYS ACTIVE + online dot
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvSys = new TextView(this);
        tvSys.setText("SYS ACTIVE");
        tvSys.setTextColor(Color.parseColor("#00AAFF"));
        tvSys.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.3f));
        tvSys.setLetterSpacing(0.1f);
        tvSys.setTypeface(Typeface.MONOSPACE);
        right.addView(tvSys);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dg =
            new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dg.setColor(Color.parseColor("#CC2200"));
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(5), dp(5));
        dlp.rightMargin = dp(3); dot.setLayoutParams(dlp);
        pulse(dot); statusRow.addView(dot);
        TextView tvOnline = new TextView(this);
        tvOnline.setText("ONLINE");
        tvOnline.setTextColor(Color.parseColor("#00AAFF"));
        tvOnline.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.3f));
        tvOnline.setLetterSpacing(0.1f);
        tvOnline.setTypeface(Typeface.MONOSPACE);
        statusRow.addView(tvOnline);
        right.addView(statusRow);
        row.addView(right);
        return row;
    }

    // ── RIGHT SIDE CORNER HUD BARS
    private LinearLayout buildCornerBars() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(36), dp(3), 0);
        col.setGravity(Gravity.END);

        String[][] bars = {
            {"RUN",   "62", "#00AAFF", "0.62"},
            {"SLEEP", "45", "#00AAFF", "0.45"},
            {"MOVE",  "78", "#CC2200", "0.78"},
            {"VO2",   "55", "#00AAFF", "0.55"},
        };

        for (String[] b : bars) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(4);
            row.setLayoutParams(rlp);

            // label
            TextView tvL = new TextView(this);
            tvL.setText(b[0]);
            tvL.setTextColor(Color.parseColor("#55FFFFFF"));
            tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.1f));
            tvL.setTypeface(Typeface.MONOSPACE);
            tvL.setWidth(dp(28));
            tvL.setGravity(Gravity.END);
            row.addView(tvL);

            // track + fill
            FrameLayout track = new FrameLayout(this);
            android.graphics.drawable.GradientDrawable trackBg =
                new android.graphics.drawable.GradientDrawable();
            trackBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            trackBg.setCornerRadius(dp(2));
            trackBg.setColor(Color.argb(30, 255, 255, 255));
            track.setBackground(trackBg);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(32), dp(3));
            trackLp.leftMargin = dp(3);
            track.setLayoutParams(trackLp);

            View fill = new View(this);
            float frac = Float.parseFloat(b[3]);
            int fillColor = Color.parseColor(b[2]);
            android.graphics.drawable.GradientDrawable fillBg =
                new android.graphics.drawable.GradientDrawable();
            fillBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            fillBg.setCornerRadius(dp(2));
            fillBg.setColor(fillColor);
            fill.setBackground(fillBg);
            fill.setLayoutParams(new FrameLayout.LayoutParams(
                (int)(dp(32) * frac), ViewGroup.LayoutParams.MATCH_PARENT));
            track.addView(fill);
            row.addView(track);

            // value
            TextView tvV = new TextView(this);
            tvV.setText(b[1]);
            tvV.setTextColor(Color.parseColor(b[2]));
            tvV.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.1f));
            tvV.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = dp(3);
            tvV.setLayoutParams(vlp);
            row.addView(tvV);

            col.addView(row);
        }
        return col;
    }

    // ── STATUS INFO BAR (311° K.A.R.A CONNECTED LOCAL | KARA INACTIVE 0)
    private LinearLayout buildStatusInfoBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#030609"));
        bar.setPadding(dp(10), dp(5), dp(10), dp(5));

        // Weather chip: 31°
        FrameLayout wChip = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable wcBg =
            new android.graphics.drawable.GradientDrawable();
        wcBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        wcBg.setCornerRadius(dp(4));
        wcBg.setColor(Color.argb(40, 0, 170, 255));
        wcBg.setStroke(dp(1), Color.argb(100, 0, 170, 255));
        wChip.setBackground(wcBg);
        wChip.setPadding(dp(7), dp(3), dp(7), dp(3));
        TextView tvW = new TextView(this);
        tvW.setText("31°");
        tvW.setTextColor(Color.parseColor("#00AAFF"));
        tvW.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.0f));
        tvW.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        wChip.addView(tvW);
        LinearLayout.LayoutParams wcLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wcLp.rightMargin = dp(6);
        wChip.setLayoutParams(wcLp);
        bar.addView(wChip);

        addStatusPill(bar, "K.A.R.A",    "#00AAFF", false);
        addStatusSep(bar);
        addStatusPill(bar, "CONNECTED",  "#00AAFF", true);
        addStatusSep(bar);
        addStatusPill(bar, "LOCAL",      "#00AAFF", true);

        // Spacer
        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        addStatusPill(bar, "KARA INACTIVE", "#CC2200", true);

        // Score counter
        TextView tvScore = new TextView(this);
        tvScore.setText("0");
        tvScore.setTextColor(Color.WHITE);
        tvScore.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.0f));
        tvScore.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams scoreLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scoreLp.leftMargin = dp(6);
        tvScore.setLayoutParams(scoreLp);
        bar.addView(tvScore);
        return bar;
    }

    private void addStatusPill(LinearLayout parent, String text, String colorHex, boolean withDot) {
        LinearLayout pill = new LinearLayout(parent.getContext());
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(4);
        pill.setLayoutParams(lp);

        if (withDot) {
            View dot = new View(parent.getContext());
            android.graphics.drawable.GradientDrawable dg =
                new android.graphics.drawable.GradientDrawable();
            dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dg.setColor(Color.parseColor(colorHex));
            dot.setBackground(dg);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(4), dp(4));
            dlp.rightMargin = dp(3);
            dot.setLayoutParams(dlp);
            pill.addView(dot);
        }

        TextView tv = new TextView(parent.getContext());
        tv.setText(text);
        tv.setTextColor(Color.parseColor(colorHex));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.2f));
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLetterSpacing(0.08f);
        pill.addView(tv);
        parent.addView(pill);
    }

    private void addStatusSep(LinearLayout parent) {
        TextView sep = new TextView(parent.getContext());
        sep.setText("|");
        sep.setTextColor(Color.argb(40, 255, 255, 255));
        sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(2); lp.rightMargin = dp(2);
        sep.setLayoutParams(lp);
        parent.addView(sep);
    }

    // ── NEXT CLEAR strip
    private View buildNextClearStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setBackgroundColor(Color.parseColor("#05080F"));
        strip.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        // Icon + label
        LinearLayout labelGroup = new LinearLayout(this);
        labelGroup.setOrientation(LinearLayout.HORIZONTAL);
        labelGroup.setGravity(Gravity.CENTER_VERTICAL);
        labelGroup.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvIcon = new TextView(this);
        tvIcon.setText("✕ ");
        tvIcon.setTextColor(Color.parseColor("#CC2200"));
        tvIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        tvIcon.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        labelGroup.addView(tvIcon);

        TextView tvLabel = new TextView(this);
        tvLabel.setText("NEXT CLEAR");
        tvLabel.setTextColor(Color.parseColor("#00AAFF"));
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        tvLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvLabel.setLetterSpacing(0.1f);
        labelGroup.addView(tvLabel);
        topRow.addView(labelGroup);

        TextView tvSub = new TextView(this);
        tvSub.setText("AIR QUALITY");
        tvSub.setTextColor(Color.parseColor("#44FFFFFF"));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.4f));
        tvSub.setTypeface(Typeface.MONOSPACE);
        topRow.addView(tvSub);
        strip.addView(topRow);

        // Red bar
        View redBar = new View(this);
        android.graphics.drawable.GradientDrawable rbBg =
            new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#CC2200"), Color.parseColor("#33CC2200")});
        redBar.setBackground(rbBg);
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.72f), dp(3));
        rbLp.topMargin = dp(6); rbLp.bottomMargin = dp(2);
        redBar.setLayoutParams(rbLp);
        strip.addView(redBar);

        // Cyan bar
        View cyanBar = new View(this);
        android.graphics.drawable.GradientDrawable cbBg =
            new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#00AAFF"), Color.parseColor("#1100AAFF")});
        cyanBar.setBackground(cbBg);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.47f), dp(2));
        cbLp.bottomMargin = dp(4);
        cyanBar.setLayoutParams(cbLp);
        strip.addView(cyanBar);

        // Metric row
        LinearLayout dataRow = new LinearLayout(this);
        dataRow.setOrientation(LinearLayout.HORIZONTAL);
        dataRow.setGravity(Gravity.CENTER_VERTICAL);
        addDataChip(dataRow, "C  —  SLEEP", "--", "#00AAFF");
        addDataChip(dataRow, "HEART RATE · LAST 24H", "--", "#CC2200");
        strip.addView(dataRow);
        return strip;
    }

    private void addDataChip(LinearLayout parent, String label, String val, String color) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setPadding(dp(4), dp(3), dp(12), dp(3));
        TextView tvL = new TextView(this);
        tvL.setText(label);
        tvL.setTextColor(Color.parseColor("#44FFFFFF"));
        tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.2f));
        tvL.setTypeface(Typeface.MONOSPACE);
        chip.addView(tvL);
        TextView tvV = new TextView(this);
        tvV.setText(val);
        tvV.setTextColor(Color.parseColor(color));
        tvV.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.7f));
        tvV.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        chip.addView(tvV);
        parent.addView(chip);
    }

    // ── MINI ORB SECTION (bottom — orb + action row)
    private View buildMiniOrbSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(Color.parseColor("#020406"));

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int orbSz = (int)(dm.widthPixels * 0.62f);
        MiniOrbView orb = new MiniOrbView(this);
        orb.setOnLabelClickListener(this::onRadarLabelTapped);
        LinearLayout.LayoutParams orbLp = new LinearLayout.LayoutParams(orbSz, orbSz);
        orbLp.gravity = Gravity.CENTER_HORIZONTAL;
        section.addView(orb, orbLp);

        // Action bar
        section.addView(buildActionBar(), new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return section;
    }

    // ── BOTTOM ACTION BAR (MSG | big MIC button | SEARCH | CAMERA | VAULT)
    private View buildActionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(10));
        bar.setBackgroundColor(Color.parseColor("#020406"));

        String[][] actions = {{"MSG","#CC2200"},{"SEARCH","#00AAFF"},{"CAMERA","#CC2200"},{"VAULT","#00AAFF"}};

        // Left two buttons
        addActionBtn(bar, actions[0]);
        // Center big mic button
        FrameLayout micFrame = new FrameLayout(this);
        View micCircle = new View(this);
        android.graphics.drawable.GradientDrawable micBg =
            new android.graphics.drawable.GradientDrawable();
        micBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        micBg.setColor(Color.argb(18, 0, 170, 255));
        micBg.setStroke(dp(2), Color.argb(120, 0, 170, 255));
        micCircle.setBackground(micBg);
        FrameLayout.LayoutParams micLp = new FrameLayout.LayoutParams(dp(52), dp(52));
        micCircle.setLayoutParams(micLp);
        micFrame.addView(micCircle);
        GlassIconView micIcon = new GlassIconView(this, "TALK", Color.parseColor("#00AAFF"));
        micIcon.setLayoutParams(new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        micFrame.addView(micIcon);
        TextView micLbl = new TextView(this);
        micLbl.setText("TALK TO TALK\nK.A.R.A");
        micLbl.setTextColor(Color.parseColor("#44FFFFFF"));
        micLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(0.9f));
        micLbl.setGravity(Gravity.CENTER);
        micLbl.setTypeface(Typeface.MONOSPACE);
        FrameLayout.LayoutParams mlLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mlLp.bottomMargin = -dp(14);
        micFrame.addView(micLbl, mlLp);
        LinearLayout.LayoutParams mfLp = new LinearLayout.LayoutParams(0, dp(52), 1.5f);
        mfLp.gravity = Gravity.CENTER_VERTICAL;
        micFrame.setLayoutParams(mfLp);
        micFrame.setOnClickListener(v -> { click(); startActivity(new Intent(this, VoiceOverlayActivity.class)); });
        bar.addView(micFrame);

        addActionBtn(bar, actions[1]);
        addActionBtn(bar, actions[2]);
        addActionBtn(bar, actions[3]);
        return bar;
    }

    private void addActionBtn(LinearLayout parent, String[] item) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btn.setLayoutParams(lp);

        int color = Color.parseColor(item[1]);
        int cr = (color>>16)&0xFF, cg=(color>>8)&0xFF, cb=color&0xFF;
        FrameLayout iconBox = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable boxBg =
            new android.graphics.drawable.GradientDrawable();
        boxBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        boxBg.setCornerRadius(dp(6));
        boxBg.setColor(Color.argb(28, cr, cg, cb));
        boxBg.setStroke(dp(1), Color.argb(90, cr, cg, cb));
        iconBox.setBackground(boxBg);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(dp(36), dp(36));
        iconBox.setLayoutParams(boxLp);
        GlassIconView icon = new GlassIconView(this, item[0], color);
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        btn.addView(iconBox);

        TextView tvL = new TextView(this);
        tvL.setText(item[0]);
        tvL.setTextColor(Color.argb(160, cr, cg, cb));
        tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.3f));
        tvL.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvL.setGravity(Gravity.CENTER);
        tvL.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(3);
        tvL.setLayoutParams(llp);
        btn.addView(tvL);

        final String lbl = item[0];
        btn.setOnClickListener(v -> { click(); onGridItemTapped(lbl); });
        parent.addView(btn);
    }

    private LinearLayout.LayoutParams buildStripLp() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buildMiniOrbLp() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ACTIONS
    // ─────────────────────────────────────────────────────────────────────

    private void onRadarLabelTapped(String label) {
        click();
        switch (label.toUpperCase(Locale.US)) {
            case "TALK":      startActivity(new Intent(this, VoiceOverlayActivity.class)); break;
            case "LANDSCAPE": startActivity(new Intent(this, LandscapeHudActivity.class)); break;
            case "VAULT": case "WALLET":
                Intent vi = new Intent(this, MainActivity.class);
                vi.putExtra("vault_unlocked", true); vi.putExtra("scroll_to", "vault"); startActivity(vi); break;
            default:
                Intent di = new Intent(this, MainActivity.class);
                di.putExtra("vault_unlocked", true);
                di.putExtra("scroll_to", label.toLowerCase(Locale.US)); startActivity(di); break;
        }
    }

    private void onGridItemTapped(String label) {
        switch (label.toUpperCase(Locale.US)) {
            case "TALK": case "MSG":   startActivity(new Intent(this, VoiceOverlayActivity.class)); break;
            case "LANDSCAPE":          startActivity(new Intent(this, LandscapeHudActivity.class)); break;
            default:
                Intent i = new Intent(this, MainActivity.class);
                i.putExtra("vault_unlocked", true);
                i.putExtra("scroll_to", label.toLowerCase(Locale.US)); startActivity(i); break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DATA
    // ─────────────────────────────────────────────────────────────────────

    private void loadData() {
        String url = mSession.getJarvisUrl(), name = mSession.getUserName();
        String n   = name != null ? name.toUpperCase(Locale.US) : "OPERATOR";
        if (mCard != null) mCard.setNameAndGreeting(n, getGreeting());
        if (url == null || name == null) return;

        JarvisClient.fetchMemories(url, name, mems -> runOnUiThread(() -> {
            if (mCard != null)
                mCard.setSub(mems.length() + " MEMORIES  ·  SYSTEM ACTIVE");
        }));
        JarvisClient.fetchWallet(url, name, new JarvisClient.WalletCallback() {
            @Override public void onSuccess(String addr, double sol, double usd) {
                runOnUiThread(() -> { if (mCard != null) mCard.setWallet(String.format(Locale.US, "◎ %.4f", sol)); });
            }
            @Override public void onNotConfigured() {}
            @Override public void onError(String e) {}
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CLOCK
    // ─────────────────────────────────────────────────────────────────────

    private void startClock() {
        mClockHandler.post(new Runnable() {
            @Override public void run() {
                if (mTvClock != null) mTvClock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
                if (mTvDate  != null) mTvDate.setText(new SimpleDateFormat("dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US));
                if (mCard    != null) mCard.setGreetingPrefix(getGreeting());
                mClockHandler.postDelayed(this, 30_000);
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

    void pulse(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.15f);
        a.setDuration(900); a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE); a.start();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  RADAR HUD VIEW — full-bleed, reference-accurate
    // ═════════════════════════════════════════════════════════════════════

    public static class RadarHudView extends View {

        public interface LabelClickListener { void onLabelClick(String label); }
        private LabelClickListener mListener;
        public void setOnLabelClickListener(LabelClickListener l) { mListener = l; }

        private static final Object[][] LABEL_DEFS = {
            {"WEATHER",    20f,  0.72f, "#00AAFF"},
            {"CONTROL",    50f,  0.88f, "#00AAFF"},
            {"ASTRONOMY",  85f,  0.78f, "#CC2200"},
            {"PROC TASKS", 125f, 0.68f, "#00AAFF"},
            {"FREE TASKS", 155f, 0.85f, "#00AAFF"},
            {"EVENTS",     200f, 0.75f, "#CC2200"},
            {"APP AGENT",  240f, 0.88f, "#00AAFF"},
            {"VAULT",      280f, 0.72f, "#CC2200"},
            {"TALK",       320f, 0.82f, "#CC2200"},
        };

        private final Paint mRingP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTickP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mChipP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mVortexP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlowP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDotP    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint[] mArcP  = new Paint[5];

        private final float[] mArcA = {0f, 60f, 120f, 180f, 240f};
        private final float[] mArcS = {0.3f, -0.5f, 0.4f, -0.25f, 0.6f};
        private float mVortexRot = 0f;
        private float mGlow = 0f, mGlowDir = 1f;
        private final float[] mDotAngles = {0f, 120f, 240f};

        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;
        private final List<float[]> mLabelBounds = new ArrayList<>();

        RadarHudView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            float d = ctx.getResources().getDisplayMetrics().density;

            mRingP.setStyle(Paint.Style.STROKE); mRingP.setAntiAlias(true);
            mTickP.setStyle(Paint.Style.STROKE); mTickP.setAntiAlias(true);
            mTextP.setAntiAlias(true); mTextP.setTypeface(Typeface.MONOSPACE);
            mChipP.setAntiAlias(true); mChipP.setStyle(Paint.Style.FILL);
            mVortexP.setStyle(Paint.Style.STROKE); mVortexP.setAntiAlias(true);
            mVortexP.setStrokeCap(Paint.Cap.ROUND);
            mGlowP.setStyle(Paint.Style.FILL); mGlowP.setAntiAlias(true);
            mDotP.setStyle(Paint.Style.FILL); mDotP.setAntiAlias(true);

            // arc 0,4 = red; 1 = cyan; 2 = white-dim; 3 = cyan-dim
            int[] arcCols = {0x88CC2200, 0x6600AAFF, 0x33FFFFFF, 0x2200CCFF, 0x66CC2200};
            float[] arcWds = {3f, 2f, 1.5f, 1.8f, 2.5f};
            for (int i = 0; i < 5; i++) {
                mArcP[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mArcP[i].setStyle(Paint.Style.STROKE);
                mArcP[i].setColor(arcCols[i]);
                mArcP[i].setStrokeWidth(arcWds[i] * d);
                mArcP[i].setStrokeCap(Paint.Cap.ROUND);
            }
        }

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun = true;  tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun = false; mH.removeCallbacksAndMessages(null); }

        private void tick() {
            for (int i = 0; i < 5; i++) mArcA[i] = (mArcA[i] + mArcS[i]) % 360f;
            mVortexRot = (mVortexRot + 0.7f) % 360f;
            mGlow += mGlowDir * 0.012f;
            if (mGlow >= 1f) { mGlow = 1f; mGlowDir = -1f; }
            if (mGlow <= 0f) { mGlow = 0f; mGlowDir =  1f; }
            for (int i = 0; i < 3; i++) mDotAngles[i] = (mDotAngles[i] + 0.15f) % 360f;
            invalidate();
            if (mRun) mH.postDelayed(this::tick, 16);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float tx = e.getX(), ty = e.getY();
                for (float[] b : mLabelBounds) {
                    if (tx >= b[0] && tx <= b[2] && ty >= b[1] && ty <= b[3]) {
                        int idx = (int) b[4];
                        if (mListener != null) mListener.onLabelClick((String) LABEL_DEFS[idx][0]);
                        return true;
                    }
                }
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float maxR = Math.min(w, h) / 2f * 0.95f;
            float d = getResources().getDisplayMetrics().density;
            mLabelBounds.clear();

            // BG grid
            Paint gridP = new Paint(); gridP.setColor(Color.argb(8, 0, 150, 255)); gridP.setStrokeWidth(0.6f * d);
            for (int x = 0; x < w; x += dp(40)) canvas.drawLine(x, 0, x, h, gridP);
            for (int y = 0; y < h; y += dp(40)) canvas.drawLine(0, y, w, y, gridP);

            // Concentric rings
            float[] ringR = {0.92f, 0.78f, 0.64f, 0.50f, 0.36f};
            float[] ringA = {0.22f, 0.28f, 0.20f, 0.16f, 0.13f};
            boolean[] dash = {false, true, false, true, false};
            for (int i = 0; i < ringR.length; i++) {
                float r = maxR * ringR[i];
                mRingP.setColor(Color.argb((int)(ringA[i]*255), 0, 150, 255));
                mRingP.setStrokeWidth(1.3f * d);
                if (dash[i]) {
                    for (int t = 0; t < 36; t++) {
                        float sa = t * 10f;
                        RectF rr = new RectF(cx-r, cy-r, cx+r, cy+r);
                        canvas.drawArc(rr, sa, 7f, false, mRingP);
                    }
                } else {
                    canvas.drawCircle(cx, cy, r, mRingP);
                }
            }

            // Outer tick marks
            for (int t = 0; t < 360; t += 2) {
                double ang = Math.toRadians(t);
                boolean maj = t % 30 == 0, mid = t % 10 == 0;
                mTickP.setColor(maj ? Color.argb(100, 255,255,255)
                    : mid ? Color.argb(55, 255,255,255) : Color.argb(20, 255,255,255));
                mTickP.setStrokeWidth((maj ? 1.8f : 0.7f) * d);
                float ro = maxR * 0.92f;
                float ri = ro - (maj ? maxR*0.055f : mid ? maxR*0.033f : maxR*0.016f);
                canvas.drawLine(cx+(float)(Math.cos(ang)*ri), cy+(float)(Math.sin(ang)*ri),
                                cx+(float)(Math.cos(ang)*ro), cy+(float)(Math.sin(ang)*ro), mTickP);
            }

            // Crosshair
            mTickP.setColor(Color.argb(15, 180, 40, 0)); mTickP.setStrokeWidth(0.6f*d);
            canvas.drawLine(cx, cy-maxR*0.96f, cx, cy+maxR*0.96f, mTickP);
            canvas.drawLine(cx-maxR*0.96f, cy, cx+maxR*0.96f, cy, mTickP);

            // Spinning arcs
            float[] arcRads  = {maxR*0.92f, maxR*0.80f, maxR*0.68f, maxR*0.56f, maxR*0.44f};
            float[] arcSpans = {120f, 80f, 200f, 60f, 160f};
            for (int i = 0; i < 5; i++) {
                RectF ar = new RectF(cx-arcRads[i], cy-arcRads[i], cx+arcRads[i], cy+arcRads[i]);
                canvas.drawArc(ar, mArcA[i], arcSpans[i], false, mArcP[i]);
            }

            // Orbiting dots
            float[] dotRingR = {maxR*0.78f, maxR*0.64f, maxR*0.50f};
            int[] dotCols    = {0xCCCC2200, 0xCC00AAFF, 0xCCCC2200};
            for (int i = 0; i < 3; i++) {
                double ang = Math.toRadians(mDotAngles[i]);
                float dx = cx + (float)(Math.cos(ang)*dotRingR[i]);
                float dy = cy + (float)(Math.sin(ang)*dotRingR[i]);
                // glow halo
                RadialGradient dotGlow = new RadialGradient(dx, dy, 10*d,
                    dotCols[i], Color.TRANSPARENT, Shader.TileMode.CLAMP);
                mGlowP.setShader(dotGlow);
                canvas.drawCircle(dx, dy, 10*d, mGlowP);
                mGlowP.setShader(null);
                mDotP.setColor(dotCols[i]);
                canvas.drawCircle(dx, dy, 4f*d, mDotP);
            }

            // ── VORTEX CENTER — the KEY thing: massive red glow
            float vortexR = maxR * 0.22f;
            // Deep void
            Paint voidP = new Paint(Paint.ANTI_ALIAS_FLAG);
            voidP.setStyle(Paint.Style.FILL);
            voidP.setColor(Color.parseColor("#010308"));
            canvas.drawCircle(cx, cy, vortexR, voidP);

            // MASSIVE red radial glow — fills inner ~40% of radar
            RadialGradient redGlow = new RadialGradient(cx, cy, maxR * 0.45f,
                new int[]{
                    Color.argb((int)(180+mGlow*60), 204, 34, 0),
                    Color.argb((int)(120+mGlow*50), 160, 20, 0),
                    Color.argb((int)(60+mGlow*30),  100, 10, 0),
                    Color.argb((int)(20+mGlow*15),   60,  5, 0),
                    Color.TRANSPARENT
                },
                new float[]{0f, 0.2f, 0.45f, 0.7f, 1f},
                Shader.TileMode.CLAMP);
            mGlowP.setShader(redGlow);
            canvas.drawCircle(cx, cy, maxR * 0.45f, mGlowP);
            mGlowP.setShader(null);

            // Secondary blue/cyan glow ring (subtle, outer)
            RadialGradient blueGlow = new RadialGradient(cx, cy, maxR * 0.30f,
                Color.argb((int)(30+mGlow*20), 20, 80, 200), Color.TRANSPARENT, Shader.TileMode.CLAMP);
            mGlowP.setShader(blueGlow);
            canvas.drawCircle(cx, cy, maxR * 0.30f, mGlowP);
            mGlowP.setShader(null);

            // Vortex spiral
            canvas.save(); canvas.rotate(mVortexRot, cx, cy);
            for (int s = 0; s < 8; s++) {
                float sa = s * 45f;
                mVortexP.setColor(Color.argb((int)(40+mGlow*40), 0, 100, 220));
                mVortexP.setStrokeWidth(1.8f*d);
                RectF vr = new RectF(cx-vortexR*0.88f, cy-vortexR*0.88f, cx+vortexR*0.88f, cy+vortexR*0.88f);
                canvas.drawArc(vr, sa, 35f, false, mVortexP);
                mVortexP.setColor(Color.argb((int)(30+mGlow*30), 220, 40, 0));
                mVortexP.setStrokeWidth(1.2f*d);
                RectF vr2 = new RectF(cx-vortexR*0.60f, cy-vortexR*0.60f, cx+vortexR*0.60f, cy+vortexR*0.60f);
                canvas.drawArc(vr2, sa+20f, 28f, false, mVortexP);
            }
            canvas.restore();

            // Center dot with glow
            RadialGradient centerGlow = new RadialGradient(cx, cy, maxR*0.07f,
                Color.argb((int)(220+mGlow*35), 204,34,0), Color.TRANSPARENT, Shader.TileMode.CLAMP);
            mGlowP.setShader(centerGlow);
            canvas.drawCircle(cx, cy, maxR*0.07f, mGlowP);
            mGlowP.setShader(null);
            mDotP.setColor(Color.argb((int)(200+mGlow*55), 204,34,0));
            canvas.drawCircle(cx, cy, maxR*0.022f, mDotP);

            // ── Label chips
            mTextP.setTypeface(Typeface.MONOSPACE);
            for (int i = 0; i < LABEL_DEFS.length; i++) {
                String label = (String) LABEL_DEFS[i][0];
                float  angleDeg = (Float) LABEL_DEFS[i][1];
                float  radFrac  = (Float) LABEL_DEFS[i][2];
                int    color    = Color.parseColor((String) LABEL_DEFS[i][3]);
                int    cr = (color>>16)&0xFF, cg=(color>>8)&0xFF, cb=color&0xFF;

                double ang = Math.toRadians(angleDeg);
                float  r   = maxR * radFrac;
                float  lx  = cx + (float)(Math.cos(ang)*r);
                float  ly  = cy + (float)(Math.sin(ang)*r);

                mTextP.setTextSize(maxR * 0.062f);
                float tw = mTextP.measureText(label), th = mTextP.getTextSize();
                float padH = 7f*d, padV = 4f*d;
                float chipW = tw+padH*2, chipH = th+padV*2;
                float chipLeft = lx-chipW/2f, chipTop = ly-chipH/2f;

                RectF chipRect = new RectF(chipLeft, chipTop, chipLeft+chipW, chipTop+chipH);
                mChipP.setColor(Color.argb(55, cr, cg, cb));
                canvas.drawRoundRect(chipRect, 5*d, 5*d, mChipP);
                Paint borderP = new Paint(Paint.ANTI_ALIAS_FLAG);
                borderP.setStyle(Paint.Style.STROKE);
                borderP.setColor(Color.argb(170, cr, cg, cb));
                borderP.setStrokeWidth(1.3f*d);
                canvas.drawRoundRect(chipRect, 5*d, 5*d, borderP);
                mTextP.setColor(Color.argb(230, cr, cg, cb));
                mTextP.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(label, lx, ly+th*0.35f, mTextP);

                // Connector line
                mTickP.setColor(Color.argb(55, cr, cg, cb)); mTickP.setStrokeWidth(0.9f*d);
                float nearR = maxR * getNearestRing(radFrac);
                float ex = cx+(float)(Math.cos(ang)*nearR), ey = cy+(float)(Math.sin(ang)*nearR);
                float ex2 = lx-(float)(Math.cos(ang)*chipW*0.5f), ey2 = ly-(float)(Math.sin(ang)*chipH*0.5f);
                canvas.drawLine(ex2, ey2, ex, ey, mTickP);

                mLabelBounds.add(new float[]{chipLeft, chipTop, chipLeft+chipW, chipTop+chipH, i});
            }

            // Weather chip bottom-left
            float wX = cx - maxR*0.62f, wY = cy + maxR*0.73f;
            drawWeatherChip(canvas, wX, wY, maxR, d);

            // K.A.R.A stars + connected text
            Paint starP = new Paint(Paint.ANTI_ALIAS_FLAG);
            starP.setColor(Color.argb(190, 255, 200, 0));
            starP.setTextSize(maxR*0.055f); starP.setTypeface(Typeface.MONOSPACE);
            canvas.drawText("★★★★★", cx+maxR*0.14f, cy+maxR*0.83f, starP);
            Paint karaP = new Paint(Paint.ANTI_ALIAS_FLAG);
            karaP.setColor(Color.argb(110, 180,180,255));
            karaP.setTextSize(maxR*0.046f); karaP.setTypeface(Typeface.MONOSPACE);
            canvas.drawText("K.A.R.A  ·  CONNECTED", cx+maxR*0.04f, cy+maxR*0.91f, karaP);
        }

        private float getNearestRing(float f) {
            float[] rings={0.92f,0.78f,0.64f,0.50f,0.36f};
            float best=rings[0], bestD=Math.abs(f-rings[0]);
            for (float r:rings){float dist=Math.abs(f-r);if(dist<bestD){bestD=dist;best=r;}}
            return best;
        }

        private void drawWeatherChip(Canvas c, float x, float y, float maxR, float d) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(55, 0,150,255));
            RectF r = new RectF(x-dp2(40,d), y-dp2(14,d), x+dp2(40,d), y+dp2(14,d));
            c.drawRoundRect(r, dp2(7,d), dp2(7,d), p);
            p.setStyle(Paint.Style.STROKE); p.setColor(Color.argb(130,0,180,255)); p.setStrokeWidth(d);
            c.drawRoundRect(r, dp2(7,d), dp2(7,d), p);
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Color.argb(230,100,200,255)); p.setTextSize(maxR*0.082f);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            c.drawText("29°", x, y+maxR*0.042f, p);
        }

        private float dp2(float v, float d) { return v*d; }
        private int dp(float v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GLASS CARD VIEW — greeting BLEEDS over radar bottom
    //  Transparent top half → opaque dark bottom half
    // ═════════════════════════════════════════════════════════════════════

    static class GlassCardView extends View {
        private final Paint mBgP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBigP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mNameP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mSubP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mWalP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mKaraP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect  = new RectF();
        private float d;
        private String mGreeting = "GOOD AFTERNOON,", mName = "OPERATOR",
                       mSub = "0 MEMORIES  ·  SYSTEM ACTIVE", mWallet = "◎ 0.0000";
        private float mPulse = 0f, mPulseDir = 1f;
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;

        GlassCardView(android.content.Context ctx) {
            super(ctx); setWillNotDraw(false);
            d = ctx.getResources().getDisplayMetrics().density;
            // gradient: transparent top → dark bottom
            mBgP.setStyle(Paint.Style.FILL);
            mBigP.setColor(Color.argb(160, 255,255,255));
            mBigP.setTypeface(Typeface.DEFAULT_BOLD);
            mBigP.setLetterSpacing(0.01f);
            // Name uses a much bigger text size
            mNameP.setColor(Color.WHITE);
            mNameP.setTypeface(Typeface.DEFAULT_BOLD);
            mNameP.setLetterSpacing(0.02f);
            mSubP.setColor(Color.argb(90, 180,200,255));
            mSubP.setTypeface(Typeface.MONOSPACE);
            mSubP.setLetterSpacing(0.06f);
            mWalP.setColor(Color.parseColor("#00AAFF"));
            mWalP.setTypeface(Typeface.MONOSPACE);
            mKaraP.setColor(Color.parseColor("#CC2200"));
            mKaraP.setTypeface(Typeface.MONOSPACE);
        }

        void setNameAndGreeting(String name, String greeting) { mName=name; mGreeting=greeting; invalidate(); }
        void setGreetingPrefix(String g) { mGreeting=g; invalidate(); }
        void setSub(String s) { mSub=s; invalidate(); }
        void setWallet(String w) { mWallet=w; invalidate(); }

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun=true;  tickCard(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun=false; mH.removeCallbacksAndMessages(null); }
        private void tickCard() {
            mPulse+=mPulseDir*0.025f;
            if(mPulse>=1f){mPulse=1f;mPulseDir=-1f;}
            if(mPulse<=0f){mPulse=0f;mPulseDir=1f;}
            invalidate(); if(mRun) mH.postDelayed(this::tickCard,50);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w=getWidth(), h=getHeight();

            // Gradient background: transparent top → dark bottom
            LinearGradient bgGrad = new LinearGradient(0, 0, 0, h,
                Color.TRANSPARENT, Color.parseColor("#EE020406"),
                Shader.TileMode.CLAMP);
            mBgP.setShader(bgGrad);
            canvas.drawRect(0, 0, w, h, mBgP);
            mBgP.setShader(null);

            float tl = dp(14);
            float midY = h * 0.42f;

            // K.A.R.A tag line
            mKaraP.setTextSize(h * 0.095f);
            mKaraP.setLetterSpacing(0.08f);
            // Dot indicator
            Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotP.setStyle(Paint.Style.FILL);
            dotP.setColor(Color.parseColor("#CC2200"));
            canvas.drawCircle(tl - d*2, midY - h*0.14f, 4*d, dotP);
            canvas.drawText("K.A.R.A  10 NEED YOU · I'VE —", tl + dp(6), midY - h*0.09f, mKaraP);

            // Greeting line (small, dimmer)
            mBigP.setTextSize(h * 0.19f);
            canvas.drawText(mGreeting, tl, midY + h*0.06f, mBigP);

            // NAME — massive, 3× bigger than greeting
            mNameP.setTextSize(h * 0.46f);
            canvas.drawText(mName, tl, midY + h*0.06f + h*0.49f, mNameP);

            // Sub text
            mSubP.setTextSize(h * 0.10f);
            canvas.drawText(mSub, tl, h - dp(6), mSubP);

            // Wallet — top right
            mWalP.setTextSize(h * 0.14f);
            mWalP.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(mWallet, w - tl, midY + h*0.05f, mWalP);

            // Health circle — right side
            float cr2 = h * 0.16f;
            float ccx = w - tl*1.4f - cr2;
            float ccy = midY + h*0.06f + h*0.28f;
            Paint cirBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            cirBg.setStyle(Paint.Style.STROKE); cirBg.setStrokeWidth(2.5f*d);
            cirBg.setColor(Color.argb(35,255,255,255));
            canvas.drawCircle(ccx, ccy, cr2, cirBg);
            Paint cirFg = new Paint(Paint.ANTI_ALIAS_FLAG);
            cirFg.setStyle(Paint.Style.STROKE); cirFg.setStrokeWidth(2.5f*d);
            cirFg.setColor(Color.argb((int)(160+mPulse*70),0,170,255));
            cirFg.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(new RectF(ccx-cr2,ccy-cr2,ccx+cr2,ccy+cr2), -90f, 260f, false, cirFg);
            Paint cirTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
            cirTxt.setTextAlign(Paint.Align.CENTER);
            cirTxt.setTypeface(Typeface.MONOSPACE); cirTxt.setColor(Color.WHITE);
            cirTxt.setTextSize(cr2*0.55f);
            canvas.drawText("69", ccx, ccy+cr2*0.2f, cirTxt);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GLASS ICON VIEW
    // ═════════════════════════════════════════════════════════════════════

    static class GlassIconView extends View {
        private final String mLabel;
        private final int mColor;
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassIconView(android.content.Context ctx, String label, int color) {
            super(ctx); mLabel=label; mColor=color; setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w=getWidth(), h=getHeight();
            float cx=w/2f, cy=h/2f, s=Math.min(w,h)/2f*0.8f;
            float d=getResources().getDisplayMetrics().density;
            int cr=(mColor>>16)&0xFF, cg=(mColor>>8)&0xFF, cb=mColor&0xFF;
            mP.setColor(Color.argb(210,cr,cg,cb));
            mP.setStyle(Paint.Style.STROKE); mP.setStrokeWidth(1.8f*d);
            mP.setStrokeCap(Paint.Cap.ROUND); mP.setAntiAlias(true);

            switch (mLabel.toUpperCase(Locale.US)) {
                case "TALK": case "MSG": {
                    RectF body=new RectF(cx-s*0.35f,cy-s*0.7f,cx+s*0.35f,cy+s*0.1f);
                    c.drawRoundRect(body,s*0.35f,s*0.35f,mP);
                    RectF stand=new RectF(cx-s*0.6f,cy-s*0.1f,cx+s*0.6f,cy+s*0.5f);
                    c.drawArc(stand,0f,180f,false,mP);
                    c.drawLine(cx,cy+s*0.5f,cx,cy+s*0.85f,mP);
                    c.drawLine(cx-s*0.3f,cy+s*0.85f,cx+s*0.3f,cy+s*0.85f,mP);
                    break;
                }
                case "VAULT": case "WALLET": {
                    RectF body=new RectF(cx-s*0.6f,cy-s*0.1f,cx+s*0.6f,cy+s*0.8f);
                    c.drawRoundRect(body,s*0.12f,s*0.12f,mP);
                    RectF shack=new RectF(cx-s*0.38f,cy-s*0.85f,cx+s*0.38f,cy-s*0.1f);
                    c.drawArc(shack,180f,180f,false,mP);
                    c.drawCircle(cx,cy+s*0.35f,s*0.18f,mP);
                    c.drawLine(cx,cy+s*0.5f,cx,cy+s*0.7f,mP);
                    break;
                }
                case "SEARCH": {
                    c.drawCircle(cx-s*0.15f,cy-s*0.15f,s*0.52f,mP);
                    c.drawLine(cx+s*0.25f,cy+s*0.25f,cx+s*0.8f,cy+s*0.8f,mP);
                    break;
                }
                case "CAMERA": {
                    RectF body=new RectF(cx-s*0.75f,cy-s*0.45f,cx+s*0.75f,cy+s*0.55f);
                    c.drawRoundRect(body,s*0.12f,s*0.12f,mP);
                    c.drawCircle(cx,cy+s*0.05f,s*0.32f,mP);
                    c.drawLine(cx-s*0.22f,cy-s*0.45f,cx-s*0.22f,cy-s*0.72f,mP);
                    c.drawLine(cx-s*0.22f,cy-s*0.72f,cx+s*0.22f,cy-s*0.72f,mP);
                    c.drawLine(cx+s*0.22f,cy-s*0.72f,cx+s*0.22f,cy-s*0.45f,mP);
                    break;
                }
                default: {
                    Path dp=new Path();
                    dp.moveTo(cx,cy-s*0.7f); dp.lineTo(cx+s*0.55f,cy);
                    dp.lineTo(cx,cy+s*0.7f); dp.lineTo(cx-s*0.55f,cy); dp.close();
                    c.drawPath(dp,mP);
                    break;
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  MINI ORB VIEW
    // ═════════════════════════════════════════════════════════════════════

    public static class MiniOrbView extends View {
        public interface LabelClickListener { void onLabelClick(String label); }
        private LabelClickListener mListener;
        public void setOnLabelClickListener(LabelClickListener l) { mListener = l; }

        private static final Object[][] MINI_LABELS = {
            {"GEO",    30f,  0.76f, "#00AAFF"},
            {"CENTRE", 90f,  0.60f, "#00AAFF"},
            {"SEARCH", 150f, 0.76f, "#00AAFF"},
            {"VAULT",  210f, 0.76f, "#CC2200"},
            {"CAMERA", 270f, 0.60f, "#CC2200"},
            {"WIFI",   330f, 0.76f, "#00AAFF"},
        };

        private final Paint mP    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTxtP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mArcP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDotP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlowP= new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler mH  = new Handler(Looper.getMainLooper());
        private float mRot = 0f;
        private float mGlow = 0f, mGlowDir = 1f;
        private final List<float[]> mHits = new ArrayList<>();
        private boolean mRun;

        MiniOrbView(android.content.Context ctx) {
            super(ctx); setWillNotDraw(false);
            float d=ctx.getResources().getDisplayMetrics().density;
            mP.setStyle(Paint.Style.STROKE); mP.setAntiAlias(true);
            mTxtP.setTypeface(Typeface.MONOSPACE); mTxtP.setAntiAlias(true); mTxtP.setTextAlign(Paint.Align.CENTER);
            mArcP.setStyle(Paint.Style.STROKE); mArcP.setColor(Color.argb(80,204,34,0));
            mArcP.setStrokeWidth(2.5f*d); mArcP.setStrokeCap(Paint.Cap.ROUND); mArcP.setAntiAlias(true);
            mDotP.setStyle(Paint.Style.FILL); mDotP.setAntiAlias(true);
            mGlowP.setStyle(Paint.Style.FILL); mGlowP.setAntiAlias(true);
        }

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun=true;  tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun=false; mH.removeCallbacksAndMessages(null); }
        private void tick() {
            mRot=(mRot+0.4f)%360f;
            mGlow+=mGlowDir*0.015f;
            if(mGlow>=1f){mGlow=1f;mGlowDir=-1f;} if(mGlow<=0f){mGlow=0f;mGlowDir=1f;}
            invalidate(); if(mRun) mH.postDelayed(this::tick,16);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if(e.getAction()==MotionEvent.ACTION_UP){
                float tx=e.getX(),ty=e.getY();
                for(float[] b:mHits){
                    if(tx>=b[0]&&tx<=b[2]&&ty>=b[1]&&ty<=b[3]){
                        int idx=(int)b[4];
                        if(mListener!=null) mListener.onLabelClick((String)MINI_LABELS[idx][0]);
                        return true;
                    }
                }
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w=getWidth(), h=getHeight();
            float cx=w/2f, cy=h/2f;
            float maxR=Math.min(w,h)/2f*0.92f;
            float d=getResources().getDisplayMetrics().density;
            mHits.clear();

            // Rings
            float[] rings={0.90f,0.70f,0.50f};
            for(float r:rings){
                mP.setColor(Color.argb(45,0,150,255)); mP.setStrokeWidth(1.2f*d);
                canvas.drawCircle(cx,cy,maxR*r,mP);
            }

            // Spinning arc
            RectF ar=new RectF(cx-maxR*0.90f,cy-maxR*0.90f,cx+maxR*0.90f,cy+maxR*0.90f);
            canvas.drawArc(ar,mRot,120f,false,mArcP);

            // Red glow center
            RadialGradient rg=new RadialGradient(cx,cy,maxR*0.38f,
                Color.argb((int)(120+mGlow*60),204,34,0),Color.TRANSPARENT,Shader.TileMode.CLAMP);
            mGlowP.setShader(rg);
            canvas.drawCircle(cx,cy,maxR*0.38f,mGlowP);
            mGlowP.setShader(null);

            // Center
            mDotP.setColor(Color.parseColor("#010308"));
            canvas.drawCircle(cx,cy,maxR*0.18f,mDotP);
            mDotP.setColor(Color.argb((int)(180+mGlow*75),204,34,0));
            canvas.drawCircle(cx,cy,maxR*0.065f,mDotP);

            // Labels
            mTxtP.setTextSize(maxR*0.105f);
            for(int i=0;i<MINI_LABELS.length;i++){
                String lbl=(String)MINI_LABELS[i][0];
                float ang=(Float)MINI_LABELS[i][1];
                float rad=(Float)MINI_LABELS[i][2];
                int col=Color.parseColor((String)MINI_LABELS[i][3]);
                int cr2=(col>>16)&0xFF,cg2=(col>>8)&0xFF,cb2=col&0xFF;
                double a=Math.toRadians(ang);
                float lx=cx+(float)(Math.cos(a)*maxR*rad);
                float ly=cy+(float)(Math.sin(a)*maxR*rad);
                float tw=mTxtP.measureText(lbl),th=mTxtP.getTextSize();
                float padH=6f*d,padV=3f*d;
                float cL=lx-tw/2f-padH,cT=ly-th/2f-padV;
                RectF chipR=new RectF(cL,cT,cL+tw+padH*2,cT+th+padV*2);
                Paint bg2=new Paint(Paint.ANTI_ALIAS_FLAG); bg2.setStyle(Paint.Style.FILL);
                bg2.setColor(Color.argb(50,cr2,cg2,cb2));
                canvas.drawRoundRect(chipR,4*d,4*d,bg2);
                Paint brd=new Paint(Paint.ANTI_ALIAS_FLAG); brd.setStyle(Paint.Style.STROKE);
                brd.setColor(Color.argb(150,cr2,cg2,cb2)); brd.setStrokeWidth(1.2f*d);
                canvas.drawRoundRect(chipR,4*d,4*d,brd);
                mTxtP.setColor(Color.argb(210,cr2,cg2,cb2));
                canvas.drawText(lbl,lx,ly+th*0.35f,mTxtP);
                mHits.add(new float[]{cL,cT,cL+tw+padH*2,cT+th+padV*2,i});
            }
        }
    }
}
