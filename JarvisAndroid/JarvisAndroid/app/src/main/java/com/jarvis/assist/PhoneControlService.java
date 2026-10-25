package com.jarvis.assist;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PhoneControlService — Android Accessibility Service
 *
 * FIX: open_app now has three launch fallbacks:
 *   1. getLaunchIntentForPackage (standard)
 *   2. Play Store deep link if not installed
 *   3. queryIntentActivities scan for package by partial name
 *
 * FIX: wait_ms defaults raised — YouTube/Instagram need 2500ms cold start.
 * FIX: Extended package name map covers more Samsung/OEM variants.
 */
public class PhoneControlService extends AccessibilityService {

    private static final String TAG = "JarvisPhoneCtrl";

    // ── Singleton reference ──────────────────────────────────────────────────
    private static PhoneControlService sInstance;
    public  static PhoneControlService getInstance() { return sInstance; }

    // ── Extended local package map (fallback when PM can't resolve) ──────────
    private static final Map<String, String[]> PKG_ALIASES = new HashMap<>();
    static {
        // Each key is a lowercase app name; value is ordered list of known package names to try
        PKG_ALIASES.put("youtube",    new String[]{"com.google.android.youtube", "com.youtube.music"});
        PKG_ALIASES.put("instagram",  new String[]{"com.instagram.android"});
        PKG_ALIASES.put("tiktok",     new String[]{"com.zhiliaoapp.musically", "com.ss.android.ugc.trill"});
        PKG_ALIASES.put("spotify",    new String[]{"com.spotify.music"});
        PKG_ALIASES.put("chrome",     new String[]{"com.android.chrome", "com.chrome.beta"});
        PKG_ALIASES.put("maps",       new String[]{"com.google.android.apps.maps"});
        PKG_ALIASES.put("gmail",      new String[]{"com.google.android.gm"});
        PKG_ALIASES.put("whatsapp",   new String[]{"com.whatsapp", "com.whatsapp.w4b"});
        PKG_ALIASES.put("telegram",   new String[]{"org.telegram.messenger", "org.telegram.messenger.web"});
        PKG_ALIASES.put("twitter",    new String[]{"com.twitter.android", "com.twitter.android.lite"});
        PKG_ALIASES.put("reddit",     new String[]{"com.reddit.frontpage"});
        PKG_ALIASES.put("snapchat",   new String[]{"com.snapchat.android"});
        PKG_ALIASES.put("netflix",    new String[]{"com.netflix.mediaclient"});
        PKG_ALIASES.put("amazon",     new String[]{"com.amazon.mShop.android.shopping"});
        PKG_ALIASES.put("doordash",   new String[]{"com.dd.doordash"});
        PKG_ALIASES.put("uber",       new String[]{"com.ubercab"});
        PKG_ALIASES.put("lyft",       new String[]{"com.lyft.android"});
        PKG_ALIASES.put("settings",   new String[]{Settings.ACTION_SETTINGS});  // handled specially
        PKG_ALIASES.put("phone",      new String[]{"com.google.android.dialer", "com.samsung.android.dialer"});
        PKG_ALIASES.put("messages",   new String[]{"com.google.android.apps.messaging", "com.samsung.android.messaging"});
        PKG_ALIASES.put("contacts",   new String[]{"com.android.contacts", "com.samsung.android.contacts"});
        PKG_ALIASES.put("camera",     new String[]{"com.android.camera2", "com.samsung.android.camera"});
        PKG_ALIASES.put("files",      new String[]{"com.google.android.apps.nbu.files", "com.android.documentsui"});
        PKG_ALIASES.put("clock",      new String[]{"com.google.android.deskclock", "com.samsung.android.clock"});
        PKG_ALIASES.put("calculator", new String[]{"com.google.android.calculator", "com.samsung.android.calculator"});
        PKG_ALIASES.put("play store", new String[]{"com.android.vending"});
        PKG_ALIASES.put("photos",     new String[]{"com.google.android.apps.photos", "com.samsung.android.gallery3d"});
    }

    // ── Execution state ──────────────────────────────────────────────────────
    private final Handler          mMain    = new Handler(Looper.getMainLooper());
    private final ExecutorService  mPool    = Executors.newSingleThreadExecutor();
    private volatile boolean       mRunning = false;
    private volatile boolean       mAborted = false;

    public interface ExecutionCallback {
        void onStepStart(int index, int total, String description);
        void onSuccess(String summary);
        void onFailure(String step, String reason);
    }

    // ── LIFECYCLE ────────────────────────────────────────────────────────────

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

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() { mAborted = true; }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

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

                    JSONObject step   = steps.getJSONObject(i);
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

    public void abort() { mAborted = true; }
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
                String pkg     = step.optString("package", "").trim();
                String appName = step.optString("app_name", "").trim();

                // ── FIX: Special-case Android Settings ───────────────────────
                if (pkg.equals(Settings.ACTION_SETTINGS) ||
                    appName.equalsIgnoreCase("settings")) {
                    Intent si = new Intent(Settings.ACTION_SETTINGS);
                    si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(si);
                    sleep(step.optInt("wait_ms", 1800));
                    return true;
                }

