package com.jarvis.assist.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * JarvisLauncherActivity — the HOME launcher entry point.
 *
 * Registered with android.intent.category.HOME so the OS presents Jarvis
 * when the user presses the home button, matching the OSO AugmaOS launcher
 * pattern on the XT2271DL (Moto G Pure, Android 11).
 *
 * Flow:
 *   1. Receives HOME intent from Android.
 *   2. Immediately redirects to VaultLockActivity for secure authentication.
 *   3. After vault unlock, VaultLockActivity launches HomeHudActivity as usual.
 *
 * This keeps the security model intact while making Jarvis a full launcher.
 */
public class JarvisLauncherActivity extends Activity {

    private static final int REDIRECT_MS = 0; // immediate — no splash delay

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No setContentView — this activity is purely a routing gate.
        // Show VaultLock immediately (it IS the launcher screen).
        redirectToVault();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Android re-delivers HOME intent when already running (launchMode=singleTask).
        // Bring vault back to front or just re-enter HUD if already unlocked.
        redirectToVault();
    }

    private void redirectToVault() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) {
                Intent i = new Intent(this, VaultLockActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                // Don't finish — keep this activity in back-stack so HOME button re-triggers it.
            }
        }, REDIRECT_MS);
    }

    @Override
    public void onBackPressed() {
        // On a launcher, back button should NOT exit — it goes back to home.
        // No-op to prevent accidental app exit.
    }
}JarvisLauncherActivity
