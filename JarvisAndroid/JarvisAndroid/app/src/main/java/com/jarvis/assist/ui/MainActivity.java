package com.jarvis.assist.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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

import java.util.List;

/**
 * MainActivity — redesigned three-screen flow.
 *
 * Screen 1  URL Setup      (first ever launch or after full reset)
 * Screen 2  Account Picker  (fetches /api/profiles, shows cards)
 * Screen 3  Dashboard       (new UI: header, tray, cards for vault/memory/session)
 *             └─ Solana wallet card pulled from Jarvis server (/api/wallet/:name)
 *             └─ Memory card lists stored memories from /api/memories/:name
 *             └─ Tray: Talk, Wallet (scroll to), Memory (scroll to), Settings (dialog)
 */
public class MainActivity extends AppCompatActivity {

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

    // ── Screen 3: Dashboard shell ────────────────────────────────────────────
    private LinearLayout mLayoutDash;

    // Header
    private View     mStatusDot;
    private TextView mTvStatusLabel;
    private FrameLayout mAvatarCircle;
    private TextView mTvAvatarInitial;

    // Greeting
    private TextView mTvWelcome;
    private TextView mTvMemoryCount;     // greeting subtext

    // Tray
    private LinearLayout mTrayBtnTalk;
    private LinearLayout mTrayBtnWallet;
    private LinearLayout mTrayBtnMemory;
    private LinearLayout mTrayBtnSettings;
    private TextView     mTrayMemoryBadge;

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

    // Session card
    private TextView mTvConnectedUrl;
    private TextView mTvSessionId;

    // Account card actions
    private LinearLayout mBtnSwitchAccount;
    private LinearLayout mBtnResetAll;

    // Bottom bar
    private Button mBtnTestVoice;

    // Main scroll view inside dashboard (for tray-button scrolling)
    private ScrollView mDashScrollView;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        mSession = SessionStore.get(this);

        bindViews();

