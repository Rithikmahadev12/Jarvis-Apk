package com.jarvis.assist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.jarvis.assist.storage.SessionStore;

/**
 * BootReceiver — fires after device reboot.
 * Only starts the session service if the user has already completed setup.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "JarvisBootReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SessionStore session = SessionStore.get(ctx);
        if (session.isSetupComplete()) {
            Log.d(TAG, "Boot complete — Jarvis session configured, ready.");
            // Foreground service can be started here if needed in future
        }
    }
}
