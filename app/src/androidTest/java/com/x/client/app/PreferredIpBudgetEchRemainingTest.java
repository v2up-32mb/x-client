package com.x.client.app;

import android.accessibilityservice.AccessibilityService;
import android.app.Instrumentation;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.x.client.app.PreferredIpV2Test.*;

/** Stage D only: real budget dialogs/overflow rounds across multiple UI tasks, and same-session
 * ECH reuse with one bounded TTL=0 maintenance retry. Network rows still enter through Backend.poll;
 * no State/Row/proof/ledger field is written by the test. */
final class PreferredIpBudgetEchRemainingTest {
    static final String[] NAMES = {
        "testMeteredScanConfirmationGatesNativeStart",
        "testPerIpDownloadDialogCancelBackAndConfirm",
        "testUiDownloadTasksAccumulateSameRoundBudget",
        "testOverflowNewRoundCancelAndConfirmNoAutoDownload",
        "testLostLedgerRefusesDownloadUntilConfirmedRound",
        "testSameSessionBatchesReuseEchQueueAndBudget",
        "testPrepareRequiredMaintenanceBoundedOnceAtTtlZero",
        "testSuccessfulMaintenanceKeepsProofQueueAndStickyFallback"
    };
    private static final long MIB = 1048576L;
    private static final String ECH_KEY = "ech-sticky-fixture";
    private static final String TTL_ZERO = "prepare_required: ECH DNS returned TTL 0";

    static void run(Instrumentation i, PreferredIpFeedbackTest.Reporter reporter) throws Exception {
        Case[] cases = { PreferredIpBudgetEchRemainingTest::testMeteredScanConfirmationGatesNativeStart,
            PreferredIpBudgetEchRemainingTest::testPerIpDownloadDialogCancelBackAndConfirm,
            PreferredIpBudgetEchRemainingTest::testUiDownloadTasksAccumulateSameRoundBudget,
            PreferredIpBudgetEchRemainingTest::testOverflowNewRoundCancelAndConfirmNoAutoDownload,
            PreferredIpBudgetEchRemainingTest::testLostLedgerRefusesDownloadUntilConfirmedRound,
            PreferredIpBudgetEchRemainingTest::testSameSessionBatchesReuseEchQueueAndBudget,
            PreferredIpBudgetEchRemainingTest::testPrepareRequiredMaintenanceBoundedOnceAtTtlZero,
            PreferredIpBudgetEchRemainingTest::testSuccessfulMaintenanceKeepsProofQueueAndStickyFallback };
        eq(NAMES.length, cases.length);
        for (int n = 0; n < cases.length; n++) {
            try (Fixture f = new Fixture(i)) { cases[n].run(f); }
            reporter.passed(PreferredIpFeedbackTest.TOTAL + PreferredIpV2Test.NAMES.length
                + PreferredIpV2ContinuationTest.NAMES.length + PreferredIpUiRemainingTest.NAMES.length
                + PreferredIpConfigurationCancelTest.NAMES.length + PreferredIpContextRemainingTest.NAMES.length + n + 1, NAMES[n]);
        }
    }

    // ---------------------------------------------------------------- real dialog helpers

    static void requireDialog(PreferredIpUiRemainingTest.NativeAccess access, String text) throws Exception {
        AccessibilityNodeInfo node = access.require(n -> {
            CharSequence value = n.getText(); return value != null && value.toString().contains(text);
        });
        node.recycle();
    }

    static void dialogButton(Fixture f, PreferredIpUiRemainingTest.NativeAccess access, String title, boolean positive) throws Exception {
        requireDialog(access, title);
        access.clickId(positive ? "android:id/button1" : "android:id/button2");
        f.i.waitForIdleSync();
    }

