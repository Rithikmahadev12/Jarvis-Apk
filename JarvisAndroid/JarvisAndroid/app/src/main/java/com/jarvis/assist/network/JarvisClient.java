package com.jarvis.assist.network;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * JarvisClient — fires POST /api/chat to Jarvis Render backend.
 *
 * Uses OkHttp with:
 *   - 30s connect / 90s read (Render cold start can be slow)
 *   - Automatic retry up to 2 times on network failure
 *   - Callback-based async so it never blocks the main thread
 *
 * Request body: { message, sessionId, userName, userTitle, userTimezone }
 * Response:     { reply: "..." }  OR  { error: "..." }
 */
public class JarvisClient {

    private static final String TAG          = "JarvisClient";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RETRIES     = 2;

    // Singleton OkHttpClient — shared across all calls
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public interface ChatCallback {
        void onSuccess(String reply);
        void onError(String error);
    }

    // ── Main send method ─────────────────────────────────────────────────────

    public static void sendMessage(
            String baseUrl,
            String message,
            String sessionId,
            String userName,
            String userTitle,
            String timezone,
            ChatCallback cb
    ) {
        sendWithRetry(baseUrl, message, sessionId, userName, userTitle, timezone, cb, 0);
    }

    private static void sendWithRetry(
            String baseUrl,
            String message,
            String sessionId,
            String userName,
            String userTitle,
            String timezone,
            ChatCallback cb,
            int attempt
    ) {
        String chatUrl = baseUrl.replaceAll("/+$", "") + "/api/chat";

        JSONObject body = new JSONObject();
        try {
            body.put("message",      message);
            body.put("sessionId",    sessionId);
            body.put("userName",     userName);
            body.put("userTitle",    userTitle);
            body.put("userTimezone", timezone);
        } catch (JSONException e) {
            cb.onError("JSON build failed: " + e.getMessage());
            return;
        }

        RequestBody reqBody = RequestBody.create(body.toString(), JSON_TYPE);
        Request request = new Request.Builder()
                .url(chatUrl)
                .addHeader("Accept",       "application/json")
                .addHeader("Content-Type", "application/json")
                .post(reqBody)
                .build();

        HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (attempt < MAX_RETRIES) {
                    Log.w(TAG, "Attempt " + attempt + " failed, retrying: " + e.getMessage());
                    sendWithRetry(baseUrl, message, sessionId, userName, userTitle, timezone, cb, attempt + 1);
                } else {
                    Log.e(TAG, "All retries exhausted: " + e.getMessage());
                    cb.onError("Network error: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String rawBody = "";
                try {
                    rawBody = response.body() != null ? response.body().string() : "";
                } catch (IOException e) {
                    cb.onError("Failed to read response body");
                    return;
                }

                if (!response.isSuccessful()) {
                    // Try to extract Jarvis error field
                    String errMsg = extractField(rawBody, "error");
                    if (errMsg.isEmpty()) errMsg = "HTTP " + response.code();
                    if (attempt < MAX_RETRIES && response.code() >= 500) {
                        Log.w(TAG, "Server error " + response.code() + ", retrying...");
                        sendWithRetry(baseUrl, message, sessionId, userName, userTitle, timezone, cb, attempt + 1);
                    } else {
                        cb.onError(errMsg);
                    }
                    return;
                }

                // Extract reply field
                String reply = extractField(rawBody, "reply");
                if (reply.isEmpty()) {
                    // Fallback — maybe it's a plain string response
                    reply = rawBody.trim();
                }

                if (reply.isEmpty()) {
                    cb.onError("Empty response from Jarvis");
                } else {
                    cb.onSuccess(reply);
                }
            }
        });
    }

    // ── Health check — pings the Render server to see if it's awake ──────────

    public static void ping(String baseUrl, ChatCallback cb) {
        String pingUrl = baseUrl.replaceAll("/+$", "") + "/api/health";
        // Many Render deployments just return 200 on GET /
        // Try health first, fall back to root
        Request req = new Request.Builder()
                .url(pingUrl)
                .get()
                .build();

        HTTP.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Try root URL
                Request rootReq = new Request.Builder()
                        .url(baseUrl.replaceAll("/+$", "") + "/")
                        .get()
                        .build();
                HTTP.newCall(rootReq).enqueue(new Callback() {
                    @Override public void onFailure(Call c, IOException ex) {
                        cb.onError("Jarvis unreachable: " + ex.getMessage());
                    }
                    @Override public void onResponse(Call c, Response r) {
                        if (r.isSuccessful() || r.code() == 404) {
                            cb.onSuccess("pong");
                        } else {
                            cb.onError("HTTP " + r.code());
                        }
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (response.isSuccessful()) {
                    cb.onSuccess("pong");
                } else {
                    cb.onError("HTTP " + response.code());
                }
            }
        });
    }

    // ── Utility: pull a string field from raw JSON without full parsing ───────

    private static String extractField(String json, String field) {
        if (json == null || json.isEmpty()) return "";
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString(field, "");
        } catch (JSONException e) {
            // regex fallback for chunked/partial JSON
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            java.util.regex.Matcher m = p.matcher(json);
            if (m.find()) return m.group(1).replace("\\n", "\n").replace("\\\"", "\"");
            return "";
        }
    }
}
