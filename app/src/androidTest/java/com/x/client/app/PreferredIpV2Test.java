package com.x.client.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;
import com.google.android.material.tabs.TabLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real widgets/AtomicFile/controller transactions, simulated network wire only.
 * No controller State/result/proof/selection injection. Reflection is for read-only
 * assertions and executor fences, plus cancellation of this case's periodic future. */
final class PreferredIpV2Test {
    static final String X = "104.16.1.1:443", Y = "104.16.1.2:443", V6 = "[2606:4700::1111]:443";
    static final String[] NAMES = {
        "testNativeTabsSwipeHeadingAndIpv6",
        "testNativeSelectionValidationAndLastDisable",
        "testForegroundAtomicReadAddFailure",
        "testForegroundAtomicReadRemoveFailure",
        "testStoppedAtomicReadAddFailure",
        "testStoppedAtomicReadRemoveFailure",
        "testSettingsCoverReturnsSameActivity",
        "testPendingNativeIntentUsesDiskUntilSettled",
        "testNetworkEpochAndAbaRejectQueuedNativeAdd",
        "testStoreMonitorRejectsQueuedNativeAdd",
        "testBlockedPollCancelAndWorkerDoneResume",
        "testDownloadSixFieldsAndDurableBudget",
        "testHistoryAtomicFilesWhitelistAndLimits",
        "testHistoryMigrationTransferFailurePreservesRoot",
        "testLedgerUncertainReservationAndExplicitRound"
    };
    interface Case { void run(Fixture f) throws Exception; }
    static void run(Instrumentation instrumentation, PreferredIpFeedbackTest.Reporter reporter) throws Exception {
        Case[] cases = { PreferredIpV2Test::testNativeTabsSwipeHeadingAndIpv6,
            PreferredIpV2Test::testNativeSelectionValidationAndLastDisable,
            PreferredIpV2Test::testForegroundAtomicReadAddFailure, PreferredIpV2Test::testForegroundAtomicReadRemoveFailure,
            PreferredIpV2Test::testStoppedAtomicReadAddFailure, PreferredIpV2Test::testStoppedAtomicReadRemoveFailure,
            PreferredIpV2Test::testSettingsCoverReturnsSameActivity,
            PreferredIpV2Test::testPendingNativeIntentUsesDiskUntilSettled,
            PreferredIpV2Test::testNetworkEpochAndAbaRejectQueuedNativeAdd,
            PreferredIpV2Test::testStoreMonitorRejectsQueuedNativeAdd,
            PreferredIpV2Test::testBlockedPollCancelAndWorkerDoneResume,
            PreferredIpV2Test::testDownloadSixFieldsAndDurableBudget,
            PreferredIpV2Test::testHistoryAtomicFilesWhitelistAndLimits,
            PreferredIpV2Test::testHistoryMigrationTransferFailurePreservesRoot,
            PreferredIpV2Test::testLedgerUncertainReservationAndExplicitRound };
        eq(NAMES.length, cases.length);
        for (int n = 0; n < cases.length; n++) {
            try (Fixture f = new Fixture(instrumentation)) { cases[n].run(f); }
            // Cleanup must succeed too; never emit PASS for a failed/abandoned case.
            reporter.passed(PreferredIpFeedbackTest.TOTAL + n + 1, NAMES[n]);
        }
    }
    static void check(boolean b, String why) { if (!b) throw new AssertionError(why); }
    static void eq(Object a, Object b) { check(Objects.equals(a, b), "expected " + a + ", actual " + b); }
    static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    static Object invoke(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(o, args);
    }
    static void await(CountDownLatch latch, String why) throws Exception {
        check(latch.await(10, TimeUnit.SECONDS), "timeout: " + why);
    }
    static void fence(ExecutorService e) throws Exception { e.submit(() -> { }).get(10, TimeUnit.SECONDS); }
    interface Condition { boolean ready() throws Exception; }
    // Bounded condition polling, not a sleep-based race trigger. All race windows use latches/fences.
    static void until(Condition c, String why) throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        do { if (c.ready()) return; Thread.yield(); } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("timeout: " + why);
    }
    static JSONObject candidate(String address) throws Exception {
        String ip = address.startsWith("[") ? address.substring(1, address.indexOf(']')) : address.substring(0, address.lastIndexOf(':'));
        return new JSONObject().put("address", address).put("ip", ip).put("port", 443).put("comment", "手动备注")
            .put("source_id", "manual").put("source_kind", "manual")
            .put("source_ids", new JSONArray().put("manual")).put("source_kinds", new JSONArray().put("manual"));
    }
    static byte[] bytes(File file) throws Exception {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
    static void writeBytes(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); out.getFD().sync(); }
    }
    static void delete(File f) throws Exception {
        File[] children = f.listFiles(); if (children != null) for (File child : children) delete(child);
        if (f.exists()) check(f.delete(), "delete " + f);
    }
    static final class Network implements PreferredIpRuntime.NetworkAccess {
        volatile String value = "A"; volatile Runnable callback; volatile boolean charged;
        public String id() { return value; } public String label() { return "fixture-" + value; }
        public void requireDirect() { } public boolean ipv6() { return true; } public boolean metered() { return charged; }
        public void watch(Runnable r) { callback = r; } public void unwatch() { callback = null; }
        void change(String id) { value = id; Runnable r = callback; check(r != null, "network is watched"); r.run(); }
    }
    static final class Task {
        final JSONObject request; final String id, sessionId, networkId, targetKey, roundId;
        final long usedAtAdmission, reservedAtAdmission;
        final CountDownLatch polled = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        final BlockingQueue<JSONObject> replies = new LinkedBlockingQueue<>();
        final AtomicLong pollEntries = new AtomicLong();
        volatile JSONObject automatic, lastMatchingReply; volatile boolean consumedDone;
        Task(String id, JSONObject request, String session, String round, long used, String key) throws Exception {
            this.id = id; this.request = new JSONObject(request.toString());
            sessionId = session; roundId = round; usedAtAdmission = used; targetKey = key;
            networkId = request.getString("network_id");
            reservedAtAdmission = "download".equals(request.getString("operation")) ? Math.min(PreferredIpLedger.PER_IP, PreferredIpLedger.LIMIT - used) : 0;
        }
        boolean matches(JSONObject response) {
            return id.equals(response.optString("id")) && networkId.equals(response.optString("network_id"))
                && sessionId.equals(response.optString("session_id"));
        }
        void awaitPoll(long number) throws Exception {
            until(() -> pollEntries.get() >= number, "poll entry " + number);
        }
    }
    /** Checked script failures keep the production drain alive; collector failure forbids PASS.
     * Task identities and accounting freeze at admission, including delayed Start returns. */
    static final class Backend implements PreferredIpRuntime.Backend {
        final BlockingQueue<Task> started = new LinkedBlockingQueue<>();
        final Map<String, Task> tasks = new ConcurrentHashMap<>();
        final List<JSONObject> requests = Collections.synchronizedList(new ArrayList<>());
        final PreferredIpTestControl.Failures failures;
        volatile boolean holdProbe, holdDownload, holdFetch, cleaning;
        volatile PreferredIpTestControl.Gate startReturn;
        volatile String admitted = "", session = "", round = "round-1";
        final AtomicLong startAttempts = new AtomicLong();
        int serial, sessionSerial; long used;
        Backend(PreferredIpTestControl.Failures failures) { this.failures = failures; }
        void require(boolean value, String message) throws IOException {
            if (!value) { IOException e = new IOException(message); failures.add(e); throw e; }
        }
        public String parse(String text, String source, String kind) throws Exception {
            JSONArray rows = new JSONArray();
            for (String line : text.split("[\\r\\n]+")) {
                if (line.trim().isEmpty()) continue;
                String[] fields = line.trim().split("#", 2);
                rows.put(candidate(fields[0]).put("comment", fields.length == 2 ? fields[1] : "")
                    .put("source_id", source).put("source_kind", kind).put("source_ids", new JSONArray().put(source)).put("source_kinds", new JSONArray().put(kind)));
            }
            return new JSONObject().put("candidates", rows).put("prefixes", new JSONArray()).put("errors", new JSONArray()).toString();
        }
        public String start(String raw) throws Exception {
            startAttempts.incrementAndGet(); Task task = null;
            try {
                synchronized (this) {
                    require(admitted.isEmpty(), "second backend slot before matching worker_done");
                    JSONObject request = new JSONObject(raw); requests.add(new JSONObject(raw));
                    String operation = request.getString("operation");
                    if (operation.equals("prepare") && request.optString("session_id").isEmpty()) session = "session-" + (++sessionSerial);
                    if (request.optBoolean("new_download_round")) { round = "round-" + (++serial); used = 0; }
                    boolean fetch = operation.equals("fetch");
                    task = new Task("task-" + (++serial), request, fetch ? "" : session, fetch ? "" : round, fetch ? 0 : used,
                        fetch ? "" : targetKey(request.getJSONObject("options")));
                    tasks.put(task.id, task); admitted = task.id;
                    if (!(operation.equals("probe") && holdProbe) && !(operation.equals("download") && holdDownload) && !(fetch && holdFetch))
                        task.automatic = snapshot(task, "completed", true, true, 0);
                    started.add(task);
                }
                PreferredIpTestControl.Gate gate = startReturn;
                if (gate != null && !cleaning) {
                    try { gate.block(); }
                    catch (Exception e) { failures.add(e); cleaning = true; } // Return admitted ID so Cancel/drain remain possible.
                }
                return task.id;
            } catch (Exception e) {
                failures.add(e);
                if (task != null) { cleaning = true; return task.id; } // Return every admitted ID to the production drain.
                throw e;
            }
        }
        public String poll(String id) throws Exception {
            Task task = tasks.get(id); require(task != null, "known poll id");
            task.pollEntries.incrementAndGet(); task.polled.countDown();
            JSONObject response;
            try {
                response = cleaning ? exit(task) : task.automatic;
                if (response == null) {
                    response = task.replies.poll(10, TimeUnit.SECONDS);
                    if (response == null) {
                        failures.add(new TimeoutException("missing scripted poll reply")); cleaning = true; response = exit(task);
                    }
                }
                if (task.matches(response)) task.lastMatchingReply = new JSONObject(response.toString());
                if (response.optBoolean("worker_done") && task.matches(response)) {
                    synchronized (this) {
                        require(admitted.equals(id), "matching slot exit"); admitted = ""; task.consumedDone = true;
                        used = response.getLong("download_round_used");
                    }
                }
                return response.toString();
            } catch (Exception e) { failures.add(e); cleaning = true; throw e; }
        }
        public void cancel(String id) {
            Task task = tasks.get(id);
            if (task == null) { failures.add(new IOException("unknown cancel id")); return; }
            task.cancelled.countDown();
        }
        Task next(String operation) throws Exception {
            Task task = started.poll(10, TimeUnit.SECONDS); check(task != null, "start " + operation);
            eq(operation, task.request.getString("operation")); return task;
        }
        JSONObject exit(Task task) throws Exception {
            return task.lastMatchingReply == null ? snapshot(task, "cancelled", true, false, 0)
                : new JSONObject(task.lastMatchingReply.toString()).put("state", "cancelled").put("worker_done", true);
        }
        String targetKey(JSONObject opts) throws Exception {
            JSONObject target = new JSONObject();
            for (String key : new String[]{"protocol", "target_host", "target_port", "enable_ech", "ech_domain", "dns_server"}) target.put(key, opts.get(key));
            return PreferredIpIds.opaque("wire-target", PreferredIpResolver.fingerprint(target));
        }
        JSONObject snapshot(Task task, String state, boolean done, boolean complete, long body) throws Exception {
            String operation = task.request.getString("operation"), role = task.request.optString("probe_role", "fetch".equals(task.request.getString("operation")) ? "" : "reference");
            JSONArray cs = task.request.optJSONArray("candidates"), rows = new JSONArray();
            if (cs == null) cs = new JSONArray();
            String key = task.targetKey;
            for (int n = 0; n < cs.length(); n++) {
                JSONObject row = new JSONObject(cs.getJSONObject(n).toString()); String address = row.getString("address");
                boolean failed = V6.equals(address); boolean download = operation.equals("download");
                JSONArray parts = new JSONArray();
                String[] keys = {"tcp_success", "http_success", "tcp_latency", "http_latency", "http_jitter"};
                double[] points = X.equals(address) ? new double[]{30, 30, 14.25, 14.55, 9.9} : new double[]{30, 30, 12, 9, 9};
                int[] maxima = {30, 30, 15, 15, 10};
                for (int p = 0; p < keys.length; p++) parts.put(new JSONObject().put("key", keys[p]).put("points", complete && !failed && !download ? points[p] : 0).put("max_points", maxima[p]));
                row.put("result_kind", download ? "download" : role).put("target_key", download ? "" : key)
                    .put("probe_complete", complete && !download).put("score_ready", complete && !download).put("score_version", complete && !download ? 1 : 0)
                    .put("score", complete && !download && !failed ? (X.equals(address) ? 98.7 : 90.0) : 0).put("score_breakdown", parts)
                    .put("status", download || !complete ? "untested" : failed ? "failed" : "valid")
                    .put("colo", failed || download ? "" : "HKG").put("error", failed && complete ? "TLS certificate failure" : "")
                    .put("tcp_success", complete && !failed && !download ? 10 : 0).put("tcp_attempts", download ? 0 : 10)
                    .put("http_success", complete && !failed && !download ? 5 : 0).put("http_attempts", download ? 0 : 5)
                    .put("tcp_measured", !failed && complete && !download).put("http_measured", !failed && complete && !download)
                    .put("tcp_min_ms", download || failed ? 0 : 20).put("tcp_avg_ms", download || failed ? 0 : X.equals(address) ? 25 : 100)
                    .put("tcp_max_ms", download || failed ? 0 : X.equals(address) ? 30 : 150).put("tls_ms", download || failed ? 0 : 35)
                    .put("http_median_ms", download || failed ? 0 : X.equals(address) ? 30 : 400).put("http_jitter_ms", download || failed ? 0 : X.equals(address) ? 5 : 50)
                    .put("probe_phase", download ? "" : complete ? "done" : "connectivity")
                    .put("na_reason", download ? "仅下载；参考成绩与证明未修改" : complete ? "" : "暂停后待重测").put("ech_status", "disabled").put("recommended", false)
                    .put("tested_at", download ? 0 : 1730000002000L)
                    .put("download_state", download ? "failed" : "untested").put("download_bytes", download ? body : 0)
                    .put("download_seconds", download ? 2 : 0).put("download_mib_s", 0).put("download_peak_mib_s", 0).put("download_ech_status", download ? "fallback" : "disabled");
                if (!operation.equals("prepare")) rows.put(row);
            }
            long reserved = task.reservedAtAdmission;
            long limit = "fetch".equals(operation) ? 0 : PreferredIpLedger.LIMIT;
            return new JSONObject().put("id", task.id).put("operation", operation).put("state", state).put("phase", done ? "done" : "fetch".equals(operation) ? "fetch" : "connectivity")
                .put("worker_done", done).put("session_id", task.sessionId).put("target_key", key).put("probe_role", role)
                .put("network_id", task.networkId).put("started_at", 1730000001000L).put("updated_at", 1730000002000L)
                .put("completed", complete ? cs.length() : 0).put("total", cs.length()).put("download_bytes", body)
                .put("download_round_id", task.roundId).put("download_round_limit", limit).put("download_round_used", task.usedAtAdmission + body)
                .put("download_round_remaining", limit - task.usedAtAdmission - body).put("download_reserved_bytes", reserved)
                .put("ech", new JSONObject().put("key", "fetch".equals(operation) ? "" : "ech-key").put("state", "fetch".equals(operation) ? "" : "disabled").put("prepared_at", 0).put("expires_at", 0).put("cache_hit", false).put("error", ""))
                .put("candidates", cs).put("prefixes", new JSONArray()).put("errors", new JSONArray()).put("warnings", new JSONArray()).put("error", "").put("results", rows);
        }
        void drain() throws Exception {
            cleaning = true;
            PreferredIpTestControl.Gate gate = startReturn; if (gate != null) gate.close();
            for (Task task : tasks.values()) if (!task.consumedDone) task.replies.offer(exit(task));
            // Future Start/poll calls also see cleaning: a one-time map traversal is not the drain guarantee.
        }
    }

    static final class Fixture implements AutoCloseable {
        final Instrumentation i; final Context target, isolated; final File dir;
        final SharedPreferences prefs; final Map<String, ?> original;
        final PreferredIpTestControl.Failures failures = new PreferredIpTestControl.Failures();
        final PreferredIpTestControl.Gates gates = new PreferredIpTestControl.Gates();
        final Backend backend = new Backend(failures); final Network network = new Network();
        final PreferredIpRuntime.Factory savedFactory;
        final String profileId = "preferred-v2-fixture";
        final PreferredIpStore store;
        PreferredIpActivity activity; Activity cover; PreferredIpController controller;
        ScheduledExecutorService control; ExecutorService selection;
        boolean dispatchedStop; ReadFault fault; PreferredIpTestControl.StateTrace trace;
        final List<AutoCloseable> resources = new ArrayList<>();
        Fixture(Instrumentation i) throws Exception {
            this.i = i; target = i.getTargetContext();
            prefs = target.getSharedPreferences(Preferences.PREFS_NAME, Context.MODE_MULTI_PROCESS);
            original = new HashMap<>(prefs.getAll()); savedFactory = PreferredIpRuntime.factory;
            dir = new File(target.getFilesDir(), "preferred-v2-" + System.nanoTime()); check(dir.mkdirs(), "case directory");
            isolated = new ContextWrapper(target) {
                @Override public File getFilesDir() { return dir; }
                @Override public Context getApplicationContext() { return this; }
            };
            store = new PreferredIpStore(isolated);
            Field retained = PreferredIpRuntime.class.getDeclaredField("retained"); retained.setAccessible(true);
            check(retained.get(null) == null, "previous case must drain/forget before next fixture");
            try {
            check(prefs.edit().clear().putStringSet(Preferences.PROFILES, new HashSet<>(Collections.singleton(profileId)))
                .putString(Preferences.CURRENT_PROFILE_ID, profileId).putString(Preferences.PROFILE_NAME_PREFIX + profileId, "A fixture")
                .putString(Preferences.PROTOCOL + "_" + profileId, "xtunnel")
                .putString(Preferences.XT_SERVER_ADDR + "_" + profileId, "wss://node.example.com/path")
                .putString(Preferences.XT_RELAY_NODES + "_" + profileId, "1.1.1.1:443,1.0.0.1:443")
                .putBoolean(Preferences.XT_DISABLE_ECH + "_" + profileId, true).putInt(Preferences.XT_CONNECTIONS + "_" + profileId, 3)
                .putString(Preferences.XT_TOKEN + "_" + profileId, "secret-token-fixture").commit(), "fixture preferences");
            JSONObject root = PreferredIpStore.empty(); JSONArray cs = new JSONArray().put(candidate(X)).put(candidate(Y)).put(candidate(V6));
            root.put("candidates", cs); root.getJSONArray("sources").put(new JSONObject().put("id", "manual").put("kind", "manual")
                .put("url", "").put("enabled", true).put("candidates", cs).put("prefixes", new JSONArray()));
            store.write(root, 0);
            PreferredIpRuntime.factory = (unused, reference) -> {
                controller = new PreferredIpController(isolated, reference, backend, network, () -> 1730000003000L);
                return controller;
            };
            } catch (Throwable failure) {
                PreferredIpRuntime.factory = savedFactory; restorePreferences(prefs, original); delete(dir);
                if (failure instanceof Exception) throw (Exception) failure;
                if (failure instanceof Error) throw (Error) failure;
                throw new AssertionError(failure);
            }
        }
        void main(Runnable r) { if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else i.runOnMainSync(r); }
        <T> T onMain(Callable<T> action) throws Exception {
            final Object[] result = new Object[1]; final Throwable[] error = new Throwable[1];
            main(() -> { try { result[0] = action.call(); } catch (Throwable e) { error[0] = e; } });
            if (error[0] instanceof Exception) throw (Exception) error[0];
            if (error[0] instanceof Error) throw (Error) error[0];
            @SuppressWarnings("unchecked") T value = (T) result[0]; return value;
        }
        View view(int id) throws Exception { return onMain(() -> activity.findViewById(id)); }
        View rowWidget(String address, int id) throws Exception {
            return onMain(() -> {
                for (int n = 0; n < list().getChildCount(); n++) {
                    View row = list().getChildAt(n);
                    if (address.equals(row.getTag())) return id == 0 ? row : row.findViewById(id);
                }
                throw new AssertionError("missing laid-out address: " + address);
            });
        }
        String rowText(String address) throws Exception { return onMain(() -> textIn(rowWidget(address, 0))); }
        long stableId(String address) throws Exception { return onMain(() -> list().getChildViewHolder(rowWidget(address, 0)).getItemId()); }
        void actorDrain() throws Exception {
            fence(control);
            check(!(Boolean) field(controller, "preparingDisk") && field(controller, "active") == null, "actorDrain requires matching network exit");
            for (int round = 0; round < 3; round++) {
                fence(control);
                for (String name : new String[]{"selectionIO", "sourceIO", "historyIO", "networkIO", "canceller"}) fence((ExecutorService) field(controller, name));
                fence(control); main(() -> { });
            }
        }
        void awaitAdapterIdle() throws Exception {
            ExecutorService differ = (ExecutorService) onMain(() -> field(activity, "differ"));
            until(() -> {
                fence(differ); main(() -> { });
                return onMain(() -> {
                    Object adapter = list().getAdapter();
                    if ((Boolean) field(adapter, "diffRunning")) return false;
                    // pending is sticky after sameRows early-return; read for diagnosis, never write it.
                    field(adapter, "pending");
                    List<?> rows = (List<?>) field(adapter, "rows"); PreferredIpController.State current = (PreferredIpController.State) field(activity, "state");
                    if (current == null || rows.size() != current.rows.size()) return false;
                    for (int n = 0; n < rows.size(); n++) {
                        PreferredIpController.Row row = (PreferredIpController.Row) rows.get(n), expected = current.rows.get(n);
                        if (!sameRender(row, expected)) return false;
                    }
                    return true;
                });
            }, "adapter differ/main apply matches published State");
        }
        void launch() throws Exception {
            activity = (PreferredIpActivity) i.startActivitySync(new Intent(target, PreferredIpActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, profileId));
            control = (ScheduledExecutorService) field(controller, "control"); selection = (ExecutorService) field(controller, "selectionIO");
            settle(); until(() -> state() != null, "initial State");
        }
        PreferredIpController.State state() throws Exception {
            final Object[] value = new Object[1]; main(() -> { try { value[0] = field(activity, "state"); } catch (Exception e) { throw new AssertionError(e); } });
            return (PreferredIpController.State) value[0];
        }
        void publish() throws Exception { control.submit(() -> { try { invoke(controller, "publish", new Class<?>[0]); } catch (Exception e) { throw new AssertionError(e); } }).get(10, TimeUnit.SECONDS); main(() -> { }); }
        void settle() throws Exception { fence(control); fence(selection); fence(control); fence(selection); fence(control); main(() -> { }); }
        void pausedComplete() throws Exception {
            backend.holdProbe = true;
            launch(); tab(1); click(view(R.id.preferred_primary));
            backend.next("prepare"); Task probe = backend.next("probe"); await(probe.polled, "natural probe poll");
            probe.replies.add(backend.snapshot(probe, "running", false, true, 0));
            until(() -> { publish(); return state().completed == 3 && state().busy; }, "complete results while workers still own slot");
            click(view(R.id.preferred_primary)); // Native Pause, not a direct controller call.
            await(probe.cancelled, "real pause Cancel");
            probe.replies.add(backend.snapshot(probe, "cancelled", true, true, 0));
            until(() -> { publish(); return !state().busy && state().paused; }, "matching drain before selection");
            backend.holdProbe = false; settle(); draw();
        }
        void tab(int page) throws Exception {
            main(() -> {
                TabLayout tabs = activity.findViewById(R.id.preferred_tabs);
                ViewGroup strip = (ViewGroup) tabs.getChildAt(0); check(strip.getChildCount() == 2, "two native tabs");
                check(strip.getChildAt(page).performClick(), "native TabView click");
            });
            until(() -> { final int[] p = {-1}; main(() -> p[0] = ((ViewPager) activity.findViewById(R.id.preferred_pager)).getCurrentItem()); return p[0] == page; }, "tab page");
            i.waitForIdleSync();
        }
        void click(View view) { main(() -> check(view != null && view.isEnabled() && view.performClick(), "real enabled widget click")); }
        RecyclerView list() {
            final RecyclerView[] found = new RecyclerView[1]; main(() -> found[0] = activity.findViewById(R.id.preferred_results)); return found[0];
        }
        View widget(String address, int id) throws Exception {
            final View[] found = new View[1];
            main(() -> { for (int n = 0; n < list().getChildCount(); n++) {
                View row = list().getChildAt(n); if (address.equals(row.getTag())) found[0] = row.findViewById(id);
            } });
            check(found[0] != null, "attached widget by address: " + address); main(() -> eq(address, found[0].getTag())); return found[0];
        }
        void show(String address) throws Exception {
            publish(); awaitAdapterIdle(); final int[] position = {-1};
            main(() -> { try {
                Object adapter = list().getAdapter(); List<?> rows = (List<?>) field(adapter, "rows");
                for (int n = 0; n < rows.size(); n++) if (address.equals(field(rows.get(n), "address"))) position[0] = n + 1;
                check(position[0] > 0, "current adapter contains " + address); list().scrollToPosition(position[0]);
            } catch (Exception e) { throw new AssertionError(e); } });
            draw();
            until(() -> { try { widget(address, R.id.preferred_choice); return true; } catch (AssertionError missing) { return false; } }, "holder after real layout");
        }
        boolean checked(String address) throws Exception {
            View view = widget(address, R.id.preferred_choice); final boolean[] b = {false}; main(() -> b[0] = ((CompoundButton) view).isChecked()); return b[0];
        }
        void draw() throws Exception { awaitAdapterIdle(); drawWithoutAdapterDrain(); }
        void drawWithoutAdapterDrain() throws Exception {
            CountDownLatch rendered = new CountDownLatch(1);
            main(() -> {
                View decor = activity.getWindow().getDecorView();
                decor.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
                    public void onDraw() { rendered.countDown(); decor.post(() -> { if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnDrawListener(this); }); }
                });
                decor.requestLayout(); decor.invalidate(); list().invalidate();
            });
            await(rendered, "actual layout/pre-draw/draw"); main(() -> { });
        }
        void selectX(boolean value) throws Exception {
            show(X); eq(!value, checked(X)); click(widget(X, R.id.preferred_choice)); settle(); publish(); draw();
            eq(value, checked(X)); eq(value, selected(store.read(), X));
        }
        void stopCycle() throws Exception {
            control.submit(() -> {
                try {
                    Object executor = control;
                    if (!(executor instanceof ScheduledThreadPoolExecutor)) executor = field(executor, "e");
                    check(executor instanceof ScheduledThreadPoolExecutor, "scheduled delegate"); int cancelled = 0;
                    for (Runnable r : ((ScheduledThreadPoolExecutor) executor).getQueue().toArray(new Runnable[0])) {
                        if (r instanceof RunnableScheduledFuture && ((RunnableScheduledFuture<?>) r).isPeriodic()) {
                            check(((Future<?>) r).cancel(false), "cancel this case periodic future"); cancelled++;
                        }
                    }
                    eq(1, cancelled);
                } catch (Exception e) { throw new AssertionError(e); }
            }).get(10, TimeUnit.SECONDS); publish();
        }
        void dispatchStop() {
            i.callActivityOnPause(activity); i.callActivityOnStop(activity); dispatchedStop = true;
        }
        void dispatchReturn() throws Exception {
            main(() -> { i.callActivityOnRestart(activity); i.callActivityOnStart(activity); i.callActivityOnResume(activity); dispatchedStop = false; });
            settle(); publish(); draw();
        }
        @Override public void close() throws Exception {
            // Each cleanup stage runs independently. Shared data is restored only after all live actors exit.
            failures.attempt(() -> gates.releaseAll());
            for (int n = resources.size() - 1; n >= 0; n--) {
                AutoCloseable resource = resources.get(n); failures.attempt(resource::close);
            }
            failures.attempt(() -> { if (fault != null) fault.close(); });
            failures.attempt(backend::drain);
            boolean drained = controller == null, actorsIdle = controller == null;
            if (controller != null) {
                failures.attempt(() -> {
                    if (control == null) control = (ScheduledExecutorService) field(controller, "control");
                    if (selection == null) selection = (ExecutorService) field(controller, "selectionIO");
                    controller.pause("case cleanup");
                });
                try {
                    until(() -> control.submit(() -> field(controller, "active") == null
                        && !(Boolean) field(controller, "preparingDisk") && ((Integer) field(controller, "selectionPending")) == 0).get(10, TimeUnit.SECONDS), "cleanup matching done and pending zero");
                    drained = true;
                } catch (Throwable e) { failures.add(e); }
                if (drained) {
                    try { actorDrain(); actorsIdle = true; } catch (Throwable e) { failures.add(e); }
                }
            }
            List<Activity> windows = new ArrayList<>(); if (cover != null) windows.add(cover); if (activity != null) windows.add(activity);
            for (Activity window : windows) failures.attempt(() -> main(() -> {
                if (window == activity && dispatchedStop) {
                    i.callActivityOnRestart(activity); i.callActivityOnStart(activity); i.callActivityOnResume(activity); dispatchedStop = false;
                }
                if (!window.isDestroyed() && !window.isFinishing()) window.finish();
            }));
            boolean windowsExited = true;
            for (Activity window : windows) {
                try {
                    until(() -> onMain(window::isDestroyed), "real Activity onDestroy");
                    if (window instanceof PreferredIpActivity) {
                        ExecutorService differ = onMain(() -> (ExecutorService) field(window, "differ"));
                        check(differ.awaitTermination(10, TimeUnit.SECONDS), "Activity differ terminated after onDestroy");
                    }
                } catch (Throwable e) { failures.add(e); windowsExited = false; }
            }
            boolean traceRestored = trace == null, controllersExited = controller == null;
            if (drained && actorsIdle && windowsExited) {
                if (trace != null) {
                    try { trace.close(); traceRestored = true; } catch (Throwable e) { failures.add(e); traceRestored = trace.restored(); }
                }
                if (traceRestored) {
                    failures.attempt(PreferredIpRuntime::forget);
                    boolean terminated = true;
                    if (controller != null) for (String name : new String[]{"control", "networkIO", "canceller", "selectionIO", "historyIO", "sourceIO"}) {
                        try { check(((ExecutorService) field(controller, name)).awaitTermination(10, TimeUnit.SECONDS), "Controller terminated: " + name); }
                        catch (Throwable e) { failures.add(e); terminated = false; }
                    }
                    controllersExited = terminated;
                    main(() -> { }); // Drain all final same-Looper publications, never remove them ourselves.
                }
            }
            if (controllersExited && windowsExited && traceRestored) {
                failures.attempt(() -> check(backend.admitted.isEmpty(), "no admitted fixture slot"));
                failures.attempt(() -> check(network.callback == null, "network unwatched"));
                PreferredIpRuntime.factory = savedFactory;
                failures.attempt(() -> restorePreferences(prefs, original));
                failures.attempt(() -> delete(dir));
            } else failures.add(new IllegalStateException("fail closed: live case resources retained; no Preferences/factory/file reset"));
            failures.assertEmpty(); // Collector errors can never yield a scenario PASS, even if drain recovered.
        }
    }

    @SuppressWarnings("unchecked") static void restorePreferences(SharedPreferences prefs, Map<String, ?> original) {
        SharedPreferences.Editor e = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            String k = entry.getKey(); Object v = entry.getValue();
            if (v instanceof String) e.putString(k, (String) v); else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v); else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Float) e.putFloat(k, (Float) v); else if (v instanceof Set) e.putStringSet(k, new HashSet<>((Set<String>) v));
            else throw new AssertionError("unsupported preferences type");
        }
        check(e.commit(), "restore real Preferences"); eq(original, prefs.getAll());
    }
    static boolean sameRender(PreferredIpController.Row a, PreferredIpController.Row b) {
        return a.address.equals(b.address) && a.location.equals(b.location) && a.score.equals(b.score)
            && a.detail.equals(b.detail) && a.checked == b.checked && a.selectable == b.selectable
            && a.downloadable == b.downloadable && a.interactionKey.equals(b.interactionKey);
    }
    static boolean selected(JSONObject root, String address) { return PreferredIpPresentation.find(root.optJSONArray("selected"), address) != null; }
    static Object storeMonitor() throws Exception { Field f = PreferredIpStore.class.getDeclaredField("LOCK"); f.setAccessible(true); return f.get(null); }
    /** Real API24 AtomicFile.openRead fault, NOT a write fault or fake Store response. */
    static final class ReadFault implements AutoCloseable {
        final File base, bak, saved, savedBak;
        ReadFault(File dir) throws Exception {
            base = new File(dir, "preferred-ip-v1.json"); bak = new File(base + ".bak");
            saved = new File(dir, "saved-root"); savedBak = new File(dir, "saved-bak");
            try {
                check(base.renameTo(saved), "safely move real root");
                if (bak.exists()) check(bak.renameTo(savedBak), "safely move .bak");
                check(base.mkdir(), "nonempty base directory"); writeBytes(new File(base, "block"), new byte[]{1});
            } catch (Throwable failure) {
                try { close(); } catch (Throwable rollback) { failure.addSuppressed(rollback); }
                if (failure instanceof Exception) throw (Exception) failure;
                throw (Error) failure;
            }
        }

        public void close() throws Exception {
            if (!saved.exists()) return;
            delete(base); check(saved.renameTo(base), "restore exact root bytes");
            if (savedBak.exists()) check(savedBak.renameTo(bak), "restore .bak");
        }
    }
    static void testNativeTabsSwipeHeadingAndIpv6(Fixture f) throws Exception {
        f.pausedComplete(); f.show(X); RecyclerView list = f.list();
        f.main(() -> check(list.getAdapter().hasStableIds(), "address stable IDs"));
        final long[] identity = {-1}; f.main(() -> identity[0] = list.getChildViewHolder(list.findViewWithTag(X)).getItemId());
        View heading = f.widget(X, R.id.preferred_row_heading), choice = f.widget(X, R.id.preferred_choice);
        f.main(() -> {
            check(choice.getLeft() < heading.getLeft(), "checkbox is far left");
            check(choice.getWidth() >= 48 * f.activity.getResources().getDisplayMetrics().density, "native checkbox 48dp target");
            check(textIn(list.findViewWithTag(X)).contains(String.format(Locale.getDefault(), "%.1f", 98.7)), "transparent measured score rendered");
        });
        check(!f.controller.expanded.contains(X), "default collapsed"); f.click(choice); f.settle(); f.publish(); f.draw();
        check(!f.controller.expanded.contains(X), "checkbox does not expand");
        f.click(heading); f.settle(); f.publish(); f.draw(); check(f.controller.expanded.contains(X), "native heading expands");
        String text = f.onMain(() -> ((TextView) f.rowWidget(X, R.id.preferred_row_detail)).getText().toString());
        for (String part : new String[]{"TCP", "TLS", "HTTPS", "参考 ECH", "基础评分", "手动下载", "未手动测速"}) check(text.contains(part), "detail: " + part);
        f.click(f.widget(X, R.id.preferred_row_heading)); f.settle(); f.publish(); f.draw(); check(!f.controller.expanded.contains(X), "heading folds");
        f.show(V6); String v6text = f.rowText(V6);
        check(v6text.contains("UKN"), "unknown location strict UKN");
        check(v6text.replace("\u200b", "").contains(V6), "IPv6 address includes port");
        long cancellations = ((AtomicLong) field(f.controller, "cancelVersion")).get();
        f.tab(0); f.tab(1); eq(cancellations, ((AtomicLong) field(f.controller, "cancelVersion")).get());
        // Native MotionEvents traverse ViewPager, not setCurrentItem or controller.tab.
        swipe(f, f.view(R.id.preferred_pager), true);
        until(() -> f.controller.tab == 0, "right swipe to source tab");
        swipe(f, f.view(R.id.preferred_pager), false);
        until(() -> f.controller.tab == 1, "left swipe to tests tab");
        f.show(X); f.main(() -> eq(identity[0], list.getChildViewHolder(list.findViewWithTag(X)).getItemId()));
        eq(true, f.checked(X)); f.show(Y); eq(false, f.checked(Y));
    }
    static String textIn(View v) {
        StringBuilder out = new StringBuilder(); if (v instanceof TextView) out.append(((TextView) v).getText());
        if (v instanceof ViewGroup) for (int n = 0; n < ((ViewGroup) v).getChildCount(); n++) out.append(textIn(((ViewGroup) v).getChildAt(n)));
        return out.toString();
    }
    static void swipe(Fixture f, View v, boolean right) {
        f.main(() -> {
            long down = SystemClock.uptimeMillis(); float start = v.getWidth() * (right ? .15f : .85f), end = v.getWidth() * (right ? .85f : .15f), y = v.getHeight() * .5f;
            for (int n = 0; n <= 12; n++) {
                int action = n == 0 ? MotionEvent.ACTION_DOWN : n == 12 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                MotionEvent e = MotionEvent.obtain(down, down + n * 20, action, start + (end - start) * n / 12f, y, 0);
                v.dispatchTouchEvent(e); e.recycle();
            }
        });
    }
    static void testNativeSelectionValidationAndLastDisable(Fixture f) throws Exception {
        f.pausedComplete(); long epoch = ((AtomicLong) field(f.controller, "scanEpoch")).get();
        f.selectX(true); f.settle(); f.publish();
        JSONObject root = f.store.read(); eq(1L, root.getLong("selection_version")); check(root.getBoolean("enabled"), "real reference validation enables");
        eq(X, root.getJSONObject("validations").getJSONObject(f.profileId).getJSONArray("addresses").getString(0));
        eq("A", root.getJSONObject("validations").getJSONObject(f.profileId).getString("network_id"));
        check(!root.getJSONObject("profile_modes").has(f.profileId), "implicit mode remains implicit");
        Preferences p = new Preferences(f.target).forProfile(f.profileId); JSONObject params = PreferredIpResolver.baseParams(p);
        check(PreferredIpResolver.apply(p, params, root).applied, "actual Resolver intersection"); eq(X, params.getString("relay_nodes"));
        eq("1.1.1.1:443,1.0.0.1:443", p.getXtRelayNodes()); eq(false, new Preferences(f.target).getEnable());
        f.tab(0); f.tab(1); eq(epoch, ((AtomicLong) field(f.controller, "scanEpoch")).get());
        f.network.change("B"); f.settle(); f.publish(); f.selectX(false);
        root = f.store.read(); eq(false, root.getBoolean("enabled")); eq(2L, root.getLong("selection_version"));
        eq(0, root.getJSONArray("selected").length()); eq(0, root.getJSONObject("validations").getJSONObject(f.profileId).getJSONArray("addresses").length());
        f.settle(); f.publish(); f.draw(); eq(2L, f.store.read().getLong("selection_version")); // listener reset never recurses.
    }
    static void testForegroundAtomicReadAddFailure(Fixture f) throws Exception { atomicReadFailure(f, false, false); }
    static void testForegroundAtomicReadRemoveFailure(Fixture f) throws Exception { atomicReadFailure(f, true, false); }
    static void testStoppedAtomicReadAddFailure(Fixture f) throws Exception { atomicReadFailure(f, false, true); }
    static void testStoppedAtomicReadRemoveFailure(Fixture f) throws Exception { atomicReadFailure(f, true, true); }
    static void atomicReadFailure(Fixture f, boolean initiallySelected, boolean stopped) throws Exception {
        f.pausedComplete(); if (initiallySelected) f.selectX(true);
        f.stopCycle(); f.show(X); JSONObject before = f.store.read(); byte[] oldBytes = bytes(new File(f.dir, "preferred-ip-v1.json"));
        f.trace = new PreferredIpTestControl.StateTrace(f);
        PreferredIpController.State initial = f.state(); check(!initial.saving, "initial saving false");
        List<String> rowContent = new ArrayList<>(); for (PreferredIpController.Row row : initial.rows) rowContent.add(row.content());
        PreferredIpActivity same = f.activity; View checkBox = f.widget(X, R.id.preferred_choice);
        synchronized (storeMonitor()) {
            // main never waits for Store/selectionIO. control fence only waits the short intent runnable.
            f.main(() -> {
                check(checkBox.performClick(), "native checkbox intent");
                try { fence(f.control); eq(1, field(f.controller, "selectionPending")); }
                catch (Exception e) { throw new AssertionError(e); }
                eq(!initiallySelected, ((CompoundButton) checkBox).isChecked());
                if (stopped) f.dispatchStop();
                eq(!initiallySelected, ((CompoundButton) checkBox).isChecked());
            });
            if (stopped) { eq(null, field(f.controller, "listener")); check(!same.isDestroyed(), "same instance not destroyed"); }
            f.fault = new ReadFault(f.dir);
            try { f.store.read(); throw new AssertionError("expected real AtomicFile openRead error"); }
            catch (FileNotFoundException expected) { }
        }
        f.settle(); eq(0, field(f.controller, "selectionPending"));
        // FIFO main fence executes BOTH completion State and settled notification while listener=null.
        if (stopped) {
            eq(null, field(f.controller, "listener"));
            check(f.state() == initial, "no saving/completion frame delivered while detached");
            f.trace.noSavingAndLostCompletion(X);
            // Instrumentation lifecycle dispatch does not hide the real Window. Production
            // pre-draw may already repair native checked while stopped; no return-only causality claim.
        } else {
            f.draw(); eq(initiallySelected, f.checked(X)); check(!f.state().saving, "completion saving false");
            for (PreferredIpTestControl.Event event : f.trace.snapshot()) if (event.state != null) check(!event.state.saving, "all real fast-failure frames saving=false");
            f.failures.assertEmpty();
        }
        f.fault.close(); f.fault = null; check(Arrays.equals(oldBytes, bytes(new File(f.dir, "preferred-ip-v1.json"))), "read fault never wrote bytes");
        eq(before.toString(), f.store.read().toString());
        if (stopped) f.dispatchReturn(); else { f.publish(); f.draw(); }
        check(f.activity == same && !same.isDestroyed(), "same un-destroyed Activity returns");
        eq(initiallySelected, f.checked(X)); f.show(Y); eq(false, f.checked(Y));
        List<String> afterContent = new ArrayList<>(); for (PreferredIpController.Row row : f.state().rows) afterContent.add(row.content());
        eq(rowContent, afterContent); // original collapsed Row content can be equal; not a synthetic saving frame.
        JSONObject after = f.store.read();
        for (String key : new String[]{"selected", "validations", "enabled", "selection_version"}) eq(String.valueOf(before.opt(key)), String.valueOf(after.opt(key)));
        long revision = after.getLong("revision"); f.publish(); f.draw(); f.settle(); eq(revision, f.store.read().getLong("revision"));
        f.selectX(!initiallySelected); // A real successful retry after restoring the read fault.
        JSONObject retried = f.store.read(); eq(!initiallySelected, selected(retried, X));
        eq(before.optLong("selection_version") + 1, retried.getLong("selection_version"));
        eq(!initiallySelected, retried.getBoolean("enabled"));
        // The case's periodic future is cancelled; only real settle/attach publications run, at pending=0.
    }
    static void testSettingsCoverReturnsSameActivity(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true); PreferredIpActivity same = f.activity; long revision = f.store.read().getLong("revision");
        f.cover = f.i.startActivitySync(new Intent(f.target, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        until(() -> field(f.controller, "listener") == null, "ActivityThread natural onStop under SettingsActivity");
        check(!same.isDestroyed(), "covered original not destroyed"); eq(false, field(f.controller, "foreground"));
        f.main(f.cover::finish); f.i.waitForIdleSync();
        until(() -> field(f.controller, "listener") == same, "natural original onStart");
        f.settle(); f.publish(); f.show(X); f.draw(); eq(true, f.checked(X));
        check(f.activity == same && !same.isDestroyed(), "natural return same instance");
        eq(revision, f.store.read().getLong("revision")); eq(2, f.backend.requests.size());
    }
    static void testPendingNativeIntentUsesDiskUntilSettled(Fixture f) throws Exception {
        f.pausedComplete(); f.stopCycle(); f.show(X); f.trace = new PreferredIpTestControl.StateTrace(f);
        long version = f.store.read().optLong("selection_version");
        synchronized (storeMonitor()) {
            View checkbox = f.widget(X, R.id.preferred_choice);
            f.main(() -> {
                check(checkbox.performClick(), "native pending intent click"); eq(true, ((CompoundButton) checkbox).isChecked());
                try { fence(f.control); eq(1, field(f.controller, "selectionPending")); } catch (Exception e) { throw new AssertionError(e); }
            }); // Never require the temporary checked value to survive a Window frame.
            f.publish(); f.draw(); eq(false, f.checked(X)); check(f.state().saving, "actual pending State saving true");
        }
        f.settle(); f.publish(); f.draw(); eq(true, f.checked(X)); eq(version + 1, f.store.read().getLong("selection_version"));
        f.settle(); eq(version + 1, f.store.read().getLong("selection_version"));
        check(f.trace.snapshot().stream().anyMatch(event -> event.state != null && event.state.saving), "observer saw the actual saving=true publication");
    }
    static void testNetworkEpochAndAbaRejectQueuedNativeAdd(Fixture f) throws Exception {
        f.pausedComplete(); f.stopCycle(); f.show(X); JSONObject before = f.store.read();
        CountDownLatch entered = new CountDownLatch(1), change = new CountDownLatch(1), changed = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> gate = f.control.submit(() -> {
            entered.countDown();
            try { await(change, "click queued"); f.network.change("B"); f.network.change("A");
                eq(false, field(f.controller, "stale"));
                eq(false, invoke(f.controller, "eligible", new Class<?>[]{String.class}, X));
                changed.countDown(); await(release, "allow queued intent then invalidate");
            } catch (Exception e) { throw new AssertionError(e); }
        });
        try {
            await(entered, "control blocked"); f.click(f.widget(X, R.id.preferred_choice)); change.countDown(); await(changed, "ABA revoked old epoch before invalidate");
        } finally { change.countDown(); release.countDown(); }
        gate.get(10, TimeUnit.SECONDS); f.settle(); f.publish(); f.draw(); eq(false, f.checked(X));
        eq(before.getJSONArray("selected").toString(), f.store.read().getJSONArray("selected").toString());
        eq(before.getJSONObject("validations").toString(), f.store.read().getJSONObject("validations").toString());
        eq(false, f.store.read().getBoolean("enabled"));
    }
    static void testStoreMonitorRejectsQueuedNativeAdd(Fixture f) throws Exception {
        f.pausedComplete(); f.stopCycle(); f.show(X); JSONObject before = f.store.read();
        synchronized (storeMonitor()) {
            f.click(f.widget(X, R.id.preferred_choice)); fence(f.control); eq(1, field(f.controller, "selectionPending"));
            f.network.change("B"); f.network.change("A");
        }
        f.settle(); f.publish(); f.draw(); eq(false, f.checked(X));
        eq(before.getJSONArray("selected").toString(), f.store.read().getJSONArray("selected").toString());
        eq(before.getJSONObject("validations").toString(), f.store.read().getJSONObject("validations").toString());
        eq(before.optLong("selection_version"), f.store.read().optLong("selection_version")); eq(false, f.store.read().getBoolean("enabled"));
    }
    static void testBlockedPollCancelAndWorkerDoneResume(Fixture f) throws Exception {
        f.backend.holdProbe = true; f.launch(); f.tab(1); f.click(f.view(R.id.preferred_primary)); f.backend.next("prepare");
        Task probe = f.backend.next("probe"); await(probe.polled, "poll actually blocked"); f.publish();
        JSONObject first = f.backend.snapshot(probe, "running", false, false, 0);
        JSONObject completedX = f.backend.snapshot(probe, "running", false, true, 0).getJSONArray("results").getJSONObject(0);
        first.getJSONArray("results").put(0, completedX); first.put("completed", 1); probe.replies.add(first);
        until(() -> { f.publish(); return f.state().completed == 1; }, "first complete reference while others remain partial");
        f.tab(0); f.tab(1); eq(1L, probe.cancelled.getCount());
        f.click(f.view(R.id.preferred_primary)); await(probe.cancelled, "independent Cancel while poll blocked");
        JSONObject cancelled = f.backend.snapshot(probe, "cancelled", false, false, 0);
        cancelled.getJSONArray("results").put(0, completedX); cancelled.put("completed", 1); probe.replies.add(cancelled);
        probe.awaitPoll(3); // next poll entry happens after parsing/posting the preceding false reply
        f.settle();
        until(() -> { f.publish(); return f.state().busy && f.state().paused; }, "cancelled remains busy without worker_done");
        eq(2, f.backend.requests.size()); eq(1, f.state().completed); eq("—", f.state().rows.get(1).score);
        f.click(f.view(R.id.preferred_primary)); fence(f.control); eq(2, f.backend.requests.size());
        JSONObject exited = new JSONObject(cancelled.toString()).put("worker_done", true); probe.replies.add(exited);
        until(() -> { f.publish(); return !f.state().busy; }, "matching worker_done releases slot");
        eq(1, f.state().completed); f.backend.holdProbe = false; f.click(f.view(R.id.preferred_primary));
        Task resumed = f.backend.next("probe"); JSONArray remaining = resumed.request.getJSONArray("candidates");
        eq(2, remaining.length()); eq(Y, remaining.getJSONObject(0).getString("address")); eq(V6, remaining.getJSONObject(1).getString("address"));
        eq(probe.request.getString("session_id"), resumed.request.getString("session_id"));
        until(() -> { f.publish(); return !f.state().busy && f.state().completed == 3; }, "resume unfinished only");
    }
    static void testDownloadSixFieldsAndDurableBudget(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true); f.backend.holdDownload = true;
        JSONObject before = new JSONObject(f.state().rows.get(0).result.toString()); JSONObject validations = f.store.read().getJSONObject("validations");
        f.show(X); f.click(f.widget(X, R.id.preferred_row_heading)); f.settle(); f.publish(); f.draw();
        f.click(f.rowWidget(X, R.id.preferred_row_download)); Task task = f.backend.next("download"); await(task.polled, "download poll");
        JSONObject opts = task.request.getJSONObject("options"); eq(52428800, opts.getInt("download_bytes_per_ip")); eq(10, opts.getInt("download_seconds"));
        eq(PreferredIpController.DOWNLOAD, opts.getString("download_url")); eq(1, task.request.getJSONArray("candidates").length());
        eq(52428800L, f.store.read().getJSONObject("download_ledger").getLong("reserved")); f.publish(); f.draw();
        f.click(f.rowWidget(X, R.id.preferred_row_download)); await(task.cancelled, "native row download cancel");
        task.replies.add(f.backend.snapshot(task, "cancelled", false, false, 7 * 1048576L));
        task.awaitPoll(2); f.settle(); eq(52428800L, f.store.read().getJSONObject("download_ledger").getLong("reserved"));
        task.replies.add(f.backend.snapshot(task, "cancelled", true, false, 7 * 1048576L));
        until(() -> { f.publish(); return !f.state().busy; }, "download drain and bill actual cancelled bytes");
        JSONObject ledger = f.store.read().getJSONObject("download_ledger"); eq(7 * 1048576L, ledger.getLong("used")); eq(0L, ledger.getLong("reserved"));
        JSONObject result = f.state().rows.get(0).result;
        Set<String> six = new HashSet<>(Arrays.asList("download_bytes", "download_seconds", "download_mib_s", "download_peak_mib_s", "download_state", "download_ech_status"));
        Iterator<String> keys = before.keys(); while (keys.hasNext()) { String key = keys.next(); if (!six.contains(key)) eq(before.get(key).toString(), result.get(key).toString()); }
        eq(7 * 1048576L, result.getLong("download_bytes")); eq(2.0, result.getDouble("download_seconds"));
        eq(0.0, result.getDouble("download_mib_s")); eq(0.0, result.getDouble("download_peak_mib_s"));
        eq("failed", result.getString("download_state")); eq("fallback", result.getString("download_ech_status"));
        f.show(X); f.draw();
        String displayed = f.rowText(X);
        for (String part : new String[]{"failed", "辅助峰值", "秒", "下载 ECH fallback"}) check(displayed.contains(part), "download detail rendered: " + part);
        eq(validations.toString(), f.store.read().getJSONObject("validations").toString());
        for (JSONObject request : f.backend.requests) if (!request.getString("operation").equals("download")) {
            eq(20971520, request.getJSONObject("options").getInt("download_bytes_per_ip")); eq(5, request.getJSONObject("options").getInt("download_seconds"));
            eq(0, request.getJSONObject("options").getInt("download_top")); eq(0, request.getJSONObject("options").getInt("recommend_count"));
        }
        eq(3, f.backend.requests.size()); eq(false, new Preferences(f.target).getEnable());
    }
    static void testHistoryAtomicFilesWhitelistAndLimits(Fixture f) throws Exception {
        PreferredIpHistory history = new PreferredIpHistory(f.isolated);
        JSONObject input = new JSONObject().put("state", "completed").put("token", "secret-token-fixture").put("user_id", "secret-user")
            .put("advanced", "secret-json").put("test_target", new JSONObject().put("target_host", "node.example.com").put("token", "secret-token-fixture"))
            .put("candidates", new JSONArray().put(candidate(X))).put("results", new JSONArray());
        history.write(input, true); history.write(input, false);
        for (boolean complete : new boolean[]{true, false}) {
            JSONObject read = history.read(complete); eq(0, read.getJSONArray("results").length());
            String wire = read.toString(); check(!wire.contains("secret-") && !wire.contains("advanced"), "history whitelist excludes credentials/JSON");
        }
        File base = new File(f.dir, "preferred-ip-complete.json"), bak = new File(base + ".bak"); byte[] old = bytes(base);
        check(base.renameTo(bak), "API24 .bak recovery setup"); writeBytes(base, new byte[]{'{'});
        eq("completed", history.read(true).getString("state")); check(Arrays.equals(old, bytes(base)), "real AtomicFile restores .bak");
        JSONArray tooMany = new JSONArray(); for (int n = 0; n < 4097; n++) tooMany.put(candidate(X));
        try { history.write(new JSONObject().put("candidates", tooMany), true); throw new AssertionError("4097 history rows accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains("4096"), "explicit row limit error"); }
        char[] huge = new char[16 * 1024 * 1024 / 3 + 1]; Arrays.fill(huge, '中');
        try { history.write(new JSONObject().put("candidates", new JSONArray().put(candidate(X).put("comment", new String(huge)))), true); throw new AssertionError("oversize history accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains("16MiB"), "explicit byte limit error"); }
        check(Arrays.equals(old, bytes(base)), "limit failures preserve history"); eq(0, f.store.read().getJSONArray("selected").length());
    }
    static void testHistoryMigrationTransferFailurePreservesRoot(Fixture f) throws Exception {
        JSONObject legacy = new JSONObject().put("state", "completed").put("candidates", new JSONArray().put(candidate(X))).put("results", new JSONArray());
        JSONObject before = f.store.update(root -> { root.put("last_test", legacy); root.getJSONArray("selected").put(candidate(X)); });
        // Independent history lock-file path fault prevents transfer BEFORE lightweight Root update.
        File badLock = new File(f.dir, "preferred-ip-history.lock"); check(badLock.mkdir(), "real history I/O failure");
        writeBytes(new File(badLock, "block"), new byte[]{1}); f.launch(); fence((ExecutorService) field(f.controller, "historyIO")); f.settle();
        eq(before.toString(), f.store.read().toString()); check(!new File(f.dir, "preferred-ip-complete.json").exists(), "failed transfer did not claim history");
        check(selected(f.store.read(), X), "migration failure keeps selected pool");
    }
    static JSONObject accounting(String id, long used, long reserved, long body, boolean done) throws Exception {
        return new JSONObject().put("worker_done", done).put("download_round_id", id).put("download_round_limit", PreferredIpLedger.LIMIT)
            .put("download_round_used", used).put("download_round_remaining", PreferredIpLedger.LIMIT - used)
            .put("download_reserved_bytes", reserved).put("download_bytes", body);
    }
    static void testLedgerUncertainReservationAndExplicitRound(Fixture f) throws Exception {
        PreferredIpLedger ledger = new PreferredIpLedger(f.store); eq("", ledger.beforePrepare(false)); ledger.prepared(accounting("round", 0, 0, 0, true), false);
        eq(52428800L, ledger.reserve("round", X));
        try { ledger.settle(accounting("round", 3, 52428800L, 3, false)); throw new AssertionError("settled before worker_done"); }
        catch (IllegalStateException expected) { }
        eq(52428800L, f.store.read().getJSONObject("download_ledger").getLong("reserved"));
        try { ledger.beforePrepare(false); throw new AssertionError("auto reset outstanding reservation"); } catch (IllegalStateException expected) { }
        try { ledger.settle(accounting("wrong", 3, 52428800L, 3, true)); throw new AssertionError("wrong round settled"); } catch (IllegalStateException expected) { }
        ledger.settle(accounting("round", 3, 52428800L, 3, true)); eq(3L, f.store.read().getJSONObject("download_ledger").getLong("used"));
        eq(52428800L, ledger.reserve("round", Y)); ledger.settle(accounting("round", 52428803L, 52428800L, 52428800L, true));
        eq(52428797L, ledger.reserve("round", X)); ledger.settle(accounting("round", PreferredIpLedger.LIMIT, 52428797L, 52428797L, true));
        try { ledger.reserve("round", X); throw new AssertionError("exhausted budget admitted"); } catch (IllegalStateException expected) { }
        eq("round", ledger.beforePrepare(false)); ledger.prepared(accounting("round", PreferredIpLedger.LIMIT, 0, 0, true), false); // new scan inherits budget
        eq("round", ledger.beforePrepare(true)); ledger.prepared(accounting("new-round", 0, 0, 0, true), true);
        JSONObject disk = f.store.read().getJSONObject("download_ledger"); eq(PreferredIpLedger.LIMIT, disk.getJSONObject("previous").getLong("used")); eq(0L, disk.getLong("used"));
        f.store.update(root -> root.getJSONObject("download_ledger").put("owner", "dead-process"));
        try { ledger.beforePrepare(false); throw new AssertionError("death silently reset budget"); } catch (IllegalStateException expected) { }
        eq("", ledger.beforePrepare(true)); ledger.prepared(accounting("confirmed-after-death", 0, 0, 0, true), true);
    }
}
