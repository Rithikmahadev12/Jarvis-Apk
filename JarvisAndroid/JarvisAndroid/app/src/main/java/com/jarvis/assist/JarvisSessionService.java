package com.jarvis.assist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.jarvis.assist.R;

/**
 * JarvisSessionService — lightweight foreground service.
 * Keeps Jarvis registered as the assist app even in background.
 * Shows a persistent (silent) notification so Android doesn't kill us.
 */
public class JarvisSessionService extends Service {

    private static final String CHANNEL_ID = "jarvis_session";
    private static final int    NOTIF_ID   = 9001;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jarvis Active")
                .setContentText("Hold home button to talk to Jarvis")
                .setSmallIcon(R.drawable.ic_jarvis)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();

        startForeground(NOTIF_ID, notif);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Jarvis Session", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("Keeps Jarvis ready as your assist app");
        ch.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
}
