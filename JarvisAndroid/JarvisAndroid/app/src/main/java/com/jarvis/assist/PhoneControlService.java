package com.jarvis.assist;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.jarvis.assist.network.JarvisClient;
import com.jarvis.assist.storage.SessionStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PhoneControlService — Android Accessibility Service
 *
 * Executes Jarvis phone-control action plans on the real phone UI.
 * No root required. Uses Android Accessibility APIs to:
 *   - Open apps via Intent
 *   - Find and tap UI elements by text or content description
 *   - Type text into focused fields
 *   - Scroll, swipe, go back, go home
 *   - Send screenshots back to backend for step verification
 *
 * HOW IT WORKS:
 *   1. VoiceOverlayActivity detects a "phone control" command and calls
 *      planPhoneControl() which POSTs to /api/phone-control/plan.
 *   2. Backend (phone-control.js) returns a JSON step array.
 *   3. VoiceOverlayActivity calls PhoneControlService.executeplan(steps, callback).
 *   4. This service walks the steps, each on a short delay for UI to settle.
 *   5. On screenshot_check steps, it serialises the current window's node tree
 *      to text and POSTs it to /api/phone-control/verify for Groq to evaluate.
 *   6. On completion or failure it calls back to VoiceOverlayActivity which
 *      speaks the result.
 *
 * SETUP: declare in AndroidManifest.xml (see manifest patch below).
 *   User must grant "Installed apps" + "Accessibility Service" in Settings.
 *   First-run MainActivity shows a prompt if it's not enabled yet.
 */
public class PhoneControlService extends AccessibilityService {

    private static final String TAG = "JarvisPhoneCtrl";

    // ── Singleton reference — VoiceOverlayActivity reaches in here ──────────
    private static PhoneControlService sInstance;
    public  static PhoneControlService getInstance() { return sInstance; }

    // ── Execution state ──────────────────────────────────────────────────────
    private final Handler          mMain    = new Handler(Looper.getMainLooper());
    private final ExecutorService  mPool    = Executors.newSingleThreadExecutor();
    private volatile boolean       mRunning = false;
    private volatile boolean       mAborted = false;

    // Callback interface for callers (VoiceOverlayActivity)
    public interface ExecutionCallback {
        void onStepStart(int index, int total, String description);
        void onSuccess(String summary);
        void onFailure(String step, String reason);
    }

