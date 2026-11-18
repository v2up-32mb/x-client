package com.x.client.app;

import org.json.JSONObject;
import java.util.UUID;

/** Durable reservation BEFORE Go Start. Tracks cumulative download traffic without artificial budget caps. */
final class PreferredIpLedger {
    static final long PER_IP = 52428800L;
    private static final String PROCESS = UUID.randomUUID().toString();
    private final PreferredIpStore store;
    PreferredIpLedger(PreferredIpStore store) { this.store = store; }
    /** Called once for each explicit new scan / reset, not for ordinary maintenance. */
    String beforePrepare(boolean confirmedNewRound) throws Exception {
        JSONObject saved = store.update(root -> {
            JSONObject old = root.optJSONObject("download_ledger");
            if (old == null) {
                old = new JSONObject().put("id", "").put("used", 0).put("reserved", 0).put("owner", PROCESS);
                root.put("download_ledger", old);
            }
            if (confirmedNewRound) {
                JSONObject previous = new JSONObject(old.toString()); previous.remove("previous");
                old.put("previous", previous).put("used", 0).put("reset_pending", true).put("id", "").put("owner", PROCESS);
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
            ledger.put("id", id).put("used", used).put("reserved", 0).put("owner", PROCESS)
                    .put("uncertain", false).put("preparing", false).put("reset_pending", false);
        });
    }
    long reserve(String round, String address) throws Exception {
        JSONObject saved = store.update(root -> {
            JSONObject ledger = root.getJSONObject("download_ledger");
            long amount = PER_IP;
            ledger.put("reserved", amount).put("address", address);
        });
        return saved.getJSONObject("download_ledger").getLong("reserved");
    }
    void settle(JSONObject snapshot) throws Exception {
        if (!snapshot.optBoolean("worker_done")) throw new IllegalStateException("下载尚未真实退出");
        store.update(root -> {
            JSONObject ledger = root.getJSONObject("download_ledger");
            long used = snapshot.getLong("download_round_used");
            ledger.put("used", used).put("reserved", 0); ledger.remove("address");
        });
    }
    /** Start rejected synchronously before returning any task id: no network worker was admitted. */
    void rejectedBeforeStart(String round) throws Exception {
        store.update(root -> {
            JSONObject ledger = root.getJSONObject("download_ledger");
            ledger.put("reserved", 0); ledger.remove("address");
        });
    }
}
