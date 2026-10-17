package com.jarvis.assist;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

import com.jarvis.assist.ui.VoiceOverlayActivity;

/**
 * JarvisAssistService
 *
 * Registered as the Android Assist app via VoiceInteractionService.
 * When the user long-presses the home button (or swipes up and holds on
 * gesture-nav phones), Android calls onShowSession() here instead of Gemini.
 *
 * We immediately launch VoiceOverlayActivity to take over the screen.
 */
public class JarvisAssistService extends VoiceInteractionService {

    private static final String TAG = "JarvisAssistService";

    @Override
    public void onReady() {
        super.onReady();
        Log.d(TAG, "JarvisAssistService ready — registered as default assist app.");
    }

    @Override
    public void onShowSession(Bundle args, int flags) {
        Log.d(TAG, "Home hold detected — launching Jarvis overlay");

        Intent overlay = new Intent(this, VoiceOverlayActivity.class);
        overlay.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK      |
                Intent.FLAG_ACTIVITY_SINGLE_TOP    |
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        startActivity(overlay);
    }
}
