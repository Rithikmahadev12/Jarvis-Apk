package com.jarvis.assist.ui;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.jarvis.assist.R;
import com.jarvis.assist.storage.SessionStore;

/**
 * VaultLockActivity — the alien-symbol grid keypad seen in the video.
 *
 * Layout:
 *   ┌─────────────────────────────┐
 *   │  VAULT          [ ● online ]│  ← header
 *   │  ENTER PATTERN              │
 *   │  ┌──────────────────────┐   │
 *   │  │  ☽  ⊕  ▷  ∑  ◈  ⊛  │   │
 *   │  │  λ  ∇  ⊞  ⊠  ◇  ⊗  │   │  ← 8×7 symbol grid
 *   │  │  … (56 symbols total)│   │
 *   │  └──────────────────────┘   │
 *   │  [  CLEAR  ]  [  ENTER  ]   │
 *   └─────────────────────────────┘
 *
 * Pattern = sequence of tapped symbol indices.
 * Stored pattern = SHA-256 hash in SharedPreferences.
 * On success → launches MainActivity (skips vault lock screen).
 */
public class VaultLockActivity extends AppCompatActivity {

    // ── Constants ──────────────────────────────────────────────────────────
    private static final String PREF_VAULT_LOCK   = "jarvis_vault_lock";
    private static final String KEY_PATTERN_HASH  = "pattern_hash";
    private static final String KEY_PATTERN_SET   = "pattern_set";
    private static final int    PATTERN_MIN_LEN   = 4;
    private static final int    GRID_COLS         = 7;
    private static final int    GRID_ROWS         = 8;

    // ── The 56 alien / esoteric symbols for the grid ──────────────────────
    private static final String[] SYMBOLS = {
        "☽","⊕","▷","∑","◈","⊛","λ",
        "∇","⊞","⊠","◇","⊗","ψ","∆",
        "⋈","◉","⊘","⊡","◬","⊜","⊝",
        "∓","⊻","⋄","◊","⊙","⊚","⊗",
        "☿","♃","♆","♇","♄","♀","♂",
        "⌖","⌘","⎔","⎚","⍥","⍟","⍰",
        "⌬","⌭","⌮","⌯","⌰","⌱","⌲",
        "∰","∯","∮","∭","∬","∫","∇"
    };

    // ── State ─────────────────────────────────────────────────────────────
    private final StringBuilder mPatternInput = new StringBuilder();
    private TextView[]          mSymbolCells;
    private TextView            mPatternDisplay;
    private boolean             mIsSettingPattern = false;
    private String              mFirstPatternHash = null;
    private SessionStore        mSession;

    // ── UI refs ───────────────────────────────────────────────────────────
    private TextView mTvSubtitle;
    private TextView mTvBtnClear;
    private TextView mTvBtnEnter;
    private LinearLayout mRootLayout;

    // ──────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        mSession = SessionStore.get(this);

        // Check if vault lock is already set up
        android.content.SharedPreferences prefs =
            getSharedPreferences(PREF_VAULT_LOCK, MODE_PRIVATE);
        boolean patternSet = prefs.getBoolean(KEY_PATTERN_SET, false);

        if (!patternSet) {
            mIsSettingPattern = true;
        }

