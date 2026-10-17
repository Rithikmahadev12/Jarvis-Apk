package com.jarvis.assist.network;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * JarvisClient
 *
 * Handles all HTTP communication with the Jarvis backend.
 *
 * Key changes for real-account persistence:
 *   - /api/chat sends userName + userTitle from SessionStore (your real Jarvis identity)
 *   - fetchMemories() pulls /api/memory/:user before each chat so Jarvis
 *     remembers everything it knows about you across sessions
 *   - sessionId is the permanent one stored in SessionStore (never regenerated)
 *   - memories array is fetched fresh and injected into every /api/chat call
 */
public class JarvisClient {

    private static final String TAG         = "JarvisClient";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60,    TimeUnit.SECONDS)
            .writeTimeout(15,   TimeUnit.SECONDS)
            .build();

    // ─────────────────────────────────────────────────────────────────────────
    // Callbacks
    // ─────────────────────────────────────────────────────────────────────────

    public interface ChatCallback {
        void onSuccess(String reply);
        void onError(String error);
    }

    public interface MemoryCallback {
        void onMemories(JSONArray memories);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ping — used by setup screen to verify connection
    // ─────────────────────────────────────────────────────────────────────────

    public static void ping(String baseUrl, ChatCallback cb) {
        String url = baseUrl.replaceAll("/+$", "") + "/api/chat";
        try {
            JSONObject body = new JSONObject();
            body.put("message",   "ping");
            body.put("sessionId", "android_ping");
            body.put("userName",  "Rithik");
            body.put("userTitle", "Sir");

            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();

            HTTP.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    cb.onError(e.getMessage());
                }
                @Override public void onResponse(Call call, Response r) throws IOException {
                    if (r.isSuccessful()) cb.onSuccess("ok");
                    else cb.onError("HTTP " + r.code());
                    r.close();
                }
            });
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fetch memories — pulls /api/memory/rithik before chat
    // Jarvis stores everything it learns about you here (name prefs,
    // habits, past context). Injecting them into /api/chat gives Jarvis
    // full recall across every session.
    // ─────────────────────────────────────────────────────────────────────────

    public static void fetchMemories(String baseUrl, String userName, MemoryCallback cb) {
        String url = baseUrl.replaceAll("/+$", "")
                + "/api/memory/"
                + userName.toLowerCase().trim();

        Request req = new Request.Builder().url(url).get().build();

        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Memory fetch failed: " + e.getMessage());
                cb.onMemories(new JSONArray()); // non-fatal — continue without
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
    // Chat — main call with full identity + memories injected
    // ─────────────────────────────────────────────────────────────────────────

    public static void chat(
            String     baseUrl,
            String     message,
            String     sessionId,
            String     userName,
            String     userTitle,
            JSONArray  memories,
            ChatCallback cb
    ) {
        String url = baseUrl.replaceAll("/+$", "") + "/api/chat";
        try {
            JSONObject body = new JSONObject();
            body.put("message",   message);
            body.put("sessionId", sessionId);
            body.put("userName",  userName);
            body.put("userTitle", userTitle != null && !userTitle.isEmpty() ? userTitle : "Sir");

            // Inject memories so Jarvis has full recall of who you are
            if (memories != null && memories.length() > 0) {
                // Backend expects array of { fact, savedAt } objects
                body.put("memories", memories);
            }

            // Let Jarvis know this is a mobile voice client
            body.put("moodContext",    "voice");

            Log.d(TAG, "→ /api/chat  user=" + userName + "  session=" + sessionId);

            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();

            HTTP.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    cb.onError("Network error: " + e.getMessage());
                }
                @Override public void onResponse(Call call, Response r) throws IOException {
                    try {
                        String raw = r.body().string();
                        r.close();
                        if (!r.isSuccessful()) {
                            cb.onError("Server error " + r.code() + ": " + raw);
                            return;
                        }
                        JSONObject resp  = new JSONObject(raw);
                        String     reply = resp.optString("reply",
                                resp.optString("response",
                                        resp.optString("message", "...")));
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
}
