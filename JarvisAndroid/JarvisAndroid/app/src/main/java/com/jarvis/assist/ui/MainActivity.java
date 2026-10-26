package com.jarvis.assist.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.jarvis.assist.R;
import com.jarvis.assist.network.JarvisClient;
import com.jarvis.assist.storage.SessionStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.hardware.SensorManager;
import android.view.OrientationEventListener;


/**
 * MainActivity — full Jarvis dashboard.
 *
 * Screen 1  URL Setup
 * Screen 2  Account Picker
 * Screen 3  Dashboard with:
 *             - PASSWORD VAULT  (local AES-256-GCM encrypted, category filter, search, reveal/hide)
 *             - SOLANA WALLET   (/api/wallet/:name)
 *             - MEMORY          (/api/memory/:name)
 *             - REMINDERS       (/api/reminders)
 *             - HABITS          (/api/habits/due)
 *             - TODOS           (/api/todos — night-shift research queue)
 *             - SESSION + ACCOUNT
 */
public class MainActivity extends AppCompatActivity {

    // ─── Keystore alias for vault AES-256-GCM key ──────────────────────────
    private static final String KEYSTORE_ALIAS  = "JarvisVaultKey";
    private static final String VAULT_PREFS     = "jarvis_vault_v1";
    private static final String VAULT_KEY_INDEX = "_vault_ids";
    private static final int    GCM_TAG_LEN     = 128;

    // ─── Vault state ────────────────────────────────────────────────────────
    private List<VaultEntry>       mAllVaultEntries    = new ArrayList<>();
    private List<VaultEntry>       mFilteredEntries    = new ArrayList<>();
    private String                 mVaultActiveCategory = "All";
    private String                 mVaultSearchQuery    = "";

    // ─── Session ────────────────────────────────────────────────────────────
    private SessionStore mSession;

    // ── Screen 1 ────────────────────────────────────────────────────────────
    private ScrollView  mLayoutSetup;
    private EditText    mEtUrl;
    private ProgressBar mProgressUrl;
    private TextView    mTvUrlError;
    private Button      mBtnConnect;

    // ── Screen 2 ────────────────────────────────────────────────────────────
    private ScrollView   mLayoutPicker;
    private LinearLayout mLayoutAccountsList;
    private EditText     mEtManualName;
    private ProgressBar  mProgressPicker;
    private TextView     mTvPickerError;
    private Button       mBtnSignInManual;
    private Button       mBtnChangeUrl;

    // ── Screen 3 ────────────────────────────────────────────────────────────
    private LinearLayout mLayoutDash;

    // Header
    private View         mStatusDot;
    private TextView     mTvStatusLabel;
    private FrameLayout  mAvatarCircle;
    private TextView     mTvAvatarInitial;
    private TextView     mHeaderSearchBtn;

    // Greeting
    private TextView mTvWelcome;
    private TextView mTvMemoryCount;
    // HUD dashboard extras (new layout)
    private TextView mTvHudName;
    private TextView mTvHudClock;
    private TextView mTvHudDate;
    private final android.os.Handler mClockHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // Tray
    private LinearLayout mTrayBtnTalk;
    private LinearLayout mTrayBtnVault;
    private LinearLayout mTrayBtnWallet;
    private LinearLayout mTrayBtnMemory;
    private LinearLayout mTrayBtnReminders;
    private LinearLayout mTrayBtnTodos;
    private LinearLayout mTrayBtnSettings;
    private TextView     mTrayMemoryBadge;
    private TextView     mTrayVaultBadge;
    private TextView     mTrayRemindersBadge;
    private TextView     mTrayTodosBadge;

    // Vault card
    private LinearLayout mCardVault;
    private LinearLayout mVaultEntriesContainer;
    private LinearLayout mVaultEmptyState;
    private LinearLayout mVaultAddBtn;
    private TextView     mTvVaultCountBadge;
    private EditText     mEtVaultSearch;
    private TextView     mVaultPillAll;
    private TextView     mVaultPillSocial;
    private TextView     mVaultPillFinance;
    private TextView     mVaultPillWork;
    private TextView     mVaultPillOther;

    // Wallet card
    private LinearLayout mCardWallet;
    private TextView     mTvSolBalance;
    private TextView     mTvSolUsd;
    private TextView     mTvWalletAddress;
    private TextView     mTvCopyAddr;

    // Memory card
    private LinearLayout mCardMemory;
    private LinearLayout mMemoryItemsContainer;
    private TextView     mTvMemoryBadgeFull;
    private TextView     mTvMemoryEmpty;

    // Reminders card
    private LinearLayout mCardReminders;
    private LinearLayout mRemindersItemsContainer;
    private TextView     mTvRemindersEmpty;
    private ProgressBar  mProgressReminders;

    // Habits card
    private LinearLayout mCardHabits;
    private LinearLayout mHabitsItemsContainer;
    private TextView     mTvHabitsEmpty;
    private TextView     mTvHabitsNudge;

    // Todos card
    private LinearLayout mCardTodos;
    private LinearLayout mTodosItemsContainer;
    private TextView     mTvTodosEmpty;
    private TextView     mTvTodosCountBadge;

    // Session card
    private TextView mTvConnectedUrl;
    private TextView mTvSessionId;

    // Account card
    private LinearLayout mBtnSwitchAccount;
    private LinearLayout mBtnResetAll;

    // Mic bottom bar
    private Button mBtnTestVoice;

    // Main scroll
    private ScrollView mDashScrollView;

    // Auto-landscape listener
    private OrientationEventListener mOrientationListener;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        mSession = SessionStore.get(this);

        // ── VAULT GATE — must arrive with vault_unlocked=true from VaultLockActivity ──
        boolean vaultUnlocked = getIntent() != null && getIntent().getBooleanExtra("vault_unlocked", false);
        if (!vaultUnlocked) {
            startActivity(new Intent(this, VaultLockActivity.class));
            finish();
            return;
        }

        bindViews();
        ensureVaultKey();