        if (mSession.isSetupComplete()) {
            showDashboard();
        } else if (mSession.isUrlSaved()) {
            showAccountPicker();
        } else {
            showUrlSetup();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bind all views
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        // Screen 1
        mLayoutSetup  = findViewById(R.id.layout_setup);
        mEtUrl        = findViewById(R.id.et_jarvis_url);
        mProgressUrl  = findViewById(R.id.progress_url);
        mTvUrlError   = findViewById(R.id.tv_url_error);
        mBtnConnect   = findViewById(R.id.btn_connect_url);
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
        mBtnChangeUrl.setOnClickListener(v -> {
            mSession.clearAll();
            showUrlSetup();
        });

        // Screen 3 — dashboard shell
        mLayoutDash = findViewById(R.id.layout_dashboard);

        // Header
        mStatusDot      = findViewById(R.id.status_dot);
        mTvStatusLabel  = findViewById(R.id.tv_status_label);
        mAvatarCircle   = findViewById(R.id.avatar_circle);
        mTvAvatarInitial = findViewById(R.id.tv_avatar_initial);
        mAvatarCircle.setOnClickListener(v -> showAccountMenu());

        // Greeting
        mTvWelcome     = findViewById(R.id.tv_welcome);
        mTvMemoryCount = findViewById(R.id.tv_memory_count);

        // Tray
        mTrayBtnTalk     = findViewById(R.id.tray_btn_talk);
        mTrayBtnWallet   = findViewById(R.id.tray_btn_wallet);
        mTrayBtnMemory   = findViewById(R.id.tray_btn_memory);
        mTrayBtnSettings = findViewById(R.id.tray_btn_settings);
        mTrayMemoryBadge = findViewById(R.id.tray_memory_badge);

        mTrayBtnTalk.setOnClickListener(v ->
                startActivity(new Intent(this, VoiceOverlayActivity.class)));
        mTrayBtnWallet.setOnClickListener(v -> scrollToCard(mCardWallet));
        mTrayBtnMemory.setOnClickListener(v -> scrollToCard(mCardMemory));
        mTrayBtnSettings.setOnClickListener(v -> showAccountMenu());

        // Wallet card
        mCardWallet       = findViewById(R.id.card_wallet);
        mTvSolBalance     = findViewById(R.id.tv_sol_balance);
        mTvSolUsd         = findViewById(R.id.tv_sol_usd);
        mTvWalletAddress  = findViewById(R.id.tv_wallet_address);
        mTvCopyAddr       = findViewById(R.id.tv_copy_addr);
        mTvCopyAddr.setOnClickListener(v -> copyWalletAddress());
        mCardWallet.setOnClickListener(v -> refreshWallet());

        // Memory card
        mCardMemory            = findViewById(R.id.card_memory);
        mMemoryItemsContainer  = findViewById(R.id.memory_items_container);
        mTvMemoryBadgeFull     = findViewById(R.id.tv_memory_badge_full);
        mTvMemoryEmpty         = findViewById(R.id.tv_memory_empty);

        // Session card
        mTvConnectedUrl = findViewById(R.id.tv_connected_url);
        mTvSessionId    = findViewById(R.id.tv_session_id);

        // Account actions
        mBtnSwitchAccount = findViewById(R.id.btn_switch_account);
        mBtnResetAll      = findViewById(R.id.btn_reset_settings);
        mBtnSwitchAccount.setOnClickListener(v -> {
            mSession.clearAccount();
            showAccountPicker();
        });
        mBtnResetAll.setOnClickListener(v -> confirmFullReset());

        // Mic bottom bar
        mBtnTestVoice = findViewById(R.id.btn_test_voice);
        mBtnTestVoice.setOnClickListener(v ->
                startActivity(new Intent(this, VoiceOverlayActivity.class)));

        // Grab the inner ScrollView for programmatic scrolling
        // It's the ScrollView child of layout_dashboard (index 2, after header + greeting group)
        mDashScrollView = null; // resolved lazily via post()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screen 1 — URL Setup
    // ─────────────────────────────────────────────────────────────────────────

    private void showUrlSetup() {
        mLayoutSetup.setVisibility(View.VISIBLE);
        mLayoutPicker.setVisibility(View.GONE);
        mLayoutDash.setVisibility(View.GONE);

        if (TextUtils.isEmpty(mEtUrl.getText())) {
            mEtUrl.setText("https://mcujarvis.onrender.com");
        }
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
                    if (profiles.isEmpty()) {
                        showPickerError("No accounts on server. Enter your name below.");
                    } else {
                        buildAccountCards(profiles);
                    }
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
        int dpPad = dp(14);
        int dpMrg = dp(10);

        for (JarvisClient.JarvisProfile profile : profiles) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.parseColor(profile.isOwner() ? "#1A1500" : "#0E0E16"));
            card.setPadding(dpPad, dpPad, dpPad, dpPad);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
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
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
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
                                    "Welcome back, " + profile.name + " ✓",
                                    Toast.LENGTH_SHORT).show();
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
        for (int i = 0; i < mLayoutAccountsList.getChildCount(); i++) {
            mLayoutAccountsList.getChildAt(i).setEnabled(enabled);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screen 3 — Dashboard
    // ─────────────────────────────────────────────────────────────────────────

    private void showDashboard() {
        mLayoutSetup.setVisibility(View.GONE);
        mLayoutPicker.setVisibility(View.GONE);
        mLayoutDash.setVisibility(View.VISIBLE);

        String name = mSession.getUserName();

        // Header avatar initial
        String initial = (name != null && name.length() > 0)
                ? String.valueOf(name.charAt(0)).toUpperCase() : "J";
        mTvAvatarInitial.setText(initial);

        // Greeting
        mTvWelcome.setText("Hello, " + name + (mSession.isOwner() ? " ★" : ""));

        // Session card
        mTvConnectedUrl.setText(mSession.getJarvisUrl());
        mTvSessionId.setText(mSession.getSessionId());

        // Initial memory count from cache
        int cached = mSession.getLastMemoryCount();
        updateMemoryCountLabel(cached);
        updateTrayMemoryBadge(cached);

        // Load full memory list + wallet concurrently
        refreshMemories(name);
        refreshWallet();
    }

    // ── Memory ────────────────────────────────────────────────────────────────

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

        int dpV = dp(12);
        int limit = Math.min(memories.length(), 8); // show up to 8 in card; full list on tap

        for (int i = 0; i < limit; i++) {
            try {
                JSONObject mem = memories.getJSONObject(i);
                String content = mem.optString("content",
                        mem.optString("memory",
                        mem.optString("text", "")));
                if (content.isEmpty()) continue;

                // Row container
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.TOP);
                row.setPadding(0, i == 0 ? 0 : dpV, 0, 0);

                // Bullet dot
                TextView dot = new TextView(this);
                dot.setText("•");
                dot.setTextColor(Color.parseColor("#44FF88"));
                dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                dot.setPadding(0, 1, dp(8), 0);
                row.addView(dot);

                // Memory text
                TextView tv = new TextView(this);
                tv.setText(content);
                tv.setTextColor(Color.parseColor("#CCFFFFFF"));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tv.setLineSpacing(0, 1.4f);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tv.setLayoutParams(lp);
                row.addView(tv);

                mMemoryItemsContainer.addView(row);

            } catch (Exception ignored) {}
        }

