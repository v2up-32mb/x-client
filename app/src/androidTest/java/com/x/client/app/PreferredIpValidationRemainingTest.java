package com.x.client.app;

import android.app.Instrumentation;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static com.x.client.app.PreferredIpV2Test.*;
import static com.x.client.app.PreferredIpContextRemainingTest.*;

/** R2 E2: real Store head, final target/version and old-epoch/reservation windows.
 * Preferences changes below are explicit final-Profile fixtures, not ProfileEdit UI coverage. */
final class PreferredIpValidationRemainingTest {
    static final String SECOND = "preferred-v2-e2-target";
    static final String SERVER = "wss://other.example.com/path";
    static final String[] NAMES = {
        "testValidationStoreHeadStableCommits",
        "testValidationStoreHeadNetworkBRejects",
        "testValidationStoreHeadNetworkAbaRejects",
        "testValidationClockTailStableCommits",
        "testValidationClockTailNetworkAbaRejects",
        "testDifferentTargetFinalServerChangeRejects",
        "testDifferentTargetFinalAdvancedChangeRejects",
        "testDifferentTargetPendingSelectionVersionRejects",
        "testDifferentTargetDurableSelectionVersionRejects",
        "testDifferentTargetPartialPassStoresSelectedSubset",
        "testDifferentTargetAllFailedKeepsEffectiveOldPool",
        "testGoCanonicalPrepareReusesReferenceWithoutProbe",
        "testFollowingTargetDefaultInvalidAndIndependentBoundaries",
        "testLateValidationPrepareNetworkBRejects",
        "testLateValidationPrepareNetworkAbaRejects",
        "testLateValidationProbeNetworkBRejects",
        "testLateValidationProbeNetworkAbaRejects",
        "testDownloadReserveBeforeNetworkBRejects",
        "testDownloadReserveBeforeNetworkAbaRejects",
        "testDownloadReserveAfterNetworkBRejects",
        "testDownloadReserveAfterNetworkAbaRejects"
    };
    static void run(Instrumentation i, PreferredIpFeedbackTest.Reporter reporter) throws Exception {
        Case[] cases = { PreferredIpValidationRemainingTest::testValidationStoreHeadStableCommits,
            PreferredIpValidationRemainingTest::testValidationStoreHeadNetworkBRejects,
            PreferredIpValidationRemainingTest::testValidationStoreHeadNetworkAbaRejects,
            PreferredIpValidationRemainingTest::testValidationClockTailStableCommits,
            PreferredIpValidationRemainingTest::testValidationClockTailNetworkAbaRejects,
            PreferredIpValidationRemainingTest::testDifferentTargetFinalServerChangeRejects,
            PreferredIpValidationRemainingTest::testDifferentTargetFinalAdvancedChangeRejects,
            PreferredIpValidationRemainingTest::testDifferentTargetPendingSelectionVersionRejects,
            PreferredIpValidationRemainingTest::testDifferentTargetDurableSelectionVersionRejects,
            PreferredIpValidationRemainingTest::testDifferentTargetPartialPassStoresSelectedSubset,
            PreferredIpValidationRemainingTest::testDifferentTargetAllFailedKeepsEffectiveOldPool,
            PreferredIpValidationRemainingTest::testGoCanonicalPrepareReusesReferenceWithoutProbe,
            PreferredIpValidationRemainingTest::testFollowingTargetDefaultInvalidAndIndependentBoundaries,
            PreferredIpValidationRemainingTest::testLateValidationPrepareNetworkBRejects,
            PreferredIpValidationRemainingTest::testLateValidationPrepareNetworkAbaRejects,
            PreferredIpValidationRemainingTest::testLateValidationProbeNetworkBRejects,
            PreferredIpValidationRemainingTest::testLateValidationProbeNetworkAbaRejects,
            PreferredIpValidationRemainingTest::testDownloadReserveBeforeNetworkBRejects,
            PreferredIpValidationRemainingTest::testDownloadReserveBeforeNetworkAbaRejects,
            PreferredIpValidationRemainingTest::testDownloadReserveAfterNetworkBRejects,
            PreferredIpValidationRemainingTest::testDownloadReserveAfterNetworkAbaRejects };
        eq(NAMES.length, cases.length);
        for (int n = 0; n < cases.length; n++) {
            try (Fixture f = new Fixture(i)) { cases[n].run(f); }
            reporter.passed(PreferredIpFeedbackTest.TOTAL + PreferredIpV2Test.NAMES.length
                + PreferredIpV2ContinuationTest.NAMES.length + PreferredIpUiRemainingTest.NAMES.length
                + PreferredIpConfigurationCancelTest.NAMES.length + PreferredIpContextRemainingTest.NAMES.length
                + PreferredIpBudgetEchRemainingTest.NAMES.length + n + 1, NAMES[n]);
        }
    }

