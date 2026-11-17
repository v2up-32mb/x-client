package com.x.client.app;

import android.app.Instrumentation;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import static com.x.client.app.PreferredIpV2Test.*;
import static com.x.client.app.PreferredIpBudgetEchRemainingTest.*;

/** E3 only: admitted accounting failures and real reserve/release/maintenance/retry chains. */
final class PreferredIpDownloadRemainingTest {
    static final long MIB = 1048576L;
    static final String[] NAMES = {
        "testAdmittedDownloadWrongRoundDonePreservesReservation",
        "testAdmittedDownloadWrongReservedDonePreservesReservation",
        "testAdmittedDownloadWrongUsedDonePreservesReservation",
        "testDownloadPrepareRequiredMaintenanceRetriesReservedBudgetOnce",
        "testDownloadPrepareRequiredSecondRejectionIsBounded"
    };
    static void run(Instrumentation i, PreferredIpFeedbackTest.Reporter reporter) throws Exception {
        Case[] cases = {PreferredIpDownloadRemainingTest::testAdmittedDownloadWrongRoundDonePreservesReservation,
            PreferredIpDownloadRemainingTest::testAdmittedDownloadWrongReservedDonePreservesReservation,
            PreferredIpDownloadRemainingTest::testAdmittedDownloadWrongUsedDonePreservesReservation,
            PreferredIpDownloadRemainingTest::testDownloadPrepareRequiredMaintenanceRetriesReservedBudgetOnce,
            PreferredIpDownloadRemainingTest::testDownloadPrepareRequiredSecondRejectionIsBounded };
        eq(NAMES.length, cases.length);
        for (int n = 0; n < cases.length; n++) {
            try (Fixture f = new Fixture(i)) { cases[n].run(f); }
            reporter.passed(PreferredIpFeedbackTest.TOTAL + PreferredIpV2Test.NAMES.length
                + PreferredIpV2ContinuationTest.NAMES.length + PreferredIpUiRemainingTest.NAMES.length
                + PreferredIpConfigurationCancelTest.NAMES.length + PreferredIpContextRemainingTest.NAMES.length
                + PreferredIpBudgetEchRemainingTest.NAMES.length + PreferredIpValidationRemainingTest.NAMES.length + n + 1, NAMES[n]);
        }
    }
    static void testAdmittedDownloadWrongRoundDonePreservesReservation(Fixture f) throws Exception { admittedLedgerMismatch(f, 0); }
    static void testAdmittedDownloadWrongReservedDonePreservesReservation(Fixture f) throws Exception { admittedLedgerMismatch(f, 1); }
    static void testAdmittedDownloadWrongUsedDonePreservesReservation(Fixture f) throws Exception { admittedLedgerMismatch(f, 2); }