        // "And X more…" label if truncated
        if (memories.length() > limit) {
            TextView more = new TextView(this);
            more.setText("+ " + (memories.length() - limit) + " more memories…");
            more.setTextColor(Color.parseColor("#44FFFFFF"));
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            more.setPadding(0, dp(10), 0, 0);
            mMemoryItemsContainer.addView(more);
        }
    }

    private void updateMemoryCountLabel(int count) {
        mTvMemoryCount.setText(count > 0
                ? "Jarvis remembers " + count + " thing" + (count == 1 ? "" : "s") + " about you"
                : "No memories yet");
    }

    private void updateTrayMemoryBadge(int count) {
        mTrayMemoryBadge.setText(count + " item" + (count == 1 ? "" : "s"));
    }

    // ── Wallet ─────────────────────────────────────────────────────────────────

    private void refreshWallet() {
        String name = mSession.getUserName();
        String url  = mSession.getJarvisUrl();

        // Fetch wallet info from Jarvis backend at /api/wallet/:name
        // Expects JSON: { address, balance, usdValue } or { error }
        JarvisClient.fetchWallet(url, name, new JarvisClient.WalletCallback() {
            @Override
            public void onSuccess(String address, double solBalance, double usdValue) {
                runOnUiThread(() -> {
                    mTvSolBalance.setText(String.format("◎ %.4f", solBalance));
                    mTvSolUsd.setText(String.format("$ %.2f USD", usdValue));
                    mTvWalletAddress.setText(address);
                    mTvCopyAddr.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onNotConfigured() {
                runOnUiThread(() -> {
                    mTvSolBalance.setText("—");
                    mTvSolUsd.setText("No wallet linked");
                    mTvWalletAddress.setText("Tap to configure");
                    mTvCopyAddr.setVisibility(View.GONE);
                });
            }

            @Override
            public void onError(String error) {
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

    // ── Account menu ───────────────────────────────────────────────────────────

    private void showAccountMenu() {
        new AlertDialog.Builder(this)
                .setTitle(mSession.getUserName())
                .setMessage("Connected to:\n" + mSession.getJarvisUrl() +
                        "\n\nSession: " + mSession.getSessionId())
                .setPositiveButton("Switch Account", (d, w) -> {
                    mSession.clearAccount();
                    showAccountPicker();
                })
                .setNeutralButton("Reset All", (d, w) -> confirmFullReset())
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmFullReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Everything?")
                .setMessage("Clears your saved URL and account. You'll go back to the start.")
                .setPositiveButton("Reset", (d, w) -> {
                    mSession.clearAll();
                    showUrlSetup();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Scroll tray helper ──────────────────────────────────────────────────────

    private void scrollToCard(View card) {
        // Find the inner ScrollView at runtime (3rd child of layout_dashboard)
        if (mDashScrollView == null) {
            for (int i = 0; i < mLayoutDash.getChildCount(); i++) {
                View child = mLayoutDash.getChildAt(i);
                if (child instanceof ScrollView) {
                    mDashScrollView = (ScrollView) child;
                    break;
                }
            }
        }
        if (mDashScrollView == null || card == null) return;
        mDashScrollView.post(() -> {
            int[] cardPos = new int[2];
            card.getLocationInWindow(cardPos);
            int[] scrollPos = new int[2];
            mDashScrollView.getLocationInWindow(scrollPos);
            int scrollTo = mDashScrollView.getScrollY() + (cardPos[1] - scrollPos[1]) - dp(16);
            mDashScrollView.smoothScrollTo(0, Math.max(0, scrollTo));
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utils
    // ─────────────────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}