    static void revoke(Fixture f, boolean aba) {
        f.network.change("B"); if (aba) f.network.change("A");
    }
    static long epoch(Fixture f) throws Exception { return ((AtomicLong) field(f.controller, "scanEpoch")).get(); }
    static long memoryVersion(Fixture f) throws Exception { return ((AtomicLong) field(f.controller, "selectionVersion")).get(); }
    static void poolEquals(JSONObject before, JSONObject after) throws Exception {
        for (String key : new String[]{"selected", "validations", "profile_modes", "selection_version", "enabled"})
            eq(before.get(key).toString(), after.get(key).toString());
    }
    static void resolverUses(Fixture f, String profile, String addresses) throws Exception {
        Preferences p = new Preferences(f.target).forProfile(profile);
        JSONObject merged = PreferredIpResolver.baseParams(p);
        check(PreferredIpResolver.apply(p, merged, f.store.read()).applied, "old/new matching target still adopts its effective pool");
        eq(addresses, merged.getString("relay_nodes"));
    }
    static Thread actorThread(ExecutorService actor) throws Exception {
        return actor.submit(() -> Thread.currentThread()).get(10, TimeUnit.SECONDS);
    }
    /** Read-only JVM thread state plus production Store frame, not active!=null inference.
     * The test thread owns Store.LOCK, so a BLOCKED update/withLock stack is the real monitor window. */
    static void awaitStoreWait(Thread worker) throws Exception {
        until(() -> {
            if (worker.getState() != Thread.State.BLOCKED) return false;
            for (StackTraceElement frame : worker.getStackTrace())
                if (frame.getClassName().equals(PreferredIpStore.class.getName()) && frame.getMethodName().equals("withLock")) return true;
            return false;
        }, "worker really blocked entering PreferredIpStore.withLock");
    }
    static void pauseNative(Fixture f) throws Exception {
        PreferredIpUiRemainingTest.header(f); f.click(f.view(R.id.preferred_primary)); fence(f.control);
    }
    static void matchingExit(Fixture f, Task task) throws Exception {
        until(() -> f.control.submit(() -> field(f.controller, "active") == null
            && !(Boolean) field(f.controller, "preparingDisk")).get(10, TimeUnit.SECONDS), "actual matching network finish on control");
        check(task.consumedDone && f.backend.admitted.isEmpty(), "fixture consumed matching worker_done");
        f.actorDrain(); f.publish(); f.draw(); f.failures.assertEmpty();
    }

