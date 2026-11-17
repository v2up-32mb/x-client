package com.x.client.app;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static com.x.client.app.PreferredIpV2Test.*;

/** Stage C only: stored selection intent versus late validation, the real Clock transaction
 * window, different final targets / following profiles, and network revocation around the
 * download reservation and an already admitted old round. No State/Row/proof/Job writes. */
final class PreferredIpContextRemainingTest {
    static final String[] NAMES = {
        "testStoredIntentSurvivesLateValidationHeadClock",
        "testCommittedPoolSurvivesTailClockNetworkRevoke",
        "testDifferentTargetRealValidationPrepareProbeIsNotGrafted",
        "testFollowingProfilesShareCanonicalReferenceSubset",
        "testExistingValidationSubsetAccumulatesNewProof",
        "testLateReferenceReplyAfterNetworkRevokeIsNotMerged",
        "testDownloadRevokedAroundReserveNeverStarts",
        "testAdmittedOldRoundDownloadSettlesAndRefusesContinue",
        "testPrepareRequiredMaintenanceStopsAfterNetworkRevoke"
    };
    private static final String SECOND = "preferred-v2-second", THIRD = "preferred-v2-third", CONFLICT = "preferred-v2-conflict";
    private static final String Z = "104.16.2.31:443";
    private static final long OLD_VALIDATION = 1700000000001L;

    static void run(Instrumentation i, PreferredIpFeedbackTest.Reporter reporter) throws Exception {
        Case[] cases = { PreferredIpContextRemainingTest::testStoredIntentSurvivesLateValidationHeadClock,
            PreferredIpContextRemainingTest::testCommittedPoolSurvivesTailClockNetworkRevoke,
            PreferredIpContextRemainingTest::testDifferentTargetRealValidationPrepareProbeIsNotGrafted,
            PreferredIpContextRemainingTest::testFollowingProfilesShareCanonicalReferenceSubset,
            PreferredIpContextRemainingTest::testExistingValidationSubsetAccumulatesNewProof,
            PreferredIpContextRemainingTest::testLateReferenceReplyAfterNetworkRevokeIsNotMerged,
            PreferredIpContextRemainingTest::testDownloadRevokedAroundReserveNeverStarts,
            PreferredIpContextRemainingTest::testAdmittedOldRoundDownloadSettlesAndRefusesContinue,
            PreferredIpContextRemainingTest::testPrepareRequiredMaintenanceStopsAfterNetworkRevoke };
        eq(NAMES.length, cases.length);
        for (int n = 0; n < cases.length; n++) {
            try (Fixture f = new Fixture(i)) { cases[n].run(f); }
            reporter.passed(PreferredIpFeedbackTest.TOTAL + PreferredIpV2Test.NAMES.length
                + PreferredIpV2ContinuationTest.NAMES.length + PreferredIpUiRemainingTest.NAMES.length
                + PreferredIpConfigurationCancelTest.NAMES.length + n + 1, NAMES[n]);
        }
    }

    /** The real persisted Root decoded straight from the AtomicFile base path. This never waits on
     * the Store monitor, so a transaction holding LOCK can be inspected from its head to its tail. */
    static JSONObject persisted(Fixture f) throws Exception {
        return new JSONObject(new String(bytes(new File(f.dir, "preferred-ip-v1.json")), "UTF-8"));
    }

    static String targetKey(Fixture f, String profile) throws Exception {
        Preferences p = new Preferences(f.target).forProfile(profile);
        JSONObject merged = PreferredIpResolver.baseParams(p);
        PreferredIpResolver.validatedConnections(p, merged);
        return PreferredIpResolver.fingerprint(PreferredIpResolver.target(p, merged));
    }

    static void addNode(Fixture f, String id, String name, String server, String relay, int connections, String token, String advanced) {
        Preferences prefs = new Preferences(f.target);
        prefs.addProfile(id, name);
        Preferences node = prefs.forProfile(id);
        node.setProtocol(Preferences.PROTOCOL_X_TUNNEL);
        node.setXtServerAddr(server);
        node.setXtRelayNodes(relay);
        node.setXtConnections(connections);
        node.setXtDisableEch(true);
        node.setXtToken(token);
        node.setXtAdvancedParams(advanced);
    }