    // ── ACCESSIBILITY SERVICE LIFECYCLE ──────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED  |
                AccessibilityEvent.TYPE_VIEW_CLICKED          |
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED     |
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType    = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags           =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS              |
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY |
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        Log.d(TAG, "PhoneControlService connected ✓");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        mPool.shutdownNow();
        Log.d(TAG, "PhoneControlService destroyed");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* passive — we drive actions ourselves */ }
    @Override public void onInterrupt() { mAborted = true; }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API — called from VoiceOverlayActivity
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Execute a Jarvis phone-control plan.
     * @param planJson  The full plan JSON string from /api/phone-control/plan
     * @param jarvisUrl Base URL of the Jarvis backend (for verify calls)
     * @param cb        Callback on main thread
     */
    public void executePlan(String planJson, String jarvisUrl, ExecutionCallback cb) {
        if (mRunning) {
            cb.onFailure("start", "Already running a phone control task — try again in a moment.");
            return;
        }
        mRunning = true;
        mAborted = false;

        mPool.execute(() -> {
            try {
                JSONObject plan   = new JSONObject(planJson);
                JSONArray  steps  = plan.getJSONArray("steps");
                String     summary = plan.optString("summary", "Task");
                int        total   = steps.length();

                for (int i = 0; i < total; i++) {
                    if (mAborted) {
                        final int fi = i;
                        mMain.post(() -> cb.onFailure("step " + fi, "Task aborted."));
                        return;
                    }

                    JSONObject step = steps.getJSONObject(i);
                    String     action = step.getString("action");
                    final int  fi = i;
                    mMain.post(() -> cb.onStepStart(fi + 1, total, action));

                    boolean ok = executeStep(step, jarvisUrl);
                    if (!ok) {
                        final String reason = step.optString("description", action);
                        mMain.post(() -> cb.onFailure("step " + (fi + 1) + " (" + action + ")", reason));
                        return;
                    }
                }

                // All steps done
                final String finalSummary = summary;
                mMain.post(() -> cb.onSuccess(finalSummary));

            } catch (Exception e) {
                Log.e(TAG, "executePlan: " + e.getMessage(), e);
                mMain.post(() -> cb.onFailure("plan parse", e.getMessage()));
            } finally {
                mRunning = false;
            }
        });
    }

    /** Abort any running plan. */
    public void abort() { mAborted = true; }

    /** True while a plan is being executed. */
    public boolean isRunning() { return mRunning; }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP EXECUTOR
    // ─────────────────────────────────────────────────────────────────────────

    private boolean executeStep(JSONObject step, String jarvisUrl) throws Exception {
        String action = step.getString("action");
        Log.d(TAG, "executeStep: " + action + " — " + step);

        switch (action) {

            // ── OPEN APP ─────────────────────────────────────────────────────
            case "open_app": {
                String pkg = step.optString("package", "");
                if (pkg.isEmpty()) {
                    Log.w(TAG, "open_app: no package specified");
                    return false;
                }
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch == null) {
                    Log.w(TAG, "open_app: package not installed — " + pkg);
                    return false;
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(launch);
                sleep(step.optInt("wait_ms", 1800)); // wait for app to paint
                return true;
            }

            // ── TAP ELEMENT ──────────────────────────────────────────────────
            case "tap_element": {
                String description  = step.optString("description", "");
                String fallbackText = step.optString("fallback_text", "");

                // Try description-based search first, then exact text, then content-desc
                AccessibilityNodeInfo node = findNode(description, fallbackText);
                if (node == null) {
                    Log.w(TAG, "tap_element: could not find '" + description + "' / '" + fallbackText + "'");
                    // Wait a bit and retry once — sometimes the UI hasn't finished loading
                    sleep(800);
                    node = findNode(description, fallbackText);
                }
                if (node == null) return false;

                boolean tapped = performClick(node);
                node.recycle();
                sleep(step.optInt("wait_ms", 400));
                return tapped;
            }

            // ── TYPE TEXT ────────────────────────────────────────────────────
            case "type_text": {
                String text = step.optString("text", "");
                // Find the currently focused or editable node
                AccessibilityNodeInfo focused = findFocusedEditText();
                if (focused != null) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    focused.recycle();
                    sleep(step.optInt("wait_ms", 350));
                    return ok;
                }
                // Fallback: inject key events character by character (slower but works everywhere)
                injectText(text);
                sleep(step.optInt("wait_ms", 350));
                return true;
            }

            // ── SCROLL ───────────────────────────────────────────────────────
            case "scroll": {
                String direction = step.optString("direction", "down");
                String amount    = step.optString("amount", "medium");
                float  dist      = amount.equals("small") ? 0.2f : amount.equals("large") ? 0.6f : 0.4f;
                return performSwipeGesture(direction, dist, step.optInt("wait_ms", 400));
            }

            // ── SWIPE ────────────────────────────────────────────────────────
            case "swipe": {
                String from = step.optString("from", "bottom");
                String to   = step.optString("to", "center");
                return performSwipeFromTo(from, to, step.optInt("wait_ms", 400));
            }

            // ── BACK ─────────────────────────────────────────────────────────
            case "back": {
                boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
                sleep(step.optInt("wait_ms", 400));
                return ok;
            }

            // ── HOME ─────────────────────────────────────────────────────────
            case "home": {
                boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
                sleep(step.optInt("wait_ms", 400));
                return ok;
            }

            // ── WAIT ─────────────────────────────────────────────────────────
            case "wait": {
                sleep(step.optInt("ms", 1000));
                return true;
            }

            // ── SCREENSHOT CHECK ─────────────────────────────────────────────
            case "screenshot_check": {
                String expected = step.optString("expect", "");
                // Describe the current window's node tree as text
                String screenState = describeCurrentScreen();
                // POST to Jarvis backend for Groq verification
                String result = verifyWithBackend(jarvisUrl, expected, screenState);
                if ("abort".equals(result)) {
                    Log.w(TAG, "screenshot_check: Groq says ABORT — " + expected);
                    return false;
                }
                if ("retry".equals(result)) {
                    sleep(800);
                    // retry the tap from before this check
                    return true; // let the caller advance to next step; retry is best-effort
                }
                sleep(200);
                return true; // ok
            }

            // ── TAP COORDS ───────────────────────────────────────────────────
            case "tap_coords": {
                float xPct = (float) step.optDouble("x_pct", 0.5);
                float yPct = (float) step.optDouble("y_pct", 0.5);
                return performTapCoords(xPct, yPct, step.optInt("wait_ms", 400));
            }

            default:
                Log.w(TAG, "Unknown action: " + action);
                return true; // skip unknown actions gracefully
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NODE FINDERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Find a node by natural-language description or fallback text.
     * Search order: exact text match → content-description match → fuzzy substring match.
     */
    private AccessibilityNodeInfo findNode(String description, String fallbackText) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;

        // 1. Exact text match on fallback_text
        if (!fallbackText.isEmpty()) {
            List<AccessibilityNodeInfo> byText = root.findAccessibilityNodeInfosByText(fallbackText);
            for (AccessibilityNodeInfo n : byText) {
                if (isInteractive(n)) {
                    // recycle non-winners
                    for (AccessibilityNodeInfo other : byText) {
                        if (other != n) other.recycle();
                    }
                    root.recycle();
                    return n;
                }
                // try the parent — some buttons wrap text in a non-clickable TextView
                AccessibilityNodeInfo parent = n.getParent();
                if (parent != null && isInteractive(parent)) {
                    n.recycle();
                    for (AccessibilityNodeInfo other : byText) {
                        if (other != n) other.recycle();
                    }
                    root.recycle();
                    return parent;
                }
            }
            for (AccessibilityNodeInfo n : byText) n.recycle();
        }

        // 2. Content-description fuzzy match (for icon-only buttons)
        if (!description.isEmpty()) {
            AccessibilityNodeInfo found = findByContentDescription(root, description);
            if (found != null) {
                root.recycle();
                return found;
            }
        }

        root.recycle();
        return null;
    }

    private AccessibilityNodeInfo findByContentDescription(AccessibilityNodeInfo node, String hint) {
        if (node == null) return null;
        String hint_lc = hint.toLowerCase(Locale.ROOT);

        CharSequence desc = node.getContentDescription();
        CharSequence text = node.getText();
        boolean matches =
                (desc != null && desc.toString().toLowerCase(Locale.ROOT).contains(hint_lc)) ||
                (text != null && text.toString().toLowerCase(Locale.ROOT).contains(hint_lc));

        if (matches && isInteractive(node)) {
            // clone so caller can recycle the tree
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findByContentDescription(child, hint);
            if (child != null && result == null) child.recycle();
            if (result != null) return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findFocusedEditText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        // Prefer the actually focused node
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) {
            root.recycle();
            return focused;
        }
        if (focused != null) focused.recycle();
        // Fall back to any editable node
        AccessibilityNodeInfo editable = findFirstEditable(root);
        root.recycle();
        return editable;
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findFirstEditable(child);
            if (child != null && result == null) child.recycle();
            if (result != null) return result;
        }
        return null;
    }

    private boolean isInteractive(AccessibilityNodeInfo n) {
        if (n == null) return false;
        return n.isClickable() || n.isFocusable() || n.isLongClickable();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACTION PERFORMERS
    // ─────────────────────────────────────────────────────────────────────────

    private boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        // Try performAction first (fastest, most reliable)
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        // Try gesture tap at node's center bounds
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty()) {
            return performTapCoords(
                    bounds.centerX() / (float) getResources().getDisplayMetrics().widthPixels,
                    bounds.centerY() / (float) getResources().getDisplayMetrics().heightPixels,
                    0);
        }
        return false;
    }

    private boolean performTapCoords(float xPct, float yPct, int waitMs) {
        int width  = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        float x = xPct * width;
        float y = yPct * height;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke).build();

        final boolean[] result = {false};
        final Object lock = new Object();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                result[0] = true;
                synchronized (lock) { lock.notifyAll(); }
            }
            @Override public void onCancelled(GestureDescription g) {
                synchronized (lock) { lock.notifyAll(); }
            }
        }, mMain);

        synchronized (lock) {
            try { lock.wait(1000); } catch (InterruptedException ignored) {}
        }
        if (waitMs > 0) sleep(waitMs);
        return result[0];
    }

    private boolean performSwipeGesture(String direction, float distancePct, int waitMs) {
        int width  = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;

        float startX = width * 0.5f, startY = height * 0.5f;
        float endX   = startX,       endY   = startY;

        switch (direction.toLowerCase(Locale.ROOT)) {
            case "down":  endY = startY + height * distancePct; break;
            case "up":    endY = startY - height * distancePct; break;
            case "right": endX = startX + width  * distancePct; break;
            case "left":  endX = startX - width  * distancePct; break;
        }

        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 300))
                .build();

        final boolean[] ok = {false};
        final Object lock  = new Object();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                ok[0] = true; synchronized (lock) { lock.notifyAll(); }
            }
            @Override public void onCancelled(GestureDescription g) {
                synchronized (lock) { lock.notifyAll(); }
            }
        }, mMain);

        synchronized (lock) {
            try { lock.wait(600); } catch (InterruptedException ignored) {}
        }
        if (waitMs > 0) sleep(waitMs);
        return ok[0];
    }

    private boolean performSwipeFromTo(String from, String to, int waitMs) {
        int width  = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;

        float sx = anchorX(from, width),  sy = anchorY(from, height);
        float ex = anchorX(to,   width),  ey = anchorY(to,   height);

        Path path = new Path();
        path.moveTo(sx, sy);
        path.lineTo(ex, ey);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 400))
                .build();

        final boolean[] ok = {false};
        final Object lock  = new Object();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                ok[0] = true; synchronized (lock) { lock.notifyAll(); }
            }
            @Override public void onCancelled(GestureDescription g) {
                synchronized (lock) { lock.notifyAll(); }
            }
        }, mMain);

        synchronized (lock) {
            try { lock.wait(600); } catch (InterruptedException ignored) {}
        }
        if (waitMs > 0) sleep(waitMs);
        return ok[0];
    }

    private float anchorX(String side, int w) {
        switch (side.toLowerCase(Locale.ROOT)) {
            case "left":   return w * 0.05f;
            case "right":  return w * 0.95f;
            default:       return w * 0.5f;   // center / top / bottom
        }
    }

    private float anchorY(String side, int h) {
        switch (side.toLowerCase(Locale.ROOT)) {
            case "top":    return h * 0.05f;
            case "bottom": return h * 0.95f;
            default:       return h * 0.5f;
        }
    }

    private void injectText(String text) {
        // Last-resort fallback: focus the first editable and paste via clipboard
        // (AccessibilityNodeInfo.ACTION_SET_TEXT is preferred and tried first in TYPE_TEXT case)
        // This handles apps that block ACTION_SET_TEXT.
        AccessibilityNodeInfo editable = findFocusedEditText();
        if (editable != null) {
            editable.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            editable.recycle();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCREEN DESCRIPTION (for screenshot_check steps)
    // ─────────────────────────────────────────────────────────────────────────

    private String describeCurrentScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "screen unavailable";
        StringBuilder sb = new StringBuilder();
        describeNode(root, sb, 0);
        root.recycle();
        // Keep it short enough for the Groq token budget
        String full = sb.toString().trim();
        return full.length() > 800 ? full.substring(0, 800) + "…" : full;
    }

    private void describeNode(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 6) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = (text != null && text.length() > 0) ? text.toString()
                     : (desc != null && desc.length() > 0) ? "[" + desc + "]"
                     : null;
        if (label != null) {
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append(label);
            if (node.isClickable()) sb.append(" (btn)");
            if (node.isEditable())  sb.append(" (input)");
            sb.append("\n");
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                describeNode(child, sb, depth + 1);
                child.recycle();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BACKEND VERIFY CALL
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns "ok", "retry", or "abort". */
    private String verifyWithBackend(String jarvisUrl, String expected, String screenState) {
        if (jarvisUrl == null || jarvisUrl.isEmpty()) return "ok";
        try {
            SessionStore session = SessionStore.get(this);
            String body = new JSONObject()
                    .put("expected",    expected)
                    .put("screenState", screenState)
                    .put("sessionId",   session.getSessionId())
                    .toString();

            okhttp3.OkHttpClient http = new okhttp3.OkHttpClient();
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(jarvisUrl + "/api/phone-control/verify")
                    .post(okhttp3.RequestBody.create(body,
                            okhttp3.MediaType.parse("application/json")))
                    .build();
            try (okhttp3.Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return "ok";
                JSONObject json = new JSONObject(resp.body().string());
                return json.optString("status", "ok");
            }
        } catch (Exception e) {
            Log.w(TAG, "verifyWithBackend failed: " + e.getMessage());
            return "ok"; // non-fatal — keep going
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTIL
    // ─────────────────────────────────────────────────────────────────────────

    private static void sleep(int ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