    // ---------------------------------------------------------------- real validation Store head
    static void validationHead(Fixture f, int network) throws Exception {
        f.pausedComplete(); f.selectX(true); f.stopCycle(); f.actorDrain();
        JSONObject oldPool = f.store.read();
        Thread worker = actorThread(f.selection);
        f.show(Y);
        PreferredIpTestControl.Gate first = PreferredIpV2ContinuationTest.block(f, f.selection);
        f.click(f.widget(Y, R.id.preferred_choice)); fence(f.control);
        eq(1, f.control.submit(() -> field(f.controller, "selectionPending")).get(10, TimeUnit.SECONDS));
        PreferredIpTestControl.Gate second = f.gates.create();
        Future<?> between = f.selection.submit(() -> { try { second.block(); } catch (Exception e) { f.failures.add(e); } });
        first.close(); second.awaitEnter(); fence(f.control);
        eq(0, f.control.submit(() -> field(f.controller, "selectionPending")).get(10, TimeUnit.SECONDS));
        JSONObject intent = f.store.read();
        check(selected(intent, Y), "intent is genuinely durable before validation Store head");
        eq(oldPool.getLong("selection_version") + 1, intent.getLong("selection_version"));
        eq(oldPool.getJSONObject("validations").toString(), intent.getJSONObject("validations").toString());
        byte[] heldBytes = bytes(new File(f.dir, "preferred-ip-v1.json"));
        long oldEpoch = epoch(f), attempts = f.backend.startAttempts.get();
        synchronized (storeMonitor()) {
            second.close(); between.get(10, TimeUnit.SECONDS); awaitStoreWait(worker);
            check(Arrays.equals(heldBytes, bytes(new File(f.dir, "preferred-ip-v1.json"))), "validation is blocked before its first Store mutation/check");
            if (network != 0) { revoke(f, network == 2); eq(oldEpoch + network, epoch(f)); }
        }
        f.actorDrain(); f.publish(); f.draw();
        JSONObject after = f.store.read();
        eq(attempts, f.backend.startAttempts.get());
        if (network == 0) {
            eq(oldEpoch, epoch(f));
            eq(intent.getLong("revision") + 1, after.getLong("revision"));
            eq(2, after.getJSONObject("validations").getJSONObject(f.profileId).getJSONArray("addresses").length());
            resolverUses(f, f.profileId, X + "," + Y);
        } else {
            check(Arrays.equals(heldBytes, bytes(new File(f.dir, "preferred-ip-v1.json"))), "revoked Store head leaves exact durable intent and old pool bytes");
            poolEquals(intent, after); resolverUses(f, f.profileId, X);
            check(f.state().stale, "old epoch never becomes current again, including ABA");
        }
        f.show(Y); eq(true, f.checked(Y));
    }
    static void testValidationStoreHeadStableCommits(Fixture f) throws Exception { validationHead(f, 0); }
    static void testValidationStoreHeadNetworkBRejects(Fixture f) throws Exception { validationHead(f, 1); }
    static void testValidationStoreHeadNetworkAbaRejects(Fixture f) throws Exception { validationHead(f, 2); }

    // E1 retains the B tail and newer-intent positive; these add the unchanged-version stable and ABA arms.
    static void validationTail(Fixture f, boolean aba) throws Exception {
        f.pausedComplete(); f.selectX(true); f.stopCycle(); f.actorDrain();
        JSONObject old = f.store.read();
        GatedClock clock = new GatedClock(f); f.clockHook = clock; clock.arm();
        f.show(Y); f.click(f.widget(Y, R.id.preferred_choice)); clock.awaitReached();
        JSONObject intent = persisted(f); byte[] held = bytes(new File(f.dir, "preferred-ip-v1.json"));
        eq(old.getLong("selection_version") + 1, intent.getLong("selection_version"));
        eq(old.getJSONObject("validations").toString(), intent.getJSONObject("validations").toString());
        long oldEpoch = epoch(f), attempts = f.backend.startAttempts.get();
        if (aba) revoke(f, true);
        clock.letGo(); f.actorDrain(); f.publish(); f.draw();
        eq(1, clock.gateHits.get()); eq(1, clock.validationCalls.get());
        eq(attempts, f.backend.startAttempts.get());
        if (aba) {
            eq(oldEpoch + 2, epoch(f));
            check(Arrays.equals(held, bytes(new File(f.dir, "preferred-ip-v1.json"))), "ABA tail leaves exact bytes");
            poolEquals(intent, f.store.read()); resolverUses(f, f.profileId, X);
        } else {
            eq(oldEpoch, epoch(f));
            JSONObject after = f.store.read();
            eq(intent.getLong("revision") + 1, after.getLong("revision"));
            eq(GatedClock.FIXED, after.getJSONObject("validations").getJSONObject(f.profileId).getLong("tested_at"));
            resolverUses(f, f.profileId, X + "," + Y);
        }
    }
    static void testValidationClockTailStableCommits(Fixture f) throws Exception { validationTail(f, false); }
    static void testValidationClockTailNetworkAbaRejects(Fixture f) throws Exception { validationTail(f, true); }

