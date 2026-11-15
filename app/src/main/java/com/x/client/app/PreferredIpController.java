package com.x.client.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import xclient.Xclient;

/** Retained per-page controller. All Go calls, parsing and disk writes are off the main thread. */
final class PreferredIpController {
    interface Listener { void changed(State state); }
    interface Work { void run() throws Exception; }
    static final class State {
        JSONObject data, snapshot, results, preview, parse;
        JSONArray chosen;
        String message, taskId, network, referenceId, referenceSummary;
        boolean busy, stale;
    }
    private final Context context;
    private final PreferredIpStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService canceller = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private volatile Listener listener;
    private volatile String taskId = "";
    private volatile boolean foreground;
    private volatile String network;
    private boolean registered;
    private JSONObject data, snapshot = new JSONObject(), preview, parsed;
    private JSONObject testResults = new JSONObject();
    private JSONArray draftCandidates = new JSONArray();
    private final Set<String> chosen = new LinkedHashSet<>();
    private String message = "", referenceId, referenceSummary = "";
    private boolean busy, scanning, testedThisSession;
    private String parseSource, parseKind, parseUrl = "";
    private boolean parseAppend = true;
    private volatile State latest;

    PreferredIpController(Context context, String referenceId) {
        this.context = context.getApplicationContext();
        this.store = new PreferredIpStore(this.context);
        this.referenceId = referenceId;
        this.network = PreferredIpNetwork.id(context);
        execute(() -> {
            data = store.read();
            snapshot = data.getJSONObject("last_test");
            testResults = snapshot;
            for (int i = 0; i < data.getJSONArray("selected").length(); i++) {
                chosen.add(data.getJSONArray("selected").getJSONObject(i).getString("address"));
            }
            publish();
        });
    }

    void attach(Listener next) {
        foreground = true;
        listener = next;
        if (latest != null) next.changed(latest);
        String now = PreferredIpNetwork.id(context);
        if (!now.equals(network)) networkChanged();
        if (!registered) {
            context.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(callback);
            registered = true;
        }
    }