        buildUI();
    }

    // ──────────────────────────────────────────────────────────────────────
    // UI Construction
    // ──────────────────────────────────────────────────────────────────────

    private void buildUI() {
        // Root — full black background
        mRootLayout = new LinearLayout(this);
        mRootLayout.setOrientation(LinearLayout.VERTICAL);
        mRootLayout.setBackgroundColor(Color.parseColor("#050508"));
        mRootLayout.setPadding(dp(16), dp(0), dp(16), dp(16));

        // ── HEADER ────────────────────────────────────────────────────────
        FrameLayout header = new FrameLayout(this);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        header.setLayoutParams(headerLp);

        // Top-left: VAULT title
        LinearLayout leftHeader = new LinearLayout(this);
        leftHeader.setOrientation(LinearLayout.VERTICAL);
        leftHeader.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        leftLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        leftHeader.setLayoutParams(leftLp);

        // Small top tag
        TextView tvTag = new TextView(this);
        tvTag.setText("JARVIS SYS · SECURE");
        tvTag.setTextColor(Color.parseColor("#33FFFFFF"));
        tvTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvTag.setLetterSpacing(0.18f);
        leftHeader.addView(tvTag);

        // Big VAULT text
        TextView tvVaultTitle = new TextView(this);
        tvVaultTitle.setText("VAULT");
        tvVaultTitle.setTextColor(Color.WHITE);
        tvVaultTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        tvVaultTitle.setTypeface(null, Typeface.BOLD);
        tvVaultTitle.setLetterSpacing(0.12f);
        leftHeader.addView(tvVaultTitle);

        header.addView(leftHeader);

        // Top-right: online indicator
        LinearLayout rightHeader = new LinearLayout(this);
        rightHeader.setOrientation(LinearLayout.HORIZONTAL);
        rightHeader.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        rightLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        rightHeader.setLayoutParams(rightLp);

        // Pulsing dot
        View dot = new View(this);
        dot.setBackgroundColor(Color.parseColor("#44FF88"));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(6);
        dot.setLayoutParams(dotLp);
        dot.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            // Make it round
            dot.setBackground(createCircleDrawable(Color.parseColor("#44FF88")));
        });
        pulseView(dot);
        rightHeader.addView(dot);

        TextView tvOnline = new TextView(this);
        tvOnline.setText("SECURED");
        tvOnline.setTextColor(Color.parseColor("#44FF88"));
        tvOnline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvOnline.setLetterSpacing(0.1f);
        rightHeader.addView(tvOnline);

        header.addView(rightHeader);
        mRootLayout.addView(header);

        // ── SUBTITLE ─────────────────────────────────────────────────────
        mTvSubtitle = new TextView(this);
        mTvSubtitle.setText(mIsSettingPattern ? "CREATE YOUR PATTERN" : "ENTER PATTERN");
        mTvSubtitle.setTextColor(Color.parseColor("#88FFFFFF"));
        mTvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mTvSubtitle.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dp(10);
        mTvSubtitle.setLayoutParams(subLp);
        mRootLayout.addView(mTvSubtitle);

        // ── PATTERN DISPLAY ───────────────────────────────────────────────
        mPatternDisplay = new TextView(this);
        mPatternDisplay.setText("·  ·  ·  ·");
        mPatternDisplay.setTextColor(Color.parseColor("#33FFFFFF"));
        mPatternDisplay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mPatternDisplay.setLetterSpacing(0.2f);
        LinearLayout.LayoutParams pdLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pdLp.bottomMargin = dp(12);
        mPatternDisplay.setLayoutParams(pdLp);
        mRootLayout.addView(mPatternDisplay);

        // ── SYMBOL GRID ───────────────────────────────────────────────────
        buildSymbolGrid();

        // ── BOTTOM BUTTONS ────────────────────────────────────────────────
        buildBottomButtons();

        setContentView(mRootLayout);
    }

    private void buildSymbolGrid() {
        // Outer card with dark border glow
        FrameLayout gridCard = new FrameLayout(this);
        gridCard.setBackground(createGridBackground());
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.bottomMargin = dp(12);
        gridCard.setLayoutParams(cardLp);
        gridCard.setPadding(dp(6), dp(6), dp(6), dp(6));

        // Grid layout
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(GRID_COLS);
        grid.setRowCount(GRID_ROWS);

        mSymbolCells = new TextView[SYMBOLS.length];

        for (int i = 0; i < SYMBOLS.length; i++) {
            final int idx = i;
            TextView cell = new TextView(this);
            cell.setText(SYMBOLS[i]);
            cell.setTextColor(Color.parseColor("#6699AACC")); // dim cyan-white
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(createCellBackground(false));
            cell.setClickable(true);
            cell.setFocusable(true);

            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.columnSpec = GridLayout.spec(i % GRID_COLS, 1f);
            glp.rowSpec    = GridLayout.spec(i / GRID_COLS, 1f);
            glp.setMargins(dp(2), dp(2), dp(2), dp(2));
            glp.width  = GridLayout.LayoutParams.FILL_PARENT;
            glp.height = 0; // fill by row weight
            cell.setLayoutParams(glp);

            cell.setOnClickListener(v -> onSymbolTapped(idx, cell));
            mSymbolCells[i] = cell;
            grid.addView(cell);
        }

        gridCard.addView(grid);
        mRootLayout.addView(gridCard);
    }

    private void buildBottomButtons() {
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        btnRow.setLayoutParams(rowLp);

        // CLEAR button
        mTvBtnClear = new TextView(this);
        mTvBtnClear.setText("CLEAR");
        mTvBtnClear.setTextColor(Color.parseColor("#AAFFFFFF"));
        mTvBtnClear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mTvBtnClear.setLetterSpacing(0.15f);
        mTvBtnClear.setGravity(Gravity.CENTER);
        mTvBtnClear.setBackground(createButtonBackground(false));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        clearLp.rightMargin = dp(8);
        mTvBtnClear.setLayoutParams(clearLp);
        mTvBtnClear.setClickable(true);
        mTvBtnClear.setFocusable(true);
        mTvBtnClear.setOnClickListener(v -> clearPattern());
        btnRow.addView(mTvBtnClear);

        // ENTER button
        mTvBtnEnter = new TextView(this);
        mTvBtnEnter.setText("ENTER");
        mTvBtnEnter.setTextColor(Color.parseColor("#FF3333"));
        mTvBtnEnter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mTvBtnEnter.setLetterSpacing(0.15f);
        mTvBtnEnter.setGravity(Gravity.CENTER);
        mTvBtnEnter.setBackground(createButtonBackground(true));
        LinearLayout.LayoutParams enterLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        mTvBtnEnter.setLayoutParams(enterLp);
        mTvBtnEnter.setClickable(true);
        mTvBtnEnter.setFocusable(true);
        mTvBtnEnter.setOnClickListener(v -> submitPattern());
        btnRow.addView(mTvBtnEnter);

        mRootLayout.addView(btnRow);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Symbol tap logic
    // ──────────────────────────────────────────────────────────────────────

    private void onSymbolTapped(int idx, TextView cell) {
        // Haptic
        Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vib != null && vib.hasVibrator()) {
            vib.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
        }

        // Flash the cell bright
        cell.setTextColor(Color.parseColor("#00FFCC"));
        cell.setBackground(createCellBackground(true));

        cell.postDelayed(() -> {
            cell.setTextColor(Color.parseColor("#9955AAEE"));
            cell.setBackground(createCellBackground(false));
        }, 300);

        // Append to pattern
        mPatternInput.append((char)('A' + idx));
        updatePatternDisplay();
    }

    private void updatePatternDisplay() {
        int len = mPatternInput.length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(i < len ? "●" : "·");
            if (i < len - 1) sb.append("  ");
        }
        // dim remaining dots
        if (len < 6) {
            if (sb.length() > 0 && len > 0) sb.append("  ");
            for (int i = len; i < Math.max(len + 1, 4); i++) {
                sb.append("·");
                if (i < Math.max(len + 1, 4) - 1) sb.append("  ");
            }
        }
        mPatternDisplay.setText(sb.toString());
        mPatternDisplay.setTextColor(Color.parseColor("#88FFFFFF"));
    }

    private void clearPattern() {
        mPatternInput.setLength(0);
        mPatternDisplay.setText("·  ·  ·  ·");
        mPatternDisplay.setTextColor(Color.parseColor("#33FFFFFF"));
        // Flash all cells dim
        for (TextView cell : mSymbolCells) {
            cell.setTextColor(Color.parseColor("#6699AACC"));
            cell.setBackground(createCellBackground(false));
        }
    }

    private void submitPattern() {
        String pattern = mPatternInput.toString();
        if (pattern.length() < PATTERN_MIN_LEN) {
            shakePatternDisplay();
            Toast.makeText(this, "Pattern too short — tap at least " + PATTERN_MIN_LEN + " symbols", Toast.LENGTH_SHORT).show();
            return;
        }

        String hash = sha256(pattern);
        android.content.SharedPreferences prefs =
                getSharedPreferences(PREF_VAULT_LOCK, MODE_PRIVATE);

        if (mIsSettingPattern) {
            // Setting phase 1 — confirm phase
            if (mFirstPatternHash == null) {
                mFirstPatternHash = hash;
                clearPattern();
                mTvSubtitle.setText("CONFIRM PATTERN");
                flashSubtitle("#FFD700");
            } else {
                // Phase 2
                if (mFirstPatternHash.equals(hash)) {
                    prefs.edit()
                         .putString(KEY_PATTERN_HASH, hash)
                         .putBoolean(KEY_PATTERN_SET, true)
                         .apply();
                    flashSuccess();
                } else {
                    mFirstPatternHash = null;
                    clearPattern();
                    mTvSubtitle.setText("MISMATCH — TRY AGAIN");
                    flashSubtitle("#FF4444");
                    shakePatternDisplay();
                }
            }
        } else {
            // Validate
            String saved = prefs.getString(KEY_PATTERN_HASH, "");
            if (saved.equals(hash)) {
                flashSuccess();
            } else {
                clearPattern();
                shakePatternDisplay();
                flashSubtitle("#FF4444");
                mTvSubtitle.setText("WRONG PATTERN");
                mTvSubtitle.postDelayed(() -> mTvSubtitle.setText("ENTER PATTERN"), 1500);
            }
        }
    }

    private void flashSuccess() {
        // Green flash, then launch vault dashboard
        mRootLayout.setBackgroundColor(Color.parseColor("#0A3322"));
        mTvSubtitle.setText("ACCESS GRANTED");
        mTvSubtitle.setTextColor(Color.parseColor("#44FF88"));
        mRootLayout.postDelayed(() -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("vault_unlocked", true);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, 600);
    }

    private void flashSubtitle(String hexColor) {
        ObjectAnimator anim = ObjectAnimator.ofArgb(
                mTvSubtitle, "textColor",
                Color.parseColor(hexColor),
                Color.parseColor("#88FFFFFF"));
        anim.setDuration(800);
        anim.start();
    }

    private void shakePatternDisplay() {
        android.view.animation.TranslateAnimation shake =
                new android.view.animation.TranslateAnimation(-dp(8), dp(8), 0, 0);
        shake.setDuration(80);
        shake.setRepeatCount(4);
        shake.setRepeatMode(android.view.animation.Animation.REVERSE);
        mPatternDisplay.startAnimation(shake);
        // Red flash
        ObjectAnimator colorAnim = ObjectAnimator.ofArgb(
                mPatternDisplay, "textColor",
                Color.parseColor("#FF4444"),
                Color.parseColor("#33FFFFFF"));
        colorAnim.setDuration(500);
        colorAnim.start();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Drawable helpers
    // ──────────────────────────────────────────────────────────────────────

    private android.graphics.drawable.Drawable createCellBackground(boolean active) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(4));
        if (active) {
            gd.setColor(Color.parseColor("#1A00FFCC"));
            gd.setStroke(dp(1), Color.parseColor("#00FFCC"));
        } else {
            gd.setColor(Color.parseColor("#0D1A2A"));
            gd.setStroke(dp(1), Color.parseColor("#1A3355"));
        }
        return gd;
    }

    private android.graphics.drawable.Drawable createGridBackground() {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(8));
        gd.setColor(Color.parseColor("#070B12"));
        gd.setStroke(dp(1), Color.parseColor("#1A3355"));
        return gd;
    }

    private android.graphics.drawable.Drawable createButtonBackground(boolean isEnter) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(6));
        if (isEnter) {
            gd.setColor(Color.parseColor("#1A220008"));
            gd.setStroke(dp(1), Color.parseColor("#993322"));
        } else {
            gd.setColor(Color.parseColor("#0F0F18"));
            gd.setStroke(dp(1), Color.parseColor("#333355"));
        }
        return gd;
    }

    private android.graphics.drawable.Drawable createCircleDrawable(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        return gd;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Animation helpers
    // ──────────────────────────────────────────────────────────────────────

    private void pulseView(View v) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.2f);
        anim.setDuration(900);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.start();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Crypto
    // ──────────────────────────────────────────────────────────────────────

    private String sha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return input; // fallback — shouldn't happen
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Util
    // ──────────────────────────────────────────────────────────────────────

    private int dp(int val) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, val, getResources().getDisplayMetrics());
    }
}