    // ---------------------------------------------------------------- different final targets
    static void differentFixture(Fixture f) throws Exception {
        addNode(f, SECOND, "E2 following", SERVER, "1.1.1.1:443", 2, "other-token", "");
        f.store.update(root -> root.getJSONObject("profile_modes").put(f.profileId, "independent"));
        f.backend.holdValidationPrepare = true; f.backend.holdValidationProbe = true;
        f.pausedComplete(); f.actorDrain();
    }
    static void selectNative(Fixture f, String address, boolean value) throws Exception {
        f.show(address); eq(!value, f.checked(address)); f.click(f.widget(address, R.id.preferred_choice));
        f.settle(); f.publish(); f.draw(); eq(value, selected(f.store.read(), address));
    }
    static Task startDifferent(Fixture f, boolean holdPrepare) throws Exception {
        // Real periodic schedule dispatches different targets after the complete scan is paused.
        // Hold prepare before native intents so this remains deterministic even on a slow UI.
        Task prepare = f.backend.next("prepare");
        eq("validation", prepare.request.getString("probe_role"));
        eq(0, prepare.request.getJSONArray("candidates").length());
        eq("other.example.com", prepare.request.getJSONObject("options").getString("target_host"));
        if (holdPrepare) { await(prepare.polled, "held different-target prepare poll"); return prepare; }
        await(prepare.polled, "actual validation prepare awaits its scripted reply");
        prepare.replies.add(f.backend.snapshot(prepare, "completed", true, true, 0));
        Task probe = f.backend.next("probe"); await(probe.polled, "held different-target probe poll");
        check(prepare.consumedDone, "matching prepare completed before dependent probe");
        eq(prepare.sessionId, probe.sessionId); eq(prepare.roundId, probe.roundId);
        eq(prepare.targetKey, probe.targetKey); eq("validation", probe.request.getString("probe_role"));
        check(!probe.targetKey.equals(((String) field(field(f.controller, "scanContext"), "targetKey"))), "actual different Go target");
        for (int n = 0; n < probe.request.getJSONArray("candidates").length(); n++)
            check(selected(f.store.read(), probe.request.getJSONArray("candidates").getJSONObject(n).getString("address")), "validation requests only currently selected addresses");
        return probe;
    }
    static JSONObject validationReply(Fixture f, Task task, String... failed) throws Exception {
        Set<String> failures = new HashSet<>(Arrays.asList(failed));
        JSONObject reply = f.backend.snapshot(task, "completed", true, true, 0);
        JSONArray rows = reply.getJSONArray("results");
        for (int n = 0; n < rows.length(); n++) {
            JSONObject row = rows.getJSONObject(n);
            if (failures.contains(row.getString("address"))) {
                row.put("status", "failed").put("http_success", 0).put("tcp_success", 0)
                    .put("tcp_measured", false).put("http_measured", false).put("colo", "")
                    .put("score", 0).put("error", "HTTPS复查失败: context deadline exceeded");
                JSONArray parts = row.getJSONArray("score_breakdown");
                for (int p = 0; p < parts.length(); p++) parts.getJSONObject(p).put("points", 0);
            }
        }
        return reply;
    }
    static void effectiveOtherPool(Fixture f) throws Exception {
        differentFixture(f); selectNative(f, X, true);
        Task probe = startDifferent(f, false);
        probe.replies.add(validationReply(f, probe)); matchingExit(f, probe);
        resolverUses(f, SECOND, X);
        check(f.store.read().getBoolean("enabled"), "actual different-target proof enabled the old pool");
    }
    static void finalChange(Fixture f, int change) throws Exception {
        effectiveOtherPool(f); selectNative(f, Y, true);
        Task probe = startDifferent(f, false); f.stopCycle();
        eq(2, probe.request.getJSONArray("candidates").length());
        pauseNative(f); f.settle();
        JSONObject old = f.store.read(); long attempts = f.backend.startAttempts.get(), version = memoryVersion(f);
        Preferences other = new Preferences(f.target).forProfile(SECOND);
        PreferredIpTestControl.Gate intentGate = null;
        if (change == 0) other.setXtServerAddr("wss://changed.example.com/path");
        else if (change == 1) other.setXtAdvancedParams("{\"server_addr\":\"wss://advanced.example.com/path\",\"connections\":4}");
        else if (change == 2) {
            f.show(Y); intentGate = PreferredIpV2ContinuationTest.block(f, f.selection);
            f.click(f.widget(Y, R.id.preferred_choice)); fence(f.control);
            eq(version + 1, memoryVersion(f));
            eq(old.getLong("selection_version"), persisted(f).getLong("selection_version"));
            eq(1, f.control.submit(() -> field(f.controller, "selectionPending")).get(10, TimeUnit.SECONDS));
        } else {
            selectNative(f, Y, false); pauseNative(f); f.settle();
            eq(version + 1, memoryVersion(f));
            eq(old.getLong("selection_version") + 1, f.store.read().getLong("selection_version"));
            old = f.store.read();
        }
        JSONObject expected = old;
        probe.replies.add(validationReply(f, probe));
        if (intentGate != null) {
            // Matching network actor exits while the newer intent is still queued; no Store fence can run yet.
            until(() -> f.control.submit(() -> field(f.controller, "active") == null).get(10, TimeUnit.SECONDS), "late validation matching exit with newer pending intent");
            check(probe.consumedDone, "old validation really completed"); fence((ExecutorService) field(f.controller, "networkIO")); fence(f.control);
            poolEquals(expected, persisted(f));
            intentGate.close(); f.actorDrain(); f.publish(); f.draw();
            eq(expected.getLong("selection_version") + 1, f.store.read().getLong("selection_version"));
            eq(expected.getLong("revision") + 1, f.store.read().getLong("revision")); // Only the queued native intent, no stale validation rewrite.
            check(!selected(f.store.read(), Y), "queued native remove becomes durable after old proof was refused");
            eq(expected.getJSONObject("validations").getJSONObject(SECOND).toString(), f.store.read().getJSONObject("validations").getJSONObject(SECOND).toString());
        } else { matchingExit(f, probe); poolEquals(expected, f.store.read()); eq(expected.toString(), f.store.read().toString()); }
        eq(attempts, f.backend.startAttempts.get());
        check(f.store.read().getBoolean("enabled"), "late proof did not destroy the old enabled pool");
        if (change <= 1) {
            JSONObject merged = PreferredIpResolver.baseParams(other);
            check(!PreferredIpResolver.apply(other, merged, f.store.read()).applied, "changed final target cannot use old target proof");
            eq("1.1.1.1:443", merged.getString("relay_nodes"));
            // Restore the real Profile fixture: the retained old proof is still live for its matching target.
            other.setXtServerAddr(SERVER); other.setXtAdvancedParams("");
        }
        resolverUses(f, SECOND, X);
    }
    static void testDifferentTargetFinalServerChangeRejects(Fixture f) throws Exception { finalChange(f, 0); }
    static void testDifferentTargetFinalAdvancedChangeRejects(Fixture f) throws Exception { finalChange(f, 1); }
    static void testDifferentTargetPendingSelectionVersionRejects(Fixture f) throws Exception { finalChange(f, 2); }
    static void testDifferentTargetDurableSelectionVersionRejects(Fixture f) throws Exception { finalChange(f, 3); }

