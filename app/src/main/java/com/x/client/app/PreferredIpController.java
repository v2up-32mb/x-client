package com.x.client.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One process-retained UI session, one Go network slot, independent short selection transactions.
 * State belongs to control; blocking Go polling NEVER occupies control, canceller or selectionIO. */
final class PreferredIpController {
    interface Listener { void changed(State state); }
    interface Work { void run() throws Exception; }
    interface Finished { void run(JSONObject result) throws Exception; }
    static final class State {
        JSONObject data, parse;
        Map<String, String> sourceMessages;
        List<Row> rows;
        String message, network, referenceId, referenceSummary, phase, sessionId, activeDownload;
        boolean busy, paused, stale, scanning, parseThirdParty, saving, metered, ledgerBlocked;
        int completed, success, total, selected;
        long used, reserved;
    }
    static final class Row {
        final String address, location, score, detail, interactionKey;
        final boolean checked, selectable, downloadable;
        final JSONObject result;
        Row(String address, String location, String score, String detail, boolean checked,
                boolean selectable, boolean downloadable, JSONObject result, String interactionKey) {
            this.address = address; this.location = location; this.score = score; this.detail = detail;
            this.interactionKey = interactionKey; this.checked = checked; this.selectable = selectable; this.downloadable = downloadable; this.result = result;
        }
        String content() { return location + score + detail + checked + selectable + downloadable + interactionKey; }
    }
    // Main-thread UI memory is retained by address, not adapter position. Not a network checkpoint.
    final Set<String> expanded = Collections.synchronizedSet(new LinkedHashSet<>());
    int tab;
    Parcelable scroll;
    String uiReference = "";
    int uiSample = 256, uiConcurrency = 8;
    String manualDraft = "", urlDraft = "https://776771.xyz/all.txt";

    private final Context context;
    private final PreferredIpStore store;
    private final PreferredIpHistory history;
    private final PreferredIpLedger ledger;
    private final PreferredIpRuntime.Backend backend;
    private final PreferredIpRuntime.NetworkAccess net;
    private final PreferredIpRuntime.Clock clock;
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService networkIO = Executors.newSingleThreadExecutor();
    private final ExecutorService canceller = Executors.newSingleThreadExecutor();
    private final ExecutorService selectionIO = Executors.newSingleThreadExecutor();
    private final ExecutorService historyIO = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong scanEpoch = new AtomicLong(), selectionVersion = new AtomicLong(), cancelVersion = new AtomicLong();
    private volatile Listener listener;
    private volatile boolean foreground, stopRequested = true;
    private volatile String taskId = "";
    private volatile String network;
    private volatile Job active;
    private JSONObject data, parsed, options, target, ech = new JSONObject();
    private JSONObject oldHistory = new JSONObject();
    private final LinkedHashMap<String, JSONObject> candidates = new LinkedHashMap<>(), results = new LinkedHashMap<>();
    private final Map<String, String> validationNotes = new LinkedHashMap<>(), sourceMessages = new LinkedHashMap<>(), downloadNotes = new LinkedHashMap<>();
    private final Set<String> validationSeen = new HashSet<>();
    private String session = "", round = "", targetKey = "", contextKey = "", referenceKey = "", sessionNetwork = "";
    private String observedProfiles = "";
    private String referenceId, referenceSummary = "", message = "加载优选库…", phase = "尚未测试";
    private String parseSource, parseKind, parseUrl = "", pendingDownload = "";
    private boolean parseAppend = true, stale = true, paused = true, scanning, dirty = true, preparingDisk, validationAllowed = true;
    private int selectionPending;
    private long startedAt, liveDownloadUsed, liveDownloadBytes;

