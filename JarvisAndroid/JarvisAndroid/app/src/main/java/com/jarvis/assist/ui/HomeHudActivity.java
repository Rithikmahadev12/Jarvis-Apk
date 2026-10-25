package com.jarvis.assist.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
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
import android.view.OrientationEventListener;
import android.view.MotionEvent;
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
import java.util.Locale;

/**
 * HomeHudActivity — Iron Man radial wheel HUD.
 * Portrait mode: full-screen radial sector wheel with arc reactor center.
 * Sectors: TALK · VAULT (shows wallet inline) · MEMORY · REMINDERS · HUD · SETTINGS
 */
public class HomeHudActivity extends AppCompatActivity {

    private SessionStore mSession;
    private final Handler mClockHandler = new Handler(Looper.getMainLooper());

    private float SD;
    private float SS;

    private TextView mTvClock;
    private TextView mTvGreeting;
    private TextView mTvName;
    private TextView mTvStatus;
    private TextView mTvWalletBal;
    private TextView mTvMemCount;

    private RadialWheelView mWheel;
    private VaultPanelView mVaultPanel;
    private boolean mVaultOpen = false;

    private SoundPool mSoundPool;
    private int mClickSoundId = -1;
    private boolean mSoundLoaded = false;

    private OrientationEventListener mOrientationListener;
    private boolean mInLandscape = false;

    // Sector definitions
    private static final String[] SECTOR_LABELS = {
        "TALK", "VAULT", "MEMORY", "REMINDERS", "HUD", "SETTINGS"
    };
    private static final int[] SECTOR_COLORS = {
        0xFFCC2200, 0xFF9945FF, 0xFF44FF88, 0xFFFFD700, 0xFF4499FF, 0xFF667788
    };
    private static final String[] SECTOR_ICONS = {
        "🎙", "🔐", "🧠", "⏰", "◉", "⚙"
    };

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
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        SS = shortSide / 100f / dm.scaledDensity;