    static void testDifferentTargetPartialPassStoresSelectedSubset(Fixture f) throws Exception {
        addNode(f, SECOND, "E2 following", SERVER, "1.1.1.1:443", 2, "other-token", "");
        f.store.update(root -> root.getJSONObject("profile_modes").put(f.profileId, "independent"));
        f.backend.holdProbe = true; f.backend.holdValidationPrepare = true; f.backend.holdValidationProbe = true;
        f.launch(); f.tab(1); f.click(f.view(R.id.preferred_primary)); f.backend.next("prepare");
        Task reference = f.backend.next("probe"); await(reference.polled, "reference held while two intents become durable");
        reference.replies.add(f.backend.snapshot(reference, "running", false, true, 0)); reference.awaitPoll(2);
        f.settle(); f.publish(); eq(3, f.state().completed);
        selectNative(f, X, true); selectNative(f, Y, true);
        // A real admitted reference slot prevents different-target dispatch until both UI intents exist.
        f.backend.holdProbe = false; pauseNative(f); await(reference.cancelled, "pause after both real selections");
        reference.replies.add(f.backend.snapshot(reference, "cancelled", true, true, 0));
        check(!f.store.read().getBoolean("enabled"), "intent alone never enables the pool");
        Task probe = startDifferent(f, false); f.stopCycle();
        eq(2, probe.request.getJSONArray("candidates").length());
        check(PreferredIpPresentation.find(probe.request.getJSONArray("candidates"), V6) == null, "unselected candidate never requested");
        probe.replies.add(validationReply(f, probe, Y)); matchingExit(f, probe);
        JSONObject root = f.store.read(), validation = root.getJSONObject("validations").getJSONObject(SECOND);
        eq(1, validation.getJSONArray("addresses").length()); eq(X, validation.getJSONArray("addresses").getString(0));
        check(selected(root, X) && selected(root, Y) && root.getBoolean("enabled"), "only actual passing selected subset first enables");
        check(!root.getJSONObject("validations").has(f.profileId), "independent reference is not taken over");
        eq(targetKey(f, SECOND), validation.getString("target_key")); resolverUses(f, SECOND, X);
    }
    static void testDifferentTargetAllFailedKeepsEffectiveOldPool(Fixture f) throws Exception {
        effectiveOtherPool(f); selectNative(f, Y, true);
        Task probe = startDifferent(f, false); f.stopCycle(); JSONObject old = f.store.read(); long attempts = f.backend.startAttempts.get();
        probe.replies.add(validationReply(f, probe, X, Y)); matchingExit(f, probe);
        poolEquals(old, f.store.read()); eq(old.toString(), f.store.read().toString());
        eq(attempts, f.backend.startAttempts.get()); resolverUses(f, SECOND, X);
    }