    /** Real admitted UI task; only matching identity is retained while one actual settle field is wrong. */
    static void admittedLedgerMismatch(Fixture f, int mismatch) throws Exception {
        PreferredIpEchScript ech = new PreferredIpEchScript(f.backend); f.backend.echScript = ech; // Actual disabled request/wire is checked.
        f.pausedComplete(); f.selectX(true); f.stopCycle(); f.actorDrain(); f.backend.holdDownload = true;
        PreferredIpBudgetEchRemainingTest.openExpanded(f, X);
        long attempts = f.backend.startAttempts.get();
        JSONObject oldValidation = new JSONObject(f.store.read().getJSONObject("validations").toString());
        PreferredIpBudgetEchRemainingTest.downloadOnce(f, X, 7 * MIB); f.actorDrain();
        JSONObject before = f.store.read(), priorLedger = new JSONObject(ledger(f).toString());
        eq(7 * MIB, priorLedger.getLong("used")); eq(0L, priorLedger.getLong("reserved"));
        PreferredIpBudgetEchRemainingTest.openExpanded(f, X);
        f.click(f.rowWidget(X, R.id.preferred_row_download));
        Task task = f.backend.next("download"); await(task.polled, "real matching-identity manual worker poll");
        eq("download", task.request.getString("operation"));
        eq(f.state().sessionId, task.sessionId); eq("A", task.networkId);
        eq(f.backend.targetKey(task.request.getJSONObject("options")), task.targetKey);
        eq(field(field(f.controller, "scanContext"), "targetKey"), task.targetKey);
        eq(PreferredIpController.DOWNLOAD, task.request.getJSONObject("options").getString("download_url"));
        JSONObject reserved = f.store.read(), beforeLedger = new JSONObject(ledger(f).toString());
        eq(50 * MIB, beforeLedger.getLong("reserved")); eq(7 * MIB, beforeLedger.getLong("used"));
        eq(X, beforeLedger.getString("address"));
        JSONObject done = f.backend.snapshot(task, "completed", true, false, 4 * MIB);
        switch (mismatch) {
            case 0: done.put("download_round_id", task.roundId + "-stale"); break;
            case 1: done.put("download_reserved_bytes", 49 * MIB); break;
            case 2:
                done.put("download_round_used", task.usedAtAdmission + 4 * MIB + 1)
                    .put("download_round_remaining", PreferredIpLedger.LIMIT - task.usedAtAdmission - 4 * MIB - 1);
                break;
            default: throw new AssertionError("unknown settlement mismatch arm");
        }
        task.replies.add(done);
        until(() -> task.consumedDone && f.control.submit(() -> field(f.controller, "active") == null).get(10, TimeUnit.SECONDS),
            "matching-ID/session/network worker_done consumed despite budget mismatch");
        check(task.matches(done) && task.consumedDone && f.backend.admitted.isEmpty(), "mismatch is ledger-only, not a wrong-identity fixture reply");
        eq(task.targetKey, done.getString("target_key")); eq("download", done.getString("operation"));
        eq(task.request.optString("probe_role", "reference"), done.getString("probe_role"));
        f.actorDrain(); f.publish(); f.draw();
        eq(attempts + 2, f.backend.startAttempts.get()); // Exactly two real UI downloads after the reference baseline.
        eq(reserved.toString(), f.store.read().toString());
        JSONObject stuck = ledger(f);
        eq(priorLedger.getString("id"), stuck.getString("id")); eq(7 * MIB, stuck.getLong("used"));
        eq(50 * MIB, stuck.getLong("reserved")); eq(X, stuck.getString("address"));
        check(f.state().ledgerBlocked && f.state().activeDownload.isEmpty(), "settlement failure leaves a blocked ledger after the admitted slot exits");
        check(f.state().message.contains("ledger_mismatch"), "real Controller reports conservative ledger failure: " + f.state().message);
        eq(oldValidation.toString(), f.store.read().getJSONObject("validations").toString());
        eq(before.getJSONArray("selected").toString(), f.store.read().getJSONArray("selected").toString());
        long rejectedUiBaseline = f.backend.startAttempts.get();
        eq(true, f.onMain(() -> f.rowWidget(X, R.id.preferred_row_download).isEnabled()));
        f.click(f.rowWidget(X, R.id.preferred_row_download)); // Production leaves the ledger-blocked row enabled; real native input must be refused by reserve.
        until(() -> { f.publish(); return f.state().activeDownload.isEmpty() && f.state().message.contains("下载账本不一致"); }, "real row attempt is rejected while reservation remains uncertain");
        f.actorDrain(); eq(rejectedUiBaseline, f.backend.startAttempts.get());
        eq(reserved.toString(), f.store.read().toString());
        PreferredIpUiRemainingTest.NativeAccess access = new PreferredIpUiRemainingTest.NativeAccess(f);
        int downloads = operationCount(f, "download"); long beforeRound = f.backend.startAttempts.get();
        overflowItem(f, access, "开启新一轮100MiB下载额度");
        requireDialog(access, "新增100MiB下载额度");
        dialogButton(f, access, "新增100MiB下载额度", false); access.transientWindow = false;
        f.actorDrain(); f.publish(); eq(reserved.toString(), f.store.read().toString());
        eq(beforeRound, f.backend.startAttempts.get());
        overflowItem(f, access, "开启新一轮100MiB下载额度");
        requireDialog(access, "新增100MiB下载额度");
        dialogButton(f, access, "新增100MiB下载额度", true); access.transientWindow = false;
        Task prepare = f.backend.next("prepare");
        check(prepare.request.getBoolean("new_download_round"), "only actual confirmed overflow action starts a new round");
        eq(false, prepare.request.getJSONObject("options").getBoolean("enable_ech"));
        until(() -> { f.publish(); return !f.state().busy && prepare.consumedDone; }, "confirmed accounting recovery prepare exits");
        f.actorDrain(); f.publish(); f.draw();
        JSONObject recovered = ledger(f);
        eq(0L, recovered.getLong("used")); eq(0L, recovered.getLong("reserved"));
        check(!recovered.getString("id").equals(priorLedger.getString("id")), "new Go round acknowledged after confirmation");
        eq(beforeLedger.toString(), recovered.getJSONObject("previous").toString());
        check(!recovered.optBoolean("reset_pending") && !f.state().ledgerBlocked, "explicit confirmation preserves prior audit and clears the block");
        eq(beforeRound + 1, f.backend.startAttempts.get()); eq(downloads, operationCount(f, "download"));
        check(f.state().activeDownload.isEmpty() && f.state().paused, "recovery does not auto-download");
        ech.assertConsumed(); f.failures.assertEmpty();
    }