        if (mSession.isSetupComplete()) {
            showDashboard();
        } else if (mSession.isUrlSaved()) {
            showAccountPicker();
        } else {
            showUrlSetup();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mOrientationListener != null) mOrientationListener.disable();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mClockHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-enable only when we're on the dashboard
        if (mOrientationListener != null && mLayoutDash != null
                && mLayoutDash.getVisibility() == android.view.View.VISIBLE) {
            if (mOrientationListener.canDetectOrientation()) mOrientationListener.enable();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bind views
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        // Screen 1
        mLayoutSetup = findViewById(R.id.layout_setup);
        mEtUrl       = findViewById(R.id.et_jarvis_url);
        mProgressUrl = findViewById(R.id.progress_url);
        mTvUrlError  = findViewById(R.id.tv_url_error);
        mBtnConnect  = findViewById(R.id.btn_connect_url);
        mBtnConnect.setOnClickListener(v -> attemptUrlConnect());

        // Screen 2
        mLayoutPicker       = findViewById(R.id.layout_account_picker);
        mLayoutAccountsList = findViewById(R.id.layout_accounts_list);
        mEtManualName       = findViewById(R.id.et_manual_name);
        mProgressPicker     = findViewById(R.id.progress_picker);
        mTvPickerError      = findViewById(R.id.tv_picker_error);
        mBtnSignInManual    = findViewById(R.id.btn_sign_in_manual);
        mBtnChangeUrl       = findViewById(R.id.btn_change_url);
        mBtnSignInManual.setOnClickListener(v -> attemptManualSignIn());
        mBtnChangeUrl.setOnClickListener(v -> { mSession.clearAll(); showUrlSetup(); });

        // Screen 3 shell
        mLayoutDash = findViewById(R.id.layout_dashboard);

        // Header
        mStatusDot       = findViewById(R.id.status_dot);
        mTvStatusLabel   = findViewById(R.id.tv_status_label);
        mAvatarCircle    = findViewById(R.id.avatar_circle);
        mTvAvatarInitial = findViewById(R.id.tv_avatar_initial);
        mHeaderSearchBtn = findViewById(R.id.header_search_btn);
        mAvatarCircle.setOnClickListener(v -> showAccountMenu());
        mHeaderSearchBtn.setOnClickListener(v -> showVaultSearchDialog());

        // Greeting + HUD extras
        mTvWelcome     = findViewById(R.id.tv_welcome);
        mTvMemoryCount = findViewById(R.id.tv_memory_count);
        mTvHudName     = findViewById(R.id.tv_hud_name);
        mTvHudClock    = findViewById(R.id.tv_hud_clock);
        mTvHudDate     = findViewById(R.id.tv_hud_date);
        startHudClock();

        // Tray
        mTrayBtnTalk      = findViewById(R.id.tray_btn_talk);
        mTrayBtnVault     = findViewById(R.id.tray_btn_vault);
        mTrayBtnWallet    = findViewById(R.id.tray_btn_wallet);
        mTrayBtnMemory    = findViewById(R.id.tray_btn_memory);
        mTrayBtnReminders = findViewById(R.id.tray_btn_reminders);
        mTrayBtnTodos     = findViewById(R.id.tray_btn_todos);
        mTrayBtnSettings  = findViewById(R.id.tray_btn_settings);
        mTrayMemoryBadge  = findViewById(R.id.tray_memory_badge);
        mTrayVaultBadge   = findViewById(R.id.tray_vault_badge);
        mTrayRemindersBadge = findViewById(R.id.tray_reminders_badge);
        mTrayTodosBadge   = findViewById(R.id.tray_todos_badge);

        mTrayBtnTalk.setOnClickListener(v -> startActivity(new Intent(this, VoiceOverlayActivity.class)));
        mTrayBtnVault.setOnClickListener(v -> scrollToCard(mCardVault));
        mTrayBtnWallet.setOnClickListener(v -> scrollToCard(mCardWallet));
        mTrayBtnMemory.setOnClickListener(v -> scrollToCard(mCardMemory));
        mTrayBtnReminders.setOnClickListener(v -> scrollToCard(mCardReminders));
        mTrayBtnTodos.setOnClickListener(v -> scrollToCard(mCardTodos));
        mTrayBtnSettings.setOnClickListener(v -> showAccountMenu());

        // HUD mode button
        LinearLayout trayBtnHud = findViewById(R.id.tray_btn_hud);
        if (trayBtnHud != null) trayBtnHud.setOnClickListener(v -> startActivity(new Intent(this, LandscapeHudActivity.class)));

        // ── VAULT card ────────────────────────────────────────────────────
        mCardVault             = findViewById(R.id.card_vault);
        mVaultEntriesContainer = findViewById(R.id.vault_entries_container);
        mVaultEmptyState       = findViewById(R.id.vault_empty_state);
        mVaultAddBtn           = findViewById(R.id.vault_add_btn);
        mTvVaultCountBadge     = findViewById(R.id.tv_vault_count_badge);
        mEtVaultSearch         = findViewById(R.id.et_vault_search);
        mVaultPillAll          = findViewById(R.id.vault_pill_all);
        mVaultPillSocial       = findViewById(R.id.vault_pill_social);
        mVaultPillFinance      = findViewById(R.id.vault_pill_finance);
        mVaultPillWork         = findViewById(R.id.vault_pill_work);
        mVaultPillOther        = findViewById(R.id.vault_pill_other);

        mVaultAddBtn.setOnClickListener(v -> showAddVaultEntryDialog(null));

        // Category pill clicks
        mVaultPillAll.setOnClickListener(v -> { mVaultActiveCategory = "All"; applyVaultFilter(); updateCategoryPillStyles(); });
        mVaultPillSocial.setOnClickListener(v -> { mVaultActiveCategory = "Social"; applyVaultFilter(); updateCategoryPillStyles(); });
        mVaultPillFinance.setOnClickListener(v -> { mVaultActiveCategory = "Finance"; applyVaultFilter(); updateCategoryPillStyles(); });
        mVaultPillWork.setOnClickListener(v -> { mVaultActiveCategory = "Work"; applyVaultFilter(); updateCategoryPillStyles(); });
        mVaultPillOther.setOnClickListener(v -> { mVaultActiveCategory = "Other"; applyVaultFilter(); updateCategoryPillStyles(); });

        // Search watcher
        mEtVaultSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                mVaultSearchQuery = s.toString().trim().toLowerCase();
                applyVaultFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // ── WALLET card ───────────────────────────────────────────────────
        mCardWallet      = findViewById(R.id.card_wallet);
        mTvSolBalance    = findViewById(R.id.tv_sol_balance);
        mTvSolUsd        = findViewById(R.id.tv_sol_usd);
        mTvWalletAddress = findViewById(R.id.tv_wallet_address);
        mTvCopyAddr      = findViewById(R.id.tv_copy_addr);
        mTvCopyAddr.setOnClickListener(v -> copyWalletAddress());
        mCardWallet.setOnClickListener(v -> refreshWallet());

        // ── MEMORY card ───────────────────────────────────────────────────
        mCardMemory           = findViewById(R.id.card_memory);
        mMemoryItemsContainer = findViewById(R.id.memory_items_container);
        mTvMemoryBadgeFull    = findViewById(R.id.tv_memory_badge_full);
        mTvMemoryEmpty        = findViewById(R.id.tv_memory_empty);

        // ── REMINDERS card ────────────────────────────────────────────────
        mCardReminders            = findViewById(R.id.card_reminders);
        mRemindersItemsContainer  = findViewById(R.id.reminders_items_container);
        mTvRemindersEmpty         = findViewById(R.id.tv_reminders_empty);
        mProgressReminders        = findViewById(R.id.progress_reminders);

        // ── HABITS card ───────────────────────────────────────────────────
        mCardHabits           = findViewById(R.id.card_habits);
        mHabitsItemsContainer = findViewById(R.id.habits_items_container);
        mTvHabitsEmpty        = findViewById(R.id.tv_habits_empty);
        mTvHabitsNudge        = findViewById(R.id.tv_habits_nudge);

        // ── TODOS card ────────────────────────────────────────────────────
        mCardTodos            = findViewById(R.id.card_todos);
        mTodosItemsContainer  = findViewById(R.id.todos_items_container);
        mTvTodosEmpty         = findViewById(R.id.tv_todos_empty);
        mTvTodosCountBadge    = findViewById(R.id.tv_todos_count_badge);

        // Session / Account
        mTvConnectedUrl   = findViewById(R.id.tv_connected_url);
        mTvSessionId      = findViewById(R.id.tv_session_id);
        mBtnSwitchAccount = findViewById(R.id.btn_switch_account);
        mBtnResetAll      = findViewById(R.id.btn_reset_settings);
        mBtnSwitchAccount.setOnClickListener(v -> { mSession.clearAccount(); showAccountPicker(); });
        mBtnResetAll.setOnClickListener(v -> confirmFullReset());

        // Mic
        mBtnTestVoice = findViewById(R.id.btn_test_voice);
        mBtnTestVoice.setOnClickListener(v -> startActivity(new Intent(this, VoiceOverlayActivity.class)));

        // Scroll view resolved lazily
        mDashScrollView = null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screen 1 — URL Setup
    // ─────────────────────────────────────────────────────────────────────────

    private void showUrlSetup() {
        mLayoutSetup.setVisibility(View.VISIBLE);
        mLayoutPicker.setVisibility(View.GONE);
        mLayoutDash.setVisibility(View.GONE);
        if (TextUtils.isEmpty(mEtUrl.getText())) mEtUrl.setText("https://mcujarvis.onrender.com");
        mTvUrlError.setVisibility(View.GONE);
    }

    private void attemptUrlConnect() {
        String raw = mEtUrl.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            mTvUrlError.setText("Enter your Jarvis server URL");
            mTvUrlError.setVisibility(View.VISIBLE);
            return;
        }
        if (!raw.startsWith("http")) raw = "https://" + raw;
        final String url = raw;
        mTvUrlError.setVisibility(View.GONE);
        mProgressUrl.setVisibility(View.VISIBLE);
        mBtnConnect.setEnabled(false);
        JarvisClient.ping(url, new JarvisClient.SimpleCallback() {
            @Override public void onSuccess() {
                runOnUiThread(() -> {
                    mProgressUrl.setVisibility(View.GONE);
                    mBtnConnect.setEnabled(true);
                    mSession.saveUrl(url);
                    showAccountPicker();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    mProgressUrl.setVisibility(View.GONE);
                    mBtnConnect.setEnabled(true);
                    mTvUrlError.setText("Can't reach Jarvis: " + error + "\n\nSave anyway?");
                    mTvUrlError.setVisibility(View.VISIBLE);
                    mBtnConnect.setText("Save Anyway");
                    mBtnConnect.setOnClickListener(vv -> {
                        mSession.saveUrl(url);
                        mBtnConnect.setText("Connect");
                        mBtnConnect.setOnClickListener(v2 -> attemptUrlConnect());
                        showAccountPicker();
                    });
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screen 2 — Account Picker
    // ─────────────────────────────────────────────────────────────────────────

    private void showAccountPicker() {
        mLayoutSetup.setVisibility(View.GONE);
        mLayoutPicker.setVisibility(View.VISIBLE);
        mLayoutDash.setVisibility(View.GONE);
        mLayoutAccountsList.removeAllViews();
        mTvPickerError.setVisibility(View.GONE);
        mEtManualName.setText("");
        mProgressPicker.setVisibility(View.VISIBLE);

        JarvisClient.fetchProfiles(mSession.getJarvisUrl(), new JarvisClient.ProfilesCallback() {
            @Override public void onSuccess(List<JarvisClient.JarvisProfile> profiles) {
                runOnUiThread(() -> {
                    mProgressPicker.setVisibility(View.GONE);
                    if (profiles.isEmpty()) showPickerError("No accounts on server. Enter your name below.");
                    else buildAccountCards(profiles);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    mProgressPicker.setVisibility(View.GONE);
                    showPickerError("Could not load accounts: " + error + "\nEnter name below.");
                });
            }
        });
    }

    private void buildAccountCards(List<JarvisClient.JarvisProfile> profiles) {
        mLayoutAccountsList.removeAllViews();
        int dpPad = dp(14), dpMrg = dp(10);
        for (JarvisClient.JarvisProfile profile : profiles) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.parseColor(profile.isOwner() ? "#1A1500" : "#0E0E16"));
            card.setPadding(dpPad, dpPad, dpPad, dpPad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dpMrg;
            card.setLayoutParams(lp);
            TextView tvName = new TextView(this);
            tvName.setText(profile.name + (profile.isOwner() ? "  ★" : ""));
            tvName.setTextColor(Color.WHITE);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvName.setTypeface(null, Typeface.BOLD);
            card.addView(tvName);
            if (!TextUtils.isEmpty(profile.title) && !profile.title.equals("Sir")) {
                TextView tvTitle = new TextView(this);
                tvTitle.setText(profile.title);
                tvTitle.setTextColor(Color.parseColor("#66FFFFFF"));
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                card.addView(tvTitle);
            }
            Button btn = new Button(this);
            btn.setText("Sign in as " + profile.name);
            btn.setTextColor(Color.BLACK);
            btn.setBackgroundColor(Color.parseColor(profile.isOwner() ? "#FFD700" : "#FFFFFF"));
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnLp.topMargin = dp(10);
            btn.setLayoutParams(btnLp);
            final JarvisClient.JarvisProfile fp = profile;
            btn.setOnClickListener(v -> signInWithProfile(fp.name));
            card.addView(btn);
            mLayoutAccountsList.addView(card);
        }
    }

    private void showPickerError(String msg) {
        mTvPickerError.setText(msg);
        mTvPickerError.setVisibility(View.VISIBLE);
    }

    private void attemptManualSignIn() {
        String name = mEtManualName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) { showPickerError("Enter your name"); return; }
        signInWithProfile(name);
    }

    private void signInWithProfile(String name) {
        mTvPickerError.setVisibility(View.GONE);
        mProgressPicker.setVisibility(View.VISIBLE);
        setBtnPickerEnabled(false);

        JarvisClient.fetchProfile(mSession.getJarvisUrl(), name, new JarvisClient.ProfileCallback() {
            @Override public void onSuccess(JarvisClient.JarvisProfile profile) {
                JarvisClient.fetchMemories(mSession.getJarvisUrl(), profile.name, memories ->
                        runOnUiThread(() -> {
                            mProgressPicker.setVisibility(View.GONE);
                            setBtnPickerEnabled(true);
                            mSession.signIn(profile.name, profile.title, profile.role);
                            mSession.cacheMemoryCount(memories.length());
                            Toast.makeText(MainActivity.this,
                                    "Welcome back, " + profile.name + " ✓", Toast.LENGTH_SHORT).show();
                            showDashboard();
                        }));
            }
            @Override public void onNotFound() {
                runOnUiThread(() -> {
                    mProgressPicker.setVisibility(View.GONE);
                    setBtnPickerEnabled(true);
                    showPickerError("No account found for \"" + name + "\". Check spelling.");
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    mProgressPicker.setVisibility(View.GONE);
                    setBtnPickerEnabled(true);
                    showPickerError("Can't reach server: " + error + "\n\nTap 'Save Offline'.");
                    mBtnSignInManual.setText("Save Offline");
                    mBtnSignInManual.setOnClickListener(v -> {
                        mSession.signIn(name, "Sir", "");
                        mBtnSignInManual.setText("Sign In");
                        mBtnSignInManual.setOnClickListener(v2 -> attemptManualSignIn());
                        showDashboard();
                    });
                });
            }
        });
    }

    private void setBtnPickerEnabled(boolean enabled) {
        mBtnSignInManual.setEnabled(enabled);
        mBtnChangeUrl.setEnabled(enabled);
        for (int i = 0; i < mLayoutAccountsList.getChildCount(); i++)
            mLayoutAccountsList.getChildAt(i).setEnabled(enabled);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screen 3 — Dashboard
    // ─────────────────────────────────────────────────────────────────────────

    private void showDashboard() {
        mLayoutSetup.setVisibility(View.GONE);
        mLayoutPicker.setVisibility(View.GONE);
        mLayoutDash.setVisibility(View.VISIBLE);

        String name    = mSession.getUserName();
        String initial = (name != null && name.length() > 0)
                ? String.valueOf(name.charAt(0)).toUpperCase() : "J";
        mTvAvatarInitial.setText(initial);
        // HUD-style greeting (matches HomeHudActivity GlassCardView)
        mTvWelcome.setText(getHudGreeting());
        if (mTvHudName != null)
            mTvHudName.setText(name != null ? name.toUpperCase(java.util.Locale.US) : "OPERATOR");
        mTvConnectedUrl.setText(mSession.getJarvisUrl());
        mTvSessionId.setText(mSession.getSessionId());

        int cached = mSession.getLastMemoryCount();
        updateMemoryCountLabel(cached);
        updateTrayMemoryBadge(cached);

        // Resolve main scroll view
        mDashScrollView = findViewById(R.id.dash_scroll_view);

        // ✅ FIX: auto-launch landscape HUD when phone is physically rotated
        // instead of requiring a button tap.
        if (mOrientationListener == null) {
            mOrientationListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
                private boolean mInLandscape = false;
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == ORIENTATION_UNKNOWN) return;
                    // Landscape = phone rotated 90° or 270° (with ±30° tolerance)
                    boolean nowLandscape = (orientation > 60 && orientation < 120)
                                       || (orientation > 240 && orientation < 300);
                    if (nowLandscape && !mInLandscape) {
                        mInLandscape = true;
                        startActivity(new Intent(MainActivity.this, LandscapeHudActivity.class));
                    } else if (!nowLandscape) {
                        mInLandscape = false;
                    }
                }
            };
        }
        if (mOrientationListener.canDetectOrientation()) {
            mOrientationListener.enable();
        }

        // Load everything in parallel
        loadVaultEntries();
        refreshMemories(name);
        refreshWallet();
        refreshReminders();
        refreshHabits();
        refreshTodos();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAULT — local encrypted storage (AES-256-GCM via Android Keystore)
    // ─────────────────────────────────────────────────────────────────────────

    /** Model for a single vault entry */
    static class VaultEntry {
        String id;
        String siteName;
        String username;
        String passwordEnc;   // base64(iv + ciphertext)
        String category;      // "Social" | "Finance" | "Work" | "Other"
        String emoji;         // auto-assigned icon per category
        boolean revealed;     // transient UI state

        VaultEntry(String id, String siteName, String username,
                   String passwordEnc, String category) {
            this.id          = id;
            this.siteName    = siteName;
            this.username    = username;
            this.passwordEnc = passwordEnc;
            this.category    = category;
            this.emoji       = emojiFor(category);
            this.revealed    = false;
        }

        static String emojiFor(String cat) {
            if (cat == null) return "🔑";
            switch (cat) {
                case "Social":  return "💬";
                case "Finance": return "💳";
                case "Work":    return "💼";
                default:        return "🔑";
            }
        }
    }

    /** Ensure the AES key exists in Keystore; generate if not. */
    private void ensureVaultKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(KEYSTORE_ALIAS)) {
                KeyGenerator kg = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                kg.init(new KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
                kg.generateKey();
            }
        } catch (Exception e) {
            // Keystore unavailable — vault will warn at runtime
        }
    }

    /** Encrypt a plaintext password → base64(iv[12] + ciphertext). */
    private String encryptPassword(String plaintext) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey key    = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
        Cipher    cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv         = cipher.getIV();   // 12 bytes
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined   = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    /** Decrypt a base64-encoded vault password. */
    private String decryptPassword(String encoded) throws Exception {
        byte[]    combined   = Base64.decode(encoded, Base64.NO_WRAP);
        byte[]    iv         = new byte[12];
        byte[]    ciphertext = new byte[combined.length - 12];
        System.arraycopy(combined, 0, iv, 0, 12);
        System.arraycopy(combined, 12, ciphertext, 0, ciphertext.length);
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey key    = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
        Cipher    cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    /** Persist a vault entry to SharedPreferences as JSON. */
    private void saveVaultEntry(VaultEntry entry) {
        SharedPreferences prefs = getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", entry.id);
            obj.put("siteName", entry.siteName);
            obj.put("username", entry.username);
            obj.put("passwordEnc", entry.passwordEnc);
            obj.put("category", entry.category);
            prefs.edit().putString(entry.id, obj.toString()).apply();
            // Update index
            String rawIds = prefs.getString(VAULT_KEY_INDEX, "[]");
            JSONArray ids = new JSONArray(rawIds);
            // Avoid duplicates
            boolean found = false;
            for (int i = 0; i < ids.length(); i++) {
                if (entry.id.equals(ids.optString(i))) { found = true; break; }
            }
            if (!found) {
                ids.put(entry.id);
                prefs.edit().putString(VAULT_KEY_INDEX, ids.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    /** Delete a vault entry from SharedPreferences. */
    private void deleteVaultEntry(String entryId) {
        SharedPreferences prefs = getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE);
        prefs.edit().remove(entryId).apply();
        try {
            String rawIds = prefs.getString(VAULT_KEY_INDEX, "[]");
            JSONArray ids = new JSONArray(rawIds);
            JSONArray newIds = new JSONArray();
            for (int i = 0; i < ids.length(); i++) {
                if (!entryId.equals(ids.optString(i))) newIds.put(ids.optString(i));
            }
            prefs.edit().putString(VAULT_KEY_INDEX, newIds.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Load all vault entries from SharedPreferences into mAllVaultEntries. */
    private void loadVaultEntries() {
        mAllVaultEntries.clear();
        SharedPreferences prefs = getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE);
        try {
            String   rawIds = prefs.getString(VAULT_KEY_INDEX, "[]");
            JSONArray ids   = new JSONArray(rawIds);
            for (int i = 0; i < ids.length(); i++) {
                String id  = ids.optString(i);
                String raw = prefs.getString(id, null);
                if (raw == null) continue;
                JSONObject obj   = new JSONObject(raw);
                VaultEntry entry = new VaultEntry(
                        obj.optString("id", id),
                        obj.optString("siteName", ""),
                        obj.optString("username", ""),
                        obj.optString("passwordEnc", ""),
                        obj.optString("category", "Other"));
                mAllVaultEntries.add(entry);
            }
        } catch (Exception ignored) {}
        applyVaultFilter();
        updateVaultBadges();
    }

    /** Apply category + search filter then re-render. */
    private void applyVaultFilter() {
        mFilteredEntries.clear();
        for (VaultEntry e : mAllVaultEntries) {
            boolean catMatch = "All".equals(mVaultActiveCategory)
                    || mVaultActiveCategory.equals(e.category);
            boolean searchMatch = mVaultSearchQuery.isEmpty()
                    || e.siteName.toLowerCase().contains(mVaultSearchQuery)
                    || e.username.toLowerCase().contains(mVaultSearchQuery);
            if (catMatch && searchMatch) mFilteredEntries.add(e);
        }
        renderVaultEntries();
    }

    /** Rebuild the vault entries list in the UI. */
    private void renderVaultEntries() {
        if (mVaultEntriesContainer == null) return;
        mVaultEntriesContainer.removeAllViews();

        if (mFilteredEntries.isEmpty()) {
            mVaultEmptyState.setVisibility(View.VISIBLE);
            return;
        }
        mVaultEmptyState.setVisibility(View.GONE);

        for (int idx = 0; idx < mFilteredEntries.size(); idx++) {
            VaultEntry entry = mFilteredEntries.get(idx);
            mVaultEntriesContainer.addView(buildVaultEntryRow(entry, idx == 0));
        }
    }

    /** Build a single vault entry row view. */
    private View buildVaultEntryRow(VaultEntry entry, boolean isFirst) {
        // Outer container
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(getDrawable(R.drawable.vault_entry_bg));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(10);
        if (!isFirst) rowLp.topMargin = dp(0);
        row.setLayoutParams(rowLp);

        // Top row: emoji + siteName + username
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        // Emoji icon
        TextView tvEmoji = new TextView(this);
        tvEmoji.setText(entry.emoji);
        tvEmoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tvEmoji.setPadding(0, 0, dp(10), 0);
        topRow.addView(tvEmoji);

        // Names column
        LinearLayout namesCol = new LinearLayout(this);
        namesCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ncLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        namesCol.setLayoutParams(ncLp);

        TextView tvSiteName = new TextView(this);
        tvSiteName.setText(entry.siteName);
        tvSiteName.setTextColor(Color.WHITE);
        tvSiteName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvSiteName.setTypeface(null, Typeface.BOLD);
        namesCol.addView(tvSiteName);

        TextView tvUsername = new TextView(this);
        tvUsername.setText(entry.username);
        tvUsername.setTextColor(Color.parseColor("#88FFFFFF"));
        tvUsername.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        namesCol.addView(tvUsername);

        topRow.addView(namesCol);

        // Category pill (top right)
        TextView tvCat = new TextView(this);
        tvCat.setText(entry.category);
        tvCat.setTextColor(Color.parseColor("#9945FF"));
        tvCat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvCat.setPadding(dp(8), dp(3), dp(8), dp(3));
        tvCat.setBackground(getDrawable(R.drawable.pill_dark));
        topRow.addView(tvCat);

        row.addView(topRow);

        // ── Password row ──────────────────────────────────────────────────
        LinearLayout pwRow = new LinearLayout(this);
        pwRow.setOrientation(LinearLayout.HORIZONTAL);
        pwRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pwRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pwRowLp.topMargin = dp(10);
        pwRow.setLayoutParams(pwRowLp);
        pwRow.setBackground(getDrawable(R.drawable.pill_dark));
        pwRow.setPadding(dp(10), dp(8), dp(8), dp(8));

        // Masked / revealed password text
        TextView tvPassword = new TextView(this);
        tvPassword.setText(entry.revealed ? tryDecrypt(entry.passwordEnc) : "••••••••••••");
        tvPassword.setTextColor(entry.revealed
                ? Color.parseColor("#44FF88") : Color.parseColor("#44FFFFFF"));
        tvPassword.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvPassword.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams tvPwLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvPassword.setLayoutParams(tvPwLp);
        tvPassword.setSingleLine(true);
        tvPassword.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pwRow.addView(tvPassword);

        // Action buttons: reveal | copy | delete
        TextView btnReveal = new TextView(this);
        btnReveal.setText(entry.revealed ? "🙈" : "👁");
        btnReveal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnReveal.setPadding(dp(6), dp(2), dp(4), dp(2));
        btnReveal.setClickable(true);
        btnReveal.setFocusable(true);
        btnReveal.setOnClickListener(v -> {
            entry.revealed = !entry.revealed;
            // Animate alpha flash
            AlphaAnimation anim = new AlphaAnimation(0.3f, 1.0f);
            anim.setDuration(200);
            row.startAnimation(anim);
            renderVaultEntries(); // re-render
        });
        pwRow.addView(btnReveal);

        TextView btnCopy = new TextView(this);
        btnCopy.setText("📋");
        btnCopy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnCopy.setPadding(dp(4), dp(2), dp(4), dp(2));
        btnCopy.setClickable(true);
        btnCopy.setFocusable(true);
        btnCopy.setOnClickListener(v -> {
            String plain = tryDecrypt(entry.passwordEnc);
            if (plain.isEmpty()) { Toast.makeText(this, "Decryption failed", Toast.LENGTH_SHORT).show(); return; }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("password", plain));
            Toast.makeText(this, "Password copied ✓", Toast.LENGTH_SHORT).show();
        });
        pwRow.addView(btnCopy);

        TextView btnUsernamesCopy = new TextView(this);
        btnUsernamesCopy.setText("👤");
        btnUsernamesCopy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnUsernamesCopy.setPadding(dp(4), dp(2), dp(4), dp(2));
        btnUsernamesCopy.setClickable(true);
        btnUsernamesCopy.setFocusable(true);
        btnUsernamesCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("username", entry.username));
            Toast.makeText(this, "Username copied ✓", Toast.LENGTH_SHORT).show();
        });
        pwRow.addView(btnUsernamesCopy);

        TextView btnDelete = new TextView(this);
        btnDelete.setText("🗑");
        btnDelete.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnDelete.setPadding(dp(4), dp(2), dp(2), dp(2));
        btnDelete.setClickable(true);
        btnDelete.setFocusable(true);
        btnDelete.setOnClickListener(v -> confirmDeleteVaultEntry(entry));
        pwRow.addView(btnDelete);

        row.addView(pwRow);

        return row;
    }

    private String tryDecrypt(String enc) {
        try { return decryptPassword(enc); } catch (Exception e) { return ""; }
    }

    private void updateVaultBadges() {
        int count = mAllVaultEntries.size();
        if (mTvVaultCountBadge != null) mTvVaultCountBadge.setText(String.valueOf(count));
        if (mTrayVaultBadge   != null) mTrayVaultBadge.setText(count + " saved");
    }

    private void updateCategoryPillStyles() {
        int selectedBg = R.drawable.category_pill_selected;
        int normalBg   = R.drawable.pill_dark;
        int selColor   = Color.WHITE;
        int normColor  = Color.parseColor("#88FFFFFF");

        TextView[] pills = { mVaultPillAll, mVaultPillSocial, mVaultPillFinance,
                             mVaultPillWork, mVaultPillOther };
        String[]   cats  = { "All", "Social", "Finance", "Work", "Other" };

        for (int i = 0; i < pills.length; i++) {
            boolean selected = cats[i].equals(mVaultActiveCategory);
            pills[i].setBackground(getDrawable(selected ? selectedBg : normalBg));
            pills[i].setTextColor(selected ? selColor : normColor);
        }
    }

    /** Show "Add Password" dialog. */
    private void showAddVaultEntryDialog(VaultEntry editTarget) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(editTarget == null ? "Add Password" : "Edit Password");

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        form.setPadding(pad, dp(8), pad, 0);

        EditText etSite = makeDialogInput(form, "Site / App name", InputType.TYPE_CLASS_TEXT);
        EditText etUser = makeDialogInput(form, "Username / Email", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText etPw   = makeDialogInput(form, "Password",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // Category spinner (simple text toggle)
        final String[] cats      = { "Other", "Social", "Finance", "Work" };
        final int[]    catIndex  = { 0 };
        TextView tvCatToggle = new TextView(this);
        tvCatToggle.setText("Category: Other");
        tvCatToggle.setTextColor(Color.parseColor("#9945FF"));
        tvCatToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvCatToggle.setPadding(0, dp(12), 0, dp(4));
        tvCatToggle.setClickable(true);
        tvCatToggle.setFocusable(true);
        tvCatToggle.setOnClickListener(v -> {
            catIndex[0] = (catIndex[0] + 1) % cats.length;
            tvCatToggle.setText("Category: " + cats[catIndex[0]]);
        });
        form.addView(tvCatToggle);

        if (editTarget != null) {
            etSite.setText(editTarget.siteName);
            etUser.setText(editTarget.username);
            // Leave password blank — user re-enters if they want to change it
            for (int i = 0; i < cats.length; i++) {
                if (cats[i].equals(editTarget.category)) { catIndex[0] = i; break; }
            }
            tvCatToggle.setText("Category: " + cats[catIndex[0]]);
        }

        builder.setView(form);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String site = etSite.getText().toString().trim();
            String user = etUser.getText().toString().trim();
            String pw   = etPw.getText().toString();
            String cat  = cats[catIndex[0]];

            if (site.isEmpty()) { Toast.makeText(this, "Site name required", Toast.LENGTH_SHORT).show(); return; }
            if (user.isEmpty()) { Toast.makeText(this, "Username required", Toast.LENGTH_SHORT).show(); return; }

            try {
                String encPw = pw.isEmpty() && editTarget != null
                        ? editTarget.passwordEnc
                        : encryptPassword(pw.isEmpty() ? "—" : pw);

                String id = editTarget != null ? editTarget.id
                        : "v_" + System.currentTimeMillis();

                VaultEntry entry = new VaultEntry(id, site, user, encPw, cat);
                saveVaultEntry(entry);

                // Update in-memory list
                if (editTarget != null) {
                    for (int i = 0; i < mAllVaultEntries.size(); i++) {
                        if (mAllVaultEntries.get(i).id.equals(id)) {
                            mAllVaultEntries.set(i, entry);
                            break;
                        }
                    }
                } else {
                    mAllVaultEntries.add(entry);
                }
                applyVaultFilter();
                updateVaultBadges();
                Toast.makeText(this, "Saved ✓", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Encryption failed: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private EditText makeDialogInput(LinearLayout parent, String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(Color.parseColor("#55FFFFFF"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        et.setLayoutParams(lp);
        parent.addView(et);
        return et;
    }

    private void confirmDeleteVaultEntry(VaultEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + entry.siteName + "?")
                .setMessage("This password will be permanently removed from your local vault.")
                .setPositiveButton("Delete", (d, w) -> {
                    deleteVaultEntry(entry.id);
                    mAllVaultEntries.remove(entry);
                    applyVaultFilter();
                    updateVaultBadges();
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Quick search dialog that sets the search query and scrolls to vault. */
    private void showVaultSearchDialog() {
        scrollToCard(mCardVault);
        if (mEtVaultSearch != null) {
            mEtVaultSearch.requestFocus();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Memory
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshMemories(String name) {
        JarvisClient.fetchMemories(mSession.getJarvisUrl(), name, memories -> {
            int count = memories.length();
            mSession.cacheMemoryCount(count);
            runOnUiThread(() -> {
                updateMemoryCountLabel(count);
                updateTrayMemoryBadge(count);
                mTvMemoryBadgeFull.setText(String.valueOf(count));
                renderMemoryItems(memories);
            });
        });
    }

    private void renderMemoryItems(JSONArray memories) {
        mMemoryItemsContainer.removeAllViews();
        if (memories.length() == 0) {
            mTvMemoryEmpty.setVisibility(View.VISIBLE);
            return;
        }
        mTvMemoryEmpty.setVisibility(View.GONE);
        int limit = Math.min(memories.length(), 8);
        for (int i = 0; i < limit; i++) {
            try {
                JSONObject mem     = memories.getJSONObject(i);
                String     content = mem.optString("content",
                        mem.optString("memory", mem.optString("text", "")));
                if (content.isEmpty()) continue;

                LinearLayout rowL = new LinearLayout(this);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                rowL.setGravity(Gravity.TOP);
                rowL.setPadding(0, i == 0 ? 0 : dp(10), 0, 0);

                TextView dot = new TextView(this);
                dot.setText("•");
                dot.setTextColor(Color.parseColor("#44FF88"));
                dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                dot.setPadding(0, 1, dp(8), 0);
                rowL.addView(dot);

                TextView tv = new TextView(this);
                tv.setText(content);
                tv.setTextColor(Color.parseColor("#CCFFFFFF"));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tv.setLineSpacing(0, 1.4f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tv.setLayoutParams(lp);
                rowL.addView(tv);

                mMemoryItemsContainer.addView(rowL);
            } catch (Exception ignored) {}
        }
        if (memories.length() > limit) {
            TextView more = new TextView(this);
            more.setText("+ " + (memories.length() - limit) + " more memories…");
            more.setTextColor(Color.parseColor("#44FFFFFF"));
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            more.setPadding(0, dp(10), 0, 0);
            mMemoryItemsContainer.addView(more);
        }
    }

    // ── HUD clock ticker (mirrors HomeHudActivity.startClock)
    private void startHudClock() {
        mClockHandler.post(new Runnable() {
            @Override public void run() {
                java.text.SimpleDateFormat timeFmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
                java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("dd MMM", java.util.Locale.US);
                java.util.Date now = new java.util.Date();
                if (mTvHudClock != null) mTvHudClock.setText(timeFmt.format(now));
                if (mTvHudDate  != null) mTvHudDate.setText(dateFmt.format(now).toUpperCase(java.util.Locale.US));
                if (mTvWelcome  != null) mTvWelcome.setText(getHudGreeting());
                mClockHandler.postDelayed(this, 30_000);
            }
        });
    }

    private String getHudGreeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (h < 12) return "GOOD MORNING,";
        if (h < 17) return "GOOD AFTERNOON,";
        if (h < 21) return "GOOD EVENING,";
        return "GOOD NIGHT,";
    }

    private void updateMemoryCountLabel(int count) {
        mTvMemoryCount.setText(count > 0
                ? "Jarvis remembers " + count + " thing" + (count == 1 ? "" : "s") + " about you"
                : "No memories yet");
    }

    private void updateTrayMemoryBadge(int count) {
        if (mTrayMemoryBadge != null)
            mTrayMemoryBadge.setText(count + " item" + (count == 1 ? "" : "s"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Wallet
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshWallet() {
        JarvisClient.fetchWallet(mSession.getJarvisUrl(), mSession.getUserName(),
                new JarvisClient.WalletCallback() {
                    @Override public void onSuccess(String address, double solBalance, double usdValue) {
                        runOnUiThread(() -> {
                            mTvSolBalance.setText(String.format(Locale.US, "◎ %.4f", solBalance));
                            mTvSolUsd.setText(String.format(Locale.US, "$ %.2f USD", usdValue));
                            mTvWalletAddress.setText(address);
                            mTvCopyAddr.setVisibility(View.VISIBLE);
                        });
                    }
                    @Override public void onNotConfigured() {
                        runOnUiThread(() -> {
                            mTvSolBalance.setText("—");
                            mTvSolUsd.setText("No wallet linked");
                            mTvWalletAddress.setText("Tap to configure");
                            mTvCopyAddr.setVisibility(View.GONE);
                        });
                    }
                    @Override public void onError(String error) {
                        runOnUiThread(() -> {
                            mTvSolBalance.setText("—");
                            mTvSolUsd.setText("Offline");
                            mTvWalletAddress.setText("Could not reach server");
                            mTvCopyAddr.setVisibility(View.GONE);
                        });
                    }
                });
    }

    private void copyWalletAddress() {
        String addr = mTvWalletAddress.getText().toString();
        if (TextUtils.isEmpty(addr) || addr.equals("Not connected")) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Solana address", addr));
        Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reminders
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshReminders() {
        if (mProgressReminders != null) {
            runOnUiThread(() -> mProgressReminders.setVisibility(View.VISIBLE));
        }
        JarvisClient.fetchReminders(mSession.getJarvisUrl(),
                new JarvisClient.RemindersCallback() {
                    @Override public void onSuccess(List<JarvisClient.JarvisReminder> reminders) {
                        runOnUiThread(() -> {
                            if (mProgressReminders != null)
                                mProgressReminders.setVisibility(View.GONE);
                            renderReminders(reminders);
                        });
                    }
                    @Override public void onError(String error) {
                        runOnUiThread(() -> {
                            if (mProgressReminders != null)
                                mProgressReminders.setVisibility(View.GONE);
                            renderReminders(new ArrayList<>());
                        });
                    }
                });
    }

    private void renderReminders(List<JarvisClient.JarvisReminder> reminders) {
        mRemindersItemsContainer.removeAllViews();
        if (reminders.isEmpty()) {
            mTvRemindersEmpty.setVisibility(View.VISIBLE);
            if (mTrayRemindersBadge != null) mTrayRemindersBadge.setText("0 set");
            return;
        }
        mTvRemindersEmpty.setVisibility(View.GONE);
        if (mTrayRemindersBadge != null)
            mTrayRemindersBadge.setText(reminders.size() + " set");

        for (int i = 0; i < reminders.size(); i++) {
            JarvisClient.JarvisReminder r = reminders.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(getDrawable(R.drawable.reminder_item_bg));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);

            // Type emoji
            String typeEmoji = "timer".equals(r.type) ? "⏱" :
                    "event".equals(r.type) ? "📅" : "⏰";
            TextView tvEmoji = new TextView(this);
            tvEmoji.setText(typeEmoji);
            tvEmoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            tvEmoji.setPadding(0, 0, dp(10), 0);
            row.addView(tvEmoji);

            // Label + time
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            col.setLayoutParams(colLp);

            TextView tvLabel = new TextView(this);
            tvLabel.setText(r.label);
            tvLabel.setTextColor(Color.WHITE);
            tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvLabel.setTypeface(null, Typeface.BOLD);
            col.addView(tvLabel);

            if (!TextUtils.isEmpty(r.fireAt)) {
                TextView tvTime = new TextView(this);
                tvTime.setText(formatIsoTime(r.fireAt));
                tvTime.setTextColor(Color.parseColor(r.isDue ? "#44FF88" : "#88FFFFFF"));
                tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                col.addView(tvTime);
            }
            row.addView(col);

            // Due badge
            if (r.isDue) {
                TextView tvDue = new TextView(this);
                tvDue.setText("DUE");
                tvDue.setTextColor(Color.parseColor("#44FF88"));
                tvDue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
                tvDue.setBackground(getDrawable(R.drawable.pill_green));
                tvDue.setPadding(dp(6), dp(2), dp(6), dp(2));
                row.addView(tvDue);
            }

            mRemindersItemsContainer.addView(row);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Habits
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshHabits() {
        String tz = TimeZone.getDefault().getID();
        JarvisClient.fetchHabits(
                mSession.getJarvisUrl(),
                mSession.getUserTitle(),
                mSession.getSessionId(),
                tz,
                nudge -> runOnUiThread(() -> renderHabits(nudge))
        );
    }

    private void renderHabits(String nudgePrompt) {
        mHabitsItemsContainer.removeAllViews();

        if (nudgePrompt != null && !nudgePrompt.isEmpty()) {
            // Jarvis has a habit nudge for today
            mTvHabitsEmpty.setVisibility(View.GONE);
            if (mTvHabitsNudge != null) mTvHabitsNudge.setVisibility(View.VISIBLE);

            LinearLayout nudgeRow = new LinearLayout(this);
            nudgeRow.setOrientation(LinearLayout.HORIZONTAL);
            nudgeRow.setGravity(Gravity.CENTER_VERTICAL);
            nudgeRow.setBackground(getDrawable(R.drawable.reminder_item_bg));
            nudgeRow.setPadding(dp(14), dp(12), dp(14), dp(12));

            TextView tvIcon = new TextView(this);
            tvIcon.setText("💡");
            tvIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            tvIcon.setPadding(0, 0, dp(10), 0);
            nudgeRow.addView(tvIcon);

            TextView tvNudge = new TextView(this);
            tvNudge.setText(nudgePrompt);
            tvNudge.setTextColor(Color.parseColor("#CCFFFFFF"));
            tvNudge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvNudge.setLineSpacing(0, 1.4f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvNudge.setLayoutParams(lp);
            nudgeRow.addView(tvNudge);
            mHabitsItemsContainer.addView(nudgeRow);
        } else {
            mTvHabitsEmpty.setVisibility(View.VISIBLE);
            if (mTvHabitsNudge != null) mTvHabitsNudge.setVisibility(View.GONE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Todos / Research Queue
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshTodos() {
        JarvisClient.fetchTodos(mSession.getJarvisUrl(), new JarvisClient.TodosCallback() {
            @Override public void onSuccess(List<JarvisClient.JarvisTodo> todos) {
                runOnUiThread(() -> renderTodos(todos));
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> renderTodos(new ArrayList<>()));
            }
        });
    }

    private void renderTodos(List<JarvisClient.JarvisTodo> todos) {
        mTodosItemsContainer.removeAllViews();
        int pending = 0;
        for (JarvisClient.JarvisTodo t : todos)
            if ("pending".equals(t.status) || "researching".equals(t.status)) pending++;

        if (mTvTodosCountBadge != null) mTvTodosCountBadge.setText(String.valueOf(todos.size()));
        if (mTrayTodosBadge    != null) mTrayTodosBadge.setText(pending + " pending");

        if (todos.isEmpty()) {
            mTvTodosEmpty.setVisibility(View.VISIBLE);
            return;
        }
        mTvTodosEmpty.setVisibility(View.GONE);

        for (JarvisClient.JarvisTodo todo : todos) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(getDrawable(R.drawable.todo_item_bg));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);

            // Status emoji
            String statusEmoji = "done".equals(todo.status) ? "✅" :
                    "researching".equals(todo.status) ? "🔍" : "📌";
            TextView tvIcon = new TextView(this);
            tvIcon.setText(statusEmoji);
            tvIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvIcon.setPadding(0, 0, dp(10), 0);
            row.addView(tvIcon);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            col.setLayoutParams(colLp);

            TextView tvText = new TextView(this);
            tvText.setText(todo.text);
            tvText.setTextColor("done".equals(todo.status)
                    ? Color.parseColor("#55FFFFFF") : Color.WHITE);
            tvText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            if ("done".equals(todo.status)) {
                tvText.setPaintFlags(tvText.getPaintFlags()
                        | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            }
            col.addView(tvText);

            if (!TextUtils.isEmpty(todo.addedAt)) {
                TextView tvAdded = new TextView(this);
                tvAdded.setText("Added " + formatIsoDate(todo.addedAt));
                tvAdded.setTextColor(Color.parseColor("#44FFFFFF"));
                tvAdded.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                col.addView(tvAdded);
            }
            row.addView(col);

            // Status pill
            String statusLabel = "done".equals(todo.status) ? "done"
                    : "researching".equals(todo.status) ? "running" : "queued";
            int statusColor = "done".equals(todo.status) ? Color.parseColor("#44FF88")
                    : "researching".equals(todo.status) ? Color.parseColor("#FFD700")
                    : Color.parseColor("#88FFFFFF");
            TextView tvStatus = new TextView(this);
            tvStatus.setText(statusLabel);
            tvStatus.setTextColor(statusColor);
            tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            tvStatus.setBackground(getDrawable(R.drawable.pill_dark));
            tvStatus.setPadding(dp(6), dp(2), dp(6), dp(2));
            row.addView(tvStatus);

            mTodosItemsContainer.addView(row);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Account menu
    // ─────────────────────────────────────────────────────────────────────────

    private void showAccountMenu() {
        new AlertDialog.Builder(this)
                .setTitle(mSession.getUserName())
                .setMessage("Connected to:\n" + mSession.getJarvisUrl()
                        + "\n\nSession: " + mSession.getSessionId())
                .setPositiveButton("Switch Account", (d, w) -> { mSession.clearAccount(); showAccountPicker(); })
                .setNeutralButton("Reset All", (d, w) -> confirmFullReset())
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmFullReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Everything?")
                .setMessage("Clears your saved URL and account. You'll go back to the start.")
                .setPositiveButton("Reset", (d, w) -> { mSession.clearAll(); showUrlSetup(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scroll helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void scrollToCard(View card) {
        if (mDashScrollView == null) {
            mDashScrollView = findViewById(R.id.dash_scroll_view);
        }
        if (mDashScrollView == null || card == null) return;
        mDashScrollView.post(() -> {
            int[] cardPos   = new int[2];
            int[] scrollPos = new int[2];
            card.getLocationInWindow(cardPos);
            mDashScrollView.getLocationInWindow(scrollPos);
            int scrollTo = mDashScrollView.getScrollY() + (cardPos[1] - scrollPos[1]) - dp(16);
            mDashScrollView.smoothScrollTo(0, Math.max(0, scrollTo));
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Date/time formatting helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String formatIsoTime(String iso) {
        try {
            SimpleDateFormat inFmt  = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            SimpleDateFormat inFmt2 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            inFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            inFmt2.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat outFmt = new SimpleDateFormat("MMM d, h:mm a", Locale.US);
            Date date;
            try { date = inFmt.parse(iso); }
            catch (Exception e2) { date = inFmt2.parse(iso); }
            return outFmt.format(date);
        } catch (Exception e) { return iso; }
    }

    private String formatIsoDate(String iso) {
        try {
            SimpleDateFormat inFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            inFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat outFmt = new SimpleDateFormat("MMM d", Locale.US);
            return outFmt.format(inFmt.parse(iso));
        } catch (Exception e) { return ""; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utils
    // ─────────────────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}