    static void testGoCanonicalPrepareReusesReferenceWithoutProbe(Fixture f) throws Exception {
        // Java URI/fingerprint keeps the terminal DNS dot; Go validateTaskOptions strips it.
        addNode(f, SECOND, "Canonical dot", "wss://node.example.com./path", "1.1.1.1:443", 2, "other-token", "");
        f.store.update(root -> root.getJSONObject("profile_modes").put(f.profileId, "independent"));
        f.backend.holdValidationPrepare = true;
        f.pausedComplete(); selectNative(f, X, true);
        check(!targetKey(f, f.profileId).equals(targetKey(f, SECOND)), "Java fingerprints really differ");
        Task prepare = f.backend.next("prepare"); await(prepare.polled, "canonical validation prepare really admitted"); f.stopCycle();
        JSONObject referenceOptions = f.backend.requests.get(0).getJSONObject("options"), validationOptions = prepare.request.getJSONObject("options");
        eq("node.example.com", referenceOptions.getString("target_host")); eq("node.example.com.", validationOptions.getString("target_host"));
        eq(f.backend.goTargetPayload(referenceOptions), f.backend.goTargetPayload(validationOptions));
        eq(f.backend.targetKey(referenceOptions), prepare.targetKey);
        long attempts = f.backend.startAttempts.get();
        prepare.replies.add(f.backend.snapshot(prepare, "completed", true, true, 0)); matchingExit(f, prepare);
        eq(attempts, f.backend.startAttempts.get()); eq(3L, attempts); // Initial prepare/probe, exactly one validation prepare, no duplicate probe.
        JSONObject validation = f.store.read().getJSONObject("validations").getJSONObject(SECOND);
        eq(targetKey(f, SECOND), validation.getString("target_key")); eq(X, validation.getJSONArray("addresses").getString(0));
        eq(1730000003000L, validation.getLong("tested_at")); resolverUses(f, SECOND, X);
        check(!f.store.read().getJSONObject("validations").has(f.profileId), "canonical callback still respects independent reference");
    }

