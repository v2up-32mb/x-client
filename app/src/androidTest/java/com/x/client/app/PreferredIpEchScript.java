package com.x.client.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import static com.x.client.app.PreferredIpV2Test.*;

/** Source-derived Go ECH wire script, not a Go/cache/network test. Only preparation can change
 * cache metadata; a non-prepare Start is rejected only for enabled missing/expired ready state. */
final class PreferredIpEchScript {
    enum Preparation { READY, EXPIRED_READY, FALLBACK }
    static final long INITIAL_TIME = 1730000002500L;
    static final class PrepareRequired extends IOException {
        PrepareRequired() { super("prepare_required: ECH配置缺失或过期，请统一prepare（不清队列/账本）"); }
    }
    final Backend backend;
    final ArrayDeque<Preparation> preparations = new ArrayDeque<>();
    final Map<String, JSONObject> cache = new HashMap<>();
    long now = INITIAL_TIME;
    int rejected;
    boolean requireEnabled;
    PreferredIpEchScript(Backend backend) { this.backend = backend; }
    synchronized void prepare(Preparation value) { preparations.add(value); }
    synchronized void advanceTo(long time) { check(time >= now, "ECH script clock is monotonic"); now = time; }
    synchronized long time() { return now; }
    static JSONObject metadata(String key, String state, long at, long expiry, boolean hit, String error) throws Exception {
        return new JSONObject().put("key", key).put("state", state).put("prepared_at", at)
            .put("expires_at", expiry).put("cache_hit", hit).put("error", error);
    }
    String key(JSONObject request) throws Exception {
        JSONArray target = new JSONArray(backend.goTargetPayload(request.getJSONObject("options")));
        String tuple = new JSONArray().put(request.getString("network_id")).put(target.getBoolean(3))
            .put(target.getString(4)).put(target.getJSONArray(5)).toString().replace("\\/", "/");
        return PreferredIpIds.opaque("ech", tuple).substring("ech-".length());
    }
    synchronized JSONObject beforeStart(JSONObject request) throws Exception {
        String operation = request.getString("operation");
        if ("fetch".equals(operation)) return metadata("", "", 0, 0, false, "");
        boolean enabled = request.getJSONObject("options").getBoolean("enable_ech");
        backend.require(!requireEnabled || enabled, "enabled ECH case sent disabled request");
        String key = key(request), session = request.optString("session_id");
        if (!enabled) return metadata("prepare".equals(operation) ? key : "", "disabled", 0, 0, false, "");
        JSONObject old = session.isEmpty() ? null : cache.get(session + "|" + key);
        boolean usable = old != null && (old.getLong("expires_at") == 0 || now < old.getLong("expires_at"));
        if (!"prepare".equals(operation)) {
            if (!usable) { rejected++; throw new PrepareRequired(); }
            return new JSONObject(old.toString()).put("cache_hit", true);
        }
        if (usable && !request.optBoolean("refresh_ech")) return new JSONObject(old.toString()).put("cache_hit", true);
        Preparation plan = preparations.poll();
        backend.require(plan != null, "enabled prepare requires an explicit legal ECH script");
        if (plan == Preparation.FALLBACK)
            return metadata(key, "fallback", now, 0, false, "未获取ECH配置，使用验证证书的标准TLS");
        return metadata(key, "ready", now, plan == Preparation.EXPIRED_READY ? now : now + 1000, false, "");
    }
    synchronized void matchingReply(Task task, JSONObject response) throws Exception {
        JSONObject wire = response.getJSONObject("ech"), expected = task.echAtAdmission;
        for (String field : new String[]{"key", "state", "prepared_at", "expires_at", "cache_hit", "error"})
            backend.require(wire.get(field).toString().equals(expected.get(field).toString()), "ECH wire field does not match its actual request/admission stage: " + field);
        String state = wire.getString("state"); long at = wire.getLong("prepared_at"), expiry = wire.getLong("expires_at");
        boolean fetch = "fetch".equals(task.request.getString("operation"));
        boolean enabled = !fetch && task.request.getJSONObject("options").getBoolean("enable_ech");
        backend.require(enabled ? ("ready".equals(state) || "fallback".equals(state)) : (fetch ? state.isEmpty() : "disabled".equals(state)), "illegal ECH state for actual enable_ech");
        backend.require(response.getLong("updated_at") >= response.getLong("started_at")
            && response.getLong("updated_at") >= at, "ECH metadata cannot be newer than its actual task snapshot");
        if (enabled) {
            backend.require(wire.getString("key").equals(key(task.request)) && at > 0, "ECH key/prepared_at must belong to actual request");
            backend.require("ready".equals(state) ? expiry >= at && wire.getString("error").isEmpty()
                : expiry == 0 && !wire.getString("error").isEmpty(), "illegal ready/fallback ECH expiry/error");
        } else {
            backend.require(wire.getString("key").equals(!fetch && "prepare".equals(task.request.getString("operation")) ? key(task.request) : ""), "disabled/fetch ECH key follows actual Go stage");
            backend.require(at == 0 && expiry == 0 && !wire.getBoolean("cache_hit") && wire.getString("error").isEmpty(), "disabled/fetch ECH metadata must remain empty");
        }
        if (enabled && "prepare".equals(task.request.getString("operation"))
                && response.optBoolean("worker_done") && "completed".equals(response.optString("state"))) {
            if (task.request.optString("session_id").isEmpty()) cache.clear();
            cache.put(task.sessionId + "|" + wire.getString("key"), new JSONObject(wire.toString()));
        }
    }
    synchronized void assertConsumed() { check(preparations.isEmpty(), "every scripted preparation was actually admitted"); }
}
