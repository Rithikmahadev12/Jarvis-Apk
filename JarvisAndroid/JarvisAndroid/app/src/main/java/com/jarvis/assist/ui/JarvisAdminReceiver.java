package com.jarvis.assist;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * JarvisAdminReceiver — Device Administrator Receiver.
 *
 * Mirrors OSO AugmaOS oso_admin.xml policy: force-lock capability.
 * Required for VaultLockActivity to call DevicePolicyManager.lockNow()
 * which locks the screen immediately when Jarvis detects inactivity or
 * the user manually triggers vault lock.
 *
 * To activate:
 *   DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
 *   ComponentName admin = new ComponentName(this, JarvisAdminReceiver.class);
 *   if (!dpm.isAdminActive(admin)) {
 *       Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
 *       i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
 *       i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Jarvis needs admin to lock your screen.");
 *       startActivity(i);
 *   }
 */
public class JarvisAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        // Device admin activated — Jarvis can now call lockNow()
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Disabling Jarvis admin will prevent screen auto-lock. Continue?";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        // Admin revoked — graceful degrade; vault lock still works via lock screen intent
    }

    @Override
    public void onLockTaskModeEntering(Context context, Intent intent, String pkg) {
        // Jarvis entered lock task (kiosk) mode — expected on launcher devices
    }

    @Override
    public void onLockTaskModeExiting(Context context, Intent intent) {
        // Exiting lock task mode
    }
}