    static void testFollowingTargetDefaultInvalidAndIndependentBoundaries(Fixture f) throws Exception {
        String independent = "e2-independent", defaults = "e2-default", invalid = "e2-invalid", conflict = "e2-conflict";
        addNode(f, SECOND, "Valid follower", "wss://node.example.com/path", "1.1.1.1:443", 2, "token", "");
        addNode(f, independent, "Independent", "wss://node.example.com/path", "2.2.2.2:443", 2, "token", "");
        addNode(f, defaults, "Default", "wss://node.example.com/path", "3.3.3.3:443", 2, "token", "");
        addNode(f, invalid, "Invalid", "ws://not-tls.example.com/path", "4.4.4.4:443", 2, "token", "");
        addNode(f, conflict, "Conflict", "wss://node.example.com/path", "5.5.5.5:443", 2, "token", "{\"relay_nodes\":\"9.9.9.9:443\"}");
        f.store.update(root -> root.getJSONObject("profile_modes").put(independent, "independent").put(defaults, "default"));
        f.pausedComplete(); f.stopCycle(); f.selectX(true); f.actorDrain();
        JSONObject root = f.store.read(); resolverUses(f, SECOND, X);
        for (String id : new String[]{independent, defaults, invalid, conflict}) check(!root.getJSONObject("validations").has(id), "nonfollowing/invalid target got no proof");
        for (String id : new String[]{independent, defaults, conflict}) {
            Preferences p = new Preferences(f.target).forProfile(id); JSONObject merged = PreferredIpResolver.baseParams(p);
            check(!PreferredIpResolver.apply(p, merged, root).applied, "actual boundary preserves non-adoption");
            eq(id.equals(defaults) ? "" : id.equals(conflict) ? "9.9.9.9:443" : "2.2.2.2:443", merged.getString("relay_nodes"));
        }
        eq(2L, f.backend.startAttempts.get());
    }

    // ---------------------------------------------------------------- asynchronous late validation B / ABA
    static void lateValidation(Fixture f, boolean prepareStage, boolean aba) throws Exception {
        f.pausedComplete(); f.selectX(true); f.actorDrain();
        f.backend.holdValidationPrepare = true; f.backend.holdValidationProbe = true;
        addNode(f, SECOND, "Late target", SERVER, "1.1.1.1:443", 2, "other-token", "");
        Task task = startDifferent(f, prepareStage); f.stopCycle(); f.settle();
        JSONObject before = f.store.read(); long attempts = f.backend.startAttempts.get(), oldEpoch = epoch(f);
        check(before.getBoolean("enabled"), "old real reference pool already enabled");
        revoke(f, aba); await(task.cancelled, "old validation cancel remains independent");
        task.replies.add(f.backend.snapshot(task, "completed", true, true, 0)); matchingExit(f, task);
        eq(oldEpoch + (aba ? 2 : 1), epoch(f)); eq(attempts, f.backend.startAttempts.get());
        poolEquals(before, f.store.read()); eq(before.toString(), f.store.read().toString()); resolverUses(f, f.profileId, X);
        check(!f.store.read().getJSONObject("validations").has(SECOND), "late other-target proof never grafted");
        check(f.state().stale && f.state().completed == 0, "late old validation cannot renew epoch");
    }
    static void testLateValidationPrepareNetworkBRejects(Fixture f) throws Exception { lateValidation(f, true, false); }
    static void testLateValidationPrepareNetworkAbaRejects(Fixture f) throws Exception { lateValidation(f, true, true); }
    static void testLateValidationProbeNetworkBRejects(Fixture f) throws Exception { lateValidation(f, false, false); }
    static void testLateValidationProbeNetworkAbaRejects(Fixture f) throws Exception { lateValidation(f, false, true); }

