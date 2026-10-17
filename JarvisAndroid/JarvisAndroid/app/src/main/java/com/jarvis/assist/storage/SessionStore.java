package com.jarvis.assist.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * SessionStore — persistent, encrypted, never-expires login vault.
 *
 * One login, forever.
 * Uses AndroidX EncryptedSharedPreferences backed by AES256-GCM / AES256-SIV
 * stored in the Android Keystore. Even if someone pulls the APK data folder,
 * they still need the device's hardware-backed keystore to decrypt.
 *
 * Fields persisted:
 *   jarvis_url      — e.g. https://mcujarvis.onrender.com
 *   session_id      — UUID generated once, reused forever
 *   user_name       — display name (default "Sir")
 *   user_title      — title passed to Jarvis brain
 *   setup_complete  — boolean; skips onboarding on every subsequent launch
 */
public class SessionStore {

    private static final String TAG           = "SessionStore";
    private static final String PREFS_FILE    = "jarvis_secure_session";
    private static final String KEY_URL       = "jarvis_url";
    private static final String KEY_SESSION   = "session_id";
    private static final String KEY_NAME      = "user_name";
    private static final String KEY_TITLE     = "user_title";
    private static final String KEY_SETUP     = "setup_complete";
    private static final String KEY_TZ        = "user_timezone";

    private static SessionStore sInstance;
    private final SharedPreferences mPrefs;

    // ── Private constructor — singleton ──────────────────────────────────────
    private SessionStore(Context ctx) {
        SharedPreferences prefs;
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            prefs = EncryptedSharedPreferences.create(
                    ctx,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            // Keystore unavailable (emulator without Play Services sometimes) — fall back to
            // plain prefs. Not ideal but keeps the app functional for testing.
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs: " + e.getMessage());
            prefs = ctx.getSharedPreferences(PREFS_FILE + "_plain", Context.MODE_PRIVATE);
        }
        mPrefs = prefs;
    }

    public static synchronized SessionStore get(Context ctx) {
        if (sInstance == null) {
            sInstance = new SessionStore(ctx.getApplicationContext());
        }
        return sInstance;
    }

    // ── Setup complete flag ──────────────────────────────────────────────────

    public boolean isSetupComplete() {
        return mPrefs.getBoolean(KEY_SETUP, false);
    }

    /**
     * Call once the user finishes onboarding. Never called again — the app
     * skips setup on every subsequent cold start.
     */
    public void markSetupComplete(String jarvisUrl, String userName, String userTitle) {
        // Generate a permanent session ID on first setup
        String sessionId = mPrefs.getString(KEY_SESSION, null);
        if (sessionId == null) {
            sessionId = "android_" + UUID.randomUUID().toString().replace("-", "");
        }
        String tz = java.util.TimeZone.getDefault().getID();

        mPrefs.edit()
                .putString(KEY_URL,     jarvisUrl.trim())
                .putString(KEY_SESSION, sessionId)
                .putString(KEY_NAME,    userName.trim().isEmpty() ? "Sir" : userName.trim())
                .putString(KEY_TITLE,   userTitle.trim().isEmpty() ? "Sir" : userTitle.trim())
                .putString(KEY_TZ,      tz)
                .putBoolean(KEY_SETUP,  true)
                .apply();

        Log.d(TAG, "Session persisted → sessionId=" + sessionId + " url=" + jarvisUrl);
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public String getJarvisUrl() {
        return mPrefs.getString(KEY_URL, "https://mcujarvis.onrender.com");
    }

    public String getSessionId() {
        String id = mPrefs.getString(KEY_SESSION, null);
        if (id == null) {
            // Safety: generate one if somehow missing
            id = "android_" + UUID.randomUUID().toString().replace("-", "");
            mPrefs.edit().putString(KEY_SESSION, id).apply();
        }
        return id;
    }

    public String getUserName() {
        return mPrefs.getString(KEY_NAME, "Sir");
    }

    public String getUserTitle() {
        return mPrefs.getString(KEY_TITLE, "Sir");
    }

    public String getTimezone() {
        return mPrefs.getString(KEY_TZ, java.util.TimeZone.getDefault().getID());
    }

    // ── Update individual fields without full re-setup ────────────────────────

    public void updateUrl(String url) {
        mPrefs.edit().putString(KEY_URL, url.trim()).apply();
    }

    public void updateName(String name) {
        mPrefs.edit().putString(KEY_NAME, name.trim()).apply();
    }

    // ── Nuclear option — only if user explicitly taps "Reset" in settings ────

    public void clearAll() {
        mPrefs.edit().clear().apply();
        Log.d(TAG, "Session cleared.");
    }
}
