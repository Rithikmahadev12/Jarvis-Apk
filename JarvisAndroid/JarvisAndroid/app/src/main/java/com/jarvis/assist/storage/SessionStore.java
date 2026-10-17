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
 * SessionStore — two-phase persistent vault.
 *
 * Phase 1 — URL only (urlSaved = true):
 *   The server URL is saved. The account picker screen shows.
 *
 * Phase 2 — Signed in (setupComplete = true):
 *   A real account from /api/profile/:name was verified and saved.
 *   The app boots directly to dashboard — no screens, no typing.
 *   This persists forever (AES256-GCM hardware keystore).
 *
 * Reset paths:
 *   clearAccount() → goes back to account picker (keeps URL)
 *   clearAll()     → full reset, back to URL entry
 */
public class SessionStore {

    private static final String TAG        = "SessionStore";
    private static final String PREFS_FILE = "jarvis_secure_session";

    private static final String KEY_URL        = "jarvis_url";
    private static final String KEY_URL_SAVED  = "url_saved";
    private static final String KEY_SESSION    = "session_id";
    private static final String KEY_NAME       = "user_name";
    private static final String KEY_TITLE      = "user_title";
    private static final String KEY_ROLE       = "user_role";
    private static final String KEY_SETUP      = "setup_complete";
    private static final String KEY_TZ         = "user_timezone";
    private static final String KEY_MEM_COUNT  = "last_memory_count";

    private static SessionStore sInstance;
    private final SharedPreferences mPrefs;

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
            Log.w(TAG, "EncryptedSharedPreferences unavailable, using plain: " + e.getMessage());
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

    // ── Phase checks ─────────────────────────────────────────────────────────

    /** True once user has entered + verified the server URL. */
    public boolean isUrlSaved() {
        return mPrefs.getBoolean(KEY_URL_SAVED, false);
    }

    /** True once user has picked + verified their Jarvis account. Full boot. */
    public boolean isSetupComplete() {
        return mPrefs.getBoolean(KEY_SETUP, false);
    }

    // ── Phase 1: save URL ────────────────────────────────────────────────────

    public void saveUrl(String jarvisUrl) {
        mPrefs.edit()
                .putString(KEY_URL, jarvisUrl.trim())
                .putBoolean(KEY_URL_SAVED, true)
                .apply();
        Log.d(TAG, "URL saved → " + jarvisUrl);
    }

    // ── Phase 2: sign in with a verified account ──────────────────────────────

    /**
     * Called once the user's name was verified against /api/profile/:name.
     * Generates a permanent session ID tied to this account on this device.
     * Never called again until the user explicitly resets.
     */
    public void signIn(String userName, String userTitle, String userRole) {
        // Generate a permanent session ID the first time (or reuse if re-signing in)
        String sessionId = mPrefs.getString(KEY_SESSION, null);
        if (sessionId == null) {
            sessionId = "android_" + UUID.randomUUID().toString().replace("-", "");
        }
        String tz = java.util.TimeZone.getDefault().getID();

        mPrefs.edit()
                .putString(KEY_NAME,    userName.trim().isEmpty() ? "Sir" : userName.trim())
                .putString(KEY_TITLE,   userTitle != null && !userTitle.trim().isEmpty() ? userTitle.trim() : "Sir")
                .putString(KEY_ROLE,    userRole != null ? userRole : "")
                .putString(KEY_SESSION, sessionId)
                .putString(KEY_TZ,      tz)
                .putBoolean(KEY_SETUP,  true)
                .apply();

        Log.d(TAG, "Signed in → user=" + userName + "  session=" + sessionId);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getJarvisUrl()  { return mPrefs.getString(KEY_URL, "https://mcujarvis.onrender.com"); }
    public String getUserName()   { return mPrefs.getString(KEY_NAME, "Sir"); }
    public String getUserTitle()  { return mPrefs.getString(KEY_TITLE, "Sir"); }
    public String getUserRole()   { return mPrefs.getString(KEY_ROLE, ""); }
    public boolean isOwner()      { return "owner".equals(getUserRole()); }
    public int getLastMemoryCount() { return mPrefs.getInt(KEY_MEM_COUNT, 0); }

    public String getSessionId() {
        String id = mPrefs.getString(KEY_SESSION, null);
        if (id == null) {
            id = "android_" + UUID.randomUUID().toString().replace("-", "");
            mPrefs.edit().putString(KEY_SESSION, id).apply();
        }
        return id;
    }

    public String getTimezone() {
        return mPrefs.getString(KEY_TZ, java.util.TimeZone.getDefault().getID());
    }

    // ── Cache memory count for dashboard display ──────────────────────────────

    public void cacheMemoryCount(int count) {
        mPrefs.edit().putInt(KEY_MEM_COUNT, count).apply();
    }

    // ── Reset paths ───────────────────────────────────────────────────────────

    /** Go back to account picker — keeps URL, clears signed-in user. */
    public void clearAccount() {
        mPrefs.edit()
                .remove(KEY_NAME)
                .remove(KEY_TITLE)
                .remove(KEY_ROLE)
                .remove(KEY_SESSION)
                .remove(KEY_SETUP)
                .remove(KEY_MEM_COUNT)
                .apply();
        Log.d(TAG, "Account cleared — back to picker.");
    }

    /** Full reset — clears everything including URL. */
    public void clearAll() {
        mPrefs.edit().clear().apply();
        Log.d(TAG, "Full reset.");
    }
}
