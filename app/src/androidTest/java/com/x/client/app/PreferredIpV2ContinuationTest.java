package com.x.client.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import static com.x.client.app.PreferredIpV2Test.*;

/** b48 continuation: the same fixture, explicit causal gates rather than timing guesses. */
final class PreferredIpV2ContinuationTest {
    static final String[] NAMES = {
        "testStoppedAbsentHolderReadFailureAdd", "testStoppedAbsentHolderReadFailureRemove",
        "testHiddenCachedHolderReturnAdd", "testHiddenCachedHolderReturnRemove",
        "testRapidNativeAddThenCancel", "testRepeatedNativeAddIntent",
        "testLostBackgroundSuccess", "testLostBackgroundEarlyReject",
        "testBlockedStartReturnsIdThenCancels", "testNativeFetchWorkerDoneDrain",
        "testWrongTaskDoneKeepsSlot", "testWrongSessionDoneKeepsSlot", "testWrongNetworkDoneKeepsSlot",
        "testSuccessfulCompleteHistoryMigrationOrder", "testSuccessfulCheckpointHistoryMigrationOrder",
        "testAtomicWriteAddFailurePreservesPool", "testAtomicWriteRemoveFailurePreservesPool",
        "testAtomicHistoryWriteFailurePreservesBytes", "testFirstStateBeforeFirstHolder", "testLegacyMigrationAtomicWriteFailure"
    };
    static void run(Instrumentation i, PreferredIpFeedbackTest.Reporter reporter) throws Exception {
        Case[] cases = { PreferredIpV2ContinuationTest::testStoppedAbsentHolderReadFailureAdd,
            PreferredIpV2ContinuationTest::testStoppedAbsentHolderReadFailureRemove,
            PreferredIpV2ContinuationTest::testHiddenCachedHolderReturnAdd, PreferredIpV2ContinuationTest::testHiddenCachedHolderReturnRemove,
            PreferredIpV2ContinuationTest::testRapidNativeAddThenCancel, PreferredIpV2ContinuationTest::testRepeatedNativeAddIntent,
            PreferredIpV2ContinuationTest::testLostBackgroundSuccess, PreferredIpV2ContinuationTest::testLostBackgroundEarlyReject,
            PreferredIpV2ContinuationTest::testBlockedStartReturnsIdThenCancels, PreferredIpV2ContinuationTest::testNativeFetchWorkerDoneDrain,
            PreferredIpV2ContinuationTest::testWrongTaskDoneKeepsSlot, PreferredIpV2ContinuationTest::testWrongSessionDoneKeepsSlot,
            PreferredIpV2ContinuationTest::testWrongNetworkDoneKeepsSlot,
            PreferredIpV2ContinuationTest::testSuccessfulCompleteHistoryMigrationOrder, PreferredIpV2ContinuationTest::testSuccessfulCheckpointHistoryMigrationOrder,
            PreferredIpV2ContinuationTest::testAtomicWriteAddFailurePreservesPool, PreferredIpV2ContinuationTest::testAtomicWriteRemoveFailurePreservesPool,
            PreferredIpV2ContinuationTest::testAtomicHistoryWriteFailurePreservesBytes, PreferredIpV2ContinuationTest::testFirstStateBeforeFirstHolder, PreferredIpV2ContinuationTest::testLegacyMigrationAtomicWriteFailure };
        eq(NAMES.length, cases.length);
        for (int n = 0; n < cases.length; n++) {
            try (Fixture f = new Fixture(i)) { cases[n].run(f); }
            reporter.passed(PreferredIpFeedbackTest.TOTAL + PreferredIpV2Test.NAMES.length + n + 1, NAMES[n]);
        }
    }
    static void testStoppedAbsentHolderReadFailureAdd(Fixture f) throws Exception { absentFailure(f, false, false); }
    static void testStoppedAbsentHolderReadFailureRemove(Fixture f) throws Exception { absentFailure(f, true, false); }
    static void testHiddenCachedHolderReturnAdd(Fixture f) throws Exception { absentFailure(f, false, true); }
    static void testHiddenCachedHolderReturnRemove(Fixture f) throws Exception { absentFailure(f, true, true); }
    static void absentFailure(Fixture f, boolean saved, boolean hidden) throws Exception {
        f.pausedComplete(); if (saved) f.selectX(true); f.stopCycle(); f.show(X); f.actorDrain();
        f.trace = new PreferredIpTestControl.StateTrace(f);
        PreferredIpTestControl.AbsentHolder absence = new PreferredIpTestControl.AbsentHolder(f);
        JSONObject before = f.store.read(); byte[] oldBytes = bytes(new File(f.dir, "preferred-ip-v1.json"));
        View choice = f.widget(X, R.id.preferred_choice); boolean fold = f.controller.expanded.contains(X);
        String heading = f.onMain(() -> f.widget(X, R.id.preferred_row_heading).getContentDescription().toString());
        PreferredIpController.State initial = f.state();
        synchronized (storeMonitor()) {
            f.main(() -> {
                check(choice.performClick(), "actual checkbox"); eq(!saved, ((CompoundButton) choice).isChecked());
                try { fence(f.control); eq(1, field(f.controller, "selectionPending")); } catch (Exception e) { throw new AssertionError(e); }
                absence.detach(); f.dispatchStop();
                eq(!saved, ((CompoundButton) choice).isChecked());
            });
            f.fault = new ReadFault(f.dir);
        }
        f.settle(); f.trace.noSavingAndLostCompletion(X); eq(0, field(f.controller, "selectionPending"));
        check(f.state() == initial, "completion State was actually lost while listener=null");
        f.fault.close(); f.fault = null; check(Arrays.equals(oldBytes, bytes(new File(f.dir, "preferred-ip-v1.json"))), "read failure preserves bytes");
        if (hidden) f.tab(0);
        f.main(() -> { f.i.callActivityOnRestart(f.activity); f.i.callActivityOnStart(f.activity); f.i.callActivityOnResume(f.activity); f.dispatchedStop = false; });
        f.settle(); f.publish(); f.awaitAdapterIdle();
        f.main(() -> { eq(-1, f.list().indexOfChild(absence.view)); eq(!saved, ((CompoundButton) choice).isChecked()); });
        check(f.state() != initial, "onStart State consumed while old holder remains absent");
        if (hidden) f.tab(1);
        int statesAtAttach = f.trace.snapshot().size(); CountDownLatch firstDraw = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean observeFirst = new java.util.concurrent.atomic.AtomicBoolean(true);
        f.main(() -> {
            absence.attachMismatch(saved); // Hard precondition: same holder and still mismatch, not a rebind success.
            View decor = f.activity.getWindow().getDecorView();
            ViewTreeObserver.OnPreDrawListener observation = new ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                    if (!observeFirst.getAndSet(false)) return true;
                    f.failures.attempt(() -> {
                        absence.identity(); eq(saved, ((CompoundButton) choice).isChecked());
                        eq(0L, absence.notifications.get()); eq(statesAtAttach, f.trace.snapshot().size());
                        eq(absence.top, absence.view.getTop());
                    });
                    firstDraw.countDown(); return true;
                }
            };
            decor.getViewTreeObserver().addOnPreDrawListener(observation);
            f.resources.add(() -> f.main(() -> { if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnPreDrawListener(observation); }));
            decor.invalidate(); f.list().invalidate();
        });
        await(firstDraw, "first actual pre-draw after unchanged holder attach"); f.drawWithoutAdapterDrain(); f.failures.assertEmpty();
        f.main(() -> eq(heading, absence.view.findViewById(R.id.preferred_row_heading).getContentDescription().toString()));
        eq(fold, f.controller.expanded.contains(X)); eq(before.toString(), f.store.read().toString());
        absence.close(); f.show(Y); eq(false, f.checked(Y)); f.show(X); eq(saved, f.checked(X));
        eq(before.optLong("selection_version"), f.store.read().optLong("selection_version"));
    }
    static PreferredIpTestControl.Gate block(Fixture f, ExecutorService executor) throws Exception {
        PreferredIpTestControl.Gate gate = f.gates.create();
        executor.execute(() -> { try { gate.block(); } catch (Exception e) { f.failures.add(e); } });
        gate.awaitEnter(); return gate;
    }
    static void testRapidNativeAddThenCancel(Fixture f) throws Exception {
        f.pausedComplete(); f.stopCycle(); f.show(X); f.trace = new PreferredIpTestControl.StateTrace(f);
        long version = f.store.read().optLong("selection_version"); View choice = f.widget(X, R.id.preferred_choice);
        PreferredIpTestControl.Gate io = block(f, f.selection);
        try {
            f.main(() -> {
                check(choice.performClick(), "rapid add"); eq(true, ((CompoundButton) choice).isChecked());
                check(choice.performClick(), "rapid cancel"); eq(false, ((CompoundButton) choice).isChecked());
            });
            fence(f.control); eq(2, field(f.controller, "selectionPending"));
        } finally { io.close(); }
        f.settle(); f.publish(); f.draw(); JSONObject root = f.store.read();
        eq(0, root.getJSONArray("selected").length()); eq(false, root.getBoolean("enabled")); eq(version + 2, root.getLong("selection_version"));
        eq(false, f.checked(X)); f.show(Y); eq(false, f.checked(Y));
        f.publish(); f.draw(); f.settle(); eq(version + 2, f.store.read().getLong("selection_version"));
    }
    static void testRepeatedNativeAddIntent(Fixture f) throws Exception {
        f.pausedComplete(); f.stopCycle(); f.show(X); long version = f.store.read().optLong("selection_version");
        PreferredIpTestControl.Gate io = block(f, f.selection);
        try {
            View choice = f.widget(X, R.id.preferred_choice);
            f.main(() -> { check(choice.performClick(), "first add"); eq(true, ((CompoundButton) choice).isChecked()); });
            fence(f.control);
            // No saving publication: the ordinary production pre-draw repairs to old disk=false.
            f.draw(); eq(false, f.checked(X));
            f.main(() -> { check(choice.performClick(), "second genuine same-direction intent"); eq(true, ((CompoundButton) choice).isChecked()); });
            fence(f.control); eq(2, field(f.controller, "selectionPending"));
        } finally { io.close(); }
        f.settle(); f.publish(); f.draw(); eq(1, f.store.read().getJSONArray("selected").length());
        eq(version + 2, f.store.read().getLong("selection_version")); eq(true, f.checked(X));
    }
    static void testLostBackgroundSuccess(Fixture f) throws Exception {
        f.pausedComplete(); f.stopCycle(); f.show(X); f.trace = new PreferredIpTestControl.StateTrace(f);
        PreferredIpTestControl.Gate io = block(f, f.selection); View choice = f.widget(X, R.id.preferred_choice);
        try {
            f.main(() -> { check(choice.performClick(), "background add"); eq(true, ((CompoundButton) choice).isChecked()); f.dispatchStop(); });
            fence(f.control); eq(1, field(f.controller, "selectionPending"));
        } finally { io.close(); }
        f.settle(); f.trace.noSavingAndLostCompletion(X);
        JSONObject saved = f.store.read(); check(selected(saved, X), "background intent saved"); eq(false, saved.getBoolean("enabled"));
        eq(0, saved.getJSONObject("validations").length()); f.dispatchReturn(); f.show(X); eq(true, f.checked(X));
        eq(2, f.backend.requests.size()); // Return alone does not start I/O or fabricate validation.
    }
    static void testLostBackgroundEarlyReject(Fixture f) throws Exception {
        f.pausedComplete(); f.stopCycle(); f.show(X); f.trace = new PreferredIpTestControl.StateTrace(f); JSONObject before = f.store.read();
        Runnable watched = f.network.callback; check(watched != null, "capture actual NetworkAccess callback before onStop");
        PreferredIpTestControl.Gate entered = f.gates.create(), change = f.gates.create();
        f.control.execute(() -> {
            entered.entered.countDown();
            try { change.block(); f.network.value = "B"; watched.run(); f.network.value = "A"; watched.run(); }
            catch (Exception e) { f.failures.add(e); }
        });
        await(entered.entered, "control queue gate"); View choice = f.widget(X, R.id.preferred_choice);
        f.main(() -> { check(choice.performClick(), "old epoch intent"); eq(true, ((CompoundButton) choice).isChecked()); f.dispatchStop(); });
        eq(null, f.network.callback); // Unregister does not revoke a callback already captured in the fixture.
        change.close(); f.settle();
        f.trace.noSavingAndLostCompletion(X); eq(0, field(f.controller, "selectionPending"));
        eq(before.toString(), f.store.read().toString()); f.dispatchReturn(); f.show(X); eq(false, f.checked(X));
    }
    static void testBlockedStartReturnsIdThenCancels(Fixture f) throws Exception {
        f.backend.holdProbe = true; f.backend.startReturn = f.gates.create(); f.launch(); f.tab(1); f.click(f.view(R.id.preferred_primary));
        Task prepare = f.backend.next("prepare"); f.backend.startReturn.awaitEnter(); f.publish();
        long cancel = ((java.util.concurrent.atomic.AtomicLong) field(f.controller, "cancelVersion")).get();
        f.click(f.view(R.id.preferred_primary)); fence(f.control);
        check(((java.util.concurrent.atomic.AtomicLong) field(f.controller, "cancelVersion")).get() > cancel, "Pause independent of blocked Start");
        eq(1L, prepare.cancelled.getCount()); check((Boolean) field(f.controller, "stopRequested"), "immediate stop before returned task ID");
        eq(1L, f.backend.startAttempts.get()); f.backend.startReturn.close(); await(prepare.cancelled, "Cancel补发 after ID return");
        // prepare was already admitted; drain its matching exit without automatically probing.
        until(() -> { f.publish(); return !f.state().busy; }, "late Start exit"); eq(1L, f.backend.startAttempts.get());
    }
    static void testNativeFetchWorkerDoneDrain(Fixture f) throws Exception {
        f.backend.holdFetch = true; f.launch(); JSONObject before = f.store.read();
        f.onMain(() -> {
            check(ViewGroupSearch.clickText(f.activity.getWindow().getDecorView(), f.activity.getString(R.string.preferred_fetch_v4)), "native official source button"); return null;
        });
        Task fetch = f.backend.next("fetch"); await(fetch.polled, "fetch poll blocked");
        // Leaving the page is a real lifecycle Pause/Cancel entry, not Controller.fetch/cancel calls.
        f.main(f::dispatchStop); await(fetch.cancelled, "fetch cancelled onStop");
        fetch.replies.add(f.backend.snapshot(fetch, "cancelled", false, false, 0)); fetch.awaitPoll(2); f.settle();
        eq(false, fetch.consumedDone); check(!f.backend.admitted.isEmpty(), "fetch still owns slot");
        fetch.replies.add(f.backend.snapshot(fetch, "cancelled", true, false, 0));
        until(() -> f.control.submit(() -> field(f.controller, "active") == null).get(10, TimeUnit.SECONDS), "matching fetch drain");
        eq(before.getJSONArray("sources").toString(), f.store.read().getJSONArray("sources").toString()); f.dispatchReturn(); eq(1L, f.backend.startAttempts.get());
    }
    static void testWrongTaskDoneKeepsSlot(Fixture f) throws Exception { wrongDone(f, "id"); }
    static void testWrongSessionDoneKeepsSlot(Fixture f) throws Exception { wrongDone(f, "session_id"); }
    static void testWrongNetworkDoneKeepsSlot(Fixture f) throws Exception { wrongDone(f, "network_id"); }
    static void wrongDone(Fixture f, String key) throws Exception {
        f.pausedComplete(); f.selectX(true); f.backend.holdDownload = true; f.show(X);
        f.click(f.widget(X, R.id.preferred_row_heading)); f.settle(); f.publish(); f.draw(); f.click(f.rowWidget(X, R.id.preferred_row_download));
        Task task = f.backend.next("download"); await(task.polled, "download poll");
        Object active = field(f.controller, "active"); JSONObject wrong = f.backend.snapshot(task, "cancelled", true, false, 3);
        wrong.put(key, "wrong-identity"); task.replies.add(wrong); task.awaitPoll(2); f.settle(); f.publish(); f.draw();
        check(field(f.controller, "active") == active, "wrong done retains exact Controller Job"); eq(task.id, f.backend.admitted); eq(false, task.consumedDone);
        eq(52428800L, f.store.read().getJSONObject("download_ledger").getLong("reserved")); eq(3L, f.backend.startAttempts.get());
        f.click(f.rowWidget(X, R.id.preferred_row_download)); await(task.cancelled, "native cancel after wrong reply");
        task.replies.add(f.backend.snapshot(task, "cancelled", true, false, 3)); until(() -> { f.publish(); return !f.state().busy; }, "matching final done");
        eq(3L, f.store.read().getJSONObject("download_ledger").getLong("used")); eq(0L, f.store.read().getJSONObject("download_ledger").getLong("reserved"));
    }
    static void testSuccessfulCompleteHistoryMigrationOrder(Fixture f) throws Exception { migrationOrder(f, true); }
    static void testSuccessfulCheckpointHistoryMigrationOrder(Fixture f) throws Exception { migrationOrder(f, false); }
    static void migrationOrder(Fixture f, boolean complete) throws Exception {
        JSONObject legacy = new JSONObject().put("state", complete ? "completed" : "paused").put("token", "secret-token")
            .put("candidates", new JSONArray().put(candidate(X))).put("results", new JSONArray());
        JSONObject root = f.store.update(data -> { data.put("last_test", legacy); data.getJSONArray("selected").put(candidate(X)); });
        Field lock = PreferredIpHistory.class.getDeclaredField("LOCK"); lock.setAccessible(true); Object historyLock = lock.get(null);
        PreferredIpTestControl.Gate history = f.gates.create();
        ExecutorService holder = Executors.newSingleThreadExecutor(); f.resources.add(() -> { history.close(); holder.shutdown(); check(holder.awaitTermination(10, TimeUnit.SECONDS), "history monitor holder exits"); });
        holder.execute(() -> { synchronized (historyLock) { try { history.block(); } catch (Exception e) { f.failures.add(e); } } }); history.awaitEnter();
        f.launch(); String filename = complete ? "preferred-ip-complete.json" : "preferred-ip-checkpoint.json";
        synchronized (storeMonitor()) {
            history.close(); until(() -> new File(f.dir, filename).isFile(), "History commits before lightweight Root");
            JSONObject transferred = new PreferredIpHistory(f.isolated).read(complete);
            check(!transferred.toString().contains("secret-token"), "migration whitelist"); eq(0, transferred.getJSONArray("results").length());
            eq(root.toString(), new JSONObject(new String(bytes(new File(f.dir, "preferred-ip-v1.json")), java.nio.charset.StandardCharsets.UTF_8)).toString());
        }
        f.actorDrain(); JSONObject updated = f.store.read(); eq(filename, updated.getJSONObject("last_test").getString("history_file"));
        for (String key : new String[]{"selected", "profile_modes", "validations", "enabled"}) eq(root.get(key).toString(), updated.get(key).toString());
    }
    static final class WritePermissionFault implements AutoCloseable {
        final File dir; final LinkedHashMap<File, Integer> modes = new LinkedHashMap<>(); boolean closed;
        WritePermissionFault(Fixture f, String filename) throws Exception {
            dir = f.dir; check(android.os.Process.myUid() != 0, "file permission fault must execute as ordinary app UID");
            File base = new File(dir, filename); check(base.isFile(), "old AtomicFile base exists");
            check(!new File(base + ".bak").exists(), "read/recovery drained before permission fault");
            try {
                modes.put(dir, Os.stat(dir.getPath()).st_mode & 0777); modes.put(base, Os.stat(base.getPath()).st_mode & 0777);
                Os.chmod(base.getPath(), 0444); Os.chmod(dir.getPath(), 0555);
            } catch (Throwable error) { try { close(); } catch (Throwable rollback) { error.addSuppressed(rollback); } if (error instanceof Exception) throw (Exception) error; throw (Error) error; }
            f.resources.add(this);
        }
        public void close() throws Exception {
            if (closed) return; Exception error = null;
            for (Map.Entry<File, Integer> entry : modes.entrySet()) try { Os.chmod(entry.getKey().getPath(), entry.getValue()); } catch (Exception e) { if (error == null) error = e; else error.addSuppressed(e); }
            if (error != null) throw error; closed = true;
        }
    }
    static void testAtomicWriteAddFailurePreservesPool(Fixture f) throws Exception { writeFailure(f, false); }
    static void testAtomicWriteRemoveFailurePreservesPool(Fixture f) throws Exception { writeFailure(f, true); }
    static void writeFailure(Fixture f, boolean saved) throws Exception {
        f.pausedComplete(); if (saved) f.selectX(true); f.stopCycle(); f.show(X); f.actorDrain();
        f.trace = new PreferredIpTestControl.StateTrace(f); JSONObject before = f.store.read(); byte[] old = bytes(new File(f.dir, "preferred-ip-v1.json"));
        try (WritePermissionFault fault = new WritePermissionFault(f, "preferred-ip-v1.json")) {
            eq(before.toString(), f.store.read().toString()); // Proves permanent lock and openRead still work.
            View choice = f.widget(X, R.id.preferred_choice);
            f.main(() -> { check(choice.performClick(), "real write-fault selection"); eq(!saved, ((CompoundButton) choice).isChecked()); });
            f.settle(); f.publish(); f.draw(); eq(saved, f.checked(X));
            check(f.state().message.contains("选择保存失败"), "production Store write failure surfaced");
            eq(before.toString(), f.store.read().toString()); check(Arrays.equals(old, bytes(new File(f.dir, "preferred-ip-v1.json"))), "startWrite failure preserves original bytes");
        }
        f.selectX(!saved); eq(before.optLong("selection_version") + 1, f.store.read().getLong("selection_version"));
    }
    static void testAtomicHistoryWriteFailurePreservesBytes(Fixture f) throws Exception {
        PreferredIpHistory history = new PreferredIpHistory(f.isolated);
        history.write(new JSONObject().put("state", "completed"), true); byte[] old = bytes(new File(f.dir, "preferred-ip-complete.json"));
        try (WritePermissionFault fault = new WritePermissionFault(f, "preferred-ip-complete.json")) {
            eq("completed", history.read(true).getString("state"));
            try { history.write(new JSONObject().put("state", "replacement"), true); throw new AssertionError("permission write fault ineffective"); }
            catch (IOException expected) { }
            check(Arrays.equals(old, bytes(new File(f.dir, "preferred-ip-complete.json"))), "History write failure preserves bytes");
            eq("completed", history.read(true).getString("state"));
        }
        history.write(new JSONObject().put("state", "replacement"), true); eq("replacement", history.read(true).getString("state"));
    }
    static void testLegacyMigrationAtomicWriteFailure(Fixture f) throws Exception {
        // Legacy data-safety fixture, not a fabricated new UI/controller success.
        JSONObject legacy = new JSONObject().put("state", "completed").put("results", new JSONArray()).put("candidates", new JSONArray().put(candidate(X)));
        Preferences profile = new Preferences(f.target).forProfile(f.profileId);
        String key = PreferredIpResolver.fingerprint(PreferredIpResolver.target(profile, PreferredIpResolver.baseParams(profile)));
        JSONObject before = f.store.update(root -> {
            root.put("last_test", legacy).put("enabled", true); root.getJSONArray("selected").put(candidate(X));
            root.getJSONObject("profile_modes").put(f.profileId, "global");
            root.getJSONObject("validations").put(f.profileId, new JSONObject().put("target_key", key).put("addresses", new JSONArray().put(X)).put("network_id", "history-A"));
        });
        PreferredIpHistory history = new PreferredIpHistory(f.isolated); history.write(new JSONObject().put("state", "old-history"), true);
        byte[] old = bytes(new File(f.dir, "preferred-ip-complete.json"));
        try (WritePermissionFault fault = new WritePermissionFault(f, "preferred-ip-complete.json")) {
            eq(before.toString(), f.store.read().toString()); eq("old-history", history.read(true).getString("state"));
            f.launch(); f.actorDrain(); eq(before.toString(), f.store.read().toString());
            check(Arrays.equals(old, bytes(new File(f.dir, "preferred-ip-complete.json"))), "failed AtomicFile transfer preserves old history and full pool");
            eq(legacy.toString(), f.store.read().getJSONObject("last_test").toString());
        }
    }
    static void testFirstStateBeforeFirstHolder(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true); f.stopCycle(); f.actorDrain();
        PreferredIpController retained = f.controller; Activity old = f.activity;
        f.main(old::finish); until(() -> f.onMain(old::isDestroyed), "first Activity destroyed naturally");
        ExecutorService oldDiffer = f.onMain(() -> (ExecutorService) field(old, "differ"));
        check(oldDiffer.awaitTermination(10, TimeUnit.SECONDS), "old differ exits");
        PreferredIpFeedbackInstrumentation runner = (PreferredIpFeedbackInstrumentation) f.i;
        PreferredIpFeedbackInstrumentation.Created saved = runner.afterActivityCreate;
        java.util.concurrent.atomic.AtomicBoolean created = new java.util.concurrent.atomic.AtomicBoolean();
        f.main(() -> runner.afterActivityCreate = actual -> {
            if (!(actual instanceof PreferredIpActivity)) return;
            f.activity = (PreferredIpActivity) actual;
            RecyclerView list = actual.findViewById(R.id.preferred_results);
            list.suppressLayout(true); eq(0, list.getChildCount()); created.set(true);
        });
        f.resources.add(() -> f.main(() -> { runner.afterActivityCreate = saved; if (f.activity != null && !f.activity.isDestroyed()) f.list().suppressLayout(false); }));
        try {
            f.activity = (PreferredIpActivity) f.i.startActivitySync(new android.content.Intent(f.target, PreferredIpActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, f.profileId));
            check(created.get(), "standard Instrumentation onCreate gate actually installed");
            f.settle(); f.publish(); f.awaitAdapterIdle();
            check(f.onMain(() -> field(f.activity, "controller")) == retained, "new Activity acquired the same actual retained Controller");
            check(selected(f.state().data, X), "authoritative State precedes first holder");
            f.main(() -> { eq(0, f.list().getChildCount()); f.list().suppressLayout(false); });
            f.tab(1); f.show(X); f.draw(); eq(true, f.checked(X)); f.show(Y); eq(false, f.checked(Y));
            f.show(V6); check(f.rowText(V6).replace("\u200b", "").contains(V6), "first IPv6 holder retains full address");
            eq(2L, f.backend.startAttempts.get()); // First binding, not an unbound-holder pre-draw causality claim.
        } finally { f.main(() -> runner.afterActivityCreate = saved); }
    }
    static final class ViewGroupSearch {
        static boolean clickText(View view, String text) {
            if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText()) && view.isEnabled() && view.performClick()) return true;
            if (view instanceof android.view.ViewGroup) for (int n = 0; n < ((android.view.ViewGroup) view).getChildCount(); n++) if (clickText(((android.view.ViewGroup) view).getChildAt(n), text)) return true;
            return false;
        }
    }
}
