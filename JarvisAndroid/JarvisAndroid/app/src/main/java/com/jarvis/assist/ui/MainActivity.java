package com.jarvis.assist.ui;

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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.jarvis.assist.R;
import com.jarvis.assist.network.JarvisClient;
import com.jarvis.assist.storage.SessionStore;

import org.json.JSONArray;

import java.util.List;

/**
 * MainActivity — three-screen flow.
 *
 * Screen 1  URL Setup     (first ever launch only — or after full reset)
 *             └─ user enters server URL → ping test → save → go to Screen 2
 *
 * Screen 2  Account Picker
 *             └─ fetches /api/profiles → shows real accounts as tap-to-sign-in cards
 *             └─ manual name field for fallback
 *             └─ tapping an account → verifies via /api/profile/:name → signs in → Screen 3
 *
 * Screen 3  Dashboard     (every subsequent launch boots straight here)
 *             └─ shows user name, title, memory count, session ID
 *             └─ Test Voice Now → VoiceOverlayActivity
 *             └─ Switch Account → back to Screen 2 (keeps URL)
 *             └─ Reset Everything → back to Screen 1 (clears all)
 */
public class MainActivity extends AppCompatActivity {

    private SessionStore mSession;

    // Screen 1 — URL Setup
    private View        mLayoutSetup;
    private EditText    mEtUrl;
    private ProgressBar mProgressUrl;
    private TextView    mTvUrlError;
    private Button      mBtnConnect;

    // Screen 2 — Account Picker
    private View         mLayoutPicker;
    private LinearLayout mLayoutAccountsList;
    private EditText     mEtManualName;
    private ProgressBar  mProgressPicker;
    private TextView     mTvPickerError;
    private Button       mBtnSignInManual;
    private Button       mBtnChangeUrl;

    // Screen 3 — Dashboard
    private View     mLayoutDash;
    private TextView mTvWelcome;
    private TextView mTvConnectedUrl;
    private TextView mTvSessionId;
    private TextView mTvMemoryCount;
    private Button   mBtnTestVoice;
    private Button   mBtnSwitchAccount;
    private Button   mBtnResetAll;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        mSession = SessionStore.get(this);

        bindViews();