    /** Only selectionIO validation timestamps hit the gate; control checkpoints stay transparent. */
    static final class GatedClock implements PreferredIpRuntime.Clock, AutoCloseable {
        static final long FIXED = 1730000099000L;
        final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger validationCalls = new AtomicInteger(), ordinaryCalls = new AtomicInteger(), gateHits = new AtomicInteger();
        final Thread selectionThread;
        final PreferredIpTestControl.Failures failures;
        volatile boolean armed, blocked;
        GatedClock(Fixture f) throws Exception {
            selectionThread = f.selection.submit(() -> Thread.currentThread()).get(10, TimeUnit.SECONDS);
            failures = f.failures; f.resources.add(this);
        }
        void arm() { armed = true; }
        void awaitReached() throws Exception { await(reached, "real validation transaction reached the Clock"); }
        void letGo() { release.countDown(); }
        public long now() {
            if (Thread.currentThread() != selectionThread) {
                ordinaryCalls.incrementAndGet();
                return FIXED;
            }
            if (armed) {
                validationCalls.incrementAndGet();
                if (!blocked) {
                    blocked = true; gateHits.incrementAndGet(); reached.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) failures.add(new IllegalStateException("Clock gate timeout"));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); failures.add(interrupted);
                    }
                }
            }
            return FIXED;
        }
        public void close() { release.countDown(); }
    }

    /** Real fixed queries with four dense candidates; only the final V6 batch stays admitted. */
    static void scanFour(Fixture f, JSONArray candidates) throws Exception {
        check(f.controller == null, "seed candidates before launch");
        f.store.update(root -> { root.put("candidates", candidates); root.getJSONArray("sources").getJSONObject(0).put("candidates", candidates); });
        f.backend.holdProbeAddress = V6;
        f.launch(); f.tab(1); PreferredIpUiRemainingTest.header(f);
        f.click(f.view(R.id.preferred_primary)); f.backend.next("prepare");
        Task last;
        do { last = f.backend.next("probe"); } while (PreferredIpPresentation.find(last.request.getJSONArray("candidates"), V6) == null);
        await(last.polled, "final dense batch remains admitted");
        last.replies.add(f.backend.snapshot(last, "running", false, true, 0));
        until(() -> { f.publish(); return f.state().completed == candidates.length() && f.state().busy; }, "all dense reference rows before drain");
        PreferredIpUiRemainingTest.header(f); f.click(f.view(R.id.preferred_primary)); await(last.cancelled, "native dense Pause");
        last.replies.add(f.backend.snapshot(last, "cancelled", true, true, 0));
        until(() -> { f.publish(); return !f.state().busy; }, "dense matching drain");
        f.backend.holdProbeAddress = ""; f.stopCycle(); f.actorDrain();
    }

    // ---------------------------------------------------------------- G7: validation timestamp tail (Store head remains mandatory)

    static void testStoredIntentSurvivesLateValidationHeadClock(Fixture f) throws Exception {
        f.pausedComplete(); f.stopCycle();
        GatedClock clock = new GatedClock(f); f.clockHook = clock;
        JSONObject before = f.store.read(); long revision = before.getLong("revision");
        clock.arm();
        f.show(X); eq(false, f.checked(X)); f.click(f.widget(X, R.id.preferred_choice));
        clock.awaitReached();
        // This timestamp is after the validation head checks, before the final version check.
        JSONObject held = persisted(f);
        check(selected(held, X), "real stored intent survives before validation commits");
        eq(before.getLong("selection_version") + 1, held.getLong("selection_version"));
        check(!held.getBoolean("enabled"), "no validation was persisted at the timestamp window");
        check(!held.getJSONObject("validations").has(f.profileId), "validation record absent while the Clock gate holds");
        // A newer native intent increments the in-memory version; its Store transaction is queued behind.
        f.show(Y); eq(false, f.checked(Y)); f.click(f.widget(Y, R.id.preferred_choice)); fence(f.control);
        eq(1, field(f.controller, "selectionPending"));
        JSONObject queued = persisted(f);
        eq(before.getLong("selection_version") + 1, queued.getLong("selection_version"));
        check(!selected(queued, Y), "second intent is still queued behind the held validation transaction");
        clock.letGo();
        f.settle(); f.publish(); f.draw();
        JSONObject root = f.store.read();
        eq(before.getLong("selection_version") + 2, root.getLong("selection_version"));
        check(selected(root, X) && selected(root, Y), "both real intents persisted");
        JSONObject record = root.getJSONObject("validations").getJSONObject(f.profileId);
        eq(2, record.getJSONArray("addresses").length());
        check(!record.getJSONArray("addresses").getString(0).equals(record.getJSONArray("addresses").getString(1)), "distinct subset addresses");
        for (int n = 0; n < 2; n++) check(selected(root, record.getJSONArray("addresses").getString(n)), "committed subset contains only currently selected addresses");
        eq(GatedClock.FIXED, record.getLong("tested_at"));
        check(root.getBoolean("enabled"), "latest real validation enables the pool");
        eq(revision + 3, root.getLong("revision")); // Two intents plus the latest validation; the stale gated validation wrote nothing.
        eq(2, clock.validationCalls.get()); eq(1, clock.gateHits.get());
        f.show(X); eq(true, f.checked(X)); f.show(Y); eq(true, f.checked(Y));
    }

    static void testCommittedPoolSurvivesTailClockNetworkRevoke(Fixture f) throws Exception {
        f.pausedComplete(); f.stopCycle();
        f.show(X); eq(false, f.checked(X)); f.click(f.widget(X, R.id.preferred_choice)); f.settle(); f.publish(); f.draw();
        JSONObject committed = f.store.read();
        JSONObject original = committed.getJSONObject("validations").getJSONObject(f.profileId);
        eq(1, original.getJSONArray("addresses").length());
        eq(1730000003000L, original.getLong("tested_at"));
        long revision = committed.getLong("revision");
        GatedClock clock = new GatedClock(f); f.clockHook = clock;
        clock.arm();
        f.show(Y); eq(false, f.checked(Y)); f.click(f.widget(Y, R.id.preferred_choice));
        clock.awaitReached();
        JSONObject tail = persisted(f);
        eq(committed.getLong("selection_version") + 1, tail.getLong("selection_version"));
        check(selected(tail, Y), "new intent is already durable at the validation tail");
        eq(original.toString(), tail.getJSONObject("validations").getJSONObject(f.profileId).toString());
        byte[] tailBytes = bytes(new File(f.dir, "preferred-ip-v1.json"));
        long epoch = ((AtomicLong) field(f.controller, "scanEpoch")).get();
        long attempts = f.backend.startAttempts.get();
        f.network.change("B");
        eq(epoch + 1, ((AtomicLong) field(f.controller, "scanEpoch")).get());
        clock.letGo();
        f.settle(); f.publish(); f.draw();
        JSONObject root = f.store.read();
        eq(committed.getLong("selection_version") + 1, root.getLong("selection_version"));
        check(selected(root, X) && selected(root, Y), "network revoke keeps both stored intents");
        eq(original.toString(), root.getJSONObject("validations").getJSONObject(f.profileId).toString());
        eq(revision + 1, root.getLong("revision")); // only the intent write; the revoked tail wrote nothing.
        eq(1, clock.validationCalls.get()); eq(1, clock.gateHits.get());
        check(clock.ordinaryCalls.get() > 0, "network checkpoint timestamp is transparent to the validation gate");
        check(Arrays.equals(tailBytes, bytes(new File(f.dir, "preferred-ip-v1.json"))), "revoked validation leaves durable intent and old pool bytes unchanged");
        eq(true, root.getBoolean("enabled"));
        eq(epoch + 1, ((AtomicLong) field(f.controller, "scanEpoch")).get());
        eq(attempts, f.backend.startAttempts.get());
        check(f.state().stale, "revoked scan is stale for the UI");
        f.show(X); eq(true, f.checked(X));
    }

    // ---------------------------------------------------------------- G7: targets, subset, mode

    static void testDifferentTargetRealValidationPrepareProbeIsNotGrafted(Fixture f) throws Exception {
        addNode(f, SECOND, "Other node", "wss://other.example.com/path", "1.1.1.1:443", 2, "other-token", "");
        f.store.update(root -> root.getJSONObject("profile_modes").put(f.profileId, "independent"));
        f.pausedComplete();
        f.show(X); eq(false, f.checked(X)); f.click(f.widget(X, R.id.preferred_choice));
        Task validationPrepare = f.backend.next("prepare"), validationProbe = f.backend.next("probe");
        until(() -> validationProbe.consumedDone && f.control.submit(() -> field(f.controller, "active") == null).get(10, TimeUnit.SECONDS), "different-target matching worker_done and real actor exit");
        check(validationPrepare.consumedDone, "different-target prepare really completed");
        f.stopCycle(); f.settle(); f.actorDrain(); f.publish(); f.draw();
        JSONObject prepare = f.backend.requests.get(2), probe = f.backend.requests.get(3);
        eq("prepare", prepare.getString("operation")); eq("validation", prepare.optString("probe_role"));
        eq("other.example.com", prepare.getJSONObject("options").getString("target_host"));
        eq("probe", probe.getString("operation")); eq("validation", probe.optString("probe_role"));
        eq("other.example.com", probe.getJSONObject("options").getString("target_host"));
        eq(X, probe.getJSONArray("candidates").getJSONObject(0).getString("address"));
        JSONObject root = f.store.read();
        check(selected(root, X), "real intent is stored for the following different-target node");
        eq(1L, root.getLong("selection_version"));
        JSONObject second = root.getJSONObject("validations").optJSONObject(SECOND);
        check(second != null, "different-target probe stores its own actual-target validation");
        eq(targetKey(f, SECOND), second.getString("target_key"));
        eq(1, second.getJSONArray("addresses").length()); eq(X, second.getJSONArray("addresses").getString(0));
        eq(1730000003000L, second.getLong("tested_at"));
        check(!root.getJSONObject("validations").has(f.profileId), "reference session is not credited with the other target proof");
        eq(true, root.getBoolean("enabled"));
        eq("independent", root.getJSONObject("profile_modes").getString(f.profileId));
        check(!root.getJSONObject("profile_modes").has(SECOND), "no explicit mode was fabricated for the following node");
        Preferences actual = new Preferences(f.target).forProfile(SECOND);
        JSONObject merged = PreferredIpResolver.baseParams(actual);
        check(PreferredIpResolver.apply(actual, merged, root).applied, "other node adopts only its own actual-target subset");
        eq(X, merged.getString("relay_nodes"));
        eq(4, f.backend.requests.size());
    }

    static void testFollowingProfilesShareCanonicalReferenceSubset(Fixture f) throws Exception {
        String same = "wss://node.example.com/path";
        addNode(f, SECOND, "Same target", same, "1.1.1.1:443", 5, "other-token", "");
        addNode(f, THIRD, "Independent", same, "1.1.1.1:443", 3, "third-token", "");
        addNode(f, CONFLICT, "Conflict", same, "1.1.1.1:443", 3, "conflict-token", "{\"relay_nodes\":\"9.9.9.9:443\"}");
        f.store.update(root -> root.getJSONObject("profile_modes").put(THIRD, "independent"));
        f.pausedComplete(); f.stopCycle();
        f.show(X); eq(false, f.checked(X)); f.click(f.widget(X, R.id.preferred_choice)); f.settle(); f.publish(); f.draw();
        JSONObject root = f.store.read();
        JSONObject fixture = root.getJSONObject("validations").getJSONObject(f.profileId);
        JSONObject second = root.getJSONObject("validations").getJSONObject(SECOND);
        eq(fixture.getString("target_key"), second.getString("target_key"));
        eq(1, fixture.getJSONArray("addresses").length()); eq(X, fixture.getJSONArray("addresses").getString(0));
        eq(1730000003000L, second.getLong("tested_at"));
        check(!root.getJSONObject("validations").has(THIRD), "explicit independent node is not taken over");
        check(!root.getJSONObject("validations").has(CONFLICT), "advanced relay_nodes conflict keeps its own entry");
        check(!root.getJSONObject("profile_modes").has(f.profileId), "implicit reference mode stays implicit");
        check(!root.getJSONObject("profile_modes").has(SECOND), "implicit following mode stays implicit");
        eq("independent", root.getJSONObject("profile_modes").getString(THIRD));
        eq(2, f.backend.requests.size()); // canonical same target reuses the reference proof; no validation prepare.
        Preferences p = new Preferences(f.target).forProfile(f.profileId);
        JSONObject merged = PreferredIpResolver.baseParams(p);
        PreferredIpResolver.Decision decision = PreferredIpResolver.apply(p, merged, root);
        check(decision.applied, "reference node adopts the saved subset"); eq(X, merged.getString("relay_nodes"));
        Preferences follower = new Preferences(f.target).forProfile(SECOND);
        JSONObject followerMerged = PreferredIpResolver.baseParams(follower);
        check(PreferredIpResolver.apply(follower, followerMerged, root).applied, "following node adopts the same saved subset");
        eq(X, followerMerged.getString("relay_nodes"));
        Preferences conflict = new Preferences(f.target).forProfile(CONFLICT);
        JSONObject conflictMerged = PreferredIpResolver.baseParams(conflict);
        check(!PreferredIpResolver.apply(conflict, conflictMerged, root).applied, "coexisting conflict node stays original");
        eq(true, f.checked(X));
    }

    static void testExistingValidationSubsetAccumulatesNewProof(Fixture f) throws Exception {
        String target = targetKey(f, f.profileId);
        JSONArray candidates = new JSONArray().put(candidate(X)).put(candidate(Y)).put(candidate(Z)).put(candidate(V6));
        f.store.update(root -> {
            root.put("candidates", candidates); root.getJSONArray("sources").getJSONObject(0).put("candidates", candidates);
            JSONArray pool = new JSONArray().put(candidate(X)).put(candidate(Y));
            root.put("selected", pool).put("enabled", true).put("selection_version", 4);
            root.getJSONObject("validations").put(f.profileId, new JSONObject().put("target_key", target).put("network_id", "A")
                .put("session_id", "old-session").put("context_key", "old-context").put("tested_at", OLD_VALIDATION)
                .put("addresses", new JSONArray().put(X)));
            root.getJSONObject("validations").put("ghost-profile", new JSONObject().put("target_key", "unknown-target")
                .put("addresses", new JSONArray().put(Y)).put("tested_at", OLD_VALIDATION));
        });
        scanFour(f, candidates);
        JSONObject scanned = f.store.read();
        JSONObject retained = scanned.getJSONObject("validations").getJSONObject(f.profileId);
        eq(X, retained.getJSONArray("addresses").getString(0));
        for (int n = 0; n < retained.getJSONArray("addresses").length(); n++)
            check(selected(scanned, retained.getJSONArray("addresses").getString(n)), "old/natural subset contains only currently selected rows");
        check(scanned.getBoolean("enabled"), "old pool stays enabled");
        eq(OLD_VALIDATION, scanned.getJSONObject("validations").getJSONObject("ghost-profile").getLong("tested_at"));
        eq("unknown-target", scanned.getJSONObject("validations").getJSONObject("ghost-profile").getString("target_key"));
        selectSelect(f, Z);
        JSONObject root = f.store.read();
        JSONObject record = root.getJSONObject("validations").getJSONObject(f.profileId);
        eq(3, record.getJSONArray("addresses").length());
        eq(X, record.getJSONArray("addresses").getString(0));
        eq(5L, root.getLong("selection_version"));
        eq(1730000003000L, record.getLong("tested_at"));
        check(selected(root, Z), "new explicit intent persisted");
        check(!root.getJSONObject("profile_modes").has(f.profileId), "new subset never fabricates an explicit mode");
        Preferences p = new Preferences(f.target).forProfile(f.profileId);
        JSONObject merged = PreferredIpResolver.baseParams(p);
        check(PreferredIpResolver.apply(p, merged, root).applied, "old pool now adopts the accumulated three-address subset");
        eq(3, merged.getString("relay_nodes").split(",").length);
        f.show(X); eq(true, f.checked(X)); f.show(Z); eq(true, f.checked(Z));
    }

    /** Real checkbox selection through the visible row. */
    static void selectSelect(Fixture f, String address) throws Exception {
        f.show(address); eq(false, f.checked(address)); f.click(f.widget(address, R.id.preferred_choice));
        f.settle(); f.publish(); f.draw(); eq(true, f.checked(address)); check(selected(f.store.read(), address), "real selection saved");
    }

    // ---------------------------------------------------------------- G8: late reply / reservation

    /** Frozen result bytes and read-only attribution copies, captured on their owning actor. */
    static final class ReferenceSnapshot {
        final Map<String, String> results = new LinkedHashMap<>();
        final Map<String, Object> contexts = new LinkedHashMap<>();
        final String history;
        final Object proof;
        @SuppressWarnings("unchecked") ReferenceSnapshot(Fixture f) throws Exception {
            for (Map.Entry<String, JSONObject> entry : ((Map<String, JSONObject>) field(f.controller, "results")).entrySet())
                results.put(entry.getKey(), entry.getValue().toString());
            contexts.putAll((Map<String, Object>) field(f.controller, "resultContexts"));
            history = ((JSONObject) field(f.controller, "oldHistory")).toString();
            proof = field(f.controller, "scanContext");
        }
        static ReferenceSnapshot capture(Fixture f) throws Exception {
            return f.control.submit(() -> new ReferenceSnapshot(f)).get(10, TimeUnit.SECONDS);
        }
    }

    static void testLateReferenceReplyAfterNetworkRevokeIsNotMerged(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true); f.stopCycle(); f.actorDrain();
        JSONObject pool = f.store.read(); long attempts = f.backend.startAttempts.get();
        PreferredIpUiRemainingTest.NativeAccess access = new PreferredIpUiRemainingTest.NativeAccess(f);
        f.backend.holdProbe = true;
        PreferredIpBudgetEchRemainingTest.overflowItem(f, access, "重新开始测试");
        PreferredIpBudgetEchRemainingTest.dialogButton(f, access, "重新开始", true); access.transientWindow = false;
        Task prepare = f.backend.next("prepare");
        Task probe = f.backend.next("probe"); await(probe.polled, "new reference poll blocked");
        eq(prepare.sessionId, probe.sessionId); eq(prepare.roundId, probe.roundId);
        eq(attempts + 2, f.backend.startAttempts.get());
        JSONObject partial = f.backend.snapshot(probe, "running", false, false, 0);
        JSONArray rows = partial.getJSONArray("results");
        JSONObject completeY = PreferredIpPresentation.find(f.backend.snapshot(probe, "running", false, true, 0).getJSONArray("results"), Y);
        check(completeY != null, "unselected Y has a real complete wire row");
        for (int n = 0; n < rows.length(); n++) if (Y.equals(rows.getJSONObject(n).getString("address"))) rows.put(n, completeY);
        partial.put("completed", 1); probe.replies.add(partial); probe.awaitPoll(2); f.settle(); f.publish();
        eq(1, f.state().completed);
        ReferenceSnapshot before = ReferenceSnapshot.capture(f);
        check(!new JSONObject(before.results.get(X)).getBoolean("probe_complete"), "selected X has no new complete proof before revoke");
        eq(completeY.toString(), before.results.get(Y));
        Object proof = before.proof;
        eq(proof, before.contexts.get(Y));
        eq("A", field(before.contexts.get(Y), "networkId"));
        eq(probe.sessionId, field(before.contexts.get(Y), "sessionId"));
        eq(pool.getJSONObject("validations").getJSONObject(f.profileId).getString("session_id"), new JSONObject(before.history).getString("session_id"));
        check(!pool.getJSONObject("validations").getJSONObject(f.profileId).getString("session_id").equals(probe.sessionId), "held reference is a genuinely new session");
        f.network.change("B"); f.settle(); f.publish();
        await(probe.cancelled, "network revoke cancels the admitted old reference");
        check(f.state().stale && f.state().busy, "network revoke invalidates the reference but still waits for worker_done"); eq(0, f.state().completed);
        probe.replies.add(f.backend.snapshot(probe, "running", false, true, 0)); probe.awaitPoll(3); f.settle(); f.publish();
        ReferenceSnapshot after = ReferenceSnapshot.capture(f);
        eq(before.results, after.results); eq(before.contexts, after.contexts); eq(before.history, after.history);
        eq(0, f.state().completed); // Historical rows do not count as current completed results.
        eq(attempts + 2, f.backend.startAttempts.get());
        eq(pool.getJSONArray("selected").toString(), f.store.read().getJSONArray("selected").toString());
        eq(pool.getJSONObject("validations").toString(), f.store.read().getJSONObject("validations").toString());
        eq(pool.getLong("selection_version"), f.store.read().getLong("selection_version"));
        probe.replies.add(f.backend.snapshot(probe, "cancelled", true, true, 0));
        until(() -> { f.publish(); return !f.state().busy; }, "old admission drains after the late reply");
        f.actorDrain(); check(probe.consumedDone, "matching old reference worker_done really exited");
        ReferenceSnapshot drained = ReferenceSnapshot.capture(f);
        eq(1, drained.results.size()); eq(before.results.get(Y), drained.results.get(Y));
        eq(1, drained.contexts.size()); eq(proof, drained.contexts.get(Y)); eq(before.history, drained.history);
        eq(attempts + 2, f.backend.startAttempts.get()); // no automatic continuation on the new network.
        eq(pool.getJSONObject("validations").toString(), f.store.read().getJSONObject("validations").toString());
        eq(pool.getLong("selection_version"), f.store.read().getLong("selection_version"));
        f.backend.holdProbe = false; f.show(Y); f.click(f.widget(Y, R.id.preferred_row_heading)); f.settle(); f.publish(); f.draw();
        check(f.rowText(Y).contains("历史成绩（不参与当前排名/新增选择）"), "retained Y is explicitly historical in the real row");
        eq(0, f.state().completed); f.show(X); eq(true, f.checked(X));
        check(f.state().stale && f.store.read().getBoolean("enabled"), "old enabled pool remains selected while current results are stale");
        eq(pool.getJSONArray("selected").toString(), f.store.read().getJSONArray("selected").toString());
    }

    static void testDownloadRevokedAroundReserveNeverStarts(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true); f.stopCycle();
        f.show(X); f.click(f.widget(X, R.id.preferred_row_heading)); f.settle(); f.publish(); f.draw();
        long attempts = f.backend.startAttempts.get(), used = f.store.read().getJSONObject("download_ledger").getLong("used");
        int tasks = f.backend.tasks.size();
        JSONObject pool = f.store.read();
        synchronized (storeMonitor()) {
            f.click(f.rowWidget(X, R.id.preferred_row_download));
            until(() -> field(f.controller, "active") != null, "download job launched towards the real Store lock");
            JSONObject blocked = persisted(f);
            eq(0L, blocked.getJSONObject("download_ledger").getLong("reserved"));
            eq(attempts, f.backend.startAttempts.get()); // no Go Start while the reservation transaction is blocked
            f.network.change("B"); f.settle(); f.publish();
        }
        until(() -> field(f.controller, "active") == null, "revoked download job exits without a worker");
        f.settle(); f.publish(); f.draw();
        eq(attempts, f.backend.startAttempts.get());
        eq(tasks, f.backend.tasks.size());
        JSONObject ledger = f.store.read().getJSONObject("download_ledger");
        eq(used, ledger.getLong("used")); eq(0L, ledger.getLong("reserved"));
        eq(pool.getJSONArray("selected").toString(), f.store.read().getJSONArray("selected").toString());
        eq(pool.getJSONObject("validations").toString(), f.store.read().getJSONObject("validations").toString());
        check(f.state().stale && f.state().activeDownload.isEmpty(), "no download was admitted around the reserve window");
        eq(pool.getJSONObject("download_ledger").getString("id"), ledger.getString("id"));
    }

    static void testAdmittedOldRoundDownloadSettlesAndRefusesContinue(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true); f.stopCycle(); f.backend.holdDownload = true;
        f.show(X); f.click(f.widget(X, R.id.preferred_row_heading)); f.settle(); f.publish(); f.draw();
        JSONObject pool = f.store.read(); String round = pool.getJSONObject("download_ledger").getString("id");
        long attempts = f.backend.startAttempts.get();
        f.backend.startReturn = f.gates.create();
        f.click(f.rowWidget(X, R.id.preferred_row_download));
        Task task = f.backend.next("download"); f.backend.startReturn.awaitEnter();
        f.publish();
        eq("", field(f.controller, "taskId")); // The admitted ID has not returned yet.
        eq(52428800L, f.store.read().getJSONObject("download_ledger").getLong("reserved"));
        Object job = field(f.controller, "active"); check(job != null, "real admitted download Job"); eq(true, field(job, "reserved"));
        eq(round, task.request.getString("download_round_id"));
        f.network.change("B"); f.settle(); f.publish();
        f.backend.startReturn.close(); await(task.cancelled, "revoked admitted download is cancelled after ID return");
        task.replies.add(f.backend.snapshot(task, "cancelled", false, false, 7 * 1048576L)); task.awaitPoll(2); f.settle();
        eq(52428800L, f.store.read().getJSONObject("download_ledger").getLong("reserved")); // false/done=false keeps the reservation
        task.replies.add(f.backend.snapshot(task, "cancelled", true, false, 7 * 1048576L));
        until(() -> { f.publish(); return !f.state().busy; }, "matching worker_done settles the old round");
        JSONObject ledger = f.store.read().getJSONObject("download_ledger");
        eq(7 * 1048576L, ledger.getLong("used")); eq(0L, ledger.getLong("reserved")); eq(round, ledger.getString("id"));
        f.actorDrain(); check(task.consumedDone, "matching old-round download worker_done really exited");
        eq(attempts + 1, f.backend.startAttempts.get()); // Only this download occurred after the baseline.
        f.show(X); View disabled = f.rowWidget(X, R.id.preferred_row_download);
        JSONObject beforeInput = f.store.read();
        long cancelVersion = ((AtomicLong) field(f.controller, "cancelVersion")).get();
        attemptDisabledDownloadTouch(f, disabled);
        f.actorDrain(); f.publish(); f.draw();
        eq(attempts + 1, f.backend.startAttempts.get()); // Native input cannot restart the stale download.
        eq(beforeInput.toString(), f.store.read().toString());
        eq(cancelVersion, ((AtomicLong) field(f.controller, "cancelVersion")).get());
        eq(false, f.onMain(disabled::isEnabled));
        eq("", f.state().activeDownload); check(f.store.read().getBoolean("enabled"), "old valid pool is retained");
        eq(pool.getJSONArray("selected").toString(), f.store.read().getJSONArray("selected").toString());
        eq(pool.getJSONObject("validations").toString(), f.store.read().getJSONObject("validations").toString());
        eq(pool.getLong("selection_version"), f.store.read().getLong("selection_version"));
    }

    /** Inject a real screen tap at a visible disabled button; no performClick/enable override. */
    static void attemptDisabledDownloadTouch(Fixture f, View button) throws Exception {
        f.main(() -> button.requestRectangleOnScreen(new Rect(0, 0, button.getWidth(), button.getHeight()), true));
        f.draw();
        float[] point = f.onMain(() -> {
            check(!button.isEnabled() && button.isShown(), "stale download is visibly disabled");
            Rect visible = new Rect(); check(button.getLocalVisibleRect(visible) && !visible.isEmpty(), "disabled download has a visible hit region");
            int[] location = new int[2]; button.getLocationOnScreen(location);
            return new float[]{location[0] + visible.exactCenterX(), location[1] + visible.exactCenterY()};
        });
        long down = SystemClock.uptimeMillis();
        try {
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try { f.i.sendPointerSync(event); } finally { event.recycle(); }
            }
        } finally {
            MotionEvent cancel = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, point[0], point[1], 0);
            cancel.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { f.i.sendPointerSync(cancel); } finally { cancel.recycle(); }
        }
        f.i.waitForIdleSync();
    }

    static void testPrepareRequiredMaintenanceStopsAfterNetworkRevoke(Fixture f) throws Exception {
        f.launch(); f.tab(1); PreferredIpUiRemainingTest.header(f);
        PreferredIpTestControl.Gate prepareGate = f.gates.create();
        f.backend.startReturn = prepareGate;
        f.click(f.view(R.id.preferred_primary));
        f.backend.next("prepare"); prepareGate.awaitEnter();
        PreferredIpTestControl.Gate maintenance = f.gates.create();
        f.backend.startReturn = maintenance; f.backend.holdStartAt = 3;
        f.backend.rejectStartWith = "prepare_required: ECH DNS returned TTL 0";
        prepareGate.close();
        maintenance.awaitEnter();
        eq(3L, f.backend.startAttempts.get()); // prepare, synchronously rejected probe, maintenance prepare
        check(field(f.controller, "active") != null, "real maintenance prepare is admitted while its Start return is held");
        f.publish(); check(f.state().busy, "maintenance keeps the only network slot busy");
        f.network.change("B"); f.settle(); f.publish();
        check(f.state().stale, "network revocation reaches the held maintenance");
        maintenance.close();
        until(() -> { f.publish(); return !f.state().busy; }, "maintenance drains without launching the retry");
        f.settle(); f.publish();
        eq(3L, f.backend.startAttempts.get()); // One bounded maintenance attempt, never the original retry.
        eq(2, f.backend.tasks.size()); // original prepare plus maintenance; the probe was rejected before admission.
        for (JSONObject request : f.backend.requests) check(!"validation".equals(request.optString("probe_role")), "no later probe on the revoked network");
        eq(0, f.store.read().getJSONArray("selected").length());
        eq(false, f.store.read().getBoolean("enabled"));
    }
}
