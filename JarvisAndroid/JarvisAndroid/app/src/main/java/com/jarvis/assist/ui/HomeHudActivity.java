package com.jarvis.assist.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
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
        // Root is a vertical ScrollView so the whole screen scrolls
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setBackgroundColor(Color.parseColor("#020406"));
        rootScroll.setVerticalScrollBarEnabled(false);
        rootScroll.setFillViewport(true);

        LinearLayout rootCol = new LinearLayout(this);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.setBackgroundColor(Color.parseColor("#020406"));

        // ── Scan-line overlay (non-interactive)
        FrameLayout topFrame = new FrameLayout(this);
        topFrame.setBackgroundColor(Color.TRANSPARENT);

        // ── Radar HUD — square, fills width
        mRadar = new RadarHudView(this);
        mRadar.setOnLabelClickListener(this::onRadarLabelTapped);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int radarSize = dm.widthPixels;  // square
        topFrame.addView(mRadar, new FrameLayout.LayoutParams(radarSize, radarSize));

        // ── Header overlay on top of radar
        topFrame.addView(buildHeaderOverlay(radarSize), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rootCol.addView(topFrame, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, radarSize));

        // ── Glass greeting card
        mCard = new GlassCardView(this);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(110));
        cardLp.leftMargin  = dp(10);
        cardLp.rightMargin = dp(10);
        cardLp.topMargin   = dp(4);
        rootCol.addView(mCard, cardLp);

        // ── "NEXT CLEAR" bar chart strip
        rootCol.addView(buildNextClearStrip(), buildStripLp());

        // ── Section label: APP GRID
        rootCol.addView(buildSectionLabel("APP SHORTCUTS"), buildSectionLp());

        // ── Glassy button grid — 4 columns, wraps
        rootCol.addView(buildButtonGrid(), buildGridLp());

        // ── Section label: QUICK ACTIONS
        rootCol.addView(buildSectionLabel("QUICK ACTIONS"), buildSectionLp());

        // ── Horizontal scroll of action chips
        rootCol.addView(buildActionChips(), buildChipsLp());

        // ── Bottom mini orb
        rootCol.addView(buildMiniOrbRow(), buildMiniOrbLp());

        // Spacer
        View spacer = new View(this);
        rootCol.addView(spacer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        rootScroll.addView(rootCol, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(rootScroll);
    }

    // ── HEADER OVERLAY (floats over radar top)
    private LinearLayout buildHeaderOverlay(int radarSize) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), 0);

        // Left: AUGMA / brand
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvBrand = new TextView(this);
        tvBrand.setText("JARVIS");
        tvBrand.setTextColor(Color.parseColor("#CC2200"));
        tvBrand.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.6f));
        tvBrand.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvBrand.setLetterSpacing(0.3f);
        left.addView(tvBrand);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this); android.graphics.drawable.GradientDrawable dg =
            new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dg.setColor(Color.parseColor("#44FF88")); dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(5), dp(5));
        dlp.rightMargin = dp(4); dot.setLayoutParams(dlp);
        pulse(dot); statusRow.addView(dot);
        TextView tvOnline = new TextView(this); tvOnline.setText("ONLINE");
        tvOnline.setTextColor(Color.parseColor("#44FF88"));
        tvOnline.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.5f));
        tvOnline.setLetterSpacing(0.1f);
        statusRow.addView(tvOnline); left.addView(statusRow);
        row.addView(left);

        // Right: clock
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        mTvClock = new TextView(this); mTvClock.setText("--:--");
        mTvClock.setTextColor(Color.WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(4.2f));
        mTvClock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        right.addView(mTvClock);
        mTvDate = new TextView(this);
        mTvDate.setText(new SimpleDateFormat("dd MMM", Locale.US).format(new Date()).toUpperCase(Locale.US));
        mTvDate.setTextColor(Color.parseColor("#44FF88"));
        mTvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.5f));
        mTvDate.setTypeface(Typeface.MONOSPACE);
        mTvDate.setGravity(Gravity.END);
        right.addView(mTvDate); row.addView(right);
        return row;
    }

    // ── NEXT CLEAR strip
    private View buildNextClearStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setBackgroundColor(Color.parseColor("#05080F"));
        strip.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvLabel = new TextView(this); tvLabel.setText("NEXT CLEAR");
        tvLabel.setTextColor(Color.parseColor("#44FF88"));
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        tvLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvLabel.setLetterSpacing(0.1f);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(tvLabel);
        TextView tvSub = new TextView(this); tvSub.setText("AIR QUALITY");
        tvSub.setTextColor(Color.parseColor("#44FFFFFF"));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.5f));
        tvSub.setTypeface(Typeface.MONOSPACE);
        topRow.addView(tvSub);
        strip.addView(topRow);

        // Red bar
        View redBar = new View(this);
        redBar.setBackgroundColor(Color.parseColor("#CC2200"));
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.7f), dp(3));
        rbLp.topMargin = dp(5); rbLp.bottomMargin = dp(3);
        redBar.setLayoutParams(rbLp);
        strip.addView(redBar);

        // Cyan bar
        View cyanBar = new View(this);
        cyanBar.setBackgroundColor(Color.parseColor("#00AAFF"));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.45f), dp(2));
        cbLp.bottomMargin = dp(2);
        cyanBar.setLayoutParams(cbLp);
        strip.addView(cyanBar);

        // Data row
        LinearLayout dataRow = new LinearLayout(this);
        dataRow.setOrientation(LinearLayout.HORIZONTAL);
        dataRow.setGravity(Gravity.CENTER_VERTICAL);
        addDataChip(dataRow, "SLEEP", "--", "#4499FF");
        addDataChip(dataRow, "HEART", "--", "#FF4488");
        addDataChip(dataRow, "LAST", "--", "#FFD700");
        strip.addView(dataRow);
        return strip;
    }

    private void addDataChip(LinearLayout parent, String label, String val, String color) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        chip.setLayoutParams(lp);
        TextView tvL = new TextView(this); tvL.setText(label);
        tvL.setTextColor(Color.parseColor("#44FFFFFF"));
        tvL.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.3f));
        tvL.setTypeface(Typeface.MONOSPACE);
        chip.addView(tvL);
        TextView tvV = new TextView(this); tvV.setText(val);
        tvV.setTextColor(Color.parseColor(color));
        tvV.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
        tvV.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        chip.addView(tvV);
        parent.addView(chip);
    }

    // ── GLASSY BUTTON GRID (4 columns)
    private static final String[][] GRID_ITEMS = {
        {"TALK",     "#CC2200"}, {"VAULT",    "#9945FF"},
        {"MEMORY",   "#4499FF"}, {"REMIND",   "#FFD700"},
        {"WEATHER",  "#00AAFF"}, {"CONTROL",  "#44FF88"},
        {"TASKS",    "#FF6644"}, {"AGENTS",   "#FF44AA"},
        {"SEARCH",   "#44FFCC"}, {"CAMERA",   "#4488FF"},
        {"CALENDAR", "#FFAA00"}, {"SETTINGS", "#667788"},
    };

    private View buildButtonGrid() {
        int cols = 4;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int btnSize = (dm.widthPixels - dp(20)) / cols;

        // Use a custom ViewGroup-like approach with a LinearLayout grid
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(8), dp(6), dp(8), dp(6));

        int rows = (int) Math.ceil(GRID_ITEMS.length / (float) cols);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(6);
            row.setLayoutParams(rowLp);

            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx >= GRID_ITEMS.length) {
                    // Empty filler
                    View filler = new View(this);
                    row.addView(filler, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f));
                    continue;
                }
                String label = GRID_ITEMS[idx][0];
                String colorStr = GRID_ITEMS[idx][1];
                int color = Color.parseColor(colorStr);

                FrameLayout cell = new FrameLayout(this);
                cell.setClickable(true); cell.setFocusable(true);
                // Glassy background
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                bg.setCornerRadius(dp(8));
                int r2 = (color >> 16) & 0xFF, g2 = (color >> 8) & 0xFF, b2 = color & 0xFF;
                bg.setColor(Color.argb(30, r2, g2, b2));
                bg.setStroke(dp(1), Color.argb(100, r2, g2, b2));
                cell.setBackground(bg);

                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                    0, btnSize, 1f);
                cellLp.rightMargin = c < cols - 1 ? dp(6) : 0;
                cell.setLayoutParams(cellLp);

                // Vector icon drawn in a small canvas view
                GlassIconView icon = new GlassIconView(this, label, color);
                FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                    dp(28), dp(28));
                iconLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                iconLp.topMargin = dp(10);
                cell.addView(icon, iconLp);

                // Label text
                TextView tvLbl = new TextView(this);
                tvLbl.setText(label);
                tvLbl.setTextColor(Color.argb(200, r2, g2, b2));
                tvLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
                tvLbl.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                tvLbl.setLetterSpacing(0.05f);
                tvLbl.setGravity(Gravity.CENTER_HORIZONTAL);
                FrameLayout.LayoutParams lblLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
                lblLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lblLp.bottomMargin = dp(8);
                cell.addView(tvLbl, lblLp);

                final String finalLabel = label;
                cell.setOnClickListener(v -> {
                    click();
                    // Press effect
                    bg.setColor(Color.argb(80, r2, g2, b2));
                    cell.postDelayed(() -> bg.setColor(Color.argb(30, r2, g2, b2)), 150);
                    onGridItemTapped(finalLabel);
                });
                row.addView(cell);
            }
            grid.addView(row);
        }
        return grid;
    }

    // ── HORIZONTAL ACTION CHIPS
    private View buildActionChips() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setPadding(dp(12), dp(4), dp(12), dp(4));

        String[][] chips = {
            {"VOICE CMD", "#CC2200"}, {"LANDSCAPE", "#4499FF"},
            {"SCAN NET", "#44FF88"}, {"HEALTH", "#FF4488"},
            {"WALLET", "#9945FF"}, {"TASKS", "#FFD700"},
        };
        for (String[] chip : chips) {
            TextView tv = new TextView(this);
            tv.setText(chip[0]);
            int color = Color.parseColor(chip[1]);
            int r2 = (color >> 16) & 0xFF, g2 = (color >> 8) & 0xFF, b2 = color & 0xFF;
            tv.setTextColor(Color.argb(220, r2, g2, b2));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.8f));
            tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            tv.setLetterSpacing(0.08f);
            android.graphics.drawable.GradientDrawable chipBg =
                new android.graphics.drawable.GradientDrawable();
            chipBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            chipBg.setCornerRadius(dp(20));
            chipBg.setColor(Color.argb(25, r2, g2, b2));
            chipBg.setStroke(dp(1), Color.argb(120, r2, g2, b2));
            tv.setBackground(chipBg);
            tv.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            tv.setLayoutParams(lp);
            tv.setClickable(true); tv.setFocusable(true);
            final String lbl = chip[0];
            tv.setOnClickListener(v -> { click(); onGridItemTapped(lbl); });
            inner.addView(tv);
        }
        hsv.addView(inner, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hsv;
    }

    // ── MINI ORB ROW (bottom)
    private View buildMiniOrbRow() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.parseColor("#020406"));
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int orbSz = (int)(dm.widthPixels * 0.55f);
        MiniOrbView orb = new MiniOrbView(this);
        orb.setOnLabelClickListener(this::onRadarLabelTapped);
        FrameLayout.LayoutParams orbLp = new FrameLayout.LayoutParams(orbSz, orbSz);
        orbLp.gravity = Gravity.CENTER_HORIZONTAL;
        frame.addView(orb, orbLp);
        return frame;
    }

    private LinearLayout.LayoutParams buildStripLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return lp;
    }

    private LinearLayout.LayoutParams buildSectionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.leftMargin = dp(16);
        return lp;
    }

    private LinearLayout.LayoutParams buildGridLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        return lp;
    }

    private LinearLayout.LayoutParams buildChipsLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return lp;
    }

    private LinearLayout.LayoutParams buildMiniOrbLp() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int sz = (int)(dm.widthPixels * 0.55f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, sz);
        lp.topMargin = dp(8);
        return lp;
    }

    private TextView buildSectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#33FFFFFF"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setLetterSpacing(0.2f);
        return tv;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ACTIONS
    // ─────────────────────────────────────────────────────────────────────

    private void onRadarLabelTapped(String label) {
        click();
        switch (label.toUpperCase(Locale.US)) {
            case "TALK":      startActivity(new Intent(this, VoiceOverlayActivity.class)); break;
            case "LANDSCAPE": startActivity(new Intent(this, LandscapeHudActivity.class)); break;
            case "VAULT":
            case "WALLET":
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
            case "TALK":       startActivity(new Intent(this, VoiceOverlayActivity.class)); break;
            case "LANDSCAPE":  startActivity(new Intent(this, LandscapeHudActivity.class)); break;
            case "VOICE CMD":  startActivity(new Intent(this, VoiceOverlayActivity.class)); break;
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
                if (mCard != null) mCard.setGreetingPrefix(getGreeting());
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
    //  RADAR HUD VIEW — concentric rings with orbiting clickable labels
    //  This is the MAIN large orb matching the reference exactly
    // ═════════════════════════════════════════════════════════════════════

    public static class RadarHudView extends View {

        public interface LabelClickListener { void onLabelClick(String label); }
        private LabelClickListener mListener;
        public void setOnLabelClickListener(LabelClickListener l) { mListener = l; }

        // Labels placed at specific angles around specific ring radii
        // {label, angleDeg, radiusFraction, colorHex}
        private static final Object[][] LABEL_DEFS = {
            {"WEATHER",    20f,  0.72f, "#00AAFF"},
            {"CONTROL",    50f,  0.88f, "#44FF88"},
            {"ASTRONOMY",  85f,  0.78f, "#CC2200"},
            {"PROC TASKS", 125f, 0.68f, "#FFD700"},
            {"FREE TASKS", 155f, 0.85f, "#4499FF"},
            {"EVENTS",     200f, 0.75f, "#FF4488"},
            {"APP AGENT",  240f, 0.88f, "#44FFCC"},
            {"VAULT",      280f, 0.72f, "#9945FF"},
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

        // Small dot positions rotating slowly
        private final float[] mDotAngles = {0f, 120f, 240f};

        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;

        // Hit rects for label touch
        private final List<float[]> mLabelBounds = new ArrayList<>(); // {x1,y1,x2,y2, labelIdx}

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

            int[] arcCols = {0x66CC2200, 0x5500AAFF, 0x44FFFFFF, 0x3300CCFF, 0x44CC2200};
            float[] arcWds = {2.5f, 1.8f, 1.2f, 1.5f, 2f};
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
            mGlow += mGlowDir * 0.015f;
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

            // ── Background subtle grid
            Paint gridP = new Paint(); gridP.setColor(Color.argb(8, 0, 150, 255)); gridP.setStrokeWidth(0.5f * d);
            for (int x = 0; x < w; x += dp(40)) canvas.drawLine(x, 0, x, h, gridP);
            for (int y = 0; y < h; y += dp(40)) canvas.drawLine(0, y, w, y, gridP);

            // ── Concentric rings (5 rings at different radii)
            float[] ringRadii = {0.92f, 0.78f, 0.64f, 0.50f, 0.36f};
            int[]   ringAlpha = {50,    60,    50,    40,    35};
            boolean[] ringDash = {false, true, false, true, false};
            for (int i = 0; i < ringRadii.length; i++) {
                float r = maxR * ringRadii[i];
                mRingP.setColor(Color.argb(ringAlpha[i], 0, 150, 255));
                mRingP.setStrokeWidth(1.2f * d);
                if (ringDash[i]) {
                    // Dashed-looking ring (draw arcs with gaps)
                    for (int t = 0; t < 36; t++) {
                        float startA = t * 10f;
                        RectF rr = new RectF(cx - r, cy - r, cx + r, cy + r);
                        canvas.drawArc(rr, startA, 7f, false, mRingP);
                    }
                } else {
                    canvas.drawCircle(cx, cy, r, mRingP);
                }
            }

            // ── Outer tick ring
            for (int t = 0; t < 360; t += 2) {
                double ang = Math.toRadians(t);
                boolean maj = t % 30 == 0, mid = t % 10 == 0;
                mTickP.setColor(maj ? Color.argb(90, 255, 255, 255)
                    : mid ? Color.argb(50, 255, 255, 255) : Color.argb(22, 255, 255, 255));
                mTickP.setStrokeWidth((maj ? 1.5f : 0.7f) * d);
                float ro = maxR * 0.92f;
                float ri = ro - (maj ? maxR * 0.05f : mid ? maxR * 0.03f : maxR * 0.015f);
                canvas.drawLine(cx + (float)(Math.cos(ang) * ri), cy + (float)(Math.sin(ang) * ri),
                                cx + (float)(Math.cos(ang) * ro), cy + (float)(Math.sin(ang) * ro), mTickP);
            }

            // ── Crosshair lines
            mTickP.setColor(Color.argb(18, 200, 50, 0)); mTickP.setStrokeWidth(0.6f * d);
            canvas.drawLine(cx, cy - maxR * 0.95f, cx, cy + maxR * 0.95f, mTickP);
            canvas.drawLine(cx - maxR * 0.95f, cy, cx + maxR * 0.95f, cy, mTickP);

            // ── Spinning arcs
            float[] arcRads = {maxR * 0.92f, maxR * 0.80f, maxR * 0.68f, maxR * 0.56f, maxR * 0.44f};
            float[] arcSpans = {120f, 80f, 200f, 60f, 160f};
            for (int i = 0; i < 5; i++) {
                float r = arcRads[i];
                RectF ar = new RectF(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(ar, mArcA[i], arcSpans[i], false, mArcP[i]);
            }

            // ── Orbiting small red/white dots on rings
            float[] dotRingR = {maxR * 0.78f, maxR * 0.64f, maxR * 0.50f};
            int[]   dotCols  = {0xAACC2200, 0xAA00AAFF, 0xAAFFFFFF};
            for (int i = 0; i < 3; i++) {
                double ang = Math.toRadians(mDotAngles[i]);
                float dx = cx + (float)(Math.cos(ang) * dotRingR[i]);
                float dy = cy + (float)(Math.sin(ang) * dotRingR[i]);
                mDotP.setColor(dotCols[i]);
                canvas.drawCircle(dx, dy, 3.5f * d, mDotP);
            }

            // ── Vortex center
            float vortexR = maxR * 0.22f;
            // Dark void
            Paint voidP = new Paint(Paint.ANTI_ALIAS_FLAG); voidP.setStyle(Paint.Style.FILL);
            voidP.setColor(Color.parseColor("#010308"));
            canvas.drawCircle(cx, cy, vortexR, voidP);
            // Glow
            RadialGradient g = new RadialGradient(cx, cy, vortexR * 1.3f,
                Color.argb((int)(50 + mGlow * 40), 30, 80, 200), Color.TRANSPARENT, Shader.TileMode.CLAMP);
            mGlowP.setShader(g);
            canvas.drawCircle(cx, cy, vortexR * 1.3f, mGlowP);
            mGlowP.setShader(null);
            // Vortex spiral
            canvas.save();
            canvas.rotate(mVortexRot, cx, cy);
            for (int s = 0; s < 8; s++) {
                float startA2 = s * 45f;
                mVortexP.setColor(Color.argb((int)(30 + mGlow * 35), 0, 100, 220));
                mVortexP.setStrokeWidth(1.5f * d);
                RectF vr = new RectF(cx - vortexR * 0.88f, cy - vortexR * 0.88f,
                                     cx + vortexR * 0.88f, cy + vortexR * 0.88f);
                canvas.drawArc(vr, startA2, 35f, false, mVortexP);
                // Inner spiral
                mVortexP.setColor(Color.argb((int)(20 + mGlow * 20), 200, 30, 0));
                mVortexP.setStrokeWidth(1f * d);
                RectF vr2 = new RectF(cx - vortexR * 0.6f, cy - vortexR * 0.6f,
                                      cx + vortexR * 0.6f, cy + vortexR * 0.6f);
                canvas.drawArc(vr2, startA2 + 20f, 28f, false, mVortexP);
            }
            canvas.restore();
            // Center dot
            mDotP.setColor(Color.argb((int)(160 + mGlow * 80), 204, 34, 0));
            canvas.drawCircle(cx, cy, maxR * 0.018f, mDotP);

            // ── Orbiting label chips — the KEY interactive elements
            mTextP.setTypeface(Typeface.MONOSPACE);
            for (int i = 0; i < LABEL_DEFS.length; i++) {
                String label = (String) LABEL_DEFS[i][0];
                float  angleDeg = (Float) LABEL_DEFS[i][1];
                float  radFrac  = (Float) LABEL_DEFS[i][2];
                int    color    = Color.parseColor((String) LABEL_DEFS[i][3]);
                int    cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF;

                double ang = Math.toRadians(angleDeg);
                float  r   = maxR * radFrac;
                float  lx  = cx + (float)(Math.cos(ang) * r);
                float  ly  = cy + (float)(Math.sin(ang) * r);

                // Chip size
                mTextP.setTextSize(maxR * 0.062f);
                float tw  = mTextP.measureText(label);
                float th  = mTextP.getTextSize();
                float padH = 6f * d, padV = 3f * d;
                float chipW = tw + padH * 2, chipH = th + padV * 2;
                float chipLeft = lx - chipW / 2f, chipTop = ly - chipH / 2f;

                // Glassy chip background
                RectF chipRect = new RectF(chipLeft, chipTop, chipLeft + chipW, chipTop + chipH);
                mChipP.setColor(Color.argb(55, cr, cg, cb));
                canvas.drawRoundRect(chipRect, 5 * d, 5 * d, mChipP);
                // Border
                Paint borderP = new Paint(Paint.ANTI_ALIAS_FLAG);
                borderP.setStyle(Paint.Style.STROKE);
                borderP.setColor(Color.argb(160, cr, cg, cb));
                borderP.setStrokeWidth(1.2f * d);
                canvas.drawRoundRect(chipRect, 5 * d, 5 * d, borderP);
                // Text
                mTextP.setColor(Color.argb(220, cr, cg, cb));
                mTextP.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(label, lx, ly + th * 0.35f, mTextP);

                // Small connector line from chip edge toward nearest ring
                mTickP.setColor(Color.argb(60, cr, cg, cb));
                mTickP.setStrokeWidth(0.8f * d);
                float nearestRingR = maxR * getNearestRing(radFrac);
                float edgeX = cx + (float)(Math.cos(ang) * nearestRingR);
                float edgeY = cy + (float)(Math.sin(ang) * nearestRingR);
                float chipEdgeX = lx - (float)(Math.cos(ang) * chipW * 0.5f);
                float chipEdgeY = ly - (float)(Math.sin(ang) * chipH * 0.5f);
                canvas.drawLine(chipEdgeX, chipEdgeY, edgeX, edgeY, mTickP);

                // Store hit bounds
                mLabelBounds.add(new float[]{chipLeft, chipTop, chipLeft + chipW, chipTop + chipH, i});
            }

            // ── Weather chip bottom-left of orb
            float wX = cx - maxR * 0.6f, wY = cy + maxR * 0.72f;
            drawWeatherChip(canvas, wX, wY, maxR, d);

            // ── K.A.R.A star rating bottom-right
            Paint starP = new Paint(Paint.ANTI_ALIAS_FLAG);
            starP.setColor(Color.argb(180, 255, 200, 0));
            starP.setTextSize(maxR * 0.055f);
            starP.setTypeface(Typeface.MONOSPACE);
            canvas.drawText("★★★★★", cx + maxR * 0.15f, cy + maxR * 0.82f, starP);
            Paint karaP = new Paint(Paint.ANTI_ALIAS_FLAG);
            karaP.setColor(Color.argb(120, 200, 200, 255));
            karaP.setTextSize(maxR * 0.048f);
            karaP.setTypeface(Typeface.MONOSPACE);
            canvas.drawText("K.A.R.A  ·  CONNECTED", cx + maxR * 0.05f, cy + maxR * 0.9f, karaP);
        }

        private float getNearestRing(float radFrac) {
            float[] rings = {0.92f, 0.78f, 0.64f, 0.50f, 0.36f};
            float best = rings[0]; float bestDist = Math.abs(radFrac - rings[0]);
            for (float r : rings) {
                float dist = Math.abs(radFrac - r);
                if (dist < bestDist) { bestDist = dist; best = r; }
            }
            return best;
        }

        private void drawWeatherChip(Canvas c, float x, float y, float maxR, float d) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(60, 0, 150, 255));
            RectF r = new RectF(x - dp2(40, d), y - dp2(14, d), x + dp2(40, d), y + dp2(14, d));
            c.drawRoundRect(r, dp2(7, d), dp2(7, d), p);
            p.setStyle(Paint.Style.STROKE); p.setColor(Color.argb(120, 0, 180, 255)); p.setStrokeWidth(d);
            c.drawRoundRect(r, dp2(7, d), dp2(7, d), p);
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Color.argb(220, 100, 200, 255)); p.setTextSize(maxR * 0.08f);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            c.drawText("29°", x, y + maxR * 0.04f, p);
        }

        private float dp2(float v, float d) { return v * d; }
        private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GLASS CARD VIEW
    // ═════════════════════════════════════════════════════════════════════

    static class GlassCardView extends View {
        private final Paint mBgP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBordP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBigP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mSubP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mWalP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect  = new RectF();
        private float d;
        private String mGreeting = "GOOD AFTERNOON,", mName = "OPERATOR",
                       mSub = "LOADING…", mWallet = "◎ —";
        private float mPulse = 0f, mPulseDir = 1f;
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;

        GlassCardView(android.content.Context ctx) {
            super(ctx); setWillNotDraw(false);
            d = ctx.getResources().getDisplayMetrics().density;
            mBgP.setStyle(Paint.Style.FILL); mBgP.setColor(Color.argb(160, 0, 8, 18));
            mBordP.setStyle(Paint.Style.STROKE); mBordP.setStrokeWidth(d); mBordP.setColor(Color.argb(40, 255,255,255));
            mBigP.setColor(Color.WHITE); mBigP.setTypeface(Typeface.DEFAULT_BOLD); mBigP.setLetterSpacing(0.02f);
            mSubP.setColor(Color.argb(100, 200,220,255)); mSubP.setTypeface(Typeface.MONOSPACE); mSubP.setLetterSpacing(0.06f);
            mWalP.setColor(Color.parseColor("#9945FF")); mWalP.setTypeface(Typeface.MONOSPACE);
        }

        void setNameAndGreeting(String name, String greeting) { mName = name; mGreeting = greeting; invalidate(); }
        void setGreetingPrefix(String g) { mGreeting = g; invalidate(); }
        void setSub(String s)   { mSub = s; invalidate(); }
        void setWallet(String w){ mWallet = w; invalidate(); }

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun=true;  tickCard(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun=false; mH.removeCallbacksAndMessages(null); }
        private void tickCard() {
            mPulse += mPulseDir * 0.025f;
            if(mPulse>=1f){mPulse=1f;mPulseDir=-1f;} if(mPulse<=0f){mPulse=0f;mPulseDir=1f;}
            invalidate(); if(mRun) mH.postDelayed(this::tickCard, 50);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            float pad = 12f * d;
            mRect.set(pad * 0.5f, pad * 0.3f, w - pad * 0.5f, h - pad * 0.3f);
            canvas.drawRoundRect(mRect, 8*d, 8*d, mBgP);
            canvas.drawRoundRect(mRect, 8*d, 8*d, mBordP);
            // Red left bar
            Paint rb = new Paint(); rb.setColor(Color.parseColor("#CC2200"));
            canvas.drawRect(pad * 0.5f, pad * 0.3f, pad * 0.5f + 3 * d, h - pad * 0.3f, rb);
            float tl = pad + 4 * d;
            // Greeting dim
            mBigP.setColor(Color.argb(140, 255,255,255));
            mBigP.setTextSize(h * 0.22f);
            canvas.drawText(mGreeting, tl, pad * 0.3f + h * 0.3f, mBigP);
            // Name big
            mBigP.setColor(Color.WHITE); mBigP.setTextSize(h * 0.38f);
            canvas.drawText(mName, tl, pad * 0.3f + h * 0.3f + h * 0.4f, mBigP);
            // Sub
            mSubP.setTextSize(h * 0.12f);
            canvas.drawText(mSub, tl, h - pad * 0.6f, mSubP);
            // Wallet right
            mWalP.setTextSize(h * 0.16f); mWalP.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(mWallet, w - pad, pad * 0.3f + h * 0.28f, mWalP);
            // Health circle
            float cr = h * 0.18f, ccx = w - pad * 1.5f - cr, ccy = h * 0.68f;
            Paint cirBg = new Paint(Paint.ANTI_ALIAS_FLAG); cirBg.setStyle(Paint.Style.STROKE);
            cirBg.setStrokeWidth(2 * d); cirBg.setColor(Color.argb(35, 255,255,255));
            canvas.drawCircle(ccx, ccy, cr, cirBg);
            Paint cirFg = new Paint(Paint.ANTI_ALIAS_FLAG); cirFg.setStyle(Paint.Style.STROKE);
            cirFg.setStrokeWidth(2 * d); cirFg.setColor(Color.argb((int)(160+mPulse*70), 0,180,100));
            cirFg.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(new RectF(ccx-cr,ccy-cr,ccx+cr,ccy+cr), -90f, 260f, false, cirFg);
            Paint cirTxt = new Paint(Paint.ANTI_ALIAS_FLAG); cirTxt.setTextAlign(Paint.Align.CENTER);
            cirTxt.setTypeface(Typeface.MONOSPACE); cirTxt.setColor(Color.WHITE);
            cirTxt.setTextSize(cr * 0.55f);
            canvas.drawText("69", ccx, ccy + cr * 0.2f, cirTxt);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GLASS ICON VIEW — vector drawn icons for grid buttons, zero emojis
    // ═════════════════════════════════════════════════════════════════════

    static class GlassIconView extends View {
        private final String mLabel;
        private final int mColor;
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassIconView(android.content.Context ctx, String label, int color) {
            super(ctx); mLabel = label; mColor = color;
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float s = Math.min(w, h) / 2f * 0.8f;
            float d = getResources().getDisplayMetrics().density;
            int cr = (mColor >> 16) & 0xFF, cg = (mColor >> 8) & 0xFF, cb = mColor & 0xFF;
            mP.setColor(Color.argb(200, cr, cg, cb));
            mP.setStyle(Paint.Style.STROKE);
            mP.setStrokeWidth(1.8f * d);
            mP.setStrokeCap(Paint.Cap.ROUND);
            mP.setAntiAlias(true);

            switch (mLabel.toUpperCase(Locale.US)) {
                case "TALK": {
                    // Mic shape
                    RectF body = new RectF(cx - s * 0.35f, cy - s * 0.7f, cx + s * 0.35f, cy + s * 0.1f);
                    c.drawRoundRect(body, s * 0.35f, s * 0.35f, mP);
                    RectF stand = new RectF(cx - s * 0.6f, cy - s * 0.1f, cx + s * 0.6f, cy + s * 0.5f);
                    c.drawArc(stand, 0f, 180f, false, mP);
                    c.drawLine(cx, cy + s * 0.5f, cx, cy + s * 0.85f, mP);
                    c.drawLine(cx - s * 0.3f, cy + s * 0.85f, cx + s * 0.3f, cy + s * 0.85f, mP);
                    break;
                }
                case "VAULT": case "WALLET": {
                    // Lock
                    RectF body = new RectF(cx - s * 0.6f, cy - s * 0.1f, cx + s * 0.6f, cy + s * 0.8f);
                    c.drawRoundRect(body, s * 0.12f, s * 0.12f, mP);
                    RectF shack = new RectF(cx - s * 0.38f, cy - s * 0.85f, cx + s * 0.38f, cy - s * 0.1f);
                    c.drawArc(shack, 180f, 180f, false, mP);
                    c.drawCircle(cx, cy + s * 0.35f, s * 0.18f, mP);
                    c.drawLine(cx, cy + s * 0.5f, cx, cy + s * 0.7f, mP);
                    break;
                }
                case "MEMORY": {
                    // Brain-like (two circles)
                    Paint fp = new Paint(mP); fp.setStyle(Paint.Style.STROKE);
                    c.drawCircle(cx - s * 0.25f, cy, s * 0.55f, fp);
                    c.drawCircle(cx + s * 0.25f, cy, s * 0.55f, fp);
                    c.drawLine(cx, cy - s * 0.55f, cx, cy + s * 0.55f, mP);
                    break;
                }
                case "REMIND": {
                    // Bell
                    Path bell = new Path();
                    bell.moveTo(cx - s * 0.6f, cy + s * 0.4f);
                    bell.lineTo(cx + s * 0.6f, cy + s * 0.4f);
                    bell.lineTo(cx + s * 0.5f, cy - s * 0.1f);
                    RectF top = new RectF(cx - s * 0.5f, cy - s * 0.8f, cx + s * 0.5f, cy - s * 0.1f);
                    bell.arcTo(top, 0f, -180f, false);
                    bell.lineTo(cx - s * 0.6f, cy + s * 0.4f);
                    c.drawPath(bell, mP);
                    c.drawLine(cx - s * 0.2f, cy + s * 0.4f, cx + s * 0.2f, cy + s * 0.4f, mP);
                    RectF clapper = new RectF(cx - s * 0.15f, cy + s * 0.4f, cx + s * 0.15f, cy + s * 0.7f);
                    c.drawArc(clapper, 0f, 180f, false, mP);
                    break;
                }
                case "WEATHER": {
                    // Cloud-like arcs
                    c.drawCircle(cx, cy - s * 0.15f, s * 0.5f, mP);
                    c.drawCircle(cx - s * 0.35f, cy + s * 0.05f, s * 0.35f, mP);
                    c.drawCircle(cx + s * 0.35f, cy + s * 0.05f, s * 0.35f, mP);
                    break;
                }
                case "SEARCH": {
                    c.drawCircle(cx - s * 0.15f, cy - s * 0.15f, s * 0.52f, mP);
                    c.drawLine(cx + s * 0.25f, cy + s * 0.25f, cx + s * 0.8f, cy + s * 0.8f, mP);
                    break;
                }
                case "CAMERA": {
                    RectF body = new RectF(cx - s * 0.75f, cy - s * 0.45f, cx + s * 0.75f, cy + s * 0.55f);
                    c.drawRoundRect(body, s * 0.12f, s * 0.12f, mP);
                    c.drawCircle(cx, cy + s * 0.05f, s * 0.32f, mP);
                    c.drawLine(cx - s * 0.22f, cy - s * 0.45f, cx - s * 0.22f, cy - s * 0.72f, mP);
                    c.drawLine(cx - s * 0.22f, cy - s * 0.72f, cx + s * 0.22f, cy - s * 0.72f, mP);
                    c.drawLine(cx + s * 0.22f, cy - s * 0.72f, cx + s * 0.22f, cy - s * 0.45f, mP);
                    break;
                }
                case "CALENDAR": {
                    RectF body = new RectF(cx - s * 0.7f, cy - s * 0.5f, cx + s * 0.7f, cy + s * 0.75f);
                    c.drawRoundRect(body, s * 0.1f, s * 0.1f, mP);
                    c.drawLine(cx - s * 0.7f, cy - s * 0.05f, cx + s * 0.7f, cy - s * 0.05f, mP);
                    c.drawLine(cx - s * 0.25f, cy - s * 0.75f, cx - s * 0.25f, cy - s * 0.25f, mP);
                    c.drawLine(cx + s * 0.25f, cy - s * 0.75f, cx + s * 0.25f, cy - s * 0.25f, mP);
                    break;
                }
                case "SETTINGS": {
                    c.drawCircle(cx, cy, s * 0.38f, mP);
                    for (int i = 0; i < 6; i++) {
                        double a = Math.toRadians(i * 60f);
                        float rx1 = cx + (float)(Math.cos(a) * s * 0.55f);
                        float ry1 = cy + (float)(Math.sin(a) * s * 0.55f);
                        float rx2 = cx + (float)(Math.cos(a) * s * 0.85f);
                        float ry2 = cy + (float)(Math.sin(a) * s * 0.85f);
                        c.drawLine(rx1, ry1, rx2, ry2, mP);
                    }
                    break;
                }
                case "HEALTH": {
                    // Heart-rate line
                    Path hr = new Path();
                    hr.moveTo(cx - s, cy);
                    hr.lineTo(cx - s * 0.4f, cy);
                    hr.lineTo(cx - s * 0.2f, cy - s * 0.7f);
                    hr.lineTo(cx, cy + s * 0.5f);
                    hr.lineTo(cx + s * 0.2f, cy - s * 0.3f);
                    hr.lineTo(cx + s * 0.4f, cy);
                    hr.lineTo(cx + s, cy);
                    c.drawPath(hr, mP);
                    break;
                }
                case "AGENTS": case "APP AGENT": {
                    // Robot face
                    RectF head = new RectF(cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.5f);
                    c.drawRoundRect(head, s * 0.15f, s * 0.15f, mP);
                    c.drawCircle(cx - s * 0.25f, cy - s * 0.1f, s * 0.18f, mP);
                    c.drawCircle(cx + s * 0.25f, cy - s * 0.1f, s * 0.18f, mP);
                    c.drawLine(cx - s * 0.25f, cy + s * 0.25f, cx + s * 0.25f, cy + s * 0.25f, mP);
                    c.drawLine(cx, cy - s * 0.6f, cx, cy - s * 0.85f, mP);
                    break;
                }
                case "TASKS": case "PROC TASKS": case "FREE TASKS": {
                    // Checklist lines
                    c.drawLine(cx - s * 0.8f, cy - s * 0.5f, cx + s * 0.8f, cy - s * 0.5f, mP);
                    c.drawLine(cx - s * 0.8f, cy,            cx + s * 0.8f, cy,            mP);
                    c.drawLine(cx - s * 0.8f, cy + s * 0.5f, cx + s * 0.8f, cy + s * 0.5f, mP);
                    c.drawLine(cx - s * 0.8f, cy - s * 0.7f, cx - s * 0.8f, cy + s * 0.7f, mP);
                    break;
                }
                case "CONTROL": {
                    // Joystick / target
                    c.drawCircle(cx, cy, s * 0.65f, mP);
                    c.drawCircle(cx, cy, s * 0.25f, mP);
                    c.drawLine(cx, cy - s * 0.65f, cx, cy - s * 0.9f, mP);
                    c.drawLine(cx, cy + s * 0.65f, cx, cy + s * 0.9f, mP);
                    c.drawLine(cx - s * 0.65f, cy, cx - s * 0.9f, cy, mP);
                    c.drawLine(cx + s * 0.65f, cy, cx + s * 0.9f, cy, mP);
                    break;
                }
                default: {
                    // Diamond
                    Path dp = new Path();
                    dp.moveTo(cx, cy - s * 0.7f); dp.lineTo(cx + s * 0.55f, cy);
                    dp.lineTo(cx, cy + s * 0.7f); dp.lineTo(cx - s * 0.55f, cy); dp.close();
                    c.drawPath(dp, mP);
                    break;
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  MINI ORB VIEW — smaller version of the radar for the bottom
    // ═════════════════════════════════════════════════════════════════════

    public static class MiniOrbView extends View {
        public interface LabelClickListener { void onLabelClick(String label); }
        private LabelClickListener mListener;
        public void setOnLabelClickListener(LabelClickListener l) { mListener = l; }

        private static final Object[][] MINI_LABELS = {
            {"GEO",    30f,  0.76f, "#4488FF"},
            {"CENTRE", 90f,  0.60f, "#44FF88"},
            {"SEARCH", 150f, 0.76f, "#00AAFF"},
            {"VAULT",  210f, 0.76f, "#9945FF"},
            {"CAMERA", 270f, 0.60f, "#FF4488"},
            {"WIFI",   330f, 0.76f, "#44FFCC"},
        };

        private final Paint mP     = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTxtP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mArcP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDotP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler mH = new Handler(Looper.getMainLooper());
        private float mRot = 0f;
        private final List<float[]> mHits = new ArrayList<>();
        private boolean mRun;

        MiniOrbView(android.content.Context ctx) {
            super(ctx); setWillNotDraw(false);
            float d = ctx.getResources().getDisplayMetrics().density;
            mP.setStyle(Paint.Style.STROKE); mP.setAntiAlias(true);
            mTxtP.setTypeface(Typeface.MONOSPACE); mTxtP.setAntiAlias(true); mTxtP.setTextAlign(Paint.Align.CENTER);
            mArcP.setStyle(Paint.Style.STROKE); mArcP.setColor(Color.argb(80, 204, 34, 0));
            mArcP.setStrokeWidth(2f * d); mArcP.setStrokeCap(Paint.Cap.ROUND); mArcP.setAntiAlias(true);
            mDotP.setStyle(Paint.Style.FILL); mDotP.setAntiAlias(true);
        }

        @Override protected void onAttachedToWindow()  { super.onAttachedToWindow();  mRun=true;  tick(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); mRun=false; mH.removeCallbacksAndMessages(null); }
        private void tick() { mRot = (mRot + 0.4f) % 360f; invalidate(); if(mRun) mH.postDelayed(this::tick,16); }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float tx = e.getX(), ty = e.getY();
                for (float[] b : mHits) {
                    if (tx >= b[0] && tx <= b[2] && ty >= b[1] && ty <= b[3]) {
                        int idx = (int) b[4];
                        if (mListener != null) mListener.onLabelClick((String) MINI_LABELS[idx][0]);
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
            float maxR = Math.min(w, h) / 2f * 0.92f;
            float d = getResources().getDisplayMetrics().density;
            mHits.clear();

            // Rings
            float[] rings = {0.90f, 0.70f, 0.50f};
            for (float r : rings) {
                mP.setColor(Color.argb(45, 0, 150, 255)); mP.setStrokeWidth(d);
                canvas.drawCircle(cx, cy, maxR * r, mP);
            }

            // Spinning arc
            RectF ar = new RectF(cx - maxR * 0.90f, cy - maxR * 0.90f, cx + maxR * 0.90f, cy + maxR * 0.90f);
            canvas.drawArc(ar, mRot, 120f, false, mArcP);

            // Center
            mDotP.setColor(Color.parseColor("#010308"));
            canvas.drawCircle(cx, cy, maxR * 0.18f, mDotP);
            mDotP.setColor(Color.parseColor("#CC2200"));
            canvas.drawCircle(cx, cy, maxR * 0.06f, mDotP);

            // Labels
            for (int i = 0; i < MINI_LABELS.length; i++) {
                String lbl = (String) MINI_LABELS[i][0];
                float  ang = (Float) MINI_LABELS[i][1];
                float  rad = (Float) MINI_LABELS[i][2];
                int    col = Color.parseColor((String) MINI_LABELS[i][3]);
                int cr2 = (col>>16)&0xFF, cg2=(col>>8)&0xFF, cb2=col&0xFF;
                double a = Math.toRadians(ang);
                float lx = cx + (float)(Math.cos(a) * maxR * rad);
                float ly = cy + (float)(Math.sin(a) * maxR * rad);
                mTxtP.setTextSize(maxR * 0.10f);
                mTxtP.setColor(Color.argb(200, cr2, cg2, cb2));
                float tw = mTxtP.measureText(lbl), th = mTxtP.getTextSize();
                float padH = 5f*d, padV = 2.5f*d;
                float cL = lx - tw/2f - padH, cT = ly - th/2f - padV;
                RectF chipR = new RectF(cL, cT, cL+tw+padH*2, cT+th+padV*2);
                Paint bg2 = new Paint(Paint.ANTI_ALIAS_FLAG); bg2.setStyle(Paint.Style.FILL);
                bg2.setColor(Color.argb(50, cr2, cg2, cb2));
                canvas.drawRoundRect(chipR, 4*d, 4*d, bg2);
                Paint brd = new Paint(Paint.ANTI_ALIAS_FLAG); brd.setStyle(Paint.Style.STROKE);
                brd.setColor(Color.argb(140, cr2, cg2, cb2)); brd.setStrokeWidth(d);
                canvas.drawRoundRect(chipR, 4*d, 4*d, brd);
                canvas.drawText(lbl, lx, ly + th*0.35f, mTxtP);
                mHits.add(new float[]{cL, cT, cL+tw+padH*2, cT+th+padV*2, i});
            }
        }
    }
}
