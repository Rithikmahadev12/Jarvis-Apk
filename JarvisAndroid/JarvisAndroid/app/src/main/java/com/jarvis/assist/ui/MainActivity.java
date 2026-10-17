package com.jarvis.assist.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.jarvis.assist.R;
import com.jarvis.assist.network.JarvisClient;
import com.jarvis.assist.storage.SessionStore;

/**
 * MainActivity — one-time setup screen.
 *
 * First launch:   Shows URL input + name fields → Save → tests connection → marks setup complete.
 * Every launch after that: Immediately skips to the "Connected" dashboard screen.
 * The user never sees the setup form again unless they tap "Reset" in settings.
 */
public class MainActivity extends AppCompatActivity {

    private SessionStore mSession;

    // ── Setup-screen views ────────────────────────────────────────────────────
    private View       mSetupLayout;
    private EditText   mEtUrl;
    private EditText   mEtName;
    private EditText   mEtTitle;
    private Button     mBtnSave;
    private ProgressBar mProgress;
    private TextView   mTvError;

    // ── Dashboard-screen views ────────────────────────────────────────────────
    private View       mDashLayout;
    private TextView   mTvConnected;
    private TextView   mTvSessionId;
    private Button     mBtnTestVoice;
    private Button     mBtnSettings;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        mSession = SessionStore.get(this);

        bindSetupViews();
        bindDashViews();

        if (mSession.isSetupComplete()) {
            showDashboard();
        } else {
            showSetup();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────────────────────────────────

    private void bindSetupViews() {
        mSetupLayout = findViewById(R.id.layout_setup);
        mEtUrl       = findViewById(R.id.et_jarvis_url);
        mEtName      = findViewById(R.id.et_user_name);
        mEtTitle     = findViewById(R.id.et_user_title);
        mBtnSave     = findViewById(R.id.btn_save);
        mProgress    = findViewById(R.id.progress);
        mTvError     = findViewById(R.id.tv_error);

        mBtnSave.setOnClickListener(v -> attemptSetup());
    }

    private void bindDashViews() {
        mDashLayout    = findViewById(R.id.layout_dashboard);
        mTvConnected   = findViewById(R.id.tv_connected_url);
        mTvSessionId   = findViewById(R.id.tv_session_id);
        mBtnTestVoice  = findViewById(R.id.btn_test_voice);
        mBtnSettings   = findViewById(R.id.btn_reset_settings);

        mBtnTestVoice.setOnClickListener(v -> {
            Intent i = new Intent(this, VoiceOverlayActivity.class);
            startActivity(i);
        });

        mBtnSettings.setOnClickListener(v -> confirmReset());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup flow
    // ─────────────────────────────────────────────────────────────────────────

    private void showSetup() {
        mSetupLayout.setVisibility(View.VISIBLE);
        mDashLayout.setVisibility(View.GONE);
        // Pre-fill with default URL
        if (TextUtils.isEmpty(mEtUrl.getText())) {
            mEtUrl.setText("https://mcujarvis.onrender.com");
        }
    }

    private void attemptSetup() {
        String url   = mEtUrl.getText().toString().trim();
        String name  = mEtName.getText().toString().trim();
        String title = mEtTitle.getText().toString().trim();

        if (TextUtils.isEmpty(url)) {
            mTvError.setText("Enter your Jarvis URL");
            mTvError.setVisibility(View.VISIBLE);
            return;
        }
        if (!url.startsWith("http")) {
            url = "https://" + url;
        }

        mTvError.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
        mBtnSave.setEnabled(false);

        final String finalUrl   = url;
        final String finalName  = name.isEmpty() ? "Sir" : name;
        final String finalTitle = title.isEmpty() ? "Sir" : title;

        JarvisClient.ping(finalUrl, new JarvisClient.ChatCallback() {
            @Override
            public void onSuccess(String r) {
                runOnUiThread(() -> {
                    mProgress.setVisibility(View.GONE);
                    mBtnSave.setEnabled(true);
                    // Persist — this is the ONE time we call markSetupComplete
                    mSession.markSetupComplete(finalUrl, finalName, finalTitle);
                    showDashboard();
                    Toast.makeText(MainActivity.this, "Connected to Jarvis ✓", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    mProgress.setVisibility(View.GONE);
                    mBtnSave.setEnabled(true);
                    mTvError.setText("Can't reach Jarvis: " + error + "\n\nSave anyway?");
                    mTvError.setVisibility(View.VISIBLE);
                    // Allow saving even if offline (Render might be sleeping)
                    mBtnSave.setText("Save Anyway");
                    mBtnSave.setOnClickListener(vv -> {
                        mSession.markSetupComplete(finalUrl, finalName, finalTitle);
                        showDashboard();
                    });
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dashboard
    // ─────────────────────────────────────────────────────────────────────────

    private void showDashboard() {
        mSetupLayout.setVisibility(View.GONE);
        mDashLayout.setVisibility(View.VISIBLE);

        mTvConnected.setText("Connected: " + mSession.getJarvisUrl());
        mTvSessionId.setText("Session: " + mSession.getSessionId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reset
    // ─────────────────────────────────────────────────────────────────────────

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Jarvis?")
                .setMessage("This clears your saved URL and session. You'll need to re-enter setup.\n\nAre you sure?")
                .setPositiveButton("Reset", (d, w) -> {
                    mSession.clearAll();
                    mBtnSave.setText("Save & Connect");
                    mBtnSave.setOnClickListener(v -> attemptSetup());
                    showSetup();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
