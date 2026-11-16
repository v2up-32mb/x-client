package com.x.client.app;

import org.json.JSONObject;
import java.util.UUID;

/** Durable reservation BEFORE Go Start. Uncertain accounting is never treated as a fresh budget. */
final class PreferredIpLedger {
    static final long LIMIT = 104857600L, PER_IP = 52428800L;
    private static final String PROCESS = UUID.randomUUID().toString();
    private final PreferredIpStore store;
    PreferredIpLedger(PreferredIpStore store) { this.store = store; }
    static boolean lost(JSONObject ledger) {
        return ledger != null && (!PROCESS.equals(ledger.optString("owner")) || ledger.optBoolean("uncertain")
                || ledger.optLong("reserved") != 0);
    }
    /** Called once for each explicit new scan / new round, not for ordinary maintenance. */
    String beforePrepare(boolean confirmedNewRound) throws Exception {
        JSONObject saved = store.update(root -> {
            JSONObject old = root.optJSONObject("download_ledger");
            if (old != null && lost(old) && !confirmedNewRound)
                throw new IllegalStateException("下载账本未结清/进程已更换；请明确确认新一轮100MiB，不会自动清账");
            if (old == null) {
                old = new JSONObject().put("id", "").put("used", 0).put("reserved", 0).put("owner", PROCESS);
                root.put("download_ledger", old);
            }
            if (confirmedNewRound) {
                // Preserve the old accounting until Go acknowledges the new round.
                JSONObject previous = new JSONObject(old.toString()); previous.remove("previous");
                boolean newProcess = !PROCESS.equals(old.optString("owner"));
                old.put("previous", previous).put("reset_pending", true);
                // Only an explicit user confirmation after process loss permits a fresh Go ledger.
                if (newProcess) old.put("id", "").put("owner", PROCESS);
            }
            old.put("preparing", true);
        });
        return saved.getJSONObject("download_ledger").getString("id");
    }
    void prepared(JSONObject snapshot, boolean newRound) throws Exception {
        store.update(root -> {
            JSONObject ledger = root.getJSONObject("download_ledger");
            String id = snapshot.getString("download_round_id");
            long used = snapshot.getLong("download_round_used");
            if (id.isEmpty() || snapshot.getLong("download_round_limit") != LIMIT
                    || snapshot.getLong("download_round_remaining") != LIMIT - used || used < 0 || used > LIMIT)
                throw new IllegalStateException("ledger_mismatch：准备账本不一致");
            if (newRound) {
                if (!ledger.optBoolean("reset_pending") || used != 0) throw new IllegalStateException("新下载轮未经确认/未清零");
            } else if ((!ledger.optString("id").isEmpty() && !id.equals(ledger.optString("id")))
                    || used != ledger.optLong("used") || lost(ledger)) {
                throw new IllegalStateException("ledger_mismatch：旧下载轮不能被覆盖");
            }
            ledger.put("id", id).put("used", used).put("reserved", 0).put("owner", PROCESS)
                    .put("uncertain", false).put("preparing", false).put("reset_pending", false);
        });
    }
    long reserve(String round, String address) throws Exception {
        JSONObject saved = store.update(root -> {
            JSONObject ledger = root.getJSONObject("download_ledger");
            if (lost(ledger) || !round.equals(ledger.optString("id")) || ledger.optBoolean("preparing"))
                throw new IllegalStateException("下载账本不一致/已有预留；需确认新轮");
            long amount = Math.min(PER_IP, LIMIT - ledger.getLong("used"));
            if (amount <= 0) throw new IllegalStateException("100MiB额度已耗尽，请明确开启新一轮");
            ledger.put("reserved", amount).put("address", address);
        });
        return saved.getJSONObject("download_ledger").getLong("reserved");
    }
    void settle(JSONObject snapshot) throws Exception {
        if (!snapshot.optBoolean("worker_done")) throw new IllegalStateException("下载尚未真实退出");
        store.update(root -> {
            JSONObject ledger = root.getJSONObject("download_ledger");
            long bytes = snapshot.getLong("download_bytes"), used = snapshot.getLong("download_round_used");
            if (!PROCESS.equals(ledger.optString("owner")) || !ledger.getString("id").equals(snapshot.getString("download_round_id"))
                    || ledger.getLong("reserved") != snapshot.getLong("download_reserved_bytes")
                    || bytes < 0 || bytes > ledger.getLong("reserved") || used != ledger.getLong("used") + bytes
                    || used > LIMIT || snapshot.getLong("download_round_remaining") != LIMIT - used
                    || snapshot.getLong("download_round_limit") != LIMIT)
                throw new IllegalStateException("ledger_mismatch：结算不一致，保留预留，禁止继续下载");
            ledger.put("used", used).put("reserved", 0); ledger.remove("address");
        });
    }
    /** Start rejected synchronously before returning any task id: no network worker was admitted. */
    void rejectedBeforeStart(String round) throws Exception {
        store.update(root -> {
            JSONObject ledger = root.getJSONObject("download_ledger");
            if (!round.equals(ledger.optString("id")) || !PROCESS.equals(ledger.optString("owner")))
                throw new IllegalStateException("ledger_mismatch：不能释放其它轮预留");
            ledger.put("reserved", 0); ledger.remove("address");
        });
    }
}
