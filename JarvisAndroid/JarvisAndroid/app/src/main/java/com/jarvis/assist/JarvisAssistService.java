package com.jarvis.assist;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

import com.jarvis.assist.ui.VoiceOverlayActivity;

/**
 * JarvisAssistService
 *
 * Registered as the Android Assist app via VoiceInteractionService.
 *
 * ARCHITECTURE FIX:
 *   onShowSession() does NOT exist on VoiceInteractionService.
 *   The correct override is:
 *     - VoiceInteractionService.onCreateSession()  → return your Session subclass
 *     - VoiceInteractionSession.onShow()           → handle the home-hold event
 *
 * This is the pattern Google ships in AOSP VoiceInteractionService samples.
 * The original code tried to @Override a method that doesn't exist in the
 * supertype at compileSdk 34 — hence the compile failure.
 */
public class JarvisAssistService extends VoiceInteractionService {

    private static final String TAG = "JarvisAssistService";

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onReady() {
        super.onReady();
        Log.d(TAG, "JarvisAssistService ready — registered as system assist.");
    }

    /**
     * Android calls this to get a session object when the assist gesture fires.
     * We return our JarvisSession which overrides onShow() correctly.
     */
    @Override
    public VoiceInteractionSession onCreateSession() {
        Log.d(TAG, "onCreateSession() — spinning up JarvisSession");
        return new JarvisSession(this, new Handler(Looper.getMainLooper()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner Session class — legally owns onShow()
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * JarvisSession handles the actual assist trigger.
     *
     * onShow() is invoked by Android when:
     *   - User long-presses home button
     *   - User swipes-and-holds on gesture-nav devices
     *   - adb shell am start-activity triggers an assist intent (for testing)
     *
     * We immediately launch VoiceOverlayActivity then call hide() to release
     * the session surface — the Activity owns the UI from that point.
     */
    public static class JarvisSession extends VoiceInteractionSession {

        private static final String STAG = "JarvisSession";

        public JarvisSession(android.content.Context context, Handler handler) {
            super(context, handler);
        }

        @Override
        public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);
            Log.d(STAG, "onShow() triggered — showFlags=" + showFlags);

            // Launch the Jarvis overlay activity
            Intent overlay = new Intent(getContext(), VoiceOverlayActivity.class);
            overlay.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK   |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            );
            getContext().startActivity(overlay);

            // Hand off UI to the Activity; hide session surface immediately
            hide();
        }

        @Override
        public void onHide() {
            super.onHide();
            Log.d(STAG, "Session hidden cleanly — VoiceOverlayActivity is live.");
        }

        /**
         * Called if the system wants to show the session's own UI.
         * We have none — our UI is entirely in VoiceOverlayActivity.
         * Returning null is the correct no-op for this pattern.
         */
        @Override
        public android.view.View onCreateContentView() {
            return null;
        }
    }
}