    static void downloadPrepareRequired(Fixture f, boolean rejectAgain) throws Exception {
        PreferredIpEchScript ech = enableEch(f);
        ech.prepare(PreferredIpEchScript.Preparation.READY);
        ech.prepare(rejectAgain ? PreferredIpEchScript.Preparation.EXPIRED_READY : PreferredIpEchScript.Preparation.READY);
        f.backend.holdProbe = true;
        f.pausedComplete(); f.selectX(true); f.stopCycle(); f.actorDrain(); f.backend.holdDownload = true;
        PreferredIpBudgetEchRemainingTest.openExpanded(f, X);
        PreferredIpBudgetEchRemainingTest.downloadOnce(f, X, 7 * MIB); f.actorDrain();
        JSONObject rootBefore = f.store.read(), ledgerBefore = new JSONObject(ledger(f).toString());
        JSONObject validationBefore = new JSONObject(rootBefore.getJSONObject("validations").toString());
        JSONObject resultBefore = PreferredIpBudgetEchRemainingTest.resultOf(f, X);
        PreferredIpContextRemainingTest.ReferenceSnapshot referenceBefore = PreferredIpContextRemainingTest.ReferenceSnapshot.capture(f);
        List<String> queueBefore = referenceQueue(f);
        long startedAt = storedStartedAt(f), now = ech.time(), expiry = PreferredIpEchScript.INITIAL_TIME + 1000;
        ech.advanceTo(expiry); check(ech.time() >= expiry && expiry > now, "reference ready ECH is truly expired at download admission");
        long attempts = f.backend.startAttempts.get(); int taskCount = f.backend.tasks.size();
        List<JSONObject> reservesAtStart = new ArrayList<>();
        List<JSONObject> downloadRequests = new ArrayList<>();
        Thread networkThread = PreferredIpValidationRemainingTest.actorThread((ExecutorService) field(f.controller, "networkIO"));
        f.resources.add(() -> f.backend.startObserver = null);
        f.backend.startObserver = request -> {
            if (Thread.currentThread() == networkThread && "download".equals(request.getString("operation"))) {
                f.backend.require(request.getJSONObject("options").getLong("download_bytes_per_ip") == 50 * MIB
                    && request.getJSONObject("options").getInt("download_seconds") == 10, "real download keeps 50MiB/10-second options on retry");
                JSONObject disk = f.store.read();
                reservesAtStart.add(new JSONObject(disk.getJSONObject("download_ledger").toString()));
                downloadRequests.add(new JSONObject(request.toString()));
                f.backend.require(disk.getJSONObject("download_ledger").getLong("reserved") == 50 * MIB,
                    "both original and retry must persist real reservation before Start");
                f.backend.require(disk.getJSONObject("download_ledger").getLong("used") == 7 * MIB,
                    "rejected Starts cannot charge or reset the prior nonzero budget");
            }
        };
        PreferredIpTestControl.Gate maintenanceReturn = f.gates.create(), retryReturn = f.gates.create();
        f.backend.startReturn = maintenanceReturn; f.backend.holdStartAt = attempts + 2;
        f.click(f.rowWidget(X, R.id.preferred_row_download));
        Task maintenance = f.backend.next("prepare"); maintenanceReturn.awaitEnter();
        eq(attempts + 2, f.backend.startAttempts.get()); // one reserved download rejected, then maintenance prepare admitted.
        eq(1, ech.rejected); eq("prepare", maintenance.request.getString("operation"));
        eq(1, reservesAtStart.size()); eq(50 * MIB, reservesAtStart.get(0).getLong("reserved"));
        eq(referenceBefore.proof, field(f.controller, "scanContext"));
        eq(referenceBefore.results, PreferredIpContextRemainingTest.ReferenceSnapshot.capture(f).results);
        eq(resultBefore.toString(), resultOf(f, X).toString());
        eq(validationBefore.toString(), f.store.read().getJSONObject("validations").toString());
        eq(maintenance.request.getString("session_id"), field(referenceBefore.proof, "sessionId"));
        eq(maintenance.request.getString("session_id"), maintenance.sessionId);
        eq(ledgerBefore.toString(), ledger(f).toString()); // download rejectedBeforeStart released reserved before maintenance was admitted.
        eq(rootBefore.getLong("revision") + 2, f.store.read().getLong("revision"));
        eq(0L, f.store.read().getJSONObject("download_ledger").getLong("reserved"));
        eq(ledgerBefore.getLong("used"), f.store.read().getJSONObject("download_ledger").getLong("used"));
        eq(ledgerBefore.getString("id"), maintenance.request.getString("download_round_id"));
        eq(0, maintenance.request.getJSONArray("candidates").length()); eq(0, maintenance.request.getJSONArray("prefixes").length());
        check(!maintenance.request.has("new_download_round"), "maintenance is same-session and does not reset quota");
        eq(true, maintenance.request.getJSONObject("options").getBoolean("enable_ech"));
        eq("ready", maintenance.echAtAdmission.getString("state"));
        if (rejectAgain) eq(maintenance.echAtAdmission.getLong("prepared_at"), maintenance.echAtAdmission.getLong("expires_at"));
        else check(maintenance.echAtAdmission.getLong("expires_at") > ech.time(), "successful maintenance is immediately usable");
        JSONObject ledgerAtMaintenance = new JSONObject(ledger(f).toString());
        f.backend.startReturn = retryReturn; f.backend.holdStartAt = attempts + 3;
        maintenanceReturn.close();
        if (rejectAgain) {
            until(() -> { f.publish(); return !f.state().busy && f.state().paused; }, "second download prepare_required stops without another maintenance");
            f.actorDrain(); f.publish(); f.draw();
            check(maintenance.consumedDone, "real maintenance matching worker_done completed before bounded retry rejection");
            eq(attempts + 3, f.backend.startAttempts.get()); eq(2, ech.rejected);
            eq(taskCount + 1, f.backend.tasks.size()); // only maintenance admitted; neither download was admitted.
            eq(ledgerAtMaintenance.toString(), ledger(f).toString());
            eq(ledgerBefore.toString(), ledger(f).toString());
            eq(PreferredIpBudgetEchRemainingTest.resultOf(f, X).toString(), resultBefore.toString());
            eq(validationBefore.toString(), f.store.read().getJSONObject("validations").toString());
            eq(startedAt, storedStartedAt(f));
            eq(referenceBefore.results, PreferredIpContextRemainingTest.ReferenceSnapshot.capture(f).results);
            eq(0L, ledger(f).getLong("reserved")); eq(7 * MIB, ledger(f).getLong("used"));
            check(f.backend.admitted.isEmpty() && f.state().activeDownload.isEmpty(), "second rejection leaves no worker/slot or reservation");
            check(f.state().message.contains("ECH 配置无法复用"), "the second legitimate expiry ends at the bounded product error");
            eq(1, f.backend.startRequests.get(f.backend.startRequests.size()-1).getJSONArray("candidates").length());
        } else {
            Task retry = f.backend.next("download"); retryReturn.awaitEnter();
            eq(attempts + 3, f.backend.startAttempts.get()); eq(1, ech.rejected);
            eq("download", retry.request.getString("operation"));
            eq(maintenance.sessionId, retry.sessionId); eq(ledgerBefore.getString("id"), retry.roundId);
            eq(PreferredIpController.DOWNLOAD, retry.request.getJSONObject("options").getString("download_url"));
            eq(true, retry.request.getJSONObject("options").getBoolean("enable_ech"));
            eq(true, retry.echAtAdmission.getBoolean("cache_hit"));
            eq(50 * MIB, ledger(f).getLong("reserved")); eq(7 * MIB, ledger(f).getLong("used"));
            Object job = field(f.controller, "active"); eq(true, field(job, "reserved")); eq("", field(job, "id"));
            eq(7 * MIB, retry.usedAtAdmission); eq(50 * MIB, retry.reservedAtAdmission);
            retryReturn.close(); await(retry.polled, "real admitted retry download poll");
            retry.replies.add(f.backend.snapshot(retry, "completed", true, false, 4 * MIB));
            until(() -> retry.consumedDone && f.control.submit(() -> field(f.controller, "active") == null).get(10, TimeUnit.SECONDS), "matching retry worker_done settles actual body");
            f.actorDrain(); f.publish(); f.draw();
            eq(attempts + 3, f.backend.startAttempts.get()); eq(taskCount + 2, f.backend.tasks.size());
            eq(0L, ledger(f).getLong("reserved")); eq(11 * MIB, ledger(f).getLong("used"));
            eq(ledgerBefore.getString("id"), ledger(f).getString("id"));
            JSONObject resultAfter = PreferredIpBudgetEchRemainingTest.resultOf(f, X);
            PreferredIpBudgetEchRemainingTest.assertOnlyDownloadChanged(resultBefore, resultAfter);
            eq(4 * MIB, resultAfter.getLong("download_bytes"));
            eq(validationBefore.toString(), f.store.read().getJSONObject("validations").toString());
            eq(startedAt, storedStartedAt(f));
            PreferredIpContextRemainingTest.ReferenceSnapshot referenceAfter = PreferredIpContextRemainingTest.ReferenceSnapshot.capture(f);
            eq(referenceBefore.results.keySet(), referenceAfter.results.keySet());
            eq(referenceBefore.contexts, referenceAfter.contexts);
        }
        eq(2, reservesAtStart.size()); eq(2, downloadRequests.size());
        eq(rootBefore.getLong("revision") + 4, f.store.read().getLong("revision"));
        eq(rootBefore.getLong("selection_version"), f.store.read().getLong("selection_version"));
        eq(downloadRequests.get(0).toString(), downloadRequests.get(1).toString());
        eq(reservesAtStart.get(0).toString(), reservesAtStart.get(1).toString());
        eq(referenceBefore.proof, field(f.controller, "scanContext"));
        check(maintenance.consumedDone, "maintenance had real matching exit");
        eq(queueBefore, referenceQueue(f));
        long finalAttempts = f.backend.startAttempts.get();
        fence(f.control); f.publish(); fence(f.control); f.publish();
        eq(finalAttempts, f.backend.startAttempts.get()); // No third download or second maintenance.
        assertEchEnabledRequests(f); ech.assertConsumed(); f.failures.assertEmpty();
    }
    static List<String> referenceQueue(Fixture f) throws Exception {
        return f.control.submit(() -> {
            @SuppressWarnings("unchecked") Map<String, JSONObject> candidates = (Map<String, JSONObject>) field(f.controller, "candidates");
            return new ArrayList<>(candidates.keySet());
        }).get(10, TimeUnit.SECONDS);
    }
    static void testDownloadPrepareRequiredMaintenanceRetriesReservedBudgetOnce(Fixture f) throws Exception { downloadPrepareRequired(f, false); }
    static void testDownloadPrepareRequiredSecondRejectionIsBounded(Fixture f) throws Exception { downloadPrepareRequired(f, true); }

}
