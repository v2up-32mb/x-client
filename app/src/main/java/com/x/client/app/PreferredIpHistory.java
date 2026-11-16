package com.x.client.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;

/** Separate bounded history; never part of a checkbox transaction. API24 recovery is locked too. */
final class PreferredIpHistory {
    private static final Object LOCK = new Object();
    private static final int LIMIT = 16 * 1024 * 1024;
    private final File directory;
    PreferredIpHistory(Context context) { directory = context.getFilesDir(); }
    private AtomicFile file(boolean complete) {
        return new AtomicFile(new File(directory, complete ? "preferred-ip-complete.json" : "preferred-ip-checkpoint.json"));
    }
    void write(JSONObject history, boolean complete) throws Exception {
        byte[] bytes = publicHistory(history).toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("历史未完整保存：超过16MiB；已选池不受影响");
        synchronized (LOCK) {
            try (RandomAccessFile lock = new RandomAccessFile(new File(directory, "preferred-ip-history.lock"), "rw");
                 FileLock held = lock.getChannel().lock()) {
                AtomicFile file = file(complete);
                FileOutputStream stream = null;
                try {
                    stream = file.startWrite(); stream.write(bytes); stream.getFD().sync();
                    file.finishWrite(stream); stream = null;
                } finally { if (stream != null) file.failWrite(stream); }
            }
        }
    }
    JSONObject read(boolean complete) throws Exception {
        synchronized (LOCK) {
            try (RandomAccessFile lock = new RandomAccessFile(new File(directory, "preferred-ip-history.lock"), "rw");
                 FileLock held = lock.getChannel().lock()) {
                AtomicFile file = file(complete);
                if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return new JSONObject();
                try (InputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] b = new byte[8192]; int n;
                    while ((n = in.read(b)) != -1) {
                        if (out.size() + n > LIMIT) throw new IOException("历史超过16MiB");
                        out.write(b, 0, n);
                    }
                    return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
                }
            }
        }
    }
    /** Explicit public-only whitelist also sanitizes the legacy last_test migration. */
    static JSONObject publicHistory(JSONObject input) throws Exception {
        JSONObject out = new JSONObject();
        for (String key : new String[]{"session_id", "network_id", "context_key", "reference_id", "target_key",
                "started_at", "updated_at", "state", "score_version"}) if (input.has(key)) out.put(key, input.get(key));
        JSONObject notes = input.optJSONObject("download_notes");
        if (notes != null) {
            if (notes.length() > 4096) throw new IOException("历史下载说明超过4096条");
            JSONObject safeNotes = new JSONObject(); java.util.Iterator<String> keys = notes.keys();
            while (keys.hasNext()) { String key = keys.next(); if (notes.opt(key) instanceof String) safeNotes.put(key, notes.getString(key)); }
            out.put("download_notes", safeNotes);
        }
        JSONObject target = input.optJSONObject("test_target");
        if (target != null) {
            JSONObject safe = new JSONObject();
            for (String key : new String[]{"protocol", "target_host", "target_port", "enable_ech", "ech_domain", "dns_server"})
                if (target.has(key)) safe.put(key, target.get(key));
            out.put("test_target", safe);
        }
        for (String group : new String[]{"candidates", "results"}) {
            JSONArray source = input.optJSONArray(group), rows = new JSONArray();
            if (source != null && source.length() > 4096) throw new IOException("历史未完整保存：超过4096条");
            for (int i = 0; source != null && i < source.length(); i++) {
                JSONObject row = source.getJSONObject(i), safe = candidate(row);
                if ("results".equals(group)) for (String key : new String[]{"status", "error", "colo", "tcp_success", "tcp_attempts",
                        "tcp_min_ms", "tcp_max_ms", "tcp_avg_ms", "tls_ms", "http_success", "http_attempts", "http_median_ms",
                        "http_jitter_ms", "ech_status", "tested_at", "probe_complete", "score_ready", "score_version", "score",
                        "score_breakdown", "probe_phase", "na_reason", "result_kind", "target_key", "tcp_measured", "http_measured",
                        "download_bytes", "download_seconds", "download_mib_s", "download_peak_mib_s", "download_state", "download_ech_status"})
                    if (row.has(key)) safe.put(key, row.get(key));
                rows.put(safe);
            }
            out.put(group, rows);
        }
        return out;
    }
    static JSONObject candidate(JSONObject row) throws Exception {
        JSONObject out = new JSONObject();
        for (String key : new String[]{"address", "ip", "port", "comment", "source_id", "source_kind", "source_ids", "source_kinds"})
            if (row.has(key)) out.put(key, row.get(key));
        return out;
    }
}