    PreferredIpController(Context context, String referenceId, PreferredIpRuntime.Backend backend,
            PreferredIpRuntime.NetworkAccess net, PreferredIpRuntime.Clock clock) {
        this.context = context.getApplicationContext(); this.referenceId = referenceId;
        this.backend = backend; this.net = net; this.clock = clock;
        store = new PreferredIpStore(this.context); history = new PreferredIpHistory(this.context);
        ledger = new PreferredIpLedger(store); network = net.id();
        control.scheduleWithFixedDelay(() -> safe(() -> { checkContext(); schedule(); if (dirty) publish(); }), 0, 500, TimeUnit.MILLISECONDS);
        selectionIO.execute(() -> {
            try {
                JSONObject loaded = store.read();
                post(() -> { acceptData(loaded); message = "勾选即保存；下次连接生效，不重连VPN"; });
                historyIO.execute(() -> loadHistory(loaded));
            } catch (Exception e) { error(e); }
        });
    }
    private void loadHistory(JSONObject loaded) {
        try {
            JSONObject legacy = loaded.optJSONObject("last_test");
            if (legacy != null && legacy.optJSONArray("results") != null) {
                // Transfer first, CAS the lightweight reference second. Failure preserves the old pool.
                history.write(legacy, "completed".equals(legacy.optString("state")));
                JSONObject saved = store.update(root -> {
                    if (legacy.toString().equals(root.optJSONObject("last_test").toString())) {
                        root.put("last_test", new JSONObject().put("history_file", "completed".equals(legacy.optString("state"))
                                ? "preferred-ip-complete.json" : "preferred-ip-checkpoint.json"));
                    }
                });
                post(() -> acceptData(saved));
            }
            JSONObject latest = history.read(false);
            if (latest.length() == 0) latest = history.read(true);
            JSONObject restored = latest;
            post(() -> { if (session.isEmpty()) { oldHistory = restored; dirty = true; } });
        } catch (Exception e) { error(e); }
    }
    void attach(Listener next) {
        listener = next; foreground = true;
        net.watch(this::networkChanged);
        networkChanged();
        selectionIO.execute(() -> { try { JSONObject latest = store.read(); post(() -> acceptData(latest)); } catch (Exception e) { error(e); } });
        post(() -> { dirty = true; publish(); });
    }
    void detach(boolean rotating) {
        listener = null;
        // Rotation and same-process return are manually resumable, never automatically restart I/O.
        foreground = false; net.unwatch(); post(() -> validationAllowed = false); pause("已暂停；返回页面后可继续未完成的 IP");
    }
    void dispose() {
        detach(false);
        // Do not abandon the poll/worker_done drain on teardown. Executors stop only after drain.
        post(() -> { if (active == null) shutdown(); else disposeWhenDone = true; });
    }
    private boolean disposeWhenDone;
    private void shutdown() { control.shutdown(); networkIO.shutdown(); canceller.shutdown(); selectionIO.shutdown(); historyIO.shutdown(); sourceIO.shutdown(); }
    private void post(Work work) { if (!control.isShutdown()) control.execute(() -> safe(work)); }
    private void safe(Work work) { try { work.run(); } catch (Exception e) { message = shortError(e); dirty = true; } }
    private void error(Exception e) { post(() -> { message = shortError(e); dirty = true; }); }
    private static String shortError(Exception e) {
        String value = e.getMessage(); return value == null ? "操作失败（" + e.getClass().getSimpleName() + "）" : value;
    }
    private void acceptData(JSONObject latest) {
        if (data == null || latest.optLong("revision") >= data.optLong("revision")) data = latest;
        dirty = true;
    }
    private void cancelIO() {
        stopRequested = true;
        String id = taskId;
        if (!id.isEmpty() && !canceller.isShutdown()) canceller.execute(() -> cancelBackend(id));
    }
    void pause(String reason) {
        cancelVersion.incrementAndGet();
        cancelIO(); // Immediate control path: cannot queue behind Start/poll/selection I/O.
        post(() -> {
            cancelIO();
            if (active != null && active.kind.startsWith("validation")) validationAllowed = false;
            paused = true; pendingDownload = ""; message = reason;
            phase = active == null ? "已暂停" : "正在暂停，等待网络退出";
            if (active == null) checkpoint(); dirty = true;
        });
    }
    void cancel(String reason) { pause(reason); }
    private void networkChanged() {
        String current = net.id();
        if (!current.equals(network)) {
            network = current; scanEpoch.incrementAndGet(); cancelIO();
            post(() -> invalidate("网络已变化；旧成绩仅供历史查看，请重新开始"));
        } else {
            try { net.requireDirect(); } catch (Exception e) { if (active != null) pause(shortError(e)); }
        }
    }
    private void invalidate(String reason) throws Exception {
        stale = true; paused = true; scanning = false; pendingDownload = "";
        validationSeen.clear(); validationNotes.clear(); message = reason; dirty = true; checkpoint();
    }
    private String currentReferenceKey(String id) throws Exception {
        Preferences p = new Preferences(context).forProfile(id);
        JSONObject merged = PreferredIpResolver.baseParams(p);
        PreferredIpResolver.validatedConnections(p, merged);
        // Advanced credentials are only hashed, never persisted or displayed.
        return PreferredIpIds.opaque("reference", id + "|" + merged.toString());
    }
    private void checkContext() throws Exception {
        if (!foreground || session.isEmpty() || stale) return;
        String signature = profilesKey();
        if (!signature.equals(observedProfiles)) { observedProfiles = signature; validationSeen.clear(); validationNotes.clear(); dirty = true; }
        if (!network.equals(net.id()) || !referenceKey.equals(currentReferenceKey(referenceId))) {
            scanEpoch.incrementAndGet(); cancelIO(); invalidate("参考节点最终参数/网络已变化；请重新开始");
        }
    }
    void refreshDetails() { post(() -> dirty = true); }
    void inspectReference(String id) {
        post(() -> {
            Preferences p = new Preferences(context).forProfile(id);
            JSONObject merged = PreferredIpResolver.baseParams(p);
            JSONObject value = PreferredIpResolver.target(p, merged);
            referenceSummary = PreferredIpPresentation.target(value) + "\n" + PreferredIpResolver.effectiveCount(p, merged);
            dirty = true;
        });
    }
    void parametersChanged(String profile, int sample, int concurrency) {
        post(() -> {
            if (session.isEmpty() || stale || options == null) return;
            if (!profile.equals(referenceId) || sample != options.optInt("sample_limit") || concurrency != options.optInt("probe_concurrency")) {
                scanEpoch.incrementAndGet(); cancelIO(); invalidate("参考节点/测试参数改变；需重新开始，旧成绩仅为历史");
            }
        });
    }
    void parseReplacement(String content, String id) {
        sourceIO.execute(() -> {
            try {
                if (content.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw new IllegalArgumentException("文本超过2MiB");
                JSONObject response = new JSONObject(backend.parse(content, id, "manual"));
                post(() -> { parsed = response; parseSource = id; parseKind = "manual"; parseUrl = ""; parseAppend = false;
                    message = "解析完成，确认有效行后替换该来源；当前队列不变"; dirty = true; });
            } catch (Exception e) { error(e); }
        });
    }
    static final String DOWNLOAD = "https://speed.cloudflare.com/__down?bytes=52428800";
    static final String PROBE_DOWNLOAD = "https://speed.cloudflare.com/__down?bytes=20971520";
    static JSONObject options(JSONObject target, boolean download, int sample, int concurrency, String url) throws Exception {
        return new JSONObject(target.toString()).put("sample_limit", sample).put("probe_concurrency", concurrency)
                .put("recommend_count", 0).put("download_top", 0)
                .put("download_bytes_per_ip", download ? 52428800 : 20971520).put("download_seconds", download ? 10 : 5)
                .put("download_url", download ? url : PROBE_DOWNLOAD);
    }
    void test(String profile, int sample, int concurrency, String ignoredDownloadUrl) {
        post(() -> startScan(profile, sample, concurrency, false));
    }
    private void startScan(String profile, int sample, int concurrency, boolean confirmedRound) throws Exception {
        if (active != null || preparingDisk) throw new IllegalStateException("请先暂停并等待网络任务真正退出");
        if (data == null) throw new IllegalStateException("优选库尚未加载");
        if (sample < 1 || sample > 4096 || concurrency < 1 || concurrency > 32) throw new IllegalArgumentException("抽样1–4096，并发1–32");
        requireForeground();
        Preferences p = new Preferences(context).forProfile(profile);
        JSONObject merged = PreferredIpResolver.baseParams(p);
        PreferredIpResolver.validatedConnections(p, merged);
        JSONObject newTarget = PreferredIpResolver.target(p, merged);
        String key = currentReferenceKey(profile);
        JSONObject priorHistory = historySnapshot(); checkpoint();
        scanEpoch.incrementAndGet(); referenceId = profile; referenceKey = key;
        target = newTarget; options = options(target, false, sample, concurrency, PROBE_DOWNLOAD);
        contextKey = PreferredIpIds.opaque("scan", key + "|" + network + "|" + sample + "|" + concurrency + "|" + scanEpoch.get());
        referenceSummary = PreferredIpPresentation.target(target);
        if (priorHistory.length() != 0) oldHistory = priorHistory; candidates.clear(); results.clear(); downloadNotes.clear(); validationSeen.clear(); validationNotes.clear();
        validationAllowed = true; sessionNetwork = network; session = ""; targetKey = ""; stale = false; paused = false; scanning = true; startedAt = clock.now();
        beginPrepare(true, confirmedRound);
    }
    private void requireForeground() {
        if (!foreground) throw new IllegalStateException("页面不在前台；不会自动开始网络任务");
        if (!network.equals(net.id())) throw new IllegalStateException("网络已变化");
        net.requireDirect();
    }
    void resume() {
        post(() -> {
            checkContext(); requireForeground();
            if (stale || session.isEmpty()) throw new IllegalStateException("上下文已失效，请重新开始");
            if (active != null || preparingDisk) throw new IllegalStateException("仍在等待网络任务退出");
            paused = false; scanning = true; validationAllowed = true; stopRequested = false; message = "只继续未完成的 IP"; schedule(); dirty = true;
        });
    }
    /** A confirmed explicit round action; never called by an error handler or automatic retry. */
    void newDownloadRound(String profile, int sample, int concurrency) {
        post(() -> {
            if (active != null || preparingDisk) throw new IllegalStateException("请先暂停并等待网络退出");
            requireForeground(); paused = true;
            if (session.isEmpty() || stale || !profile.equals(referenceId) || options == null
                    || sample != options.optInt("sample_limit") || concurrency != options.optInt("probe_concurrency")) {
                startScan(profile, sample, concurrency, true);
                paused = true;
            } else beginPrepare(false, true);
        });
    }
    private void beginPrepare(boolean fresh, boolean newRound) throws Exception {
        preparingDisk = true; phase = "准备候选 / ECH"; dirty = true;
        long epoch = scanEpoch.get(), cancellation = cancelVersion.get();
        selectionIO.execute(() -> {
            try {
                String roundId = ledger.beforePrepare(newRound);
                JSONObject latest = store.read();
                post(() -> {
                    preparingDisk = false; acceptData(latest);
                    if (epoch != scanEpoch.get() || cancellation != cancelVersion.get() || !foreground) { paused = true; return; }
                    round = roundId;
                    JSONObject request = request("prepare", options, new JSONArray());
                    if (fresh) {
                        request.remove("session_id");
                        request.put("candidates", data.getJSONArray("candidates")).put("prefixes", data.getJSONArray("prefixes"));
                    }
                    if (newRound) request.put("new_download_round", true);
                    Job job = new Job(request, "prepare", response -> { });
                    job.accountPrepare = true; job.newRound = newRound;
                    job.finished = response -> {
                        if (!"completed".equals(response.optString("state"))) { paused = true; return; }
                        session = response.getString("session_id"); round = response.getString("download_round_id");
                        targetKey = response.getString("target_key"); ech = response.getJSONObject("ech");
                        if (fresh) {
                            JSONArray frozen = response.getJSONArray("candidates");
                            for (int i = 0; i < frozen.length(); i++) {
                                JSONObject c = frozen.getJSONObject(i); candidates.put(c.getString("address"), c);
                            }
                        }
                        message = newRound ? "已确认新100MiB下载轮；不会自动测速" : "候选已冻结；逐 IP 完整测试，不下载";
                        phase = paused ? "已暂停" : "测试中"; checkpoint();
                    };
                    launch(job);
                });
            } catch (Exception e) { post(() -> { preparingDisk = false; paused = true; phase = "已暂停"; message = shortError(e); dirty = true; }); }
        });
    }
    private JSONObject request(String operation, JSONObject opts, JSONArray chunk) throws Exception {
        JSONObject request = new JSONObject().put("operation", operation).put("network_id", network)
                .put("options", new JSONObject(opts.toString())).put("candidates", chunk).put("prefixes", new JSONArray());
        if (!session.isEmpty()) request.put("session_id", session);
        if (!round.isEmpty()) request.put("download_round_id", round);
        return request;
    }
    private final class Job {
        final JSONObject request;
        final long epoch;
        final String networkId, sessionId, kind;
        Finished finished;
        boolean accountPrepare, newRound, retryUsed, reserved;
        String id = "";
        Job(JSONObject request, String kind, Finished finished) {
            this.request = request; this.kind = kind; this.finished = finished;
            epoch = scanEpoch.get(); networkId = request.optString("network_id"); sessionId = request.optString("session_id");
        }
    }
    private void launch(Job job) throws Exception {
        if (active != null || preparingDisk) throw new IllegalStateException("内部网络任务槽仍占用");
        requireForeground();
        if (job.epoch != scanEpoch.get() || !job.networkId.equals(network)
                || (!job.sessionId.isEmpty() && !sessionNetwork.equals(network)))
            throw new IllegalStateException("任务请求上下文已变化，请重新开始");
        if ("download".equals(job.kind)) { liveDownloadUsed = 0; liveDownloadBytes = 0; }
        active = job; stopRequested = false; phase = job.kind.equals("download") ? "单 IP 下载" : job.kind.equals("validation") ? "实际节点验证" : job.kind.equals("fetch") ? "抓取来源" : "准备 / 测试";
        dirty = true;
        networkIO.execute(() -> runJob(job));
    }
    private boolean relevant(Job job, JSONObject snapshot) {
        return active == job && job.epoch == scanEpoch.get() && job.networkId.equals(network)
                && job.id.equals(snapshot.optString("id")) && job.networkId.equals(snapshot.optString("network_id"))
                && (job.sessionId.isEmpty() || job.sessionId.equals(snapshot.optString("session_id")));
    }
    private void cancelBackend(String id) {
        try { backend.cancel(id); } catch (Exception e) { error(new IllegalStateException("取消请求失败，仍等待 worker_done")); }
    }
    private static void pollDelay() {
        try { Thread.sleep(500); } catch (InterruptedException ignored) { /* Never abandon the worker_done drain. */ }
    }
    private void runJob(Job job) {
        JSONObject terminal = null;
        Exception failure = null;
        try {
            if (!foreground || job.epoch != scanEpoch.get() || stopRequested || !job.networkId.equals(net.id()))
                throw new IllegalStateException("已暂停/网络已变化，未启动任务");
            net.requireDirect();
            if ("download".equals(job.kind)) {
                ledger.reserve(job.request.getString("download_round_id"), job.request.getJSONArray("candidates").getJSONObject(0).getString("address"));
                job.reserved = true;
                JSONObject reserved = store.read(); post(() -> acceptData(reserved));
            }
            job.id = backend.start(job.request.toString()); taskId = job.id;
            if (stopRequested || !foreground || job.epoch != scanEpoch.get()) cancelBackend(job.id);
            while (true) {
                JSONObject snapshot;
                try { snapshot = new JSONObject(backend.poll(job.id)); }
                catch (Exception pollError) {
                    // No worker_done means NO slot release, even if Get failed. Cancel is independent.
                    cancelBackend(job.id); error(new IllegalStateException("等待网络退出：" + shortError(pollError)));
                    pollDelay(); continue;
                }
                if (!job.id.equals(snapshot.optString("id")) || !job.networkId.equals(snapshot.optString("network_id"))
                        || (!job.sessionId.isEmpty() && !job.sessionId.equals(snapshot.optString("session_id")))) {
                    cancelBackend(job.id); error(new IllegalStateException("忽略任务/会话/网络不匹配响应；等待真实退出"));
                    pollDelay(); continue;
                }
                JSONObject update = snapshot;
                post(() -> { if (relevant(job, update)) mergeSnapshot(job, update); });
                if (snapshot.optBoolean("worker_done")) { terminal = snapshot; break; }
                if (stopRequested || !foreground || job.epoch != scanEpoch.get() || !job.networkId.equals(net.id())) cancelBackend(job.id);
                pollDelay();
            }
            if ("download".equals(job.kind)) ledger.settle(terminal);
            if (job.accountPrepare && "completed".equals(terminal.optString("state"))) ledger.prepared(terminal, job.newRound);
        } catch (Exception e) {
            failure = e;
            if (job.id.isEmpty() && job.reserved) {
                // The four API contract rejects Start synchronously before admitting a worker.
                try { ledger.rejectedBeforeStart(job.request.optString("download_round_id")); }
                catch (Exception accounting) { failure = accounting; }
            }
        }
        if (!job.id.isEmpty() && terminal == null) {
            // A dependency exception after admission is not an exit acknowledgement. Keep draining,
            // even when a test backend interrupts/misbehaves; never release the network slot speculatively.
            cancelBackend(job.id);
            while (terminal == null) {
                try {
                    JSONObject snapshot = new JSONObject(backend.poll(job.id));
                    if (job.id.equals(snapshot.optString("id")) && job.networkId.equals(snapshot.optString("network_id"))
                            && (job.sessionId.isEmpty() || job.sessionId.equals(snapshot.optString("session_id")))
                            && snapshot.optBoolean("worker_done")) terminal = snapshot;
                } catch (Exception ignored) { }
                if (terminal == null) pollDelay();
            }
            if ("download".equals(job.kind)) {
                try { ledger.settle(terminal); } catch (Exception accounting) { failure = accounting; }
            }
        }
        // Only a synchronous Start rejection or a matching worker_done can reach this point.
        JSONObject finalSnapshot = terminal;
        Exception finalFailure = failure;
        JSONObject disk = null;
        try { disk = store.read(); } catch (Exception e) { if (failure == null) finalFailure = e; }
        JSONObject latest = disk; Exception finalError = finalFailure;
        post(() -> finishJob(job, finalSnapshot, finalError, latest));
    }
    private void mergeSnapshot(Job job, JSONObject snapshot) throws Exception {
        if (snapshot.optJSONObject("ech") != null) ech = snapshot.getJSONObject("ech");
        if ("probe".equals(job.kind)) {
            if (!"reference".equals(snapshot.optString("probe_role")) || !targetKey.equals(snapshot.optString("target_key"))) return;
            JSONArray rows = snapshot.getJSONArray("results");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i); String address = row.getString("address");
                if (PreferredIpPresentation.find(job.request.optJSONArray("candidates"), address) == null || !candidates.containsKey(address) || !"reference".equals(row.optString("result_kind"))
                        || !targetKey.equals(row.optString("target_key"))) continue;
                JSONObject old = results.get(address);
                if (old == null || !old.optBoolean("probe_complete")) results.put(address, row);
            }
        } else if ("download".equals(job.kind)) {
            if (!round.equals(snapshot.optString("download_round_id"))) return;
            liveDownloadUsed = Math.max(liveDownloadUsed, snapshot.optLong("download_round_used"));
            liveDownloadBytes = Math.max(liveDownloadBytes, snapshot.optLong("download_bytes"));
            if (snapshot.optBoolean("worker_done")) {
                JSONArray warnings = snapshot.optJSONArray("warnings"); StringBuilder note = new StringBuilder();
                for (int i = 0; warnings != null && i < warnings.length(); i++) {
                    if (note.length() > 0) note.append('\n'); note.append(warnings.optString(i));
                }
                downloadNotes.put(job.request.getJSONArray("candidates").getJSONObject(0).getString("address"), note.toString());
            }
            JSONArray rows = snapshot.getJSONArray("results");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i), original = results.get(row.optString("address"));
                if (original == null || !job.request.getJSONArray("candidates").getJSONObject(0).getString("address").equals(row.optString("address"))
                        || !"download".equals(row.optString("result_kind"))) continue;
                JSONObject merged = new JSONObject(original.toString());
                for (String key : new String[]{"download_bytes", "download_seconds", "download_mib_s", "download_peak_mib_s", "download_state", "download_ech_status"})
                    if (row.has(key)) merged.put(key, row.get(key));
                results.put(row.getString("address"), merged);
            }
        }
        dirty = true;
    }
    private void finishJob(Job job, JSONObject snapshot, Exception error, JSONObject latest) throws Exception {
        if (active != job) return;
        boolean current = snapshot != null && relevant(job, snapshot);
        active = null; taskId = "";
        if (stopRequested) paused = true;
        if (latest != null) acceptData(latest);
        if (disposeWhenDone) { shutdown(); return; }
        if ("fetch".equals(job.kind)) {
            String id = job.request.getJSONObject("source").getString("id");
            sourceMessages.put(id, error != null ? "更新失败，保留旧快照" : snapshot == null || !"completed".equals(snapshot.optString("state"))
                    ? "未完成，保留旧快照" : "已获取，正在保存有效行");
        }
        if (error != null) {
            String text = shortError(error);
            if (job.epoch == scanEpoch.get() && text.contains("prepare_required") && !job.retryUsed && foreground && !stopRequested) {
                maintainThen(job); return;
            }
            paused = true; validationAllowed = false;
            message = text.contains("prepare_required") ? "ECH 配置无法复用（可能 DNS TTL=0）；已停止，请调整配置或明确重试" : text;
        } else if (current) {
            if ("failed".equals(snapshot.optString("state"))) { paused = true; validationAllowed = false; message = snapshot.optString("error", "任务失败"); }
            else if ("cancelled".equals(snapshot.optString("state"))) { paused = true; message = "已暂停；完整结果保留，未完成可继续"; }
            job.finished.run(snapshot);
        }
        if (active != null) { dirty = true; return; } // A prepare callback has already dispatched its dependent job.
        // Remove partials after true worker_done; complete successes AND failures remain frozen.
        if ("probe".equals(job.kind)) {
            results.entrySet().removeIf(e -> !e.getValue().optBoolean("probe_complete"));
        }
        if (!"fetch".equals(job.kind)) checkpoint();
        phase = paused ? "已暂停" : "测试中"; dirty = true; schedule();
    }
    /** A single foreground maintenance/re-dispatch attempt. TTL=0 cannot create a retry loop. */
    private void maintainThen(Job original) throws Exception {
        original.retryUsed = true;
        JSONObject maintenance = request("prepare", original.request.getJSONObject("options"), new JSONArray());
        // prepare uses generic non-download options even when maintaining a download context.
        maintenance.put("options", options(original.request.getJSONObject("options"), false,
                options.optInt("sample_limit", 256), options.optInt("probe_concurrency", 8), PROBE_DOWNLOAD));
        if ("validation".equals(original.kind)) maintenance.put("probe_role", "validation");
        Job job = new Job(maintenance, "maintenance", response -> {
            if (!"completed".equals(response.optString("state")) || stopRequested || !foreground) return;
            Job retry = new Job(original.request, original.kind, original.finished); retry.retryUsed = true;
            launch(retry);
        });
        launch(job);
    }
    private void schedule() throws Exception {
        if (!foreground || active != null || preparingDisk || data == null || session.isEmpty() || stale) return;
        checkContext(); if (stale) return;
        if (!pendingDownload.isEmpty()) {
            String address = pendingDownload; pendingDownload = "";
            if (!eligible(address)) throw new IllegalStateException("只能下载本轮完整合格参考 IP");
            JSONObject opts = options(target, true, options.optInt("sample_limit"), options.optInt("probe_concurrency"), DOWNLOAD);
            Job download = new Job(request("download", opts, new JSONArray().put(candidates.get(address))), "download", response -> {
                paused = true; message = "测速结束，保持暂停；下载不改变参考分数/证明";
            });
            launch(download); return;
        }
        // Matching reference proof needs no network slot and may commit while a scan is running.
        validateSelections(!paused && scanning);
        if (active != null || paused || stopRequested || !scanning) return;
        JSONArray chunk = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : candidates.entrySet()) {
            JSONObject r = results.get(entry.getKey());
            if (r == null || !r.optBoolean("probe_complete")) chunk.put(entry.getValue());
            if (chunk.length() == options.optInt("probe_concurrency", 8)) break;
        }
        if (chunk.length() == 0) {
            scanning = false; paused = true; phase = "测试完成"; message = "全部候选处理完成；勾选即保存，下载须手动";
            checkpoint(); dirty = true; return;
        }
        JSONObject request = request("probe", options, chunk).put("probe_role", "reference");
        launch(new Job(request, "probe", response -> { }));
    }
    void download(String address) {
        cancelIO();
        post(() -> {
            cancelIO();
            if (!eligible(address)) throw new IllegalStateException("只允许当前完整合格参考 IP 手动测速");
            paused = true; pendingDownload = address;
            message = active == null ? "准备单 IP 测速" : "先暂停扫描，等待 worker_done 后测速";
            dirty = true; schedule();
        });
    }
    private boolean eligible(String address) {
        JSONObject row = results.get(address);
        return !stale && !session.isEmpty() && row != null && row.optBoolean("probe_complete") && row.optBoolean("score_ready")
                && row.optInt("score_version") == 1 && "reference".equals(row.optString("result_kind"))
                && targetKey.equals(row.optString("target_key")) && compatible(row);
    }
    /** Legacy low-level predicate remains for the original six safety tests; NOT a new-proof gate. */
    static boolean compatible(JSONObject row) {
        return "valid".equals(row.optString("status")) && row.optInt("http_success") > 0 && !row.optString("colo").isEmpty();
    }
    void select(String address, boolean selected) {
        post(() -> {
            if (data == null) throw new IllegalStateException("优选库尚未加载");
            if (selected && !eligible(address)) throw new IllegalStateException("新增选择需要当前网络完整合格参考结果");
            final JSONObject candidate = selected ? PreferredIpHistory.candidate(candidates.get(address)) : null;
            final long epoch = scanEpoch.get(); final String currentNetwork = network, ref = referenceKey;
            selectionVersion.incrementAndGet(); selectionPending++; validationAllowed = true; dirty = true;
            selectionIO.execute(() -> {
                try {
                    JSONObject saved = store.update(root -> {
                        if (selected && (epoch != scanEpoch.get() || !currentNetwork.equals(net.id())
                                || !ref.equals(currentReferenceKey(referenceId)))) throw new IllegalStateException("上下文已变化，未保存新增选择");
                        JSONArray rows = root.getJSONArray("selected"), next = new JSONArray();
                        for (int i = 0; i < rows.length(); i++) if (!address.equals(rows.getJSONObject(i).getString("address"))) next.put(rows.getJSONObject(i));
                        if (selected) next.put(candidate);
                        root.put("selected", next).put("selection_version", root.optLong("selection_version") + 1);
                        // Removing a proof prevents deselect/reselect from reviving an old-network validation.
                        JSONObject validations = root.getJSONObject("validations");
                        Iterator<String> ids = validations.keys();
                        while (ids.hasNext()) {
                            JSONObject validation = validations.getJSONObject(ids.next());
                            JSONArray old = validation.optJSONArray("addresses"), kept = new JSONArray();
                            for (int i = 0; old != null && i < old.length(); i++) if (!address.equals(old.getString(i))) kept.put(old.getString(i));
                            validation.put("addresses", kept);
                        }
                        if (next.length() == 0) root.put("enabled", false);
                        // Checkbox changes only intent (+ removal of obsolete proof), never modes or fabricated validation.
                    });
                    post(() -> { acceptData(saved); selectionPending--; validationSeen.clear(); validationNotes.clear();
                        message = "选择已保存；实际节点兼容验证后下次连接生效，不重连VPN"; validateSelections(true); });
                } catch (Exception e) {
                    post(() -> { selectionPending--; message = "选择保存失败，已回滚：" + shortError(e); dirty = true; });
                }
            });
        });
    }
    private String profilesKey() throws Exception {
        StringBuilder key = new StringBuilder(); Preferences prefs = new Preferences(context);
        for (Preferences.ProfileInfo info : prefs.getProfileList()) {
            Preferences p = prefs.forProfile(info.id); String params;
            try { params = PreferredIpResolver.baseParams(p).toString(); }
            catch (Exception e) { params = p.getXtAdvancedParams(); }
            key.append(info.id.length()).append(':').append(info.id).append(info.name.length()).append(':').append(info.name)
                    .append(params.length()).append(':').append(params);
        }
        return PreferredIpIds.opaque("profiles", key.toString());
    }
    private boolean following(JSONObject pool, Preferences p) throws Exception {
        if (PreferredIpResolver.conflict(p)) return false;
        JSONObject modes = pool.getJSONObject("profile_modes");
        // Unset remains implicit, including after a failed/all-failed validation. Never migrate explicit exceptions.
        return !modes.has(p.getCurrentProfileId()) || "global".equals(modes.optString(p.getCurrentProfileId()));
    }
    private static final class ValidationGroup {
        JSONObject target; String key;
        final List<String> ids = new ArrayList<>();
    }
    private void validateSelections(boolean referenceOnly) throws Exception {
        if (!foreground || !validationAllowed || stale || data == null || session.isEmpty() || selectionPending > 0) return;
        long version = selectionVersion.get(), epoch = scanEpoch.get(); String profileSignature = profilesKey();
        JSONArray selected = data.getJSONArray("selected");
        if (selected.length() == 0) return;
        Map<String, ValidationGroup> groups = new LinkedHashMap<>(); Preferences prefs = new Preferences(context);
        for (Preferences.ProfileInfo info : prefs.getProfileList()) {
            Preferences p = prefs.forProfile(info.id);
            try {
                if (!following(data, p)) continue;
                JSONObject merged = PreferredIpResolver.baseParams(p);
                PreferredIpResolver.validatedConnections(p, merged);
                JSONObject actual = PreferredIpResolver.target(p, merged);
                String key = PreferredIpResolver.fingerprint(actual);
                ValidationGroup group = groups.get(key);
                if (group == null) { group = new ValidationGroup(); group.target = actual; group.key = key; groups.put(key, group); }
                group.ids.add(info.id);
            } catch (Exception ignored) { /* Resolver's actual non-adoption reason is rendered in each row. */ }
        }
        for (ValidationGroup group : groups.values()) {
            boolean same = group.key.equals(PreferredIpResolver.fingerprint(target));
            JSONArray chunk = new JSONArray();
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < selected.length(); i++) {
                String address = selected.getJSONObject(i).getString("address");
                // History-selected addresses stay visible, but only current reference candidates acquire new proof.
                if (!eligible(address)) continue;
                String key = version + "|" + profileSignature + "|" + group.key + "|" + address;
                if (validationSeen.contains(key)) continue;
                chunk.put(candidates.get(address)); seen.add(key);
                if (chunk.length() == options.optInt("probe_concurrency", 8)) break;
            }
            if (chunk.length() == 0 || (!same && (referenceOnly || active != null))) continue;
            if (validationSeen.size() + seen.size() > 8192) { validationAllowed = false; message = "兼容证明缓存达到8192项上限；请减少参与节点或选择后明确继续"; dirty = true; return; }
            validationSeen.addAll(seen);
            if (same) {
                JSONArray proof = new JSONArray();
                for (int i = 0; i < chunk.length(); i++) proof.put(results.get(chunk.getJSONObject(i).getString("address")));
                commitValidation(group, proof, version, epoch, network, profileSignature, "reference", targetKey);
                continue;
            }
            final String currentNetwork = network;
            JSONObject opts = options(group.target, false, options.optInt("sample_limit"), options.optInt("probe_concurrency"), PROBE_DOWNLOAD);
            JSONObject prep = request("prepare", opts, new JSONArray()).put("probe_role", "validation");
            Job preparation = new Job(prep, "validation_prepare", response -> {
                if (!"completed".equals(response.optString("state")) || version != selectionVersion.get()
                        || !profileSignature.equals(profilesKey()) || !foreground || stopRequested) { validationSeen.removeAll(seen); return; }
                String goKey = response.getString("target_key");
                // Canonically identical Go targets must reuse reference (never send validation for the reference key).
                if (goKey.equals(targetKey)) {
                    JSONArray proof = new JSONArray();
                    for (int i = 0; i < chunk.length(); i++) proof.put(results.get(chunk.getJSONObject(i).getString("address")));
                    commitValidation(group, proof, version, epoch, currentNetwork, profileSignature, "reference", goKey);
                    return;
                }
                JSONObject request = request("probe", opts, chunk).put("probe_role", "validation");
                Job validation = new Job(request, "validation", result -> {
                    if (!"completed".equals(result.optString("state"))) { validationSeen.removeAll(seen); return; }
                    if (!goKey.equals(result.optString("target_key")) || !"validation".equals(result.optString("probe_role"))) return;
                    commitValidation(group, result.getJSONArray("results"), version, epoch, currentNetwork, profileSignature, "validation", goKey);
                });
                launch(validation);
            });
            launch(preparation); return; // Exactly one Go slot, never a profile×4096 task queue.
        }
    }
    private void commitValidation(ValidationGroup group, JSONArray proofs, long version, long epoch, String networkId,
            String profileSignature, String role, String goKey) throws Exception {
        if (version != selectionVersion.get() || epoch != scanEpoch.get() || !networkId.equals(net.id())
                || !profileSignature.equals(profilesKey())) return;
        JSONArray addresses = new JSONArray();
        for (int i = 0; i < proofs.length(); i++) {
            JSONObject r = proofs.getJSONObject(i);
            if (!eligible(r.optString("address")) || !r.optBoolean("probe_complete") || !role.equals(r.optString("result_kind")) || !goKey.equals(r.optString("target_key"))) continue;
            String address = r.getString("address");
            validationNotes.put(group.key + "|" + address, compatible(r) ? "本轮实际目标 HTTPS/CF 通过" : "本轮失败：" + r.optString("error", "HTTPS/CF 未通过"));
            if (compatible(r)) addresses.put(address);
        }
        dirty = true;
        if (addresses.length() == 0) return; // A failed new check never destroys a user's historical effective pool.
        String sessionId = session, referenceSignature = referenceKey;
        long storedSelectionVersion = data.optLong("selection_version");
        selectionIO.execute(() -> {
            try {
                JSONObject saved = store.update(root -> {
                    if (version != selectionVersion.get() || storedSelectionVersion != root.optLong("selection_version")
                            || epoch != scanEpoch.get() || !networkId.equals(net.id())
                            || !profileSignature.equals(profilesKey()) || !referenceSignature.equals(currentReferenceKey(referenceId)))
                        throw new IllegalStateException("验证已过期；未覆盖新选择/节点参数");
                    Preferences prefs = new Preferences(context);
                    JSONObject validations = root.getJSONObject("validations");
                    for (String id : group.ids) {
                        if (!prefs.getProfileIds().contains(id)) throw new IllegalStateException("节点集合已变化");
                        Preferences p = prefs.forProfile(id);
                        if (!following(root, p)) continue;
                        JSONObject merged = PreferredIpResolver.baseParams(p); PreferredIpResolver.validatedConnections(p, merged);
                        if (!group.key.equals(PreferredIpResolver.fingerprint(PreferredIpResolver.target(p, merged))))
                            throw new IllegalStateException("最终目标已变化");
                        Set<String> subset = new LinkedHashSet<>(); JSONObject old = validations.optJSONObject(id);
                        if (old != null && group.key.equals(old.optString("target_key"))) {
                            JSONArray prior = old.optJSONArray("addresses");
                            for (int i = 0; prior != null && i < prior.length(); i++) if (isSelected(root, prior.getString(i))) subset.add(prior.getString(i));
                        }
                        boolean added = false;
                        for (int i = 0; i < addresses.length(); i++) if (isSelected(root, addresses.getString(i))) { subset.add(addresses.getString(i)); added = true; }
                        if (!added) continue;
                        validations.put(id, new JSONObject().put("target_key", group.key).put("network_id", networkId)
                                .put("session_id", sessionId).put("tested_at", clock.now()).put("addresses", new JSONArray(subset)));
                        if (!root.has("rollback_modes")) root.put("rollback_modes", new JSONObject(root.getJSONObject("profile_modes").toString()));
                        // Enabling requires at least one real following target proof; implicit modes remain implicit.
                        if (!root.optBoolean("user_disabled")) root.put("enabled", true);
                    }
                });
                post(() -> { acceptData(saved); message = "已保存实际节点兼容子集；仅下次连接生效"; });
            } catch (Exception e) { error(e); }
        });
    }
    private static boolean isSelected(JSONObject root, String address) {
        return PreferredIpPresentation.find(root.optJSONArray("selected"), address) != null;
    }
    void disable() {
        selectionVersion.incrementAndGet();
        selectionIO.execute(() -> {
            try {
                JSONObject saved = store.update(root -> {
                    root.put("enabled", false).put("user_disabled", true);
                    if (root.has("rollback_modes")) { root.put("profile_modes", root.getJSONObject("rollback_modes")); root.remove("rollback_modes"); }
                });
                post(() -> { acceptData(saved); validationSeen.clear(); message = "已禁用全局；下次连接恢复原入口，不重连"; });
            } catch (Exception e) { error(e); }
        });
    }
    void enable() {
        post(() -> {
            selectionVersion.incrementAndGet(); validationSeen.clear(); validationAllowed = true;
            selectionIO.execute(() -> {
                try {
                    JSONObject saved = store.update(root -> root.put("user_disabled", false));
                    post(() -> { acceptData(saved); message = "允许启用；当前真实节点验证通过后生效"; validateSelections(true); });
                } catch (Exception e) { error(e); }
            });
        });
    }
    void fetch(String id, String url, String kind) {
        post(() -> {
            if (active != null || preparingDisk) throw new IllegalStateException("请先暂停并等待网络退出再更新来源");
            if ("cidr6".equals(kind) && !net.ipv6()) throw new IllegalStateException("当前直连网络无可用 IPv6");
            sourceMessages.put(id, "更新中");
            JSONObject request = new JSONObject().put("operation", "fetch").put("network_id", network)
                    .put("source", new JSONObject().put("id", id).put("url", url).put("kind", kind));
            launch(new Job(request, "fetch", response -> {
                if (!"completed".equals(response.optString("state"))) { message = "来源更新未完成，保留旧快照"; return; }
                if (response.getJSONArray("errors").length() > 0) {
                    parsed = response; parseSource = id; parseKind = kind; parseUrl = url; parseAppend = false;
                    message = "来源含无效行，保留旧快照；请确认只导入有效行";
                } else sourceIO.execute(() -> replaceSource(id, url, kind, response, false));
            }));
        });
    }
    private final ExecutorService sourceIO = Executors.newSingleThreadExecutor();
    void parse(String content, String id, String kind) {
        sourceIO.execute(() -> {
            try {
                if (content.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw new IllegalArgumentException("文本超过2MiB");
                JSONObject response = new JSONObject(backend.parse(content, id, kind));
                post(() -> { parsed = response; parseSource = id; parseKind = kind; parseUrl = ""; parseAppend = true;
                    message = "解析完成，请检查错误行并确认有效行"; dirty = true; });
            } catch (Exception e) { error(e); }
        });
    }
    void importFile(Uri uri) {
        sourceIO.execute(() -> {
            try (InputStream in = context.getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) throw new IllegalStateException("无法读取 TXT");
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 2 * 1024 * 1024) throw new IllegalArgumentException("TXT超过2MiB");
                    out.write(buffer, 0, n);
                }
                String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(out.toByteArray())).toString();
                parse(content, "txt", "manual");
            } catch (Exception e) { error(e); }
        });
    }
    void legacy() {
        sourceIO.execute(() -> {
            try {
                StringBuilder text = new StringBuilder(); Preferences prefs = new Preferences(context);
                for (Preferences.ProfileInfo profile : prefs.getProfileList()) {
                    Preferences entry = prefs.forProfile(profile.id);
                    String raw = Preferences.PROTOCOL_X_TUNNEL.equals(entry.getProtocol()) ? entry.getXtRelayNodes() : entry.getPrefIp();
                    // NEVER read FallbackIp; relay originals are never modified.
                    for (String item : raw.split("[,\\r\\n]+")) {
                        String[] parts = item.trim().split("#", 2); String address = parts[0];
                        if (address.isEmpty()) continue;
                        if (address.matches("[0-9.]+")) address += ":443";
                        else if (address.startsWith("[") && address.endsWith("]")) address += ":443";
                        else if (!address.startsWith("[") && address.chars().filter(c -> c == ':').count() > 1) address = "[" + address + "]:443";
                        text.append(address); if (parts.length > 1) text.append('#').append(parts[1]); text.append('\n');
                    }
                }
                parse(text.toString(), "legacy", "legacy");
            } catch (Exception e) { error(e); }
        });
    }
    void acceptParsed() {
        post(() -> {
            if (parsed == null) return;
            JSONObject response = parsed; String id = parseSource, kind = parseKind, url = parseUrl; boolean append = parseAppend;
            sourceIO.execute(() -> replaceSource(id, url, kind, response, append));
            parsed = null; dirty = true;
        });
    }
    private void replaceSource(String id, String url, String kind, JSONObject response, boolean append) {
        try {
            JSONObject saved = store.update(root -> {
                JSONArray sources = root.getJSONArray("sources"); JSONObject source = null;
                for (int i = 0; i < sources.length(); i++) if (id.equals(sources.getJSONObject(i).getString("id"))) source = sources.getJSONObject(i);
                if (source == null) { source = new JSONObject().put("id", id); sources.put(source); }
                JSONArray rows = response.optJSONArray("candidates"); if (rows == null) rows = new JSONArray();
                if (append && source.optJSONArray("candidates") != null) rows = merge(source.getJSONArray("candidates"), rows);
                source.put("url", url).put("kind", kind).put("enabled", true).put("updated_at", clock.now())
                        .put("candidates", rows).put("prefixes", response.optJSONArray("prefixes") == null ? new JSONArray() : response.getJSONArray("prefixes"));
                rebuildSources(root);
            });
            post(() -> { acceptData(saved); sourceMessages.put(id, "已保存"); message = "来源已保存；当前冻结队列不变，下次重新开始才采用"; });
        } catch (Exception e) { error(e); }
    }
    void enableSource(String id, boolean enabled) { changeSource(id, enabled, false); }
    void deleteSource(String id) { changeSource(id, false, true); }
    private void changeSource(String id, boolean enabled, boolean delete) {
        sourceIO.execute(() -> {
            try {
                JSONObject saved = store.update(root -> {
                    JSONArray old = root.getJSONArray("sources"), kept = new JSONArray();
                    for (int i = 0; i < old.length(); i++) {
                        JSONObject source = old.getJSONObject(i);
                        if (id.equals(source.getString("id"))) { if (delete) continue; source.put("enabled", enabled); }
                        kept.put(source);
                    }
                    root.put("sources", kept); rebuildSources(root);
                });
                post(() -> { acceptData(saved); message = "来源已更新；冻结队列和已选池不变，需重新开始采用新来源"; });
            } catch (Exception e) { error(e); }
        });
    }
    private static JSONArray merge(JSONArray a, JSONArray b) throws Exception {
        Map<String, JSONObject> rows = new LinkedHashMap<>();
        for (JSONArray array : new JSONArray[]{a, b}) for (int i = 0; i < array.length(); i++) {
            JSONObject row = new JSONObject(array.getJSONObject(i).toString());
            JSONObject previous = rows.get(row.getString("address"));
            if (previous != null && !previous.optString("comment").isEmpty()) {
                String before = previous.optString("comment"), after = row.optString("comment");
                row.put("comment", after.isEmpty() || before.equals(after) ? before : before + " / " + after);
            }
            rows.put(row.getString("address"), row);
        }
        return new JSONArray(rows.values());
    }
    private static void rebuildSources(JSONObject root) throws Exception {
        // Leave cross-source dedup/provenance/priority to Go prepare, rather than losing higher-priority records here.
        JSONArray rows = new JSONArray(), prefixes = new JSONArray(), sources = root.getJSONArray("sources");
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.getJSONObject(i); if (!source.optBoolean("enabled", true)) continue;
            JSONArray cs = source.getJSONArray("candidates"), ps = source.getJSONArray("prefixes");
            for (int j = 0; j < cs.length(); j++) rows.put(cs.getJSONObject(j));
            for (int j = 0; j < ps.length(); j++) prefixes.put(ps.getJSONObject(j));
        }
        root.put("candidates", rows).put("prefixes", prefixes);
    }
    private JSONObject historySnapshot() throws Exception {
        if (candidates.isEmpty()) return new JSONObject();
        boolean complete = results.size() == candidates.size();
        for (JSONObject r : results.values()) complete &= r.optBoolean("probe_complete");
        return new JSONObject().put("session_id", session).put("network_id", sessionNetwork).put("context_key", contextKey)
                .put("reference_id", referenceId).put("target_key", targetKey).put("test_target", target)
                .put("started_at", startedAt).put("updated_at", clock.now()).put("score_version", 1)
                .put("state", complete ? "completed" : "paused").put("candidates", new JSONArray(candidates.values()))
                .put("results", new JSONArray(results.values())).put("download_notes", new JSONObject(downloadNotes));
    }
    private void checkpoint() throws Exception {
        JSONObject snapshot = historySnapshot(); if (snapshot.length() == 0) return;
        // Called at batch completion / pause only, never at the 500ms render cadence.
        JSONObject frozen = new JSONObject(snapshot.toString());
        historyIO.execute(() -> {
            try {
                history.write(frozen, false);
                if ("completed".equals(frozen.optString("state"))) history.write(frozen, true);
            } catch (Exception e) { error(e); }
        });
    }
    private String compatibility(String address) throws Exception {
        StringBuilder out = new StringBuilder(); Preferences prefs = new Preferences(context);
        for (Preferences.ProfileInfo info : prefs.getProfileList()) {
            Preferences p = prefs.forProfile(info.id); out.append('\n').append(info.name).append("：");
            try {
                JSONObject merged = PreferredIpResolver.baseParams(p);
                PreferredIpResolver.Decision decision = PreferredIpResolver.apply(p, merged, data);
                JSONObject validation = data.getJSONObject("validations").optJSONObject(info.id);
                boolean member = false;
                JSONArray addresses = validation == null ? null : validation.optJSONArray("addresses");
                for (int i = 0; addresses != null && i < addresses.length(); i++) member |= address.equals(addresses.optString(i));
                JSONObject actual = PreferredIpResolver.target(p, merged);
                if (decision.applied && member && isSelected(data, address)) {
                    out.append("已保存验证子集（历史配置） · ").append(decision.summary)
                            .append(" · 保存验证网络 ").append(validation.optString("network_id", "历史未记录"));
                } else out.append(decision.reason);
                String key = PreferredIpResolver.fingerprint(actual);
                String note = validationNotes.get(key + "|" + address);
                out.append(" · ").append(note == null ? "当前 IP 待验证/未采用新的证明" : note);
                out.append("\n").append(PreferredIpPresentation.target(actual));
            } catch (Exception e) { out.append("未采用：实际节点参数无效"); }
        }
        return out.toString();
    }
    private String detail(JSONObject candidate, JSONObject r, boolean historical, JSONObject measuredTarget, String measuredNetwork) throws Exception {
        StringBuilder out = new StringBuilder(PreferredIpPresentation.sources(candidate, data));
        String comment = PreferredIpPresentation.comment(candidate, data); if (!comment.isEmpty()) out.append("\n备注：").append(comment);
        if (r == null) out.append("\nN/A · 尚未执行参考检查");
        else {
            String na = r.optString("na_reason"); if (na.isEmpty()) na = r.optBoolean("probe_complete") ? "前一步失败/该项未取得测量" : "尚未执行或暂停后待重测";
            out.append(historical ? "\n历史成绩（不参与当前排名/新增选择）" : "\n阶段：" + PreferredIpPresentation.phase(r.optString("probe_phase")));
            out.append("\n测量网络：").append(measuredNetwork);
            out.append("\n时间：").append(r.optLong("tested_at") > 0 ? java.text.DateFormat.getDateTimeInstance().format(new Date(r.optLong("tested_at"))) : "N/A · 未完成");
            out.append("\n").append(PreferredIpPresentation.target(measuredTarget));
            out.append("\nTCP 独立连接：").append(r.optInt("tcp_success")).append('/').append(r.optInt("tcp_attempts"));
            out.append("\nTCP 最小 / 平均 / 最大：").append(r.optBoolean("tcp_measured")
                    ? String.format(Locale.getDefault(), "%.1f / %.1f / %.1f ms", r.optDouble("tcp_min_ms"), r.optDouble("tcp_avg_ms"), r.optDouble("tcp_max_ms")) : "N/A · " + na);
            out.append("\nTLS 证书 / 耗时：").append(r.optDouble("tls_ms", 0) > 0 || compatible(r) ? String.format(Locale.getDefault(), "校验证书握手 %.1f ms", r.optDouble("tls_ms")) : "N/A · " + na);
            out.append("\nHTTPS：").append(r.optInt("http_success")).append('/').append(r.optInt("http_attempts"));
            out.append(" · 中位 / 波动：").append(r.optBoolean("http_measured") ? String.format(Locale.getDefault(), "%.1f / %.1f ms", r.optDouble("http_median_ms"), r.optDouble("http_jitter_ms")) : "N/A · " + na);
            String echStatus = r.optString("ech_status", "N/A");
            out.append("\n参考 ECH：").append(echStatus).append("used".equals(echStatus) ? " · 实际 TLS 已接受"
                    : "disabled".equals(echStatus) ? " · 配置禁用" : " · 未使用/未接受 ECH，标准 TLS 仍校验证书；配置准备不等于已使用");
            if (!r.optString("error").isEmpty()) out.append("\n失败原因：").append(r.optString("error"));
            JSONArray scores = r.optJSONArray("score_breakdown");
            out.append("\n基础评分 v").append(r.optInt("score_version"));
            for (int i = 0; scores != null && i < scores.length(); i++) {
                JSONObject item = scores.getJSONObject(i);
                out.append('\n').append(scoreLabel(item.optString("key"))).append("：")
                        .append(r.optBoolean("score_ready") ? String.format(Locale.getDefault(), "%.1f / %.0f", item.optDouble("points"), item.optDouble("max_points")) : "N/A · 未完成");
            }
            String download = r.optString("download_state", "untested");
            out.append("\n手动下载：").append("untested".equals(download) ? "N/A · 未手动测速，不影响分数" : download);
            if (!"untested".equals(download)) out.append(String.format(Locale.getDefault(), " · %.2f MiB/s · 辅助峰值 %.2f MiB/s\n%.2f MiB / %.2f 秒 · 下载 ECH %s",
                    r.optDouble("download_mib_s"), r.optDouble("download_peak_mib_s"), r.optLong("download_bytes") / 1048576.0,
                    r.optDouble("download_seconds"), r.optString("download_ech_status")));
            JSONObject historicalNotes = oldHistory.optJSONObject("download_notes");
            String note = results.containsKey(candidate.getString("address")) ? downloadNotes.get(candidate.getString("address"))
                    : historicalNotes == null ? "" : historicalNotes.optString(candidate.getString("address"));
            if (note != null && !note.isEmpty()) out.append("\n下载说明：").append(note);
        }
        out.append("\nHTTPS / CF 通过不等于 VPN / WebSocket 认证或带宽。");
        out.append(compatibility(candidate.getString("address")));
        return out.toString();
    }
    private static String scoreLabel(String key) {
        switch (key) {
            case "tcp_success": return "TCP 成功率"; case "http_success": return "HTTPS 成功率";
            case "tcp_latency": return "TCP 平均延迟"; case "http_latency": return "HTTPS 中位延迟";
            case "http_jitter": return "HTTPS 波动"; default: return key;
        }
    }
    private void publish() throws Exception {
        if (data == null) {
            State loading = new State(); loading.data = PreferredIpStore.empty(); loading.rows = Collections.emptyList();
            loading.sourceMessages = Collections.emptyMap(); loading.referenceId = referenceId; loading.referenceSummary = referenceSummary;
            loading.message = message; loading.phase = "优选库尚未加载；未修改磁盘"; loading.network = net.label();
            loading.sessionId = ""; loading.activeDownload = ""; loading.paused = true; loading.stale = true;
            loading.metered = net.metered(); dirty = false;
            main.post(() -> { Listener current = listener; if (current != null) current.changed(loading); });
            return;
        }
        State state = new State(); state.data = data; // Published data is immutable; store transactions always return a new object.
        state.sourceMessages = Collections.unmodifiableMap(new LinkedHashMap<>(sourceMessages));
        state.parse = parsed; state.parseThirdParty = "list".equals(parseKind);
        state.message = message; state.network = net.label(); state.referenceId = referenceId; state.referenceSummary = referenceSummary;
        state.activeDownload = active != null && "download".equals(active.kind) ? active.request.getJSONArray("candidates").getJSONObject(0).getString("address") : "";
        state.sessionId = session; state.busy = active != null || preparingDisk; state.paused = paused; state.stale = stale;
        state.scanning = scanning; state.saving = selectionPending > 0; state.metered = net.metered(); state.selected = data.getJSONArray("selected").length();
        state.phase = phase + " · ECH " + ech.optString("state", "未准备")
                + (ech.optString("error").isEmpty() ? "" : " · " + ech.optString("error"));
        JSONObject budget = data.optJSONObject("download_ledger"); state.ledgerBlocked = PreferredIpLedger.lost(budget) && state.activeDownload.isEmpty();
        if (budget != null) { state.used = budget.optLong("used"); state.reserved = budget.optLong("reserved"); }
        if (!state.activeDownload.isEmpty()) { state.used = Math.max(state.used, liveDownloadUsed); state.reserved = Math.max(0, state.reserved - liveDownloadBytes); }
        LinkedHashMap<String, JSONObject> visible = new LinkedHashMap<>(candidates);
        // Keep historical selected rows visible even when the source is deleted/disabled or the new probe failed.
        Set<String> checkedAddresses = new HashSet<>();
        JSONArray selected = data.getJSONArray("selected");
        for (int i = 0; i < selected.length(); i++) { JSONObject c = selected.getJSONObject(i); checkedAddresses.add(c.getString("address")); if (!visible.containsKey(c.getString("address"))) visible.put(c.getString("address"), c); }
        if (session.isEmpty()) {
            JSONArray old = oldHistory.optJSONArray("candidates");
            for (int i = 0; old != null && i < old.length(); i++) { JSONObject c = old.getJSONObject(i); if (!visible.containsKey(c.getString("address"))) visible.put(c.getString("address"), c); }
        }
        List<String> addresses = new ArrayList<>(visible.keySet());
        // Stable sort: original Go priority/numeric order breaks ties. No Java IP sampling/sorting duplication.
        addresses.sort((a, b) -> {
            JSONObject ra = results.get(a), rb = results.get(b);
            int ga = rank(ra, candidates.containsKey(a)), gb = rank(rb, candidates.containsKey(b));
            if (ga != gb) return Integer.compare(ga, gb);
            return ga == 0 ? Double.compare(rb.optDouble("score"), ra.optDouble("score")) : 0;
        });
        List<Row> rows = new ArrayList<>();
        for (String address : addresses) {
            JSONObject r = results.get(address); boolean historical = stale || !candidates.containsKey(address);
            if (r == null && historical) r = PreferredIpPresentation.find(oldHistory.optJSONArray("results"), address);
            boolean current = !historical && r != null && r.optBoolean("probe_complete") && r.optBoolean("score_ready") && r.optInt("score_version") == 1;
            if (current) { state.completed++; if (compatible(r)) state.success++; }
            String colo = r == null ? "" : r.optString("colo"); if (!colo.matches("[A-Za-z]{3}")) colo = "UKN";
            boolean checked = checkedAddresses.contains(address), valid = eligible(address);
            rows.add(new Row(address, colo.toUpperCase(Locale.ROOT), current ? String.format(Locale.getDefault(), "%.1f", r.optDouble("score")) : "—",
                    expanded.contains(address) ? detail(visible.get(address), r, historical, results.containsKey(address) ? target : oldHistory.optJSONObject("test_target"),
                            results.containsKey(address) ? sessionNetwork : oldHistory.optString("network_id")) : "", checked, checked || valid, valid, r, state.saving + "|" + state.activeDownload));
        }
        state.rows = Collections.unmodifiableList(rows); state.total = candidates.size();
        dirty = false;
        main.post(() -> { Listener current = listener; if (current != null) current.changed(state); });
    }
    private int rank(JSONObject r, boolean member) {
        if (stale || !member) return 3;
        if (r == null || !r.optBoolean("probe_complete") || !r.optBoolean("score_ready")) return 1;
        return compatible(r) ? 0 : 2;
    }
}