        initSoundPool();
        buildUI();
        startClock();
        loadData();
        setupOrientationListener();
    }

    @Override protected void onResume() {
        super.onResume();
        mInLandscape = false;
        if (mOrientationListener != null && mOrientationListener.canDetectOrientation())
            mOrientationListener.enable();
    }

    @Override protected void onPause() {
        super.onPause();
        if (mOrientationListener != null) mOrientationListener.disable();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        mClockHandler.removeCallbacksAndMessages(null);
        if (mSoundPool != null) mSoundPool.release();
    }

    private void initSoundPool() {
        AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        mSoundPool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attr).build();
        mSoundPool.setOnLoadCompleteListener((sp, id, status) -> mSoundLoaded = (status == 0));
        try {
            android.content.res.AssetFileDescriptor afd = getAssets().openFd("Voice.wav");
            mClickSoundId = mSoundPool.load(afd, 1);
        } catch (Exception ignored) {}
    }

    private void playClick() {
        if (mSoundLoaded && mClickSoundId != -1)
            mSoundPool.play(mClickSoundId, 0.6f, 0.6f, 1, 0, 1.0f);
    }

    private void setupOrientationListener() {
        mOrientationListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                boolean nowLandscape = (orientation > 60 && orientation < 120)
                        || (orientation > 240 && orientation < 300);
                if (nowLandscape && !mInLandscape) {
                    mInLandscape = true;
                    startActivity(new Intent(HomeHudActivity.this, LandscapeHudActivity.class));
                } else if (!nowLandscape) {
                    mInLandscape = false;
                }
            }
        };
        if (mOrientationListener.canDetectOrientation()) mOrientationListener.enable();
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#030508"));

        // Tick ruler top
        LandscapeHudActivity.TickRulerView ruler = new LandscapeHudActivity.TickRulerView(this);
        FrameLayout.LayoutParams rulerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(20));
        rulerLp.gravity = Gravity.TOP;
        root.addView(ruler, rulerLp);

        // Top red accent line
        View redLine = new View(this);
        redLine.setBackgroundColor(Color.parseColor("#CC2200"));
        FrameLayout.LayoutParams rlLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(2));
        rlLp.gravity = Gravity.TOP;
        root.addView(redLine, rlLp);

        // Header: JARVIS + ONLINE + Clock
        root.addView(buildHeaderRow(), buildHeaderLp());

        // Radial wheel — fills most of the screen
        mWheel = new RadialWheelView(this);
        mWheel.setOnSectorClickListener(idx -> {
            playClick();
            onSectorTapped(idx);
        });
        FrameLayout.LayoutParams wheelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        wheelLp.topMargin    = dp(80);
        wheelLp.bottomMargin = dp(60);
        root.addView(mWheel, wheelLp);

        // Vault panel overlay (hidden by default)
        mVaultPanel = new VaultPanelView(this);
        mVaultPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams vpLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        vpLp.topMargin    = dp(80);
        vpLp.bottomMargin = dp(60);
        root.addView(mVaultPanel, vpLp);

        // Status/info strip at bottom
        root.addView(buildBottomStrip(), buildBottomStripLp());

        setContentView(root);
    }

    private FrameLayout.LayoutParams buildHeaderLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity   = Gravity.TOP;
        lp.topMargin = dp(20);
        return lp;
    }

    private LinearLayout buildHeaderRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(10), dp(20), dp(6));

        // Left: JARVIS branding
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvJarvis = new TextView(this);
        tvJarvis.setText("J A R V I S");
        tvJarvis.setTextColor(Color.WHITE);
        tvJarvis.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(3.8f));
        tvJarvis.setLetterSpacing(0.25f);
        tvJarvis.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        left.addView(tvJarvis);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        View statusDot = new View(this);
        android.graphics.drawable.GradientDrawable sdg =
                new android.graphics.drawable.GradientDrawable();
        sdg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        sdg.setColor(Color.parseColor("#44FF88"));
        statusDot.setBackground(sdg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotLp.rightMargin = dp(5);
        statusDot.setLayoutParams(dotLp);
        pulseView(statusDot);
        statusRow.addView(statusDot);

        mTvStatus = new TextView(this);
        mTvStatus.setText("ONLINE");
        mTvStatus.setTextColor(Color.parseColor("#44FF88"));
        mTvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.6f));
        mTvStatus.setLetterSpacing(0.18f);
        statusRow.addView(mTvStatus);
        left.addView(statusRow);
        row.addView(left);

        // Right: clock
        mTvClock = new TextView(this);
        mTvClock.setText("--:--");
        mTvClock.setTextColor(Color.WHITE);
        mTvClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(4.0f));
        mTvClock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(mTvClock);

        return row;
    }

    private FrameLayout.LayoutParams buildBottomStripLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(60));
        lp.gravity = Gravity.BOTTOM;
        return lp;
    }

    private LinearLayout buildBottomStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setBackgroundColor(Color.parseColor("#05080E"));
        strip.setPadding(dp(20), dp(8), dp(20), dp(8));

        // Greeting + name row
        LinearLayout greetRow = new LinearLayout(this);
        greetRow.setOrientation(LinearLayout.HORIZONTAL);
        greetRow.setGravity(Gravity.CENTER_VERTICAL);

        mTvGreeting = new TextView(this);
        mTvGreeting.setText(getGreeting());
        mTvGreeting.setTextColor(Color.parseColor("#66FFFFFF"));
        mTvGreeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        mTvGreeting.setPadding(0, 0, dp(6), 0);
        greetRow.addView(mTvGreeting);

        mTvName = new TextView(this);
        String rawName = mSession.getUserName();
        mTvName.setText(rawName != null ? rawName.toUpperCase(Locale.US) + (mSession.isOwner() ? " ★" : "") : "OPERATOR");
        mTvName.setTextColor(Color.WHITE);
        mTvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(2.2f));
        mTvName.setTypeface(null, Typeface.BOLD);
        mTvName.setLetterSpacing(0.08f);
        greetRow.addView(mTvName);

        View spacer = new View(this);
        greetRow.addView(spacer, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        mTvWalletBal = new TextView(this);
        mTvWalletBal.setText("◎ —");
        mTvWalletBal.setTextColor(Color.parseColor("#9945FF"));
        mTvWalletBal.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.9f));
        mTvWalletBal.setTypeface(Typeface.MONOSPACE);
        greetRow.addView(mTvWalletBal);
        strip.addView(greetRow);

        mTvMemCount = new TextView(this);
        mTvMemCount.setText("…");
        mTvMemCount.setTextColor(Color.parseColor("#44FF88"));
        mTvMemCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(1.5f));
        strip.addView(mTvMemCount);

        return strip;
    }

    private void onSectorTapped(int idx) {
        switch (idx) {
            case 0: // TALK
                startActivity(new Intent(this, VoiceOverlayActivity.class));
                break;
            case 1: // VAULT — open vault panel inline with wallet
                mVaultOpen = !mVaultOpen;
                if (mVaultOpen) {
                    mVaultPanel.setVisibility(View.VISIBLE);
                    ObjectAnimator.ofFloat(mVaultPanel, "alpha", 0f, 1f).setDuration(300).start();
                    mVaultPanel.loadWalletData(mSession, this);
                } else {
                    ObjectAnimator fadeOut = ObjectAnimator.ofFloat(mVaultPanel, "alpha", 1f, 0f);
                    fadeOut.setDuration(200);
                    fadeOut.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator a) {
                            mVaultPanel.setVisibility(View.GONE);
                        }
                    });
                    fadeOut.start();
                }
                break;
            case 2: // MEMORY
                Intent memI = new Intent(this, MainActivity.class);
                memI.putExtra("vault_unlocked", true);
                memI.putExtra("scroll_to", "memory");
                startActivity(memI);
                break;
            case 3: // REMINDERS
                Intent remI = new Intent(this, MainActivity.class);
                remI.putExtra("vault_unlocked", true);
                remI.putExtra("scroll_to", "reminders");
                startActivity(remI);
                break;
            case 4: // HUD
                startActivity(new Intent(this, LandscapeHudActivity.class));
                break;
            case 5: // SETTINGS
                Intent setI = new Intent(this, MainActivity.class);
                setI.putExtra("vault_unlocked", true);
                setI.putExtra("scroll_to", "settings");
                startActivity(setI);
                break;
        }
    }

    private void loadData() {
        String url  = mSession.getJarvisUrl();
        String name = mSession.getUserName();
        if (url == null || name == null) return;

        JarvisClient.fetchMemories(url, name, mems -> runOnUiThread(() -> {
            int count = mems.length();
            if (mTvMemCount != null)
                mTvMemCount.setText(count > 0
                    ? count + " memories stored"
                    : "No memories yet");
        }));

        JarvisClient.fetchWallet(url, name, new JarvisClient.WalletCallback() {
            @Override public void onSuccess(String addr, double sol, double usd) {
                runOnUiThread(() -> {
                    if (mTvWalletBal != null)
                        mTvWalletBal.setText(String.format(Locale.US, "◎ %.4f", sol));
                });
            }
            @Override public void onNotConfigured() {
                runOnUiThread(() -> { if (mTvWalletBal != null) mTvWalletBal.setText("◎ NO WALLET"); });
            }
            @Override public void onError(String e) {
                runOnUiThread(() -> { if (mTvWalletBal != null) mTvWalletBal.setText("◎ —"); });
            }
        });
    }

    private void startClock() {
        mClockHandler.post(new Runnable() {
            @Override public void run() {
                if (mTvClock != null)
                    mTvClock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
                if (mTvGreeting != null)
                    mTvGreeting.setText(getGreeting());
                mClockHandler.postDelayed(this, 30_000);
            }
        });
    }

    private String getGreeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if      (h < 12) return "GOOD MORNING,";
        else if (h < 17) return "GOOD AFTERNOON,";
        else if (h < 21) return "GOOD EVENING,";
        else             return "GOOD NIGHT,";
    }

    private float sp(float pct) { return SS * pct; }
    private int   dp(float v)   { return Math.round(v * SD); }

    private void pulseView(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.15f);
        a.setDuration(900);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.start();
    }

    // ═══════════════════════════════════════════════════════════════════
    // RADIAL WHEEL VIEW — the full-screen Iron Man sector wheel
    // ═══════════════════════════════════════════════════════════════════

    public static class RadialWheelView extends View {
        private static final int SECTOR_COUNT = 6;
        private static final float GAP_DEG    = 4f;  // gap between sectors
        private static final float SECTOR_DEG = (360f / SECTOR_COUNT) - GAP_DEG;

        // Labels/icons/colors for sectors (in clockwise order from top-right)
        private static final String[] LABELS = {"TALK", "VAULT", "MEMORY", "REMINDERS", "HUD", "SETTINGS"};
        private static final String[] ICONS  = {"🎙", "🔐", "🧠", "⏰", "◉", "⚙"};
        private static final int[] BASE_COLORS = {
            0xFFCC2200, 0xFF9945FF, 0xFF44FF88, 0xFFFFD700, 0xFF4499FF, 0xFF667788
        };

        private final Paint[] mSectorPaint  = new Paint[SECTOR_COUNT];
        private final Paint[] mStrokePaint  = new Paint[SECTOR_COUNT];
        private final Paint   mTextPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint   mIconPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint   mCenterPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint   mRingPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint   mTickPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint   mCorePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF mOuter  = new RectF();
        private final RectF mMiddle = new RectF();
        private final RectF mInner  = new RectF();

        private int mSelectedSector = -1;
        private float mRotationOffset = -90f - (SECTOR_DEG / 2f + GAP_DEG / 2f);

        // Arc animation
        private final float[] mArcAngles = {0f, 60f, 120f, 180f};
        private final float[] mArcSpeeds = {0.4f, -0.7f, 0.5f, -0.3f};
        private float mCoreGlow = 0f;
        private float mCoreGlowDir = 1f;
        private final Handler mH = new Handler(Looper.getMainLooper());
        private boolean mRun;

        public interface OnSectorClickListener {
            void onSectorClick(int index);
        }
        private OnSectorClickListener mListener;

        public void setOnSectorClickListener(OnSectorClickListener l) { mListener = l; }

        public RadialWheelView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            float d = ctx.getResources().getDisplayMetrics().density;

            for (int i = 0; i < SECTOR_COUNT; i++) {
                mSectorPaint[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mSectorPaint[i].setStyle(Paint.Style.FILL);
                // Subtle fill — dim version of color
                int c = BASE_COLORS[i];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                mSectorPaint[i].setColor(Color.argb(30, r, g, b));

                mStrokePaint[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                mStrokePaint[i].setStyle(Paint.Style.STROKE);
                mStrokePaint[i].setStrokeWidth(2f * d);
                mStrokePaint[i].setColor(Color.argb(160, r, g, b));
            }

            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            mTextPaint.setLetterSpacing(0.1f);

            mIconPaint.setTextAlign(Paint.Align.CENTER);

            mCenterPaint.setStyle(Paint.Style.FILL);
            mCenterPaint.setColor(Color.parseColor("#08001A"));

            mRingPaint.setStyle(Paint.Style.STROKE);
            mRingPaint.setStrokeWidth(1.5f * d);
            mRingPaint.setColor(Color.parseColor("#33CC2200"));
            mRingPaint.setAntiAlias(true);

            mTickPaint.setStyle(Paint.Style.STROKE);
            mTickPaint.setStrokeWidth(1f * d);
            mTickPaint.setAntiAlias(true);

            mCorePaint.setStyle(Paint.Style.FILL);
            mCorePaint.setAntiAlias(true);

            setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    float cx = getWidth() / 2f, cy = getHeight() / 2f;
                    float dx = e.getX() - cx, dy = e.getY() - cy;
                    double dist = Math.sqrt(dx*dx + dy*dy);
                    float minR = Math.min(getWidth(), getHeight()) / 2f;
                    float innerR = minR * 0.32f, outerR = minR * 0.92f;
                    if (dist >= innerR && dist <= outerR) {
                        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
                        angleDeg = (angleDeg - mRotationOffset % 360 + 720) % 360;
                        int sector = (int)(angleDeg / (360f / SECTOR_COUNT));
                        if (sector >= 0 && sector < SECTOR_COUNT) {
                            mSelectedSector = sector;
                            invalidate();
                            if (mListener != null) mListener.onSectorClick(sector);
                        }
                    }
                    return true;
                }
                return false;
            });
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            mRun = true;
            tick();
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mRun = false;
            mH.removeCallbacksAndMessages(null);
        }

        private void tick() {
            for (int i = 0; i < mArcAngles.length; i++)
                mArcAngles[i] = (mArcAngles[i] + mArcSpeeds[i]) % 360f;
            mCoreGlow += mCoreGlowDir * 0.015f;
            if (mCoreGlow >= 1f) { mCoreGlow = 1f; mCoreGlowDir = -1f; }
            if (mCoreGlow <= 0f) { mCoreGlow = 0f; mCoreGlowDir = +1f; }
            invalidate();
            if (mRun) mH.postDelayed(this::tick, 16);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float minR = Math.min(w, h) / 2f;

            float outerR  = minR * 0.92f;
            float innerR  = minR * 0.32f;
            float midR    = (outerR + innerR) / 2f;
            float labelR  = minR * 0.70f;

            mOuter.set(cx-outerR, cy-outerR, cx+outerR, cy+outerR);
            mMiddle.set(cx-midR, cy-midR, cx+midR, cy+midR);
            mInner.set(cx-innerR, cy-innerR, cx+innerR, cy+innerR);

            // ── Outer tick ring ──
            for (int t = 0; t < 120; t++) {
                double ang = Math.toRadians(t * 3.0);
                boolean maj = t % 5 == 0;
                mTickPaint.setColor(maj ? 0x55FFFFFF : 0x22FFFFFF);
                mTickPaint.setStrokeWidth((maj ? 1.8f : 1f) * d);
                float ro = outerR;
                float ri = ro - (maj ? minR * 0.04f : minR * 0.02f);
                canvas.drawLine(
                    cx + (float)(Math.cos(ang)*ri), cy + (float)(Math.sin(ang)*ri),
                    cx + (float)(Math.cos(ang)*ro), cy + (float)(Math.sin(ang)*ro),
                    mTickPaint);
            }

            // ── Sectors ──
            float startOffset = mRotationOffset;
            for (int i = 0; i < SECTOR_COUNT; i++) {
                float startAngle = startOffset + i * (SECTOR_DEG + GAP_DEG);
                boolean selected = (i == mSelectedSector);

                // Expand selected sector slightly
                float rOuter = selected ? outerR + minR * 0.04f : outerR;
                float rInner = innerR;
                RectF sectorRect = new RectF(cx-rOuter, cy-rOuter, cx+rOuter, cy+rOuter);

                // Build sector path (annular segment)
                Path path = new Path();
                double a1 = Math.toRadians(startAngle);
                double a2 = Math.toRadians(startAngle + SECTOR_DEG);
                // Outer arc start
                path.moveTo(cx + (float)(Math.cos(a1) * rOuter), cy + (float)(Math.sin(a1) * rOuter));
                path.arcTo(sectorRect, startAngle, SECTOR_DEG, false);
                // Inner arc back
                path.lineTo(cx + (float)(Math.cos(a2) * rInner), cy + (float)(Math.sin(a2) * rInner));
                RectF innerRect = new RectF(cx-rInner, cy-rInner, cx+rInner, cy+rInner);
                path.arcTo(innerRect, startAngle + SECTOR_DEG, -SECTOR_DEG, false);
                path.close();

                // Fill
                if (selected) {
                    int c = BASE_COLORS[i];
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    mSectorPaint[i].setColor(Color.argb(80, r, g, b));
                } else {
                    int c = BASE_COLORS[i];
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    mSectorPaint[i].setColor(Color.argb(25, r, g, b));
                }
                canvas.drawPath(path, mSectorPaint[i]);
                canvas.drawPath(path, mStrokePaint[i]);

                // Radial divider lines
                mTickPaint.setColor(0x33FFFFFF);
                mTickPaint.setStrokeWidth(1f * d);
                canvas.drawLine(
                    cx + (float)(Math.cos(a1) * rInner), cy + (float)(Math.sin(a1) * rInner),
                    cx + (float)(Math.cos(a1) * rOuter), cy + (float)(Math.sin(a1) * rOuter),
                    mTickPaint);

                // Icon + label at midpoint of sector arc
                double midAngle = Math.toRadians(startAngle + SECTOR_DEG / 2f);
                float lx = cx + (float)(Math.cos(midAngle) * labelR);
                float ly = cy + (float)(Math.sin(midAngle) * labelR);

                // Icon
                mIconPaint.setTextSize(minR * 0.09f);
                mIconPaint.setColor(BASE_COLORS[i]);
                canvas.drawText(ICONS[i], lx, ly - minR * 0.025f, mIconPaint);

                // Label
                mTextPaint.setTextSize(minR * 0.055f);
                mTextPaint.setColor(selected ? BASE_COLORS[i] : Color.argb(180, 255, 255, 255));
                canvas.drawText(LABELS[i], lx, ly + minR * 0.065f, mTextPaint);
            }

            // ── Spinning arcs (arc reactor effect in center) ──
            float[][] arcDefs = {
                {innerR * 0.95f, 140f},
                {innerR * 0.80f, 80f},
                {innerR * 0.65f, 200f},
                {innerR * 0.50f, 60f},
            };
            int[] arcCols = {0x55CC2200, 0x4400CCFF, 0x33FFFFFF, 0x44CC2200};
            for (int i = 0; i < 4; i++) {
                Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
                ap.setStyle(Paint.Style.STROKE);
                ap.setStrokeWidth((2.5f - i * 0.4f) * d);
                ap.setStrokeCap(Paint.Cap.ROUND);
                ap.setColor(arcCols[i]);
                float ar = arcDefs[i][0];
                RectF ar2 = new RectF(cx-ar, cy-ar, cx+ar, cy+ar);
                canvas.drawArc(ar2, mArcAngles[i], arcDefs[i][1], false, ap);
            }

            // ── Center circle (arc reactor core) ──
            // Outer glow ring
            Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            glowPaint.setStyle(Paint.Style.FILL);
            float glowAlpha = 0.3f + mCoreGlow * 0.25f;
            glowPaint.setColor(Color.argb((int)(glowAlpha * 255), 0, 120, 220));
            canvas.drawCircle(cx, cy, innerR * 0.42f, glowPaint);

            // Core fill
            mCorePaint.setColor(Color.parseColor("#050015"));
            canvas.drawCircle(cx, cy, innerR * 0.35f, mCorePaint);

            // Inner ring
            Paint ringStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringStroke.setStyle(Paint.Style.STROKE);
            ringStroke.setStrokeWidth(2f * d);
            ringStroke.setColor(Color.argb((int)(150 + mCoreGlow * 80), 0, 150, 255));
            canvas.drawCircle(cx, cy, innerR * 0.28f, ringStroke);

            // Center dot — red
            mCorePaint.setColor(Color.argb((int)(200 + mCoreGlow * 55), 204, 34, 0));
            canvas.drawCircle(cx, cy, innerR * 0.08f, mCorePaint);

            // "JARVIS" text in center
            Paint centerText = new Paint(Paint.ANTI_ALIAS_FLAG);
            centerText.setTextAlign(Paint.Align.CENTER);
            centerText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            centerText.setColor(Color.argb((int)(160 + mCoreGlow * 80), 255, 255, 255));
            centerText.setTextSize(innerR * 0.18f);
            centerText.setLetterSpacing(0.3f);
            canvas.drawText("JARVIS", cx, cy + centerText.getTextSize() * 0.35f, centerText);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // VAULT PANEL VIEW — shown when VAULT sector is tapped
    // ═══════════════════════════════════════════════════════════════════

    public static class VaultPanelView extends FrameLayout {
        private LinearLayout mContent;
        private TextView mTvAddress;
        private TextView mTvSol;
        private TextView mTvUsd;
        private TextView mTvTitle;
        private boolean mDataLoaded = false;
        private float SD;

        public VaultPanelView(android.content.Context ctx) {
            super(ctx);
            SD = ctx.getResources().getDisplayMetrics().density;
            buildLayout();
        }

        private int dp(float v) { return Math.round(v * SD); }

        private void buildLayout() {
            // Semi-transparent dark overlay
            setBackgroundColor(Color.argb(220, 3, 5, 14));

            mContent = new LinearLayout(getContext());
            mContent.setOrientation(LinearLayout.VERTICAL);
            mContent.setGravity(Gravity.CENTER);
            mContent.setPadding(dp(32), dp(24), dp(32), dp(24));

            // Close hint
            TextView tvClose = new TextView(getContext());
            tvClose.setText("▲ TAP VAULT AGAIN TO CLOSE");
            tvClose.setTextColor(Color.parseColor("#44FFFFFF"));
            tvClose.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(11));
            tvClose.setLetterSpacing(0.15f);
            tvClose.setTypeface(Typeface.MONOSPACE);
            tvClose.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            closeLp.bottomMargin = dp(20);
            tvClose.setLayoutParams(closeLp);
            mContent.addView(tvClose);

            // VAULT title
            mTvTitle = new TextView(getContext());
            mTvTitle.setText("🔐  VAULT");
            mTvTitle.setTextColor(Color.parseColor("#9945FF"));
            mTvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
            mTvTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            mTvTitle.setLetterSpacing(0.2f);
            mTvTitle.setGravity(Gravity.CENTER);
            mContent.addView(mTvTitle);

            // Divider
            View div = new View(getContext());
            div.setBackgroundColor(Color.parseColor("#339945FF"));
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
            divLp.topMargin = dp(12);
            divLp.bottomMargin = dp(16);
            div.setLayoutParams(divLp);
            mContent.addView(div);

            // SOL balance — big
            mTvSol = new TextView(getContext());
            mTvSol.setText("◎  — SOL");
            mTvSol.setTextColor(Color.WHITE);
            mTvSol.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(36));
            mTvSol.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            mTvSol.setGravity(Gravity.CENTER);
            mContent.addView(mTvSol);

            // USD value
            mTvUsd = new TextView(getContext());
            mTvUsd.setText("$ — USD");
            mTvUsd.setTextColor(Color.parseColor("#44FF88"));
            mTvUsd.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(16));
            mTvUsd.setTypeface(Typeface.MONOSPACE);
            mTvUsd.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams usdLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            usdLp.topMargin = dp(6);
            mTvUsd.setLayoutParams(usdLp);
            mContent.addView(mTvUsd);

            // Address
            mTvAddress = new TextView(getContext());
            mTvAddress.setText("ADDRESS: —");
            mTvAddress.setTextColor(Color.parseColor("#55FFFFFF"));
            mTvAddress.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(10));
            mTvAddress.setTypeface(Typeface.MONOSPACE);
            mTvAddress.setGravity(Gravity.CENTER);
            mTvAddress.setMaxLines(1);
            mTvAddress.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            addrLp.topMargin    = dp(12);
            addrLp.bottomMargin = dp(8);
            mTvAddress.setLayoutParams(addrLp);
            mContent.addView(mTvAddress);

            // Open wallet button
            TextView btnWallet = new TextView(getContext());
            btnWallet.setText("OPEN FULL WALLET  ▶");
            btnWallet.setTextColor(Color.parseColor("#9945FF"));
            btnWallet.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(13));
            btnWallet.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            btnWallet.setLetterSpacing(0.1f);
            btnWallet.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable btnBg =
                    new android.graphics.drawable.GradientDrawable();
            btnBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            btnBg.setCornerRadius(dp(8));
            btnBg.setColor(Color.parseColor("#150A2A"));
            btnBg.setStroke(dp(1), Color.parseColor("#669945FF"));
            btnWallet.setBackground(btnBg);
            btnWallet.setPadding(dp(20), dp(10), dp(20), dp(10));
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            btnLp.topMargin = dp(16);
            btnLp.gravity   = Gravity.CENTER_HORIZONTAL;
            btnWallet.setLayoutParams(btnLp);
            btnWallet.setClickable(true);
            mContent.addView(btnWallet);

            FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            contentLp.gravity = Gravity.CENTER;
            addView(mContent, contentLp);
        }

        public void loadWalletData(SessionStore session, android.app.Activity activity) {
            if (mDataLoaded) return;
            String url  = session.getJarvisUrl();
            String name = session.getUserName();
            if (url == null || name == null) return;

            JarvisClient.fetchWallet(url, name, new JarvisClient.WalletCallback() {
                @Override public void onSuccess(String addr, double sol, double usd) {
                    activity.runOnUiThread(() -> {
                        mDataLoaded = true;
                        if (mTvSol     != null) mTvSol.setText(String.format(Locale.US, "◎  %.4f  SOL", sol));
                        if (mTvUsd     != null) mTvUsd.setText(String.format(Locale.US, "$  %.2f  USD", usd));
                        if (mTvAddress != null) mTvAddress.setText("ADDRESS: " + addr);
                    });
                }
                @Override public void onNotConfigured() {
                    activity.runOnUiThread(() -> {
                        if (mTvSol != null) mTvSol.setText("NO WALLET CONFIGURED");
                        if (mTvUsd != null) mTvUsd.setText("Set up Solana wallet in settings");
                    });
                }
                @Override public void onError(String e) {
                    activity.runOnUiThread(() -> {
                        if (mTvSol != null) mTvSol.setText("ERROR LOADING WALLET");
                    });
                }
            });
        }
    }
}
