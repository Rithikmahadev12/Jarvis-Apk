package com.jarvis.assist;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.util.Log;

import com.jarvis.assist.ui.VoiceOverlayActivity;

/**
 * JarvisAssistService
 *
 * FINAL FIX: VoiceInteractionService at compileSdk 34 with minSdk 26
 * does NOT expose onCreateSession() or onShowSession() as overridable
 * methods in the androidx/SDK stubs available on the GitHub Actions runner.
 *
 * The only safe, backward-compatible hook is:
 *   - Register as a VoiceInteractionService in the manifest
 *   - Override onReady() for init
 *   - Use showSession() to programmatically trigger — BUT the real
 *     home-hold dispatch goes through the system calling our session
 *     via the xml/interaction_service.xml sessionService attribute.
 *
 * Cleanest pattern that compiles on all API 26-34 without session subclass:
 *   Strip the service to bare minimum — no illegal @Override.
 *   The actual home-hold trigger fires VoiceOverlayActivity directly
 *   via the manifest <session-service> entry pointing at a separate
 *   JarvisVoiceSession class that extends VoiceInteractionSessionService.
 */
public class JarvisAssistService extends VoiceInteractionService {

    private static final String TAG = "JarvisAssistService";

    @Override
    public void onReady() {
        super.onReady();
        Log.d(TAG, "JarvisAssistService ready.");
    }
}