    void detach(boolean rotating) {
        listener = null;
        if (!rotating) {
            foreground = false;
            cancel("离开页面，任务已取消；已保存池不变");
            if (registered) {
                context.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback);
                registered = false;
            }
        }
    }

    void close() {
        // Queued cancellation must run before executor shutdown. No Activity reference is retained.
        worker.shutdown();
        canceller.shutdown();
    }

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { networkChanged(); }
        @Override public void onLost(Network n) { networkChanged(); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { networkChanged(); }
        @Override public void onLinkPropertiesChanged(Network n, LinkProperties p) { networkChanged(); }
    };

    private void networkChanged() {
        if (worker.isShutdown()) return;
        String now = PreferredIpNetwork.id(context);
        if (!now.equals(network)) {
            network = now;
            cancel("网络已切换，任务取消；已有结果标记为旧网络，请重测");
        } else {
            try { PreferredIpNetwork.requireDirect(context); }
            catch (Exception error) { if (!taskId.isEmpty()) cancel(error.getMessage()); }
        }
    }

    void cancel(String reason) {
        generation.incrementAndGet();
        String running = taskId;
        if (!running.isEmpty() && !canceller.isShutdown()) {
            try { canceller.execute(() -> Xclient.cancelPreferredIPTask(running)); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
        execute(() -> { preview = null; message = reason; publish(); });
    }

    private void execute(Work work) {
        if (worker.isShutdown()) return;
        try { worker.execute(() -> {
            try { work.run(); }
            catch (Exception error) { message = error.getMessage() == null ? error.toString() : error.getMessage(); publish(); }
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    void inspectReference(String id) {
        execute(() -> {
            Preferences p = new Preferences(context).forProfile(id);
            JSONObject merged = PreferredIpResolver.baseParams(p);
            JSONObject target = PreferredIpResolver.target(p, merged);
            referenceSummary = target.getString("target_host") + ":" + target.getInt("target_port")
                    + " · ECH " + target.getBoolean("enable_ech") + "\n" + target.getString("ech_domain")
                    + " · " + target.getString("dns_server")
                    + (Preferences.PROTOCOL_X_TUNNEL.equals(p.getProtocol()) ? "\n每IP " + merged.getInt("connections") + " 条通道（高级JSON合并后）" : "")
                    + (PreferredIpResolver.conflict(p) ? "\n高级relay_nodes冲突，保留独立" : "");
            referenceId = id;
            publish();
        });
    }

    private void publish() {
        try {
            State out = new State();
            out.data = data == null ? PreferredIpStore.empty() : new JSONObject(data.toString());
            out.snapshot = new JSONObject(snapshot.toString());
            out.results = new JSONObject(testResults.toString());
            out.preview = preview == null ? null : new JSONObject(preview.toString());
            out.parse = parsed == null ? null : new JSONObject(parsed.toString());
            out.chosen = new JSONArray(chosen);
            out.message = message;
            out.taskId = taskId;
            out.network = PreferredIpNetwork.label(context);
            out.referenceId = referenceId;
            out.referenceSummary = referenceSummary;
            out.busy = busy;
            out.stale = !testResults.optString("network_id", network).equals(network);
            latest = out;
            main.post(() -> { Listener current = listener; if (current != null) current.changed(out); });
        } catch (Exception error) {
            android.util.Log.e("PreferredIp", "状态发布失败", error);
        }
    }

    private void check(long epoch, String net) {
        if (!foreground || epoch != generation.get() || !net.equals(PreferredIpNetwork.id(context))) {
            throw new IllegalStateException("任务已取消或网络已变化；不替换已保存池");
        }
        PreferredIpNetwork.requireDirect(context);
    }

    private JSONObject run(JSONObject request, long epoch, String net) throws Exception {
        check(epoch, net);
        request.put("network_id", net);
        taskId = Xclient.startPreferredIPTask(request.toString());
        String id = taskId;
        try {
            while (true) {
                check(epoch, net);
                JSONObject next = new JSONObject(Xclient.getPreferredIPTask(id));
                if (!id.equals(next.getString("id")) || !net.equals(next.getString("network_id"))) {
                    throw new IllegalStateException("忽略陈旧任务响应");
                }
                snapshot = next;
                if (scanning) testResults = next;
                publish();
                String state = next.getString("state");
                if (!"queued".equals(state) && !"running".equals(state)) {
                    if (!"completed".equals(state)) throw new IllegalStateException(next.optString("error", state));
                    check(epoch, net);
                    return next;
                }
                Thread.sleep(500);
            }
        } finally {
            Xclient.cancelPreferredIPTask(id);
            // Read the actual terminal/partial snapshot after cancellation, never fabricate progress.
            try { snapshot = new JSONObject(Xclient.getPreferredIPTask(id)); } catch (Exception ignored) { }
            taskId = "";
        }
    }

    private void operation(Work work) {
        execute(() -> {
            if (data == null) throw new IllegalStateException("优选库尚未加载");
            busy = true; preview = null; message = ""; publish();
            try { work.run(); }
            finally { busy = false; publish(); }
        });
    }

    void fetch(String id, String url, String kind) {
        long epoch = generation.get();
        operation(() -> {
            String net = network;
            if ("cidr6".equals(kind) && !PreferredIpNetwork.ipv6(context)) throw new IllegalStateException("当前直连网络没有IPv6地址");
            JSONObject result = run(new JSONObject().put("operation", "fetch").put("source",
                    new JSONObject().put("id", id).put("url", url).put("kind", kind)), epoch, net);
            if (result.getJSONArray("errors").length() > 0) {
                parsed = result; parseSource = id; parseKind = kind; parseUrl = url; parseAppend = false;
                message = "来源含无效行，旧快照保留；请确认是否仅导入有效行";
                return;
            }
            replaceSource(id, url, kind, result, false);
            message = "来源更新成功；未修改全局已选池";
        });
    }

    void parse(String content, String id, String kind) {
        execute(() -> {
            if (content.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw new IllegalArgumentException("文本超过2MiB");
            parsed = new JSONObject(Xclient.parsePreferredIPList(content, id, kind));
            parseSource = id; parseKind = kind; parseUrl = ""; parseAppend = true;
            message = "解析完成，请检查错误行并确认导入";
            publish();
        });
    }

    void importFile(Uri uri) {
        execute(() -> {
            try (InputStream stream = context.getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (stream == null) throw new IllegalArgumentException("无法读取文件");
                byte[] buffer = new byte[8192];
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    if (bytes.size() + n > 2 * 1024 * 1024) throw new IllegalArgumentException("TXT超过2MiB");
                    bytes.write(buffer, 0, n);
                }
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                parsed = new JSONObject(Xclient.parsePreferredIPList(text, "txt", "manual"));
                parseSource = "txt"; parseKind = "manual"; parseUrl = ""; parseAppend = true;
                message = "TXT解析完成，请检查并确认有效行";
                publish();
            }
        });
    }

    void legacy() {
        execute(() -> {
            StringBuilder text = new StringBuilder();
            Preferences p = new Preferences(context);
            for (Preferences.ProfileInfo profile : p.getProfileList()) {
                Preferences entry = p.forProfile(profile.id);
                String raw = Preferences.PROTOCOL_X_TUNNEL.equals(entry.getProtocol()) ? entry.getXtRelayNodes() : entry.getPrefIp();
                for (String item : raw.split("[,\\r\\n]+")) {
                    String[] parts = item.trim().split("#", 2);
                    String address = parts[0];
                    if (address.isEmpty()) continue;
                    if (address.matches("[0-9.]+")) address += ":443";
                    else if (address.startsWith("[") && address.endsWith("]")) address += ":443";
                    else if (!address.startsWith("[") && address.chars().filter(c -> c == ':').count() > 1) address = "[" + address + "]:443";
                    text.append(address);
                    if (parts.length > 1) text.append('#').append(parts[1]);
                    text.append('\n');
                }
            }
            parsed = new JSONObject(Xclient.parsePreferredIPList(text.toString(), "legacy", "legacy"));
            parseSource = "legacy"; parseKind = "legacy"; parseUrl = ""; parseAppend = true;
            message = "旧域名和无效串不导入，原节点值完整保留（不收集出口FallbackIp）";
            publish();
        });
    }

    void acceptParsed() {
        execute(() -> {
            if (parsed == null) return;
            replaceSource(parseSource, parseUrl, parseKind, parsed, parseAppend);
            parsed = null;
            message = "已明确导入有效行；原配置和已选池未改变";
            publish();
        });
    }

    private void replaceSource(String id, String url, String kind, JSONObject sourceData, boolean append) throws Exception {
        JSONObject next = store.read();
        JSONArray sources = next.getJSONArray("sources");
        JSONObject source = null;
        for (int i = 0; i < sources.length(); i++) {
            if (id.equals(sources.getJSONObject(i).getString("id"))) source = sources.getJSONObject(i);
        }
        if (source == null) { source = new JSONObject().put("id", id); sources.put(source); }
        JSONArray candidates = sourceData.optJSONArray("candidates");
        if (candidates == null) candidates = new JSONArray();
        if (append && source.optJSONArray("candidates") != null) candidates = merge(source.getJSONArray("candidates"), candidates, "address");
        source.put("url", url).put("kind", kind).put("enabled", true).put("updated_at", System.currentTimeMillis())
                .put("candidates", candidates).put("prefixes", sourceData.optJSONArray("prefixes") == null ? new JSONArray() : sourceData.getJSONArray("prefixes"));
        rebuildSources(next);
        data = store.write(next, next.getLong("revision"));
    }

    void enableSource(String id, boolean enabled) {
        execute(() -> {
            JSONObject next = store.read();
            JSONArray sources = next.getJSONArray("sources");
            for (int i = 0; i < sources.length(); i++) {
                if (id.equals(sources.getJSONObject(i).getString("id"))) sources.getJSONObject(i).put("enabled", enabled);
            }
            rebuildSources(next);
            data = store.write(next, next.getLong("revision"));
            message = "来源参与状态已更新；已选池不变，停用来源中的已选IP需复测";
            preview = null;
            publish();
        });
    }

    private static void rebuildSources(JSONObject next) throws Exception {
        JSONArray all = new JSONArray(), prefixes = new JSONArray(), sources = next.getJSONArray("sources");
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.getJSONObject(i);
            if (!source.optBoolean("enabled", true)) continue;
            all = merge(all, source.getJSONArray("candidates"), "address");
            prefixes = merge(prefixes, source.getJSONArray("prefixes"), "cidr");
        }
        next.put("candidates", all).put("prefixes", prefixes);
    }

    private static JSONArray merge(JSONArray a, JSONArray b, String key) throws Exception {
        Map<String, JSONObject> entries = new LinkedHashMap<>();
        for (JSONArray array : new JSONArray[]{a, b}) for (int i = 0; i < array.length(); i++) {
            JSONObject entry = new JSONObject(array.getJSONObject(i).toString());
            JSONObject old = entries.get(entry.getString(key));
            if (old != null) {
                for (String field : new String[]{"source_id", "source_kind"}) {
                    Set<String> values = new LinkedHashSet<>();
                    for (JSONObject item : new JSONObject[]{old, entry}) {
                        values.add(item.optString(field));
                        JSONArray prior = item.optJSONArray(field + "s");
                        if (prior != null) for (int j = 0; j < prior.length(); j++) values.add(prior.getString(j));
                    }
                    old.put(field + "s", new JSONArray(values));
                }
                String comment = entry.optString("comment");
                if (!comment.isEmpty() && !old.optString("comment").contains(comment)) old.put("comment", old.optString("comment") + " / " + comment);
            } else entries.put(entry.getString(key), entry);
        }
        return new JSONArray(entries.values());
    }

    static JSONObject options(JSONObject target, boolean download, int sample, int concurrency, String url) throws Exception {
        return new JSONObject(target.toString()).put("sample_limit", sample).put("probe_concurrency", concurrency)
                .put("recommend_count", download ? 3 : 0).put("download_top", download ? 5 : 0)
                .put("download_bytes_per_ip", 20971520).put("download_seconds", 5)
                .put("download_url", url);
    }

    static final String DOWNLOAD = "https://speed.cloudflare.com/__down?bytes=20971520";

    void test(String profile, int sample, int concurrency, String url) {
        long epoch = generation.get();
        operation(() -> {
            if (sample < 1 || sample > 4096 || concurrency < 1 || concurrency > 32) throw new IllegalArgumentException("抽样1–4096，并发1–32");
            referenceId = profile;
            Preferences p = new Preferences(context).forProfile(profile);
            JSONObject target = PreferredIpResolver.target(p, PreferredIpResolver.baseParams(p));
            String net = network;
            JSONObject request = new JSONObject().put("operation", "test").put("candidates", data.getJSONArray("candidates"))
                    .put("prefixes", data.getJSONArray("prefixes")).put("options", options(target, true, sample, concurrency, url));
            draftCandidates = selectedCandidates();
            scanning = true;
            testedThisSession = false;
            snapshot = new JSONObject();
            try {
                run(request, epoch, net);
            } finally {
                scanning = false;
                testResults = new JSONObject(snapshot.toString()).put("test_target", target);
                JSONObject next = store.read();
                next.put("last_test", testResults);
                data = store.write(next, next.getLong("revision"));
            }
            check(epoch, net);
            testedThisSession = true;
            recommendInternal();
            message = "测试完成，推荐 " + chosen.size() + " 个（不足3个不凑数）。HTTP可达不代表WebSocket升级或认证成功";
        });
    }

    void select(String address, boolean selected) {
        execute(() -> { if (selected) chosen.add(address); else chosen.remove(address); preview = null; publish(); });
    }

    void recommend() { execute(() -> { recommendInternal(); preview = null; publish(); }); }

    private void recommendInternal() throws Exception {
        if (!"completed".equals(testResults.optString("state")) || !network.equals(testResults.optString("network_id"))) {
            throw new IllegalStateException("不自动荐用未完成或旧网络结果");
        }
        chosen.clear();
        JSONArray results = testResults.getJSONArray("results");
        for (int i = 0; i < results.length() && chosen.size() < 3; i++) {
            JSONObject r = results.getJSONObject(i);
            if (r.optBoolean("recommended") && "valid".equals(r.optString("status"))) chosen.add(r.getString("address"));
        }
        if (chosen.size() < 3) message = "合格推荐不足3个，不以失败项凑数";
    }

    private JSONArray selectedCandidates() throws Exception {
        JSONArray out = new JSONArray();
        Map<String, JSONObject> found = new LinkedHashMap<>();
        for (JSONArray array : new JSONArray[]{data.getJSONArray("selected"), draftCandidates, data.getJSONArray("candidates"),
                testResults.optJSONArray("results") == null ? new JSONArray() : testResults.getJSONArray("results")}) {
            for (int i = 0; i < array.length(); i++) found.put(array.getJSONObject(i).getString("address"), array.getJSONObject(i));
        }
        for (String address : chosen) {
            JSONObject value = found.get(address);
            if (value == null) throw new IllegalStateException("已选地址来源丢失，请重测");
            JSONObject candidate = new JSONObject();
            for (String key : new String[]{"address", "ip", "port", "comment", "source_id", "source_kind", "source_ids", "source_kinds"}) {
                if (value.has(key)) candidate.put(key, value.get(key));
            }
            out.put(candidate);
        }
        return out;
    }

    /** Validate at most three IPs per low-traffic task; never download before saving. */
    void prepareSave() {
        long epoch = generation.get();
        operation(() -> {
            String net = network;
            check(epoch, net);
            JSONArray selected = selectedCandidates();
            if (selected.length() == 0) throw new IllegalStateException("请至少选择一个IP");
            JSONObject next = store.read();
            boolean first = !next.has("rollback_modes");
            if (first) next.put("rollback_modes", new JSONObject(next.getJSONObject("profile_modes").toString()));
            JSONObject validations = new JSONObject();
            JSONObject modes = next.getJSONObject("profile_modes");
            StringBuilder scope = new StringBuilder();
            Preferences prefs = new Preferences(context);
            Map<String, JSONArray> cache = new LinkedHashMap<>();
            // Only a completed, same-network, same-target result from this page can seed reuse.
            JSONObject lastTarget = testResults.optJSONObject("test_target");
            if (testedThisSession && lastTarget != null && "completed".equals(testResults.optString("state")) && net.equals(testResults.optString("network_id"))) {
                cache.put(PreferredIpResolver.fingerprint(lastTarget), validAddresses(testResults));
            }
            int compatible = 0;
            for (Preferences.ProfileInfo info : prefs.getProfileList()) {
                check(epoch, net);
                Preferences p;
                JSONObject target;
                int connections;
                try {
                    p = prefs.forProfile(info.id);
                    if (PreferredIpResolver.conflict(p)) {
                        throw new IllegalArgumentException("高级JSON含 relay_nodes 覆盖，请先在编辑页确认清除冲突");
                    }
                    // Explicit independent/default nodes do not participate in takeover.
                    // Validate only participating nodes, so an unsupported target cannot
                    // silently restore a default-mode node's old relay address.
                    if ((!first || modes.has(info.id)) && !"global".equals(PreferredIpStore.mode(next, info.id))) {
                        scope.append(info.name).append("：保留独立/域名默认（用户设置）\n");
                        continue;
                    }
                    JSONObject merged = PreferredIpResolver.baseParams(p);
                    target = PreferredIpResolver.target(p, merged);
                    connections = PreferredIpResolver.validatedConnections(p, merged);
                } catch (Exception invalid) {
                    check(epoch, net); // never turn cancellation/network loss into a profile error
                    modes.put(info.id, "independent");
                    scope.append(info.name).append("：参数无效，保留独立：")
                            .append(invalid.getMessage() == null ? invalid.toString() : invalid.getMessage()).append('\n');
                    continue;
                }
                String key = PreferredIpResolver.fingerprint(target);
                JSONArray known = cache.get(key);
                Set<String> passed = new LinkedHashSet<>();
                List<JSONObject> missing = new ArrayList<>();
                for (int i = 0; i < selected.length(); i++) {
                    JSONObject candidate = selected.getJSONObject(i);
                    if (contains(known, candidate.getString("address"))) passed.add(candidate.getString("address"));
                    else missing.add(candidate);
                }
                for (int start = 0; start < missing.size(); start += 3) {
                    JSONArray chunk = new JSONArray();
                    for (int j = start; j < Math.min(start + 3, missing.size()); j++) chunk.put(missing.get(j));
                    message = "保存前实际目标检查：" + info.name + "（无下载）";
                    try {
                        JSONObject result = run(new JSONObject().put("operation", "test").put("candidates", chunk)
                                .put("prefixes", new JSONArray()).put("options", options(target, false, 3, 3, DOWNLOAD)), epoch, net);
                        JSONArray valid = validAddresses(result);
                        for (int j = 0; j < valid.length(); j++) passed.add(valid.getString(j));
                    } catch (Exception failure) {
                        check(epoch, net); // cancellation/network changes abort the whole preview
                        scope.append(info.name).append("：检查失败 ").append(failure.getMessage()).append('\n');
                    }
                }
                JSONArray addresses = new JSONArray(passed);
                cache.put(key, addresses);
                if (!passed.isEmpty()) {
                    compatible++;
                    validations.put(info.id, new JSONObject().put("target_key", key).put("network_id", net)
                            .put("tested_at", System.currentTimeMillis()).put("addresses", addresses));
                    modes.put(info.id, "global");
                    scope.append(info.name).append("：跟随 ").append(passed.size()).append("个IP");
                    if (Preferences.PROTOCOL_X_TUNNEL.equals(p.getProtocol())) {
                        scope.append(" × 每IP ").append(connections).append(" = ").append((long) passed.size() * connections).append("条通道");
                    }
                    scope.append('\n');
                } else {
                    modes.put(info.id, "independent");
                    scope.append(info.name).append("：不兼容/未知，保留原配置\n");
                }
            }
            check(epoch, net);
            if (compatible == 0) throw new IllegalStateException("没有实际节点验证通过，未接管任何节点\n" + scope);
            next.put("selected", selected).put("validations", validations).put("enabled", true);
            preview = new JSONObject().put("pool", next).put("network_id", net).put("scope", scope.toString())
                    .put("generation", epoch);
            message = "兼容检查完成，请确认接管范围；不代表协议认证成功";
        });
    }

    private static boolean contains(JSONArray array, String address) {
        if (array == null) return false;
        for (int i = 0; i < array.length(); i++) if (address.equals(array.optString(i))) return true;
        return false;
    }

    private static JSONArray validAddresses(JSONObject result) throws Exception {
        JSONArray out = new JSONArray(), results = result.getJSONArray("results");
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            if ("valid".equals(r.optString("status")) && r.optInt("http_success") > 0 && !r.optString("colo").isEmpty()) out.put(r.getString("address"));
        }
        return out;
    }

    void commitPreview() {
        execute(() -> {
            if (preview == null) throw new IllegalStateException("请重新验证");
            check(preview.getLong("generation"), preview.getString("network_id"));
            JSONObject next = preview.getJSONObject("pool");
            // Profile edits invalidate the preview even when the pool revision did not change.
            Preferences p = new Preferences(context);
            JSONObject validations = next.getJSONObject("validations");
            java.util.Iterator<String> ids = validations.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                Preferences scoped = p.forProfile(id);
                JSONObject merged = PreferredIpResolver.baseParams(scoped);
                PreferredIpResolver.validatedConnections(scoped, merged);
                if (!p.getProfileIds().contains(id) || PreferredIpResolver.conflict(scoped)
                        || !validations.getJSONObject(id).getString("target_key").equals(PreferredIpResolver.fingerprint(
                        PreferredIpResolver.target(scoped, merged)))) {
                    throw new IllegalStateException("节点参数已变化，请重新验证");
                }
            }
            data = store.write(next, next.getLong("revision"));
            preview = null;
            message = "全局优选已保存，下次连接生效；原节点地址保留，不自动重连";
            publish();
        });
    }

    void disable() {
        execute(() -> {
            JSONObject next = store.read();
            next.put("enabled", false);
            if (next.has("rollback_modes")) {
                next.put("profile_modes", next.getJSONObject("rollback_modes"));
                next.remove("rollback_modes");
            }
            data = store.write(next, next.getLong("revision"));
            preview = null;
            message = "已禁用全局并恢复接管前模式；原IP未被覆盖，下次连接生效";
            publish();
        });
    }
}