    static void dialogBack(Fixture f, PreferredIpUiRemainingTest.NativeAccess access, String title) throws Exception {
        requireDialog(access, title);
        check(access.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK), "native system Back dismisses the real dialog");
        f.i.waitForIdleSync();
        until(() -> {
            AccessibilityNodeInfo leftover = access.find(n -> "android:id/button1".equals(n.getViewIdResourceName()));
            if (leftover != null) leftover.recycle();
            return leftover == null;
        }, "real dialog removed after native Back");
    }

    static void overflowItem(Fixture f, PreferredIpUiRemainingTest.NativeAccess access, String item) throws Exception {
        String description = f.onMain(() -> f.activity.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description));
        AccessibilityNodeInfo button = access.require(n -> description.contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription()) && n.isClickable());
        access.transientWindow = true;
        try { check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK), "actual toolbar overflow"); } finally { button.recycle(); }
        access.clickText(item);
        f.i.waitForIdleSync();
    }

    // ---------------------------------------------------------------- real budget helpers

    static void seedCandidates(Fixture f, int count) throws Exception {
        check(f.controller == null, "seed candidates before launch");
        JSONArray candidates = new JSONArray().put(candidate(X)).put(candidate(Y)).put(candidate(V6));
        for (int n = 2; candidates.length() < count; n++) candidates.put(candidate("104.16.2." + n + ":443"));
        f.store.update(root -> {
            root.put("candidates", candidates);
            root.getJSONArray("sources").getJSONObject(0).put("candidates", candidates);
        });
    }

    static void openExpanded(Fixture f, String address) throws Exception {
        f.show(address);
        if (!f.controller.expanded.contains(address)) {
            f.click(f.widget(address, R.id.preferred_row_heading));
            f.settle(); f.publish(); f.draw();
        }
        check(f.controller.expanded.contains(address), "real heading keeps the download row expanded: " + address);
    }

    static Task startDownload(Fixture f, String address) throws Exception {
        f.click(f.rowWidget(address, R.id.preferred_row_download));
        Task task = f.backend.next("download");
        await(task.polled, "real download poll");
        f.publish();
        return task;
    }

    /** A real row-button Cancel plus a false worker_done that must not settle the reservation. */
    static void cancelledDownload(Fixture f, Task task, long body) throws Exception {
        long reserved = f.store.read().getJSONObject("download_ledger").getLong("reserved");
        String address = task.request.getJSONArray("candidates").getJSONObject(0).getString("address");
        f.show(address); f.draw();
        f.click(f.rowWidget(address, R.id.preferred_row_download));
        await(task.cancelled, "native row download cancel");
        task.replies.add(f.backend.snapshot(task, "cancelled", false, false, body));
        task.awaitPoll(2);
        f.settle();
        eq(reserved, f.store.read().getJSONObject("download_ledger").getLong("reserved"));
        task.replies.add(f.backend.snapshot(task, "cancelled", true, false, body));
        until(() -> { f.publish(); return !f.state().busy; }, "matching worker_done settles the real cancelled download");
    }

    static void completedDownload(Fixture f, Task task, long body) throws Exception {
        task.replies.add(f.backend.snapshot(task, "completed", true, false, body));
        until(() -> { f.publish(); return !f.state().busy; }, "matching worker_done settles the real download");
    }

    static void downloadOnce(Fixture f, String address, long body) throws Exception {
        openExpanded(f, address);
        Task task = startDownload(f, address);
        completedDownload(f, task, body);
    }

    static JSONObject resultOf(Fixture f, String address) throws Exception {
        for (PreferredIpController.Row row : f.state().rows) if (address.equals(row.address)) return new JSONObject(row.result.toString());
        throw new AssertionError("missing result for " + address);
    }

    static int operationCount(Fixture f, String operation) throws Exception {
        int count = 0;
        for (JSONObject sent : f.backend.requests) if (operation.equals(sent.getString("operation"))) count++;
        return count;
    }

    /** Only the six manual-download fields may change; tested_at/score/proof/validation stay frozen. */
    static void assertOnlyDownloadChanged(JSONObject before, JSONObject after) throws Exception {
        Set<String> six = new HashSet<>(Arrays.asList("download_bytes", "download_seconds", "download_mib_s",
            "download_peak_mib_s", "download_state", "download_ech_status"));
        Iterator<String> keys = before.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!six.contains(key)) eq(before.get(key).toString(), after.get(key).toString());
        }
        eq(before.getLong("tested_at"), after.getLong("tested_at"));
        eq(before.getDouble("score"), after.getDouble("score"));
        eq(before.getString("target_key"), after.getString("target_key"));
    }

    static JSONObject ledger(Fixture f) throws Exception { return f.store.read().getJSONObject("download_ledger"); }

    static long storedStartedAt(Fixture f) throws Exception { return (Long) field(f.controller, "startedAt"); }

    static JSONObject fallbackEch() throws Exception {
        return new JSONObject().put("key", ECH_KEY).put("state", "fallback").put("prepared_at", 1730000002500L)
            .put("expires_at", 0).put("cache_hit", false).put("error", "TTL 0");
    }

    static void eqEch(Fixture f, JSONObject expected) throws Exception {
        eq(expected.toString(), ((JSONObject) field(f.controller, "ech")).toString());
        check(f.state().phase.contains("ECH " + expected.getString("state")), "real published ECH state: " + f.state().phase);
    }

    // ---------------------------------------------------------------- G9: real charging dialogs

    static void testMeteredScanConfirmationGatesNativeStart(Fixture f) throws Exception {
        f.launch(); f.tab(1); PreferredIpUiRemainingTest.header(f);
        PreferredIpUiRemainingTest.NativeAccess access = new PreferredIpUiRemainingTest.NativeAccess(f);
        f.network.charged = true; f.publish(); f.draw();
        check(f.state().metered, "real metered network published");
        f.click(f.view(R.id.preferred_primary));
        requireDialog(access, "计费网络确认");
        eq(0L, f.backend.startAttempts.get());
        check(f.backend.requests.isEmpty(), "cancel dialog never starts a worker");
        dialogButton(f, access, "计费网络确认", false); access.transientWindow = false;
        f.settle(); f.publish();
        eq(0L, f.backend.startAttempts.get()); check(f.state().sessionId.isEmpty(), "cancelled dialog leaves no scan");
        f.click(f.view(R.id.preferred_primary));
        requireDialog(access, "计费网络确认");
        dialogBack(f, access, "计费网络确认"); access.transientWindow = false;
        f.settle(); f.publish();
        eq(0L, f.backend.startAttempts.get()); check(f.state().sessionId.isEmpty(), "Back also leaves no scan");
        f.click(f.view(R.id.preferred_primary));
        requireDialog(access, "计费网络确认");
        dialogButton(f, access, "计费网络确认", true); access.transientWindow = false;
        Task prepare = f.backend.next("prepare");
        until(() -> { f.publish(); return !f.state().busy && f.state().completed == 3; }, "confirmed metered scan really starts");
        eq("prepare", prepare.request.getString("operation"));
        eq(2, f.backend.requests.size()); // the confirmed prepare plus one probe batch.
        for (JSONObject request : f.backend.requests) check(!"download".equals(request.getString("operation")), "scan confirmation never auto-downloads");
        check(f.state().selected == 0, "scan alone selects nothing");
        f.network.charged = false;
    }

    static void testPerIpDownloadDialogCancelBackAndConfirm(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true); f.settle(); f.publish(); f.backend.holdDownload = true;
        PreferredIpUiRemainingTest.NativeAccess access = new PreferredIpUiRemainingTest.NativeAccess(f);
        f.network.charged = true; f.publish(); f.draw();
        openExpanded(f, X);
        long attempts = f.backend.startAttempts.get(), used = ledger(f).getLong("used");
        JSONObject before = resultOf(f, X), validations = f.store.read().getJSONObject("validations");
        f.click(f.rowWidget(X, R.id.preferred_row_download));
        requireDialog(access, "计费网络手动测速");
        eq(attempts, f.backend.startAttempts.get()); eq(0L, ledger(f).getLong("reserved"));
        dialogButton(f, access, "计费网络手动测速", false); access.transientWindow = false;
        f.settle(); f.publish(); eq(attempts, f.backend.startAttempts.get());
        f.click(f.rowWidget(X, R.id.preferred_row_download));
        requireDialog(access, "计费网络手动测速");
        dialogBack(f, access, "计费网络手动测速"); access.transientWindow = false;
        f.settle(); f.publish(); eq(attempts, f.backend.startAttempts.get()); eq(0L, ledger(f).getLong("reserved"));
        f.click(f.rowWidget(X, R.id.preferred_row_download));
        requireDialog(access, "计费网络手动测速");
        dialogButton(f, access, "计费网络手动测速", true); access.transientWindow = false;
        Task task = f.backend.next("download");
        await(task.polled, "confirmed per-IP download poll");
        eq(PreferredIpController.DOWNLOAD, task.request.getJSONObject("options").getString("download_url"));
        eq(52428800L, ledger(f).getLong("reserved"));
        cancelledDownload(f, task, 7 * MIB);
        JSONObject after = resultOf(f, X);
        assertOnlyDownloadChanged(before, after);
        eq(7 * MIB, after.getLong("download_bytes"));
        eq(used + 7 * MIB, ledger(f).getLong("used")); eq(0L, ledger(f).getLong("reserved"));
        eq(validations.toString(), f.store.read().getJSONObject("validations").toString());
        check(ledger(f).getString("id").equals(task.request.getString("download_round_id")), "same real round");
        check(f.state().activeDownload.isEmpty(), "download left the only slot");
        f.network.charged = false;
    }

    static void testUiDownloadTasksAccumulateSameRoundBudget(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true); f.settle(); f.publish();
        f.backend.holdDownload = true;
        f.clockHook = () -> 1730000009000L; // Any accidental fresh startScan would re-stamp startedAt.
        JSONObject validations = f.store.read().getJSONObject("validations");
        String round = ledger(f).getString("id");
        long startedAt = storedStartedAt(f);
        JSONObject xBefore = resultOf(f, X), yBefore = resultOf(f, Y);
        openExpanded(f, X); Task xTask = startDownload(f, X);
        cancelledDownload(f, xTask, 7 * MIB);
        eq(7 * MIB, ledger(f).getLong("used")); eq(0L, ledger(f).getLong("reserved"));
        assertOnlyDownloadChanged(xBefore, resultOf(f, X));
        openExpanded(f, Y); Task yTask = startDownload(f, Y);
        completedDownload(f, yTask, 50 * MIB);
        eq(57 * MIB, ledger(f).getLong("used")); eq(round, ledger(f).getString("id"));
        assertOnlyDownloadChanged(yBefore, resultOf(f, Y));
        eq(validations.toString(), f.store.read().getJSONObject("validations").toString());
        eq(startedAt, storedStartedAt(f));
        PreferredIpUiRemainingTest.NativeAccess access = new PreferredIpUiRemainingTest.NativeAccess(f);
        PreferredIpUiRemainingTest.restart(f, access, 3); // A real new scan must not reset the same round.
        JSONObject scanned = ledger(f);
        eq(round, scanned.getString("id")); eq(57 * MIB, scanned.getLong("used")); eq(0L, scanned.getLong("reserved"));
        validations = f.store.read().getJSONObject("validations"); // A real new scan legitimately re-validates the selected IP.
        openExpanded(f, X); Task last = startDownload(f, X);
        eq(43 * MIB, ledger(f).getLong("reserved")); // min(50MiB, 100MiB - 57MiB)
        completedDownload(f, last, 43 * MIB);
        JSONObject full = ledger(f);
        eq(100 * MIB, full.getLong("used")); eq(0L, full.getLong("reserved")); eq(round, full.getString("id"));
        assertOnlyDownloadChanged(xBefore, resultOf(f, X));
        eq(validations.toString(), f.store.read().getJSONObject("validations").toString());
        long attempts = f.backend.startAttempts.get();
        f.click(f.rowWidget(X, R.id.preferred_row_download));
        until(() -> { f.publish(); return f.state().message.contains("额度已耗尽"); }, "exhausted budget refuses the real row download");
        eq(attempts, f.backend.startAttempts.get());
        check(f.state().activeDownload.isEmpty(), "no download job admitted after exhaustion");
        eq(100 * MIB, ledger(f).getLong("used"));
    }

    static void testOverflowNewRoundCancelAndConfirmNoAutoDownload(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true); f.settle(); f.publish(); f.backend.holdDownload = true;
        downloadOnce(f, X, 7 * MIB);
        int downloads = operationCount(f, "download");
        JSONObject before = new JSONObject(ledger(f).toString());
        eq(7 * MIB, before.getLong("used"));
        PreferredIpUiRemainingTest.NativeAccess access = new PreferredIpUiRemainingTest.NativeAccess(f);
        long attempts = f.backend.startAttempts.get();
        overflowItem(f, access, "开启新一轮100MiB下载额度");
        requireDialog(access, "新增100MiB下载额度");
        dialogButton(f, access, "新增100MiB下载额度", false); access.transientWindow = false;
        f.settle(); f.publish();
        eq(attempts, f.backend.startAttempts.get());
        eq(before.toString(), ledger(f).toString());
        overflowItem(f, access, "开启新一轮100MiB下载额度");
        requireDialog(access, "新增100MiB下载额度");
        dialogButton(f, access, "新增100MiB下载额度", true); access.transientWindow = false;
        Task prepare = f.backend.next("prepare");
        check(prepare.request.getBoolean("new_download_round"), "explicit confirmed round reaches Go");
        eq(f.state().sessionId, prepare.request.getString("session_id"));
        eq(0, prepare.request.getJSONArray("candidates").length());
        eq(0, prepare.request.getJSONArray("prefixes").length());
        until(() -> { f.publish(); return !f.state().busy; }, "confirmed new round prepare drains");
        JSONObject after = ledger(f);
        eq(0L, after.getLong("used")); eq(0L, after.getLong("reserved"));
        check(!before.getString("id").equals(after.getString("id")), "Go acknowledged a genuinely new round");
        eq(7 * MIB, after.getJSONObject("previous").getLong("used"));
        check(!after.optBoolean("reset_pending"), "new round confirmation settled");
        eq(0L, f.state().used); eq(0L, f.state().reserved);
        eq(attempts + 1, f.backend.startAttempts.get()); // the prepare only; never an automatic download.
        check(f.state().activeDownload.isEmpty(), "new round never auto-downloads");
        eq(downloads, operationCount(f, "download")); // no download was dispatched by the round action
        check(f.state().paused, "confirmed round stays paused");
    }

    static void testLostLedgerRefusesDownloadUntilConfirmedRound(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true); f.settle(); f.publish(); f.backend.holdDownload = true;
        downloadOnce(f, X, 7 * MIB);
        int downloads = operationCount(f, "download");
        f.store.update(root -> root.getJSONObject("download_ledger").put("owner", "dead-process"));
        long attempts = f.backend.startAttempts.get(), used = ledger(f).getLong("used");
        String id = ledger(f).getString("id");
        openExpanded(f, X);
        f.click(f.rowWidget(X, R.id.preferred_row_download));
        until(() -> { f.publish(); return f.state().message.contains("下载账本不一致"); }, "lost ledger conservatively refuses the real download");
        eq(attempts, f.backend.startAttempts.get()); eq(used, ledger(f).getLong("used"));
        check(f.state().ledgerBlocked, "lost accounting blocks the UI budget");
        String status = f.onMain(() -> ((TextView) f.activity.findViewById(R.id.preferred_status)).getText().toString());
        check(status.contains("未结清/失账"), "real status renders the blocked ledger: " + status);
        PreferredIpUiRemainingTest.NativeAccess access = new PreferredIpUiRemainingTest.NativeAccess(f);
        overflowItem(f, access, "开启新一轮100MiB下载额度");
        requireDialog(access, "新增100MiB下载额度");
        dialogButton(f, access, "新增100MiB下载额度", false); access.transientWindow = false;
        f.settle(); f.publish();
        eq(id, ledger(f).getString("id")); eq("dead-process", ledger(f).getString("owner")); eq(used, ledger(f).getLong("used"));
        eq(attempts, f.backend.startAttempts.get());
        overflowItem(f, access, "开启新一轮100MiB下载额度");
        requireDialog(access, "新增100MiB下载额度");
        dialogButton(f, access, "新增100MiB下载额度", true); access.transientWindow = false;
        Task prepare = f.backend.next("prepare");
        check(prepare.request.getBoolean("new_download_round"), "lost ledger resets only through explicit confirmation");
        until(() -> { f.publish(); return !f.state().busy; }, "confirmed recovery round drains");
        JSONObject after = ledger(f);
        eq(0L, after.getLong("used")); eq(0L, after.getLong("reserved"));
        check(!after.getString("id").isEmpty() && !after.getString("id").equals(id), "fresh Go round after explicit confirmation");
        eq(used, after.getJSONObject("previous").getLong("used"));
        check(!f.state().ledgerBlocked, "confirmed round clears the blocked display");
        eq(attempts + 1, f.backend.startAttempts.get());
        check(f.state().activeDownload.isEmpty(), "recovery round never auto-downloads");
        eq(downloads, operationCount(f, "download")); // lost recovery dispatched no download
    }

    // ---------------------------------------------------------------- G10: ECH reuse and maintenance

    static void testSameSessionBatchesReuseEchQueueAndBudget(Fixture f) throws Exception {
        seedCandidates(f, 11);
        JSONObject fallback = fallbackEch(); f.backend.ech = fallback; f.backend.holdProbe = true;
        f.launch(); f.tab(1); PreferredIpUiRemainingTest.header(f);
        f.click(f.view(R.id.preferred_primary));
        Task prepare = f.backend.next("prepare");
        f.clockHook = () -> 1730000009000L; // Any accidental fresh startScan would re-stamp startedAt.
        Task first = f.backend.next("probe"); await(first.polled, "first natural batch poll");
        eq(8, first.request.getJSONArray("candidates").length());
        f.publish(); eqEch(f, fallback);
        JSONObject options = new JSONObject(first.request.getJSONObject("options").toString());
        long startedAt = storedStartedAt(f);
        JSONObject ledgerBefore = new JSONObject(ledger(f).toString());
        first.replies.add(f.backend.snapshot(first, "running", false, true, 0));
        until(() -> { f.publish(); return f.state().completed == 8 && f.state().busy; }, "first batch complete while the worker still owns the slot");
        first.replies.add(f.backend.snapshot(first, "completed", true, true, 0));
        Task second = f.backend.next("probe"); await(second.polled, "second natural batch poll");
        eq(3, second.request.getJSONArray("candidates").length());
        eq(first.sessionId, second.sessionId);
        eq(first.roundId, second.roundId);
        eq(prepare.sessionId, second.sessionId);
        eq(options.toString(), second.request.getJSONObject("options").toString());
        for (int n = 0; n < 3; n++) eq("104.16.2." + (n + 7) + ":443", second.request.getJSONArray("candidates").getJSONObject(n).getString("address"));
        f.publish(); eqEch(f, fallback);
        eq(startedAt, storedStartedAt(f)); eq(ledgerBefore.toString(), ledger(f).toString());
        second.replies.add(f.backend.snapshot(second, "running", false, true, 0));
        second.replies.add(f.backend.snapshot(second, "completed", true, true, 0));
        until(() -> { f.publish(); return !f.state().busy && f.state().completed == 11; }, "both batches drain in one session");
        eq(startedAt, storedStartedAt(f)); eq(ledgerBefore.toString(), ledger(f).toString()); eqEch(f, fallback);
        eq(3, f.backend.requests.size()); // one prepare plus exactly two probe batches.
        for (JSONObject request : f.backend.requests) {
            eq(prepare.sessionId, request.optString("session_id", prepare.sessionId));
            check(!"download".equals(request.getString("operation")), "reference replay never downloads");
        }
        check(f.backend.requests.get(0).getString("operation").equals("prepare"), "only the first request is prepare");
        check(f.backend.requests.get(1).getString("operation").equals("probe") && f.backend.requests.get(2).getString("operation").equals("probe"), "exactly two natural batches");
        f.backend.holdProbe = false;
    }

    static void testPrepareRequiredMaintenanceBoundedOnceAtTtlZero(Fixture f) throws Exception {
        f.launch(); f.tab(1); PreferredIpUiRemainingTest.header(f);
        PreferredIpTestControl.Gate prepareReturn = f.gates.create();
        f.backend.startReturn = prepareReturn; f.backend.holdStartAt = 1;
        f.click(f.view(R.id.preferred_primary));
        Task prepare = f.backend.next("prepare"); prepareReturn.awaitEnter();
        long startedAt = storedStartedAt(f);
        f.clockHook = () -> 1730000004000L; // A maintenance re-scan would re-stamp startedAt.
        PreferredIpTestControl.Gate maintenanceReturn = f.gates.create();
        f.backend.startReturn = maintenanceReturn; f.backend.holdStartAt = 3;
        f.backend.rejectStartWith = TTL_ZERO;
        prepareReturn.close();
        Task maintenance = f.backend.next("prepare"); maintenanceReturn.awaitEnter();
        JSONObject request = maintenance.request;
        eq(prepare.sessionId, request.getString("session_id"));
        eq(prepare.roundId, request.getString("download_round_id"));
        eq(0, request.getJSONArray("candidates").length());
        eq(0, request.getJSONArray("prefixes").length());
        check(!request.has("new_download_round"), "maintenance never moves the download round");
        f.backend.rejectStartWith = TTL_ZERO; // The single retry is rejected too: bounded stop.
        maintenanceReturn.close();
        until(() -> { f.publish(); return !f.state().busy && f.state().paused; }, "bounded maintenance stop");
        eq(4L, f.backend.startAttempts.get()); // prepare, rejected probe, maintenance, rejected retry.
        eq(2, f.backend.tasks.size()); // original prepare plus the one maintenance; probes were never admitted.
        int prepares = 0, probes = 0;
        for (JSONObject sent : f.backend.requests) { if ("prepare".equals(sent.getString("operation"))) prepares++; if ("probe".equals(sent.getString("operation"))) probes++; }
        eq(2, prepares); eq(0, probes);
        check(f.state().message.contains("ECH 配置无法复用"), "repeated TTL0 ends explicitly: " + f.state().message);
        JSONObject budget = ledger(f);
        eq(prepare.roundId, budget.getString("id")); eq(0L, budget.getLong("used")); eq(0L, budget.getLong("reserved"));
        eq(0, f.state().completed); eq(0, f.store.read().getJSONArray("selected").length());
        eq(startedAt, storedStartedAt(f));
        eq(false, f.store.read().getBoolean("enabled"));
    }

    static void testSuccessfulMaintenanceKeepsProofQueueAndStickyFallback(Fixture f) throws Exception {
        seedCandidates(f, 11);
        JSONObject fallback = fallbackEch(); f.backend.ech = fallback; f.backend.holdProbe = true;
        f.launch(); f.tab(1); PreferredIpUiRemainingTest.header(f);
        f.click(f.view(R.id.preferred_primary));
        Task prepare = f.backend.next("prepare");
        Task first = f.backend.next("probe"); await(first.polled, "first batch poll before real Pause");
        eq(8, first.request.getJSONArray("candidates").length());
        first.replies.add(f.backend.snapshot(first, "running", false, true, 0));
        until(() -> { f.publish(); return f.state().completed == 8 && f.state().busy; }, "first batch complete");
        PreferredIpUiRemainingTest.header(f); f.click(f.view(R.id.preferred_primary)); await(first.cancelled, "native Pause of the first batch");
        first.replies.add(f.backend.snapshot(first, "cancelled", true, true, 0));
        until(() -> { f.publish(); return !f.state().busy && f.state().paused; }, "first batch drain");
        f.backend.holdProbe = false; f.settle(); f.draw();
        f.show(X); eq(false, f.checked(X)); f.click(f.widget(X, R.id.preferred_choice)); f.settle(); f.publish(); f.draw();
        check(selected(f.store.read(), X), "real selection before maintenance");
        JSONObject xProof = resultOf(f, X), validations = f.store.read().getJSONObject("validations");
        JSONObject ledgerBefore = new JSONObject(ledger(f).toString());
        long startedAt = storedStartedAt(f);
        f.clockHook = () -> 1730000004000L; // A maintenance re-scan would re-stamp startedAt.
        f.backend.rejectStartWith = TTL_ZERO;
        long attempts = f.backend.startAttempts.get();
        PreferredIpUiRemainingTest.header(f); f.click(f.view(R.id.preferred_primary)); // Real Continue, rejected once with prepare_required.
        Task maintenance = f.backend.next("prepare");
        Task retry = f.backend.next("probe");
        until(() -> { f.publish(); return !f.state().busy && f.state().completed == 11; }, "successful maintenance continues only the remaining IPs");
        eq(attempts + 3, f.backend.startAttempts.get()); // rejected probe, maintenance, admitted retry.
        eq(prepare.sessionId, maintenance.sessionId); eq(maintenance.sessionId, retry.sessionId);
        eq(prepare.roundId, retry.roundId);
        eq(0, maintenance.request.getJSONArray("candidates").length());
        eq(0, maintenance.request.getJSONArray("prefixes").length());
        check(!maintenance.request.has("new_download_round"), "maintenance does not move the round");
        eq(3, retry.request.getJSONArray("candidates").length());
        for (int n = 0; n < 3; n++) eq("104.16.2." + (n + 7) + ":443", retry.request.getJSONArray("candidates").getJSONObject(n).getString("address"));
        eq(xProof.toString(), resultOf(f, X).toString());
        eq(validations.toString(), f.store.read().getJSONObject("validations").toString());
        eq(ledgerBefore.toString(), ledger(f).toString());
        eq(startedAt, storedStartedAt(f));
        eqEch(f, fallback);
        int prepares = 0, probes = 0;
        for (JSONObject sent : f.backend.requests) { if ("prepare".equals(sent.getString("operation"))) prepares++; if ("probe".equals(sent.getString("operation"))) probes++; }
        eq(2, prepares); // initial prepare plus exactly one maintenance; expires_at=0 never forces another refresh.
        eq(2, probes); // first batch plus the one remaining retry.
        for (JSONObject sent : f.backend.requests) check(!"download".equals(sent.getString("operation")), "ECH maintenance never downloads");
        check(f.state().paused, "successful maintenance still stops after the frozen queue drains");
    }
}
