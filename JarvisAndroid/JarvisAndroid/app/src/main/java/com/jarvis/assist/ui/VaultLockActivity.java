package com.jarvis.assist.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.jarvis.assist.storage.SessionStore;

/**
 * VaultLockActivity — alien symbol grid keypad.
 * Matches video exactly:
 *   - 3D raised bevel cells
 *   - White radial glow ripple on tap (finger glow effect)
 *   - Grid fills screen between header and CLEAR/ENTER
 *   - SHA-256 pattern hash in SharedPreferences
 */
public class VaultLockActivity extends AppCompatActivity {

    private static final String PREF_VAULT_LOCK  = "jarvis_vault_lock";
    private static final String KEY_PATTERN_HASH = "pattern_hash";
    private static final String KEY_PATTERN_SET  = "pattern_set";
    private static final int    PATTERN_MIN_LEN  = 4;
    private static final int    GRID_COLS        = 7;
    private static final int    GRID_ROWS        = 8;

    // 56 esoteric / alien symbols — matches visual style in video
    private static final String[] SYMBOLS = {
        "−","⊕","▷","∑","◈","⊛","λ",
        "∇","⊞","⊠","◇","⊗","ψ","∆",
        "⋈","◉","⊘","⊡","◬","⊜","⊝",
        "∓","⊻","⋄","◊","⊙","⊚","×",
        "☿","♃","♆","♇","♄","♀","♂",
        "⌖","⌘","⎔","⎚","⍥","⍟","⍰",
        "⌬","⌭","⌮","⌯","⌰","⌱","⌲",
        "∰","∯","∮","∭","∬","∫","≈"
    };

    private final StringBuilder mPatternInput   = new StringBuilder();
    private TextView[]          mSymbolCells;
    private TextView            mPatternDisplay;
    private boolean             mIsSettingPattern = false;
    private String              mFirstPatternHash = null;
    private SessionStore        mSession;
    private TextView            mTvSubtitle;
    private LinearLayout        mRootLayout;

    // Ripple overlay — drawn on top of everything to show finger glow
    private RippleOverlayView   mRippleOverlay;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Full screen immersive
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        mSession = SessionStore.get(this);
        android.content.SharedPreferences prefs =
            getSharedPreferences(PREF_VAULT_LOCK, MODE_PRIVATE);
        mIsSettingPattern = !prefs.getBoolean(KEY_PATTERN_SET, false);
        buildUI();
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void buildUI() {
        // Root FrameLayout so we can layer the ripple overlay on top
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Color.parseColor("#050508"));

