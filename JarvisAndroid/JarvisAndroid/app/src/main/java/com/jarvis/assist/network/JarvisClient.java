package com.jarvis.assist.network;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * JarvisClient — all HTTP calls to the Jarvis backend.
 *
 * Flow:
 *   1. ping()          — verify URL is a live Jarvis backend
 *   2. fetchProfiles() — get list of all real accounts on the server
 *   3. fetchProfile()  — load one account's full data (title, role, etc.)
 *   4. fetchMemories() — pull everything Jarvis remembers about this user
 *   5. chat()          — send a voice message with full identity + memories injected
 */
public class JarvisClient {

    private static final String TAG       = "JarvisClient";
    private static final MediaType JSON   = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60,    TimeUnit.SECONDS)
            .writeTimeout(15,   TimeUnit.SECONDS)
            .build();

    // ─────────────────────────────────────────────────────────────────────────
    // Data model for a Jarvis account returned by /api/profiles
    // ─────────────────────────────────────────────────────────────────────────

    public static class JarvisProfile {
        public final String name;
        public final String title;
        public final String role;  // "owner" or null
        public JarvisProfile(String name, String title, String role) {
            this.name  = name;
            this.title = title != null ? title : "Sir";
            this.role  = role;
        }
        public boolean isOwner() { return "owner".equals(role); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Callbacks
    // ─────────────────────────────────────────────────────────────────────────

    public interface SimpleCallback {
        void onSuccess();
        void onError(String error);
    }

    public interface ChatCallback {
        void onSuccess(String reply);
        void onError(String error);
    }

    /**
     * Extended callback that fires when the backend returns a phone-control
     * plan instead of (or alongside) a spoken reply.
     * VoiceOverlayActivity implements this to intercept PHONE_CONTROL_PLAN
     * and hand the steps straight to PhoneControlService.
     */
    public interface ChatCallbackFull extends ChatCallback {
        /** Called ONLY when action == "PHONE_CONTROL_PLAN"; raw plan JSON string. */
        void onPhoneControlPlan(String reply, String planJson);
    }

    public interface ProfilesCallback {
        void onSuccess(List<JarvisProfile> profiles);
        void onError(String error);
    }

    public interface ProfileCallback {
        void onSuccess(JarvisProfile profile);
        void onNotFound();
        void onError(String error);
    }

    public interface MemoryCallback {
        void onMemories(JSONArray memories);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. ping — verify the URL is actually a Jarvis backend
    // ─────────────────────────────────────────────────────────────────────────

    public static void ping(String baseUrl, SimpleCallback cb) {
        String url = clean(baseUrl) + "/api/profiles";
        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                cb.onError(e.getMessage());
            }
            @Override public void onResponse(Call call, Response r) throws IOException {
                r.close();
                if (r.isSuccessful()) cb.onSuccess();
                else cb.onError("HTTP " + r.code());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. fetchProfiles — GET /api/profiles → list of all accounts on the server
    //    Used to populate the account picker screen.
    // ─────────────────────────────────────────────────────────────────────────

    public static void fetchProfiles(String baseUrl, ProfilesCallback cb) {
        String url = clean(baseUrl) + "/api/profiles";
        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                cb.onError("Network error: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response r) throws IOException {
                try {
                    String body = r.body().string();
                    r.close();
                    if (!r.isSuccessful()) { cb.onError("Server error " + r.code()); return; }

                    JSONObject parsed   = new JSONObject(body);
                    JSONArray  arr      = parsed.optJSONArray("profiles");
                    List<JarvisProfile> list = new ArrayList<>();

                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject p = arr.getJSONObject(i);
                            String name  = p.optString("name", "").trim();
                            String title = p.optString("title", "Sir").trim();
                            String role  = p.optString("role", "");
                            if (!name.isEmpty()) {
                                list.add(new JarvisProfile(name, title, role));
                            }
                        }
                    }
                    cb.onSuccess(list);
                } catch (Exception e) {
                    cb.onError("Parse error: " + e.getMessage());
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. fetchProfile — GET /api/profile/:name → full account data
    //    Called after user picks their account to verify it exists and load
    //    their title, role, etc.
    // ─────────────────────────────────────────────────────────────────────────

    public static void fetchProfile(String baseUrl, String userName, ProfileCallback cb) {
        String url = clean(baseUrl) + "/api/profile/" + userName.toLowerCase().trim();
        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                cb.onError("Network error: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response r) throws IOException {
                try {
                    String body = r.body().string();
                    r.close();
                    if (!r.isSuccessful()) { cb.onError("Server error " + r.code()); return; }

                    JSONObject parsed = new JSONObject(body);
                    boolean found = parsed.optBoolean("found", false);
                    if (!found) { cb.onNotFound(); return; }

                    JSONObject profile = parsed.optJSONObject("profile");
                    if (profile == null) { cb.onNotFound(); return; }

                    String name  = profile.optString("name", userName).trim();
                    String title = profile.optString("title", "Sir").trim();
                    String role  = profile.optString("role", "");

                    cb.onSuccess(new JarvisProfile(name, title, role));
                } catch (Exception e) {
                    cb.onError("Parse error: " + e.getMessage());
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. fetchMemories — GET /api/memory/:user
    //    Pulls everything Jarvis remembers about this user and injects into chat.
    // ─────────────────────────────────────────────────────────────────────────

    public static void fetchMemories(String baseUrl, String userName, MemoryCallback cb) {
        String url = clean(baseUrl) + "/api/memory/" + userName.toLowerCase().trim();
        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Memory fetch failed (non-fatal): " + e.getMessage());
                cb.onMemories(new JSONArray());
            }
            @Override public void onResponse(Call call, Response r) throws IOException {
                try {
                    if (!r.isSuccessful()) { cb.onMemories(new JSONArray()); r.close(); return; }
                    String body = r.body().string();
                    r.close();
                    JSONObject parsed = new JSONObject(body);
                    JSONArray  mems   = parsed.optJSONArray("memories");
                    cb.onMemories(mems != null ? mems : new JSONArray());
                } catch (Exception e) {
                    Log.w(TAG, "Memory parse error: " + e.getMessage());
                    cb.onMemories(new JSONArray());
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. chat — POST /api/chat with full identity + memories
    // ─────────────────────────────────────────────────────────────────────────

    public static void chat(
            String    baseUrl,
            String    message,
            String    sessionId,
            String    userName,
            String    userTitle,
            JSONArray memories,
            ChatCallback cb
    ) {
        String url = clean(baseUrl) + "/api/chat";
        try {
            JSONObject body = new JSONObject();
            body.put("message",    message);
            body.put("sessionId",  sessionId);
            body.put("userName",   userName);
            body.put("userTitle",  userTitle != null && !userTitle.isEmpty() ? userTitle : "Sir");
            body.put("moodContext", "voice");
            body.put("source",     "android");   // ← tells Render to route phone-control back to us
            if (memories != null && memories.length() > 0) {
                body.put("memories", memories);
            }

            Log.d(TAG, "→ /api/chat  user=" + userName + "  session=" + sessionId);

            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            HTTP.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    cb.onError("Network error: " + e.getMessage());
                }
                @Override public void onResponse(Call call, Response r) throws IOException {
                    try {
                        String raw = r.body().string();
                        r.close();
                        if (!r.isSuccessful()) { cb.onError("Server error " + r.code() + ": " + raw); return; }
                        JSONObject resp   = new JSONObject(raw);
                        String     reply  = resp.optString("reply",
                                resp.optString("response",
                                        resp.optString("message", "...")));
                        String     action = resp.optString("action", "");

                        // ── Phone-control plan: Render saw source:"android" +
                        //    a phone-control intent and returned the accessibility
                        //    step plan inline.  If caller implements ChatCallbackFull
                        //    we hand it off; otherwise fall through to spoken reply.
                        if ("PHONE_CONTROL_PLAN".equals(action) && cb instanceof ChatCallbackFull) {
                            try {
                                JSONObject meta     = resp.optJSONObject("meta");
                                JSONObject planObj  = meta != null ? meta.optJSONObject("plan") : null;
                                String     planJson = planObj != null ? planObj.toString() : "{}";
                                ((ChatCallbackFull) cb).onPhoneControlPlan(reply, planJson);
                            } catch (Exception pe) {
                                cb.onSuccess(reply); // graceful fallback
                            }
                            return;
                        }

                        cb.onSuccess(reply);
                    } catch (Exception e) {
                        cb.onError("Parse error: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            cb.onError("Request build error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String clean(String url) {
        return url.trim().replaceAll("/+$", "");
    }
}
