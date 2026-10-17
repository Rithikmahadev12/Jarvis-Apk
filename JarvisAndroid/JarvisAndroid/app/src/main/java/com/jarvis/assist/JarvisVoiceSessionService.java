package com.jarvis.assist;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * JarvisVoiceSessionService
 *
 * The abstract method on VoiceInteractionSessionService at API 26-34
 * is onNewSession(Bundle args) — NOT onCreateSession().
 * onCreateSession() was a name used in older preview docs and is wrong here.
 */
public class JarvisVoiceSessionService extends VoiceInteractionSessionService {

    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new JarvisVoiceSession(this, new Handler(Looper.getMainLooper()));
    }
}