                // ── FIX: Try the package directly first ───────────────────────
                if (!pkg.isEmpty()) {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(launch);
                        // FIX: heavy apps (YouTube, Instagram) need more time
                        int defaultWait = isHeavyApp(pkg) ? 2800 : 1800;
                        sleep(step.optInt("wait_ms", defaultWait));
                        return true;
                    }
                    Log.w(TAG, "open_app: getLaunchIntent failed for '" + pkg + "', trying aliases...");
                }

                // ── FIX: Try known alias packages by app_name ─────────────────
                if (!appName.isEmpty()) {
                    String[] candidates = PKG_ALIASES.get(appName.toLowerCase(Locale.ROOT));
                    if (candidates != null) {
                        for (String candidate : candidates) {
                            Intent attempt = getPackageManager().getLaunchIntentForPackage(candidate);
                            if (attempt != null) {
                                attempt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                startActivity(attempt);
                                int defaultWait = isHeavyApp(candidate) ? 2800 : 1800;
                                sleep(step.optInt("wait_ms", defaultWait));
                                Log.d(TAG, "open_app: launched via alias → " + candidate);
                                return true;
                            }
                        }
                    }
                }

                // ── FIX: Scan installed packages for partial match ─────────────
                if (!appName.isEmpty()) {
                    String resolvedPkg = resolveByPartialName(appName);
                    if (resolvedPkg != null) {
                        Intent fallback = getPackageManager().getLaunchIntentForPackage(resolvedPkg);
                        if (fallback != null) {
                            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(fallback);
                            sleep(step.optInt("wait_ms", 2000));
                            Log.d(TAG, "open_app: launched via partial name scan → " + resolvedPkg);
                            return true;
                        }
                    }
                }

                // ── FIX: Last resort — open Play Store to install the app ──────
                if (!pkg.isEmpty()) {
                    try {
                        Intent playStore = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=" + pkg));
                        playStore.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(playStore);
                        sleep(1500);
                        Log.w(TAG, "open_app: '" + pkg + "' not installed — opened Play Store instead");
                        // Return false so the plan reports the failure clearly
                        return false;
                    } catch (Exception ignored) {}
                }

                Log.e(TAG, "open_app: completely failed for pkg='" + pkg + "' appName='" + appName + "'");
                return false;
            }

            // ── TAP ELEMENT ──────────────────────────────────────────────────
            case "tap_element": {
                String description  = step.optString("description", "");
                String fallbackText = step.optString("fallback_text", "");

                AccessibilityNodeInfo node = findNode(description, fallbackText);
                if (node == null) {
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
                AccessibilityNodeInfo focused = findFocusedEditText();
                if (focused != null) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    focused.recycle();
                    sleep(step.optInt("wait_ms", 350));
                    return ok;
                }
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
                String screenState = describeCurrentScreen();
                String result = verifyWithBackend(jarvisUrl, expected, screenState);
                if ("abort".equals(result)) {
                    Log.w(TAG, "screenshot_check: Groq says ABORT — " + expected);
                    return false;
                }
                if ("retry".equals(result)) {
                    sleep(800);
                    return true;
                }
                sleep(200);
                return true;
            }

            // ── TAP COORDS ───────────────────────────────────────────────────
            case "tap_coords": {
                float xPct = (float) step.optDouble("x_pct", 0.5);
                float yPct = (float) step.optDouble("y_pct", 0.5);
                return performTapCoords(xPct, yPct, step.optInt("wait_ms", 400));
            }

            default:
                Log.w(TAG, "Unknown action: " + action);
                return true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true for apps that are known to have slow cold-start times. */
    private boolean isHeavyApp(String pkg) {
        return pkg.contains("youtube") ||
               pkg.contains("instagram") ||
               pkg.contains("netflix") ||
               pkg.contains("tiktok") ||
               pkg.contains("spotify");
    }

    /**
     * Scan installed packages for one whose label or package name contains appName.
     * Used as last resort before Play Store fallback.
     */
    private String resolveByPartialName(String appName) {
        String lower = appName.toLowerCase(Locale.ROOT);
        try {
            List<android.content.pm.ResolveInfo> all = getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            for (android.content.pm.ResolveInfo ri : all) {
                String label = ri.loadLabel(getPackageManager()).toString().toLowerCase(Locale.ROOT);
                String pkgId = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
                if (label.contains(lower) || pkgId.contains(lower)) {
                    Log.d(TAG, "resolveByPartialName: '" + appName + "' → " + ri.activityInfo.packageName);
                    return ri.activityInfo.packageName;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveByPartialName: " + e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NODE FINDERS
    // ─────────────────────────────────────────────────────────────────────────

    private AccessibilityNodeInfo findNode(String description, String fallbackText) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;

        if (!fallbackText.isEmpty()) {
            List<AccessibilityNodeInfo> byText = root.findAccessibilityNodeInfosByText(fallbackText);
            for (AccessibilityNodeInfo n : byText) {
                if (isInteractive(n)) {
                    for (AccessibilityNodeInfo other : byText) {
                        if (other != n) other.recycle();
                    }
                    root.recycle();
                    return n;
                }
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

        if (matches && isInteractive(node)) return node;

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
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) {
            root.recycle();
            return focused;
        }
        if (focused != null) focused.recycle();
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
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
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
            default:       return w * 0.5f;
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
    // SCREEN DESCRIPTION
    // ─────────────────────────────────────────────────────────────────────────

    private String describeCurrentScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "screen unavailable";
        StringBuilder sb = new StringBuilder();
        describeNode(root, sb, 0);
        root.recycle();
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
            return "ok";
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