        // Main content column
        mRootLayout = new LinearLayout(this);
        mRootLayout.setOrientation(LinearLayout.VERTICAL);
        mRootLayout.setPadding(dp(14), dp(36), dp(14), dp(14));
        rootFrame.addView(mRootLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Ripple overlay on top (intercepts nothing — passthrough)
        mRippleOverlay = new RippleOverlayView(this);
        rootFrame.addView(mRippleOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        buildHeader();
        buildSubtitle();
        buildPatternDisplay();
        buildGrid();
        buildBottomButtons();

        setContentView(rootFrame);
    }

    private void buildHeader() {
        FrameLayout header = new FrameLayout(this);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        header.setLayoutParams(hlp);

        // Left — VAULT title
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        leftLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        left.setLayoutParams(leftLp);

        TextView tvSys = new TextView(this);
        tvSys.setText("JARVIS SYS · SECURE");
        tvSys.setTextColor(Color.parseColor("#33FFFFFF"));
        tvSys.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        tvSys.setLetterSpacing(0.2f);
        left.addView(tvSys);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("VAULT");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setLetterSpacing(0.14f);
        left.addView(tvTitle);

        header.addView(left);

        // Right — blue status dot (matches video top-right circle)
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        rightLp.gravity = Gravity.END | Gravity.TOP;
        rightLp.topMargin = dp(4);
        right.setLayoutParams(rightLp);

        // Outer ring
        FrameLayout dotFrame = new FrameLayout(this);
        LinearLayout.LayoutParams dfLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        dfLp.rightMargin = dp(4);
        dotFrame.setLayoutParams(dfLp);

        View outerRing = new View(this);
        outerRing.setBackground(createCircle(Color.parseColor("#224488")));
        dotFrame.addView(outerRing, new FrameLayout.LayoutParams(dp(22), dp(22)));

        View innerDot = new View(this);
        innerDot.setBackground(createCircle(Color.parseColor("#4488FF")));
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(dp(12), dp(12));
        innerLp.gravity = Gravity.CENTER;
        dotFrame.addView(innerDot, innerLp);
        pulseView(innerDot);

        right.addView(dotFrame);

        // Small counter like "01" in video
        TextView tvCount = new TextView(this);
        tvCount.setText("01");
        tvCount.setTextColor(Color.parseColor("#4488FF"));
        tvCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvCount.setTypeface(Typeface.MONOSPACE);
        right.addView(tvCount);

        header.addView(right);
        mRootLayout.addView(header);
    }

    private void buildSubtitle() {
        mTvSubtitle = new TextView(this);
        mTvSubtitle.setText(mIsSettingPattern ? "CREATE YOUR PATTERN" : "ENTER PATTERN");
        mTvSubtitle.setTextColor(Color.parseColor("#88FFFFFF"));
        mTvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        mTvSubtitle.setLetterSpacing(0.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        mTvSubtitle.setLayoutParams(lp);
        mRootLayout.addView(mTvSubtitle);
    }

    private void buildPatternDisplay() {
        mPatternDisplay = new TextView(this);
        mPatternDisplay.setText("·  ·  ·  ·");
        mPatternDisplay.setTextColor(Color.parseColor("#33FFFFFF"));
        mPatternDisplay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mPatternDisplay.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        mPatternDisplay.setLayoutParams(lp);
        mRootLayout.addView(mPatternDisplay);
    }

    private void buildGrid() {
        // Card wrapping the grid — dark blue-black with subtle border
        FrameLayout gridCard = new FrameLayout(this);
        gridCard.setBackground(createRoundRect(Color.parseColor("#070B12"),
                Color.parseColor("#1A2A44"), dp(10), dp(1)));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.bottomMargin = dp(10);
        gridCard.setLayoutParams(cardLp);
        gridCard.setPadding(dp(4), dp(6), dp(4), dp(6));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(GRID_COLS);
        grid.setRowCount(GRID_ROWS);
        FrameLayout.LayoutParams gridLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        grid.setLayoutParams(gridLp);

        mSymbolCells = new TextView[SYMBOLS.length];

        for (int i = 0; i < SYMBOLS.length; i++) {
            final int idx = i;
            TextView cell = new TextView(this);
            cell.setText(SYMBOLS[i]);
            cell.setTextColor(Color.parseColor("#7799BBDD"));
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(createCellBg(false));
            cell.setClickable(true);
            cell.setFocusable(true);

            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.columnSpec = GridLayout.spec(i % GRID_COLS, 1f);
            glp.rowSpec    = GridLayout.spec(i / GRID_COLS, 1f);
            glp.setMargins(dp(2), dp(2), dp(2), dp(2));
            glp.width  = 0;
            glp.height = 0;
            cell.setLayoutParams(glp);

            // Touch listener for the finger glow ripple effect
            cell.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    // Get absolute position for ripple
                    int[] loc = new int[2];
                    v.getLocationInWindow(loc);
                    float rx = loc[0] + event.getX();
                    float ry = loc[1] + event.getY();
                    mRippleOverlay.triggerRipple(rx, ry);
                }
                return false; // still fire onClick
            });

            cell.setOnClickListener(v -> onSymbolTapped(idx, cell));
            mSymbolCells[i] = cell;
            grid.addView(cell);
        }

