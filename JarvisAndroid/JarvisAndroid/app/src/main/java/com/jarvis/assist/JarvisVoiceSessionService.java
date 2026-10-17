package com.jarvis.assist;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * JarvisVoiceSessionService
 *
 * Android calls onCreateSession() on THIS class — VoiceInteractionSessionService —
 * not on VoiceInteractionService. This is the correct split:
 *
 *   VoiceInteractionService        → JarvisAssistService   (registered as android.service.voice.VoiceInteractionService)
 *   VoiceInteractionSessionService → JarvisVoiceSessionService (registered as sessionService in interaction_service.xml)
 *
 * onCreateSession() is a real, stable method on VoiceInteractionSessionService.
 * No illegal @Override. Compiles clean on API 26-34.
 */
public class JarvisVoiceSessionService extends VoiceInteractionSessionService {

    @Override
    public VoiceInteractionSession onCreateSession(Bundle args) {
        return new JarvisVoiceSession(this, new Handler(Looper.getMainLooper()));
    }
}
