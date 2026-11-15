package com.x.client.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.Iterator;
import java.util.Locale;

/** One precedence rule for testing, service starts and share snapshots: base -> XT JSON -> mode. */
public final class PreferredIpResolver {
    private PreferredIpResolver() { }

    public static JSONObject baseParams(Preferences p) throws Exception {
        JSONObject out = new JSONObject();
        if (Preferences.PROTOCOL_X_TUNNEL.equals(p.getProtocol())) {
            out.put("server_addr", p.getXtServerAddr()).put("token", p.getXtToken())
                    .put("relay_nodes", p.getXtRelayNodes()).put("connections", p.getXtConnections())
                    .put("enable_ech", !p.getXtDisableEch()).put("ech_domain", p.getEchDomain())
                    .put("dns_server", p.getEchDns()).put("insecure", p.getXtInsecure())
                    .put("ip_strategy", p.getXtIpStrategy()).put("enable_hot_pair", p.getXtEnableHotPair())
                    .put("hot_pair_count", p.getXtHotPairCount());
            mergeAdvanced(out, p);
        } else {
            String host = p.getWorkerHost().trim().replaceFirst("^(wss|https)://", "").replaceAll("/+$", "");
            out.put("worker_host", host).put("relay_ips", p.getPrefIp()).put("user_id", p.getUserID())
                    .put("proxy_ip", p.getFallbackIp()).put("ws_conn", p.getWsConn())
                    .put("enable_dynamic_pool", p.getEnableDynamicPool()).put("dynamic_pool_max", p.getDynamicPoolMax())
                    .put("enable_ech", !p.getDisableEch()).put("ech_domain", p.getEchDomain())
                    .put("ech_dns", p.getEchDns());
        }
        return out;
    }

    public static void mergeAdvanced(JSONObject out, Preferences p) throws Exception {
        if (p.getXtAdvancedParams().isEmpty()) return;
        JSONObject advanced = new JSONObject(p.getXtAdvancedParams());
        Iterator<String> keys = advanced.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            out.put(key, advanced.get(key));
        }
    }

    public static boolean conflict(Preferences p) throws Exception {
        return Preferences.PROTOCOL_X_TUNNEL.equals(p.getProtocol()) && !p.getXtAdvancedParams().isEmpty()
                && new JSONObject(p.getXtAdvancedParams()).has("relay_nodes");
    }

    /** Does not include credentials or download host. No token/user ID leaves the protocol backend. */
    public static JSONObject target(Preferences p, JSONObject merged) throws Exception {
        boolean xt = Preferences.PROTOCOL_X_TUNNEL.equals(p.getProtocol());
        String server = targetString(merged, xt ? "server_addr" : "worker_host").trim();
        URI uri = new URI(xt ? server : "https://" + server);
        if (uri.getHost() == null || (xt && !"wss".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("实际节点需要有效的TLS域名（XT必须wss://）");
        }
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("节点端口无效");
        return new JSONObject().put("target_host", uri.getHost().toLowerCase(Locale.ROOT))
                .put("target_port", port).put("protocol", xt ? "xtunnel" : "gcm")
                .put("enable_ech", merged.getBoolean("enable_ech"))
                .put("ech_domain", targetString(merged, "ech_domain"))
                .put("dns_server", targetString(merged, xt ? "dns_server" : "ech_dns"));
    }

    private static String targetString(JSONObject merged, String key) throws Exception {
        Object value = merged.get(key);
        // JSONObject.getString coerces objects/null/numbers and can hide invalid overrides.
        if (!(value instanceof String)) throw new IllegalArgumentException(key + " 必须是字符串");
        return (String) value;
    }

    /** Validate AFTER advanced merge, before probing or displaying channel multiplication. */
    static int validatedConnections(Preferences p, JSONObject merged) throws Exception {
        boolean xt = Preferences.PROTOCOL_X_TUNNEL.equals(p.getProtocol());
        String key = xt ? "connections" : "ws_conn";
        // Same bounds as ProfileEditActivity/Preferences; GCM's Go pool cap is also 64.
        int max = xt ? 16 : Preferences.MAX_DYNAMIC_POOL_LIMIT;
        Object value = merged.get(key);
        double count;
        if (value instanceof Number) {
            count = ((Number) value).doubleValue();
        } else if (value instanceof String) {
            // Go accepts integer strings, but NOT decimal strings; do not use getInt(),
            // which silently truncates fractional numbers/strings on Android.
            String text = ((String) value).trim();
            if (!text.matches("[+-]?[0-9]+")) throw new IllegalArgumentException(key + " 必须是整数");
            try { count = Integer.parseInt(text); }
            catch (NumberFormatException error) { throw new IllegalArgumentException(key + " 必须是 1–" + max + " 的整数", error); }
        } else {
            throw new IllegalArgumentException(key + " 必须是整数或整数字符串");
        }
        if (Double.isNaN(count) || Double.isInfinite(count) || count != Math.rint(count)) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
        if (count < 1 || count > max) throw new IllegalArgumentException(key + " 必须在 1–" + max + " 范围内");
        return (int) count;
    }

    public static String fingerprint(JSONObject target) throws Exception {
        // Explicit order, not JSONObject iteration order. Length delimiters are unambiguous.
        StringBuilder out = new StringBuilder();
        for (String key : new String[]{"protocol", "target_host", "target_port", "enable_ech", "ech_domain", "dns_server"}) {
            String value = String.valueOf(target.get(key));
            out.append(value.length()).append(':').append(value);
        }
        return out.toString();
    }

    public static String effectiveMode(Preferences p, JSONObject pool) throws Exception {
        if (conflict(p)) return "independent";
        return PreferredIpStore.mode(pool, p.getCurrentProfileId());
    }

    /** No valid target match means original values, never assume every XT server is on Cloudflare. */
    public static void apply(Preferences p, JSONObject merged, JSONObject pool) throws Exception {
        String key = Preferences.PROTOCOL_X_TUNNEL.equals(p.getProtocol()) ? "relay_nodes" : "relay_ips";
        String mode = effectiveMode(p, pool);
        merged.put(key, stripComments(merged.optString(key)));
        if ("default".equals(mode)) {
            merged.put(key, "");
            return;
        }
        if (!"global".equals(mode) || !pool.optBoolean("enabled")) return;
        JSONObject validations = pool.optJSONObject("validations");
        JSONObject valid = validations == null ? null : validations.optJSONObject(p.getCurrentProfileId());
        if (valid == null || !fingerprint(target(p, merged)).equals(valid.optString("target_key"))) return;
        JSONArray addresses = valid.optJSONArray("addresses");
        JSONArray selected = pool.optJSONArray("selected");
        if (addresses == null || selected == null) return;
        StringBuilder relay = new StringBuilder();
        for (int i = 0; i < selected.length(); i++) {
            String address = selected.getJSONObject(i).getString("address");
            for (int j = 0; j < addresses.length(); j++) {
                if (address.equals(addresses.getString(j))) {
                    if (relay.length() > 0) relay.append(',');
                    relay.append(address);
                    break;
                }
            }
        }
        if (relay.length() > 0) merged.put(key, relay.toString());
    }

    public static String stripComments(String value) {
        StringBuilder out = new StringBuilder();
        for (String item : value.split("[,\\r\\n]+")) {
            String address = item.split("#", 2)[0].trim();
            if (address.isEmpty()) continue;
            if (out.length() > 0) out.append(',');
            out.append(address);
        }
        return out.toString();
    }
}