        gridCard.addView(grid);
        mRootLayout.addView(gridCard);
    }

    private void buildBottomButtons() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        // CLEAR
        TextView btnClear = new TextView(this);
        btnClear.setText("CLEAR");
        btnClear.setTextColor(Color.parseColor("#AAFFFFFF"));
        btnClear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnClear.setLetterSpacing(0.15f);
        btnClear.setGravity(Gravity.CENTER);
        btnClear.setBackground(createRoundRect(Color.parseColor("#0F0F18"),
                Color.parseColor("#333355"), dp(6), dp(1)));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        clearLp.rightMargin = dp(8);
        btnClear.setLayoutParams(clearLp);
        btnClear.setClickable(true);
        btnClear.setFocusable(true);
        btnClear.setOnClickListener(v -> clearPattern());
        row.addView(btnClear);

        // ENTER — red border exactly like video
        TextView btnEnter = new TextView(this);
        btnEnter.setText("ENTER");
        btnEnter.setTextColor(Color.parseColor("#FF3333"));
        btnEnter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnEnter.setLetterSpacing(0.15f);
        btnEnter.setGravity(Gravity.CENTER);
        btnEnter.setBackground(createRoundRect(Color.parseColor("#120500"),
                Color.parseColor("#AA2200"), dp(6), dp(2)));
        btnEnter.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        btnEnter.setClickable(true);
        btnEnter.setFocusable(true);
        btnEnter.setOnClickListener(v -> submitPattern());
        row.addView(btnEnter);

        mRootLayout.addView(row);
    }

    // ── Logic ─────────────────────────────────────────────────────────────

    private void onSymbolTapped(int idx, TextView cell) {
        Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vib != null && vib.hasVibrator())
            vib.vibrate(VibrationEffect.createOneShot(14, VibrationEffect.DEFAULT_AMPLITUDE));

        // Flash cell bright cyan then back
        cell.setTextColor(Color.parseColor("#00FFEE"));
        cell.setBackground(createCellBg(true));
        cell.postDelayed(() -> {
            cell.setTextColor(Color.parseColor("#7799BBDD"));
            cell.setBackground(createCellBg(false));
        }, 280);

        mPatternInput.append((char)('A' + idx));
        updatePatternDots();
    }

    private void updatePatternDots() {
        int len = mPatternInput.length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append("●");
            if (i < len - 1) sb.append("  ");
        }
        int dots = Math.max(4, len + 1);
        for (int i = len; i < dots; i++) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("·");
        }
        mPatternDisplay.setText(sb.toString());
        mPatternDisplay.setTextColor(Color.parseColor("#88FFFFFF"));
    }

    private void clearPattern() {
        mPatternInput.setLength(0);
        mPatternDisplay.setText("·  ·  ·  ·");
        mPatternDisplay.setTextColor(Color.parseColor("#33FFFFFF"));
        for (TextView c : mSymbolCells) {
            c.setTextColor(Color.parseColor("#7799BBDD"));
            c.setBackground(createCellBg(false));
        }
    }

    private void submitPattern() {
        if (mPatternInput.length() < PATTERN_MIN_LEN) {
            shakeDisplay();
            Toast.makeText(this, "Tap at least " + PATTERN_MIN_LEN + " symbols", Toast.LENGTH_SHORT).show();
            return;
        }
        String hash = sha256(mPatternInput.toString());
        android.content.SharedPreferences prefs = getSharedPreferences(PREF_VAULT_LOCK, MODE_PRIVATE);

        if (mIsSettingPattern) {
            if (mFirstPatternHash == null) {
                mFirstPatternHash = hash;
                clearPattern();
                mTvSubtitle.setText("CONFIRM PATTERN");
                flashText(mTvSubtitle, "#FFD700");
            } else {
                if (mFirstPatternHash.equals(hash)) {
                    prefs.edit().putString(KEY_PATTERN_HASH, hash)
                                .putBoolean(KEY_PATTERN_SET, true).apply();
                    flashSuccess();
                } else {
                    mFirstPatternHash = null;
                    clearPattern();
                    mTvSubtitle.setText("MISMATCH — TRY AGAIN");
                    flashText(mTvSubtitle, "#FF4444");
                    shakeDisplay();
                }
            }
        } else {
            String saved = prefs.getString(KEY_PATTERN_HASH, "");
            if (saved.equals(hash)) {
                flashSuccess();
            } else {
                clearPattern();
                shakeDisplay();
                flashText(mTvSubtitle, "#FF4444");
                mTvSubtitle.setText("WRONG PATTERN");
                mTvSubtitle.postDelayed(() -> mTvSubtitle.setText("ENTER PATTERN"), 1500);
            }
        }
    }

    private void flashSuccess() {
        mRootLayout.setBackgroundColor(Color.parseColor("#0A3322"));
        mTvSubtitle.setText("ACCESS GRANTED");
        mTvSubtitle.setTextColor(Color.parseColor("#44FF88"));
        mRootLayout.postDelayed(() -> {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra("vault_unlocked", true);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, 600);
    }

    private void flashText(TextView tv, String hex) {
        ObjectAnimator.ofArgb(tv, "textColor",
                Color.parseColor(hex), Color.parseColor("#88FFFFFF"))
                .setDuration(800).start();
    }

    private void shakeDisplay() {
        android.view.animation.TranslateAnimation shake =
                new android.view.animation.TranslateAnimation(-dp(8), dp(8), 0, 0);
        shake.setDuration(70);
        shake.setRepeatCount(5);
        shake.setRepeatMode(android.view.animation.Animation.REVERSE);
        mPatternDisplay.startAnimation(shake);
        ObjectAnimator.ofArgb(mPatternDisplay, "textColor",
                Color.parseColor("#FF3333"), Color.parseColor("#33FFFFFF"))
                .setDuration(500).start();
    }

    // ── Drawables ─────────────────────────────────────────────────────────

    /**
     * 3D raised bevel cell — dark base, lighter top/left highlight via layered gradient.
     * Matches the video's raised button look.
     */
    private android.graphics.drawable.Drawable createCellBg(boolean active) {
        if (active) {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            gd.setCornerRadius(dp(5));
            gd.setColor(Color.parseColor("#1A00DDCC"));
            gd.setStroke(dp(1), Color.parseColor("#00FFEE"));
            return gd;
        }
        // Layered: base dark + top highlight stripe = bevel illusion
        android.graphics.drawable.GradientDrawable base = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#1C2D3E"), Color.parseColor("#0A1520")});
        base.setCornerRadius(dp(5));
        base.setStroke(dp(1), Color.parseColor("#223344"));

        android.graphics.drawable.GradientDrawable highlight = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#33557799"), Color.parseColor("#00000000")});
        highlight.setCornerRadius(dp(5));

        android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{base, highlight});
        return layer;
    }

    private android.graphics.drawable.Drawable createRoundRect(int fill, int stroke,
            float radius, int strokeW) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radius);
        gd.setColor(fill);
        gd.setStroke(strokeW, stroke);
        return gd;
    }

    private android.graphics.drawable.Drawable createCircle(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        return gd;
    }

    // ── Animations ────────────────────────────────────────────────────────

    private void pulseView(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.25f);
        a.setDuration(850);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setInterpolator(new DecelerateInterpolator());
        a.start();
    }

    // ── Crypto ────────────────────────────────────────────────────────────

    private String sha256(String input) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = d.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return input; }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ── Ripple Overlay View ───────────────────────────────────────────────
    // Draws the white radial glow exactly where the finger lands — matches video

    static class RippleOverlayView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mRx, mRy, mRadius = 0f;
        private float mAlpha = 0f;
        private boolean mAnimating = false;
        private final android.os.Handler mH = new android.os.Handler(android.os.Looper.getMainLooper());

        RippleOverlayView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            // Pass all touches through
            setClickable(false);
            setFocusable(false);
        }

        void triggerRipple(float x, float y) {
            mRx = x;
            mRy = y;
            mRadius = 0f;
            mAlpha = 1f;
            mAnimating = true;
            animateFrame();
        }

        private void animateFrame() {
            if (!mAnimating) return;
            mRadius += 12f;
            mAlpha  -= 0.06f;
            if (mAlpha <= 0f) {
                mAlpha     = 0f;
                mAnimating = false;
            }
            invalidate();
            if (mAnimating) mH.postDelayed(this::animateFrame, 16);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (mAlpha <= 0f) return;
            // Radial gradient: white center → transparent edge
            RadialGradient rg = new RadialGradient(mRx, mRy, Math.max(mRadius, 1f),
                    new int[]{
                        Color.argb((int)(200 * mAlpha), 255, 255, 255),
                        Color.argb((int)(80  * mAlpha), 180, 220, 255),
                        Color.TRANSPARENT
                    },
                    new float[]{0f, 0.4f, 1f},
                    Shader.TileMode.CLAMP);
            mPaint.setShader(rg);
            c.drawCircle(mRx, mRy, mRadius, mPaint);
        }
    }
}
