package com.x.client.app;

import org.json.JSONObject;

/** In-memory tracking of cumulative manual download traffic. Destroyed when PreferredIpController is disposed. */
final class PreferredIpLedger {
    static final long PER_IP = 52428800L;
    private String id = "";
    private long used = 0;
    private long reserved = 0;
    private String address = "";

    PreferredIpLedger() { }

    synchronized String beforePrepare(boolean confirmedNewRound) {
        if (confirmedNewRound) {
            used = 0;
            id = "";
        }
        return id;
    }

    synchronized void prepared(JSONObject snapshot, boolean newRound) {
        if (snapshot != null) {
            id = snapshot.optString("download_round_id", id);
            used = snapshot.optLong("download_round_used", used);
        }
        reserved = 0;
    }

    synchronized long reserve(String round, String address) {
        this.reserved = PER_IP;
        this.address = address;
        return PER_IP;
    }

    synchronized void settle(JSONObject snapshot) {
        if (snapshot != null) {
            used = snapshot.optLong("download_round_used", used);
        }
        reserved = 0;
        address = "";
    }

    synchronized void rejectedBeforeStart(String round) {
        reserved = 0;
        address = "";
    }

    synchronized long getUsed() { return used; }
    synchronized long getReserved() { return reserved; }
    synchronized String getId() { return id; }
}