        if (mSession.isSetupComplete()) {
            // Already fully signed in — go straight to dashboard
            showDashboard();
        } else if (mSession.isUrlSaved()) {
            // URL saved but no account chosen yet — go to picker
            showAccountPicker();
        } else {
            // First ever launch
            showUrlSetup();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bind
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        // Screen 1
        mLayoutSetup    = findViewById(R.id.layout_setup);
        mEtUrl          = findViewById(R.id.et_jarvis_url);
        mProgressUrl    = findViewById(R.id.progress_url);
        mTvUrlError     = findViewById(R.id.tv_url_error);
        mBtnConnect     = findViewById(R.id.btn_connect_url);

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

        // Screen 3
        mLayoutDash       = findViewById(R.id.layout_dashboard);
        mTvWelcome        = findViewById(R.id.tv_welcome);
        mTvConnectedUrl   = findViewById(R.id.tv_connected_url);
        mTvSessionId      = findViewById(R.id.tv_session_id);
        mTvMemoryCount    = findViewById(R.id.tv_memory_count);
        mBtnTestVoice     = findViewById(R.id.btn_test_voice);
        mBtnSwitchAccount = findViewById(R.id.btn_switch_account);
        mBtnResetAll      = findViewById(R.id.btn_reset_settings);

        mBtnTestVoice.setOnClickListener(v ->
                startActivity(new Intent(this, VoiceOverlayActivity.class)));
        mBtnSwitchAccount.setOnClickListener(v -> {
            mSession.clearAccount();
            showAccountPicker();
        });
        mBtnResetAll.setOnClickListener(v -> confirmFullReset());
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
                    mTvUrlError.setText("Can't reach Jarvis: " + error + "\n\nCheck URL or save anyway?");
                    mTvUrlError.setVisibility(View.VISIBLE);
                    // Let them save offline too (Render sleeps)
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
                        showPickerError("No accounts found on server. Enter your name below.");
                    } else {
                        buildAccountCards(profiles);
                    }
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    mProgressPicker.setVisibility(View.GONE);
                    showPickerError("Could not load accounts: " + error + "\nEnter your name below.");
                });
            }
        });
    }

    /**
     * Dynamically builds one card button per account returned by /api/profiles.
     * Owner accounts get a subtle gold tint so they stand out.
     */
    private void buildAccountCards(List<JarvisClient.JarvisProfile> profiles) {
        mLayoutAccountsList.removeAllViews();
        int dpPad = dp(14);
        int dpMrg = dp(10);

        for (JarvisClient.JarvisProfile profile : profiles) {
            // Card container
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.parseColor(profile.isOwner() ? "#1A1500" : "#141414"));
            card.setPadding(dpPad, dpPad, dpPad, dpPad);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = dpMrg;
            card.setLayoutParams(lp);

            // Name row
            TextView tvName = new TextView(this);
            tvName.setText(profile.name + (profile.isOwner() ? "  ★" : ""));
            tvName.setTextColor(Color.WHITE);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvName.setTypeface(null, Typeface.BOLD);
            card.addView(tvName);

            // Title row
            if (!TextUtils.isEmpty(profile.title) && !profile.title.equals("Sir")) {
                TextView tvTitle = new TextView(this);
                tvTitle.setText(profile.title);
                tvTitle.setTextColor(Color.parseColor("#66FFFFFF"));
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                card.addView(tvTitle);
            }

            // "Sign in as" button
            Button btn = new Button(this);
            btn.setText("Sign in as " + profile.name);
            btn.setTextColor(Color.BLACK);
            btn.setBackgroundColor(Color.parseColor(profile.isOwner() ? "#FFD700" : "#FFFFFF"));
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            btnLp.topMargin = dp(10);
            btn.setLayoutParams(btnLp);

            final JarvisClient.JarvisProfile finalProfile = profile;
            btn.setOnClickListener(v -> signInWithProfile(finalProfile.name));
            card.addView(btn);

            mLayoutAccountsList.addView(card);
        }
    }

    private void showPickerError(String msg) {
        mTvPickerError.setText(msg);
        mTvPickerError.setVisibility(View.VISIBLE);
    }

    /** Manual sign-in: user types a name → verify it against /api/profile/:name */
    private void attemptManualSignIn() {
        String name = mEtManualName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            showPickerError("Enter your name");
            return;
        }
        signInWithProfile(name);
    }

    /**
     * Core sign-in logic — calls /api/profile/:name to verify the account exists,
     * then fetches memories, stores everything and shows dashboard.
     */
    private void signInWithProfile(String name) {
        mTvPickerError.setVisibility(View.GONE);
        mProgressPicker.setVisibility(View.VISIBLE);
        setBtnPickerEnabled(false);

        JarvisClient.fetchProfile(mSession.getJarvisUrl(), name, new JarvisClient.ProfileCallback() {
            @Override public void onSuccess(JarvisClient.JarvisProfile profile) {
                // Got the real profile — fetch memories to cache the count, then sign in
                JarvisClient.fetchMemories(
                        mSession.getJarvisUrl(),
                        profile.name,
                        memories -> runOnUiThread(() -> {
                            mProgressPicker.setVisibility(View.GONE);
                            setBtnPickerEnabled(true);
                            mSession.signIn(profile.name, profile.title, profile.role);
                            mSession.cacheMemoryCount(memories.length());
                            Toast.makeText(MainActivity.this,
                                    "Welcome back, " + profile.name + " ✓",
                                    Toast.LENGTH_SHORT).show();
                            showDashboard();
                        })
                );
            }

            @Override public void onNotFound() {
                runOnUiThread(() -> {
                    mProgressPicker.setVisibility(View.GONE);
                    setBtnPickerEnabled(true);
                    showPickerError("No account found for \"" + name + "\". Check your name.");
                });
            }

            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    mProgressPicker.setVisibility(View.GONE);
                    setBtnPickerEnabled(true);
                    // If offline, allow signing in with just the name (Render sleeping)
                    showPickerError("Can't reach server: " + error +
                            "\n\nTap 'Save Offline' to use cached name.");
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
        // Also disable all account cards
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

        String name  = mSession.getUserName();
        String title = mSession.getUserTitle();

        mTvWelcome.setText("Hello, " + name + (mSession.isOwner() ? " ★" : ""));
        mTvConnectedUrl.setText(mSession.getJarvisUrl());
        mTvSessionId.setText("session: " + mSession.getSessionId());

        int memCount = mSession.getLastMemoryCount();
        mTvMemoryCount.setText(memCount > 0
                ? "Jarvis remembers " + memCount + " thing" + (memCount == 1 ? "" : "s") + " about you"
                : "");

        // Refresh memory count in background without blocking UI
        JarvisClient.fetchMemories(mSession.getJarvisUrl(), name, memories -> {
            int count = memories.length();
            mSession.cacheMemoryCount(count);
            runOnUiThread(() -> mTvMemoryCount.setText(count > 0
                    ? "Jarvis remembers " + count + " thing" + (count == 1 ? "" : "s") + " about you"
                    : "No memories yet"));
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reset
    // ─────────────────────────────────────────────────────────────────────────

    private void confirmFullReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Everything?")
                .setMessage("Clears your saved URL and account. You'll go back to the start.\n\nAre you sure?")
                .setPositiveButton("Reset", (d, w) -> {
                    mSession.clearAll();
                    showUrlSetup();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Util
    // ─────────────────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }
}
