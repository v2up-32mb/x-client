package com.x.client.app;

import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Worker-thread bounded readable event log. No raw snapshots and no repeated warning batches. */
final class PreferredIpLog {
    static final int MAX_LINES = 160, MAX_BYTES = 24576;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private int warningsSeen;
    private boolean terminalResultsLogged;
    private String task = "", progress = "", error = "", message = "";
    private int bytes;

    void add(String text) {
        if (text == null || text.isEmpty()) return;
        // External error/URL strings cannot bypass the line or byte bounds.
        for (String part : text.split("[\\r\\n]+")) {
            if (part.length() > 1000) part = part.substring(0, 1000) + "…";
            String line = DateFormat.getTimeInstance().format(new Date()) + " · " + part + "\n";
            lines.addLast(line);
            bytes += line.getBytes(StandardCharsets.UTF_8).length;
            while (lines.size() > MAX_LINES || bytes > MAX_BYTES) {
                bytes -= lines.removeFirst().getBytes(StandardCharsets.UTF_8).length;
            }
        }
    }

    void message(String text) {
        if (!message.equals(text)) { message = text; add(text); }
    }

    void snapshot(JSONObject snapshot) {
        String id = snapshot.optString("id");
        if (id.isEmpty()) return;
        if (!task.equals(id)) {
            task = id; progress = ""; error = ""; warningsSeen = 0; terminalResultsLogged = false;
            add("新任务：" + ("fetch".equals(snapshot.optString("operation")) ? "获取来源" : "HTTPS 实际目标测试"));
        }
        String next = PreferredIpPresentation.phase(snapshot.optString("phase")) + " · "
                + PreferredIpPresentation.state(snapshot.optString("state")) + " · "
                + snapshot.optInt("completed") + "/" + snapshot.optInt("total")
                + String.format(Locale.getDefault(), " · 下载正文 %.2f MiB", snapshot.optLong("download_bytes") / 1048576.0);
        if (!progress.equals(next)) { progress = next; add(next); }
        JSONArray values = snapshot.optJSONArray("warnings");
        for (int i = warningsSeen; values != null && i < values.length(); i++) {
            add("警告：" + values.optString(i));
        }
        if (values != null) warningsSeen = Math.max(warningsSeen, values.length());
        String state = snapshot.optString("state");
        if (!terminalResultsLogged && !"running".equals(state) && !"queued".equals(state)) {
            terminalResultsLogged = true;
            JSONArray results = snapshot.optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject result = results.optJSONObject(i);
                if (result != null && "failed".equals(result.optString("status"))) {
                    add("HTTPS 验证失败 " + result.optString("address") + "：" + result.optString("error"));
                }
            }
        }
        String failure = snapshot.optString("error");
        if (!failure.equals(error)) { error = failure; if (!failure.isEmpty()) add("错误：" + failure); }
    }

    String text() { return TextUtils.join("", lines); }
}
