package com.x.client.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Presentation only. Opaque source IDs and parsed comments are never rewritten. */
final class PreferredIpPresentation {
    private PreferredIpPresentation() { }

    static String source(JSONObject source) {
        String url = source.optString("url");
        return url.isEmpty() ? fallback(source.optString("id"), source.optString("kind")) : url;
    }

    private static String fallback(String id, String kind) {
        if ("cf-v4".equals(id)) return "https://www.cloudflare.com/ips-v4";
        if ("cf-v6".equals(id)) return "https://www.cloudflare.com/ips-v6";
        if ("manual".equals(id)) return "手动粘贴";
        if ("txt".equals(id)) return "手动 TXT";
        if ("legacy".equals(id)) return "旧节点入口";
        if ("list".equals(kind) || "third_party".equals(kind) || id.startsWith("list")) return "第三方列表（原 URL 映射已缺失）";
        return "来源记录已缺失（无法确认原 URL）";
    }

    static boolean hasKind(JSONObject row, String kind) {
        if (kind.equals(row.optString("source_kind"))) return true;
        JSONArray kinds = row.optJSONArray("source_kinds");
        for (int i = 0; kinds != null && i < kinds.length(); i++) if (kind.equals(kinds.optString(i))) return true;
        return false;
    }

    static String sources(JSONObject row, JSONObject data) {
        Set<String> ids = new LinkedHashSet<>(), labels = new LinkedHashSet<>();
        ids.add(row.optString("source_id"));
        JSONArray more = row.optJSONArray("source_ids"), sources = data.optJSONArray("sources");
        for (int i = 0; more != null && i < more.length(); i++) ids.add(more.optString(i));
        for (String id : ids) {
            if (id.isEmpty()) continue;
            JSONObject found = null;
            for (int i = 0; sources != null && i < sources.length(); i++) {
                JSONObject s = sources.optJSONObject(i);
                if (s != null && id.equals(s.optString("id"))) { found = s; break; }
            }
            labels.add(found == null ? fallback(id, row.optString("source_kind")) : source(found));
        }
        return labels.isEmpty() ? "来源未知" : String.join("\n", labels);
    }

    static String comment(JSONObject row, JSONObject data) {
        if (!hasKind(row, "third_party")) return row.optString("comment");
        // A merged comment has no fragment provenance. Recover ONLY pure manual source records;
        // never split a merged third-party string heuristically and accidentally expose it.
        Set<String> notes = new LinkedHashSet<>();
        JSONArray sources = data.optJSONArray("sources");
        for (int i = 0; sources != null && i < sources.length(); i++) {
            JSONArray candidates = sources.optJSONObject(i).optJSONArray("candidates");
            for (int j = 0; candidates != null && j < candidates.length(); j++) {
                JSONObject c = candidates.optJSONObject(j);
                if (c != null && row.optString("address").equals(c.optString("address"))
                        && hasKind(c, "manual") && !hasKind(c, "third_party") && !c.optString("comment").isEmpty()) {
                    notes.add(c.optString("comment"));
                }
            }
        }
        return String.join(" / ", notes);
    }

    static JSONObject find(JSONArray rows, String address) {
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && address.equals(row.optString("address"))) return row;
        }
        return null;
    }

    static String target(JSONObject target) {
        if (target == null) return "实际目标未解析";
        return target.optString("protocol") + " · " + target.optString("target_host") + ":" + target.optInt("target_port")
                + " · ECH " + target.optBoolean("enable_ech") + "\nECH 域名 " + target.optString("ech_domain")
                + " · DNS " + target.optString("dns_server");
    }

    static String phase(String value) {
        switch (value) {
            case "fetch": return "抓取来源";
            case "connectivity": return "HTTPS 验证 / TLS 证书 / CF trace";
            case "stability": return "10次 TCP 稳定性";
            case "latency": return "5次 HTTPS 请求延迟";
            case "download": return "HTTPS 顺序下载";
            case "done": return "结束";
            default: return value;
        }
    }

    static String state(String value) {
        switch (value) {
            case "queued": return "排队";
            case "running": return "运行中";
            case "completed": return "完成";
            case "cancelled": return "已取消";
            case "failed": return "失败";
            default: return value;
        }
    }
}