    // ---------------------------------------------------------------- transparent NetworkAccess reserve gates
    static final class ReserveWindow implements AutoCloseable {
        final Fixture f; final Thread worker; final boolean after;
        final PreferredIpTestControl.Gate gate;
        final AtomicInteger hits = new AtomicInteger();
        ReserveWindow(Fixture f, boolean after) throws Exception {
            this.f = f; this.after = after;
            worker = actorThread((ExecutorService) field(f.controller, "networkIO"));
            gate = f.gates.create(); f.resources.add(this);
            if (after) f.network.beforeId = this::visit; else f.network.beforeDirect = this::visit;
        }
        void visit() {
            if (Thread.currentThread() != worker || hits.get() != 0) return;
            try {
                Object job = field(f.controller, "active");
                if (job == null || !"download".equals(field(job, "kind")) || (Boolean) field(job, "reserved") != after) return;
                hits.incrementAndGet(); gate.block();
            } catch (Exception error) { f.failures.add(error); }
        }
        public void close() { gate.close(); if (after) f.network.beforeId = null; else f.network.beforeDirect = null; }
    }
    static void reserveNetwork(Fixture f, boolean after, boolean aba) throws Exception {
        f.pausedComplete(); f.selectX(true); f.stopCycle(); f.actorDrain();
        PreferredIpBudgetEchRemainingTest.openExpanded(f, X);
        f.backend.holdDownload = true;
        PreferredIpBudgetEchRemainingTest.downloadOnce(f, X, 7 * 1048576L); f.actorDrain();
        JSONObject before = f.store.read(), ledger = before.getJSONObject("download_ledger");
        eq(7 * 1048576L, ledger.getLong("used"));
        long attempts = f.backend.startAttempts.get(), oldEpoch = epoch(f);
        ReserveWindow window = new ReserveWindow(f, after);
        if (after) {
            f.click(f.rowWidget(X, R.id.preferred_row_download)); window.gate.awaitEnter();
            Object job = field(f.controller, "active");
            eq(true, field(job, "reserved")); eq("", field(job, "id"));
            eq(52428800L, f.store.read().getJSONObject("download_ledger").getLong("reserved"));
            eq(attempts, f.backend.startAttempts.get());
            revoke(f, aba); window.gate.close();
        } else {
            synchronized (storeMonitor()) {
                f.click(f.rowWidget(X, R.id.preferred_row_download)); window.gate.awaitEnter();
                eq(false, field(field(f.controller, "active"), "reserved"));
                eq(0L, persisted(f).getJSONObject("download_ledger").getLong("reserved"));
                window.gate.close(); awaitStoreWait(window.worker);
                eq(attempts, f.backend.startAttempts.get()); revoke(f, aba);
            }
        }
        until(() -> f.control.submit(() -> field(f.controller, "active") == null).get(10, TimeUnit.SECONDS), "unadmitted revoked download exits");
        f.actorDrain(); f.publish(); f.draw();
        eq(1, window.hits.get()); window.close();
        eq(oldEpoch + (aba ? 2 : 1), epoch(f)); eq(attempts, f.backend.startAttempts.get());
        JSONObject result = f.store.read().getJSONObject("download_ledger");
        eq(ledger.getString("id"), result.getString("id")); eq(ledger.getLong("used"), result.getLong("used"));
        eq(0L, result.getLong("reserved")); check(!result.has("address"), "unadmitted reservation released");
        eq(ledger.toString(), result.toString());
        eq(before.getLong("revision") + 2, f.store.read().getLong("revision")); // Real reserve plus rejectedBeforeStart release only.
        poolEquals(before, f.store.read()); resolverUses(f, f.profileId, X);
        check(f.backend.admitted.isEmpty() && f.state().activeDownload.isEmpty() && f.state().stale, "no worker was admitted and no automatic retry");
    }
    static void testDownloadReserveBeforeNetworkBRejects(Fixture f) throws Exception { reserveNetwork(f, false, false); }
    static void testDownloadReserveBeforeNetworkAbaRejects(Fixture f) throws Exception { reserveNetwork(f, false, true); }
    static void testDownloadReserveAfterNetworkBRejects(Fixture f) throws Exception { reserveNetwork(f, true, false); }
    static void testDownloadReserveAfterNetworkAbaRejects(Fixture f) throws Exception { reserveNetwork(f, true, true); }
}
