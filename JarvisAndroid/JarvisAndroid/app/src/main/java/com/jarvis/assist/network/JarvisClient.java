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
 *   1. ping()             — verify URL is a live Jarvis backend
 *   2. fetchProfiles()    — get list of all real accounts on the server
 *   3. fetchProfile()     — load one account's full data (title, role, etc.)
 *   4. fetchMemories()    — pull everything Jarvis remembers about this user
 *   5. chat()             — send a voice message with full identity + memories injected
 *   6. fetchWallet()      — GET /api/wallet/:name (Solana balance)
 *   7. fetchReminders()   — GET /api/reminders  (upcoming one-off reminders/timers)
 *   8. fetchHabits()      — GET /api/habits/due  (recurring habit nudge check)
 *   9. fetchTodos()       — GET /api/todos (night-shift research queue)
 *   10. fetchBriefing()   — GET /api/briefing/:user (today's morning briefing)
 */
public class JarvisClient {

    private static final String TAG     = "JarvisClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60,    TimeUnit.SECONDS)
            .writeTimeout(15,   TimeUnit.SECONDS)
            .build();

    // ─────────────────────────────────────────────────────────────────────────
    // Data models
    // ─────────────────────────────────────────────────────────────────────────

    public static class JarvisProfile {
        public final String name;
        public final String title;
        public final String role;
        public JarvisProfile(String name, String title, String role) {
            this.name  = name;
            this.title = title != null ? title : "Sir";
            this.role  = role;
        }
        public boolean isOwner() { return "owner".equals(role); }
    }

    /** A single upcoming reminder / timer from /api/reminders */
    public static class JarvisReminder {
        public final String id;
        public final String label;
        public final String type;        // "reminder" | "timer" | "event"
        public final String fireAt;      // ISO 8601
        public final boolean isDue;

        public JarvisReminder(String id, String label, String type, String fireAt, boolean isDue) {
            this.id     = id;
            this.label  = label;
            this.type   = type;
            this.fireAt = fireAt;
            this.isDue  = isDue;
        }
    }

    /** A single todo / research item from /api/todos */
    public static class JarvisTodo {
        public final String id;
        public final String text;
        public final String status; // "pending" | "researching" | "done"
        public final String addedAt;

        public JarvisTodo(String id, String text, String status, String addedAt) {
            this.id      = id;
            this.text    = text;
            this.status  = status;
            this.addedAt = addedAt;
        }
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

    public interface ChatCallbackFull extends ChatCallback {
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

    public interface WalletCallback {
        void onSuccess(String address, double solBalance, double usdValue);
        void onNotConfigured();
        void onError(String error);
    }

    public interface RemindersCallback {
        void onSuccess(List<JarvisReminder> reminders);
        void onError(String error);
    }

    public interface HabitsCallback {
        /** @param nudgePrompt null if no missed habit nudge; otherwise the prompt text */
        void onResult(String nudgePrompt);
    }

    public interface TodosCallback {
        void onSuccess(List<JarvisTodo> todos);
        void onError(String error);
    }

    public interface BriefingCallback {
        /** @param briefingText null if no briefing today yet */
        void onResult(String briefingText);
    }

    public interface StringCallback {
        void onResult(String value);
        void onError(String error);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. ping
    // ─────────────────────────────────────────────────────────────────────────

    public static void ping(String baseUrl, SimpleCallback cb) {
        String url = clean(baseUrl) + "/api/profiles";
        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { cb.onError(e.getMessage()); }
            @Override public void onResponse(Call call, Response r) throws IOException {
                r.close();
                if (r.isSuccessful()) cb.onSuccess();
                else cb.onError("HTTP " + r.code());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. fetchProfiles
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
                    JSONObject parsed = new JSONObject(body);
                    JSONArray  arr    = parsed.optJSONArray("profiles");
                    List<JarvisProfile> list = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject p = arr.getJSONObject(i);
                            String name  = p.optString("name", "").trim();
                            String title = p.optString("title", "Sir").trim();
                            String role  = p.optString("role", "");
                            if (!name.isEmpty()) list.add(new JarvisProfile(name, title, role));
                        }
                    }
                    cb.onSuccess(list);
                } catch (Exception e) { cb.onError("Parse error: " + e.getMessage()); }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. fetchProfile
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
                    if (!parsed.optBoolean("found", false)) { cb.onNotFound(); return; }
                    JSONObject profile = parsed.optJSONObject("profile");
                    if (profile == null) { cb.onNotFound(); return; }
                    String name  = profile.optString("name", userName).trim();
                    String title = profile.optString("title", "Sir").trim();
                    String role  = profile.optString("role", "");
                    cb.onSuccess(new JarvisProfile(name, title, role));
                } catch (Exception e) { cb.onError("Parse error: " + e.getMessage()); }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. fetchMemories
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
    // 5. chat
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
            body.put("source",     "android");
            if (memories != null && memories.length() > 0) body.put("memories", memories);

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
                        JSONObject resp  = new JSONObject(raw);
                        String     reply = resp.optString("reply",
                                resp.optString("response",
                                resp.optString("message", "...")));
                        String     action = resp.optString("action", "");

                        if ("PHONE_CONTROL_PLAN".equals(action) && cb instanceof ChatCallbackFull) {
                            try {
                                JSONObject meta    = resp.optJSONObject("meta");
                                JSONObject planObj = meta != null ? meta.optJSONObject("plan") : null;
                                String     planJson = planObj != null ? planObj.toString() : "{}";
                                ((ChatCallbackFull) cb).onPhoneControlPlan(reply, planJson);
                            } catch (Exception pe) { cb.onSuccess(reply); }
                            return;
                        }
                        cb.onSuccess(reply);
                    } catch (Exception e) { cb.onError("Parse error: " + e.getMessage()); }
                }
            });
        } catch (Exception e) { cb.onError("Request build error: " + e.getMessage()); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. fetchWallet — GET /api/wallet/:name
    // ─────────────────────────────────────────────────────────────────────────

    public static void fetchWallet(String baseUrl, String name, WalletCallback cb) {
        if (name == null || name.isEmpty()) { cb.onNotConfigured(); return; }
        String url = clean(baseUrl) + "/api/wallet/" + name.trim();
        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { cb.onError(e.getMessage()); }
            @Override public void onResponse(Call call, Response r) throws IOException {
                try {
                    String raw = r.body().string();
                    r.close();
                    if (r.code() == 404) { cb.onNotConfigured(); return; }
                    if (!r.isSuccessful()) { cb.onError("HTTP " + r.code()); return; }
                    JSONObject j = new JSONObject(raw);
                    if (j.has("error")) { cb.onNotConfigured(); return; }
                    String addr = j.optString("address", "");
                    double bal  = j.optDouble("balance", 0.0);
                    double usd  = j.optDouble("usdValue", 0.0);
                    if (addr.isEmpty()) { cb.onNotConfigured(); return; }
                    cb.onSuccess(addr, bal, usd);
                } catch (Exception e) { cb.onError("Parse error: " + e.getMessage()); }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. fetchReminders — GET /api/reminders → upcoming timers + reminders
    //    Returns list sorted by fireAt ascending.
    // ─────────────────────────────────────────────────────────────────────────

    public static void fetchReminders(String baseUrl, RemindersCallback cb) {
        String url = clean(baseUrl) + "/api/reminders";
        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                cb.onError("Network error: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response r) throws IOException {
                try {
                    String raw = r.body().string();
                    r.close();
                    if (!r.isSuccessful()) { cb.onError("HTTP " + r.code()); return; }
                    JSONObject parsed = new JSONObject(raw);
                    // Server returns { items: [...] }
                    JSONArray arr = parsed.optJSONArray("items");
                    if (arr == null) arr = parsed.optJSONArray("reminders");
                    List<JarvisReminder> list = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            try {
                                JSONObject item = arr.getJSONObject(i);
                                String id     = item.optString("id", "r" + i);
                                String label  = item.optString("label",
                                        item.optString("text",
                                        item.optString("message", "Reminder")));
                                String type   = item.optString("type", "reminder");
                                String fireAt = item.optString("fireAt",
                                        item.optString("at", ""));
                                boolean due   = item.optBoolean("due", false);
                                list.add(new JarvisReminder(id, label, type, fireAt, due));
                            } catch (Exception ignored) {}
                        }
                    }
                    cb.onSuccess(list);
                } catch (Exception e) { cb.onError("Parse error: " + e.getMessage()); }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. fetchHabits — GET /api/habits/due?tz=&sessionId=
    //    Returns { prompt: "..." } or { prompt: null }
    // ─────────────────────────────────────────────────────────────────────────

    public static void fetchHabits(String baseUrl, String userTitle, String sessionId,
                                   String tz, HabitsCallback cb) {
        String safeTitle   = (userTitle   != null && !userTitle.isEmpty())   ? userTitle   : "Sir";
        String safeSess    = (sessionId   != null && !sessionId.isEmpty())   ? sessionId   : "android";
        String safeTz      = (tz          != null && !tz.isEmpty())          ? tz          : "America/Los_Angeles";

        String url = clean(baseUrl) + "/api/habits/due"
                + "?userTitle=" + encodeParam(safeTitle)
                + "&sessionId=" + encodeParam(safeSess)
                + "&tz="        + encodeParam(safeTz);

        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Habits fetch failed (non-fatal): " + e.getMessage());
                cb.onResult(null);
            }
            @Override public void onResponse(Call call, Response r) throws IOException {
                try {
                    String raw = r.body().string();
                    r.close();
                    if (!r.isSuccessful()) { cb.onResult(null); return; }
                    JSONObject parsed = new JSONObject(raw);
                    String prompt = parsed.optString("prompt", "");
                    cb.onResult(prompt.isEmpty() ? null : prompt);
                } catch (Exception e) { cb.onResult(null); }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. fetchTodos — GET /api/todos  (night-shift research list)
    //    Server returns raw JSON array [ {id,text,status,addedAt,...} ]
    //    Falls back to /api/todos?status=pending if the server returns all.
    // ─────────────────────────────────────────────────────────────────────────

    public static void fetchTodos(String baseUrl, TodosCallback cb) {
        // Jarvis todos.js exposes its data through /api/chat; we route via
        // a direct GET to the raw data file as-served or fall back gracefully.
        // The server.js currently has NO dedicated GET /api/todos route but the
        // Night Shift script reads data/todos.json directly. We hit the briefing
        // endpoint which includes pending todos as part of context, OR we add
        // our own lightweight GET. Here we try /api/todos (works if owner added it),
        // falling back to /api/briefing/:user which includes todo count.
        String url = clean(baseUrl) + "/api/todos";
        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Todos fetch failed: " + e.getMessage());
                cb.onSuccess(new ArrayList<>());
            }
            @Override public void onResponse(Call call, Response r) throws IOException {
                try {
                    String raw = r.body().string();
                    r.close();
                    if (!r.isSuccessful()) {
                        // 404 means the route doesn't exist yet — silently return empty
                        cb.onSuccess(new ArrayList<>());
                        return;
                    }
                    List<JarvisTodo> list = new ArrayList<>();
                    // Server may return [] array or { todos: [...] }
                    if (raw.trim().startsWith("[")) {
                        JSONArray arr = new JSONArray(raw);
                        for (int i = 0; i < arr.length(); i++) {
                            try {
                                JSONObject item = arr.getJSONObject(i);
                                list.add(parseTodo(item, i));
                            } catch (Exception ignored) {}
                        }
                    } else {
                        JSONObject parsed = new JSONObject(raw);
                        JSONArray arr = parsed.optJSONArray("todos");
                        if (arr == null) arr = parsed.optJSONArray("items");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                try { list.add(parseTodo(arr.getJSONObject(i), i)); }
                                catch (Exception ignored) {}
                            }
                        }
                    }
                    cb.onSuccess(list);
                } catch (Exception e) {
                    Log.w(TAG, "Todos parse error: " + e.getMessage());
                    cb.onSuccess(new ArrayList<>());
                }
            }
        });
    }

    private static JarvisTodo parseTodo(JSONObject item, int fallbackIdx) {
        String id      = item.optString("id", "todo_" + fallbackIdx);
        String text    = item.optString("text", item.optString("topic", "Unknown topic"));
        String status  = item.optString("status", "pending");
        String addedAt = item.optString("addedAt", "");
        return new JarvisTodo(id, text, status, addedAt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. fetchBriefing — GET /api/briefing/:user
    //     Returns { briefing: "..." } or { briefing: null }
    // ─────────────────────────────────────────────────────────────────────────

    public static void fetchBriefing(String baseUrl, String userName, BriefingCallback cb) {
        if (userName == null || userName.isEmpty()) { cb.onResult(null); return; }
        String url = clean(baseUrl) + "/api/briefing/" + userName.toLowerCase().trim();
        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Briefing fetch failed: " + e.getMessage());
                cb.onResult(null);
            }
            @Override public void onResponse(Call call, Response r) throws IOException {
                try {
                    String raw = r.body().string();
                    r.close();
                    if (!r.isSuccessful()) { cb.onResult(null); return; }
                    JSONObject parsed   = new JSONObject(raw);
                    String     briefing = parsed.optString("briefing", "");
                    cb.onResult(briefing.isEmpty() ? null : briefing);
                } catch (Exception e) { cb.onResult(null); }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String clean(String url) {
        return url.trim().replaceAll("/+$", "");
    }

    private static String encodeParam(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
