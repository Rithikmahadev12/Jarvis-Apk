package com.jarvis.assist;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.View;

import com.jarvis.assist.ui.VoiceOverlayActivity;

/**
 * JarvisVoiceSession
 *
 * This is where onShow() legally lives — on VoiceInteractionSession,
 * not on VoiceInteractionService. Android instantiates this via
 * JarvisVoiceSessionService (the sessionService registered in the manifest).
 *
 * Flow on home-hold:
 *   Android → JarvisVoiceSessionService.onCreateSession()
 *           → JarvisVoiceSession.onShow()
 *           → startActivity(VoiceOverlayActivity)
 *           → hide()  [session surface dismissed, Activity owns UI]
 */
public class JarvisVoiceSession extends VoiceInteractionSession {

    private static final String TAG = "JarvisVoiceSession";

    public JarvisVoiceSession(Context context, Handler handler) {
        super(context, handler);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Log.d(TAG, "onShow() — home-hold detected, launching overlay. flags=" + showFlags);

        Intent overlay = new Intent(getContext(), VoiceOverlayActivity.class);
        overlay.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK   |
                Intent.FLAG_ACTIVITY_SINGLE_TOP |
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        getContext().startActivity(overlay);
        hide();
    }

    @Override
    public void onHide() {
        super.onHide();
        Log.d(TAG, "Session hidden — VoiceOverlayActivity is live.");
    }

    @Override
    public View onCreateContentView() {
        // No session UI — VoiceOverlayActivity owns everything
        return null;
    }
}
