/*
 ============================================================================
 Name        : ProfileUriCodec.java
 Author      : Antigravity
 Description : Unified Parser and Serializer for GCM and X-Tunnel URIs
 ============================================================================
 */

package com.x.client.app;

import android.net.Uri;
import org.json.JSONObject;
import java.util.Locale;

public final class ProfileUriCodec {

    private ProfileUriCodec() {}

    public static class ParsedProfile {
        public String protocol = Preferences.PROTOCOL_GCM;
        public String name = "";
        public String serverAddr = "";
        public String prefIp = "";
        public String fallbackIp = "";
        public String userId = "";
        public boolean disableEch = false;
        public int wsConn = Preferences.DEFAULT_WS_CONN;
        public boolean enableDynamicPool = false;
        public int dynamicPoolMax = Preferences.DEFAULT_DYNAMIC_POOL_MAX;

        // X-Tunnel
        public String xtToken = "";
        public String xtRelayNodes = "";
        public int xtConnections = Preferences.DEFAULT_XT_CONNECTIONS;
        public boolean xtDisableEch = false;
        public boolean xtInsecure = false;
        public boolean xtEnableHotPair = false;
        public int xtHotPairCount = Preferences.DEFAULT_XT_HOT_PAIR_COUNT;
        public String xtIpStrategy = Preferences.DEFAULT_XT_IP_STRATEGY;

        // SOCKS5
        public String s5Host = "";
        public int s5Port = Preferences.DEFAULT_S5_PORT;
        public String s5Username = "";
        public String s5Password = "";

        public boolean isXtunnel() {
            return Preferences.PROTOCOL_X_TUNNEL.equals(protocol);
        }

        public boolean isSocks5() {
            return Preferences.PROTOCOL_SOCKS5.equals(protocol);
        }
    }

    public static boolean isSupportedUri(String uri) {
        if (uri == null) return false;
        String s = uri.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("xtunnel://") || s.startsWith("gcm://")
                || s.startsWith("ech://") || s.startsWith("socks5://")
                || s.startsWith("socks://");
    }

    public static ParsedProfile parse(String protocol) throws IllegalArgumentException {
        if (!isSupportedUri(protocol)) {
            throw new IllegalArgumentException("Unsupported protocol format");
        }
        String trimmed = protocol.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("socks5://") || lower.startsWith("socks://")) {
            return parseSocks5(trimmed, lower.startsWith("socks5://") ? 9 : 8);
        }
        boolean isXtunnel = lower.startsWith("xtunnel://");
        String rest = isXtunnel ? trimmed.substring(10) : trimmed.substring(6);

        ParsedProfile res = new ParsedProfile();
        res.protocol = isXtunnel ? Preferences.PROTOCOL_X_TUNNEL : Preferences.PROTOCOL_GCM;

        // 分离 fragment（配置名称）
        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }

        String wssAddr;
        String query = "";
        int qmark = rest.indexOf('?');
        if (qmark >= 0) {
            wssAddr = rest.substring(0, qmark);
            query = rest.substring(qmark + 1);
        } else {
            wssAddr = rest;
        }

        // 容错：剥离 host 前的多余斜杠
        while (wssAddr.startsWith("/")) {
            wssAddr = wssAddr.substring(1);
        }
        // 确保 wss:// 前缀
        if (!wssAddr.startsWith("wss://")) {
            wssAddr = "wss://" + wssAddr;
        }
        if (wssAddr.length() <= 6) {
            throw new IllegalArgumentException("Missing server host in URI");
        }
        res.serverAddr = wssAddr;

        if (!query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0];
                    String value = kv[1];
                    switch (key) {
                        case "ip":
                        case "relay":
                            res.prefIp = value;
                            break;
                        case "fip":
                        case "fallbackip":
                            res.fallbackIp = value;
                            break;
                        case "disable_ech":
                            res.disableEch = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                            break;
                        case "ws_conn":
                            try {
                                res.wsConn = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {}
                            break;
                        case "enable_dynamic_pool":
                            res.enableDynamicPool = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                            break;
                        case "dynamic_pool_max":
                            try {
                                res.dynamicPoolMax = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {}
                            break;
                        case "token":
                        case "user_id":
                            if (isXtunnel) {
                                res.xtToken = value;
                            } else {
                                res.userId = value;
                            }
                            break;
                        case "relay_nodes":
                            res.xtRelayNodes = value;
                            break;
                        case "connections":
                            try {
                                res.xtConnections = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {}
                            break;
                        case "ech":
                            res.xtDisableEch = value.equals("0") || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("no");
                            break;
                        case "insecure":
                            res.xtInsecure = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                            break;
                        case "hotpair":
                            int hotPairValue = 1;
                            try {
                                hotPairValue = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {}
                            if (hotPairValue >= 2 && hotPairValue <= Preferences.MAX_XT_HOT_PAIR_COUNT) {
                                res.xtEnableHotPair = true;
                                res.xtHotPairCount = hotPairValue;
                            } else {
                                res.xtEnableHotPair = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                                res.xtHotPairCount = 1;
                            }
                            break;
                        case "ips":
                            if (Preferences.isValidXtIpStrategy(value)) {
                                res.xtIpStrategy = value;
                            }
                            break;
                    }
                }
            }
        }

        if (!fragment.isEmpty()) {
            res.name = urlDecode(fragment);
        }
        if (res.name == null || res.name.trim().isEmpty()) {
            res.name = "导入节点";
        }

        return res;
    }

    private static ParsedProfile parseSocks5(String trimmed, int schemeLen) {
        String rest = trimmed.substring(schemeLen);
        ParsedProfile res = new ParsedProfile();
        res.protocol = Preferences.PROTOCOL_SOCKS5;

        // 分离 #fragment（配置名称）
        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }

        // 剥离可选 ?query
        int qmark = rest.indexOf('?');
        if (qmark >= 0) {
            rest = rest.substring(0, qmark);
        }

        // 容错：剥离 host 前的多余斜杠
        while (rest.startsWith("/")) {
            rest = rest.substring(1);
        }

        // 先剥离可选 /path，避免路径中的 '@' 被误判为 userinfo 分隔符
        int pathSlash = rest.indexOf('/');
        if (pathSlash >= 0) {
            rest = rest.substring(0, pathSlash);
        }

        // 解析 [userinfo@]hostport
        String hostPort = rest;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            String userInfo = rest.substring(0, at);
            hostPort = rest.substring(at + 1);
            if (!userInfo.isEmpty()) {
                String[] creds = userInfo.split(":", 2);
                res.s5Username = urlDecode(creds[0]).trim();
                if (creds.length > 1) {
                    res.s5Password = urlDecode(creds[1]);
                }
            }
        }
        if (res.s5Username.isEmpty() && !res.s5Password.isEmpty()) {
            throw new IllegalArgumentException("Password requires username in URI");
        }

        hostPort = hostPort.trim();
        if (hostPort.isEmpty()) {
            throw new IllegalArgumentException("Missing server host in URI");
        }

        String host;
        int port = Preferences.DEFAULT_S5_PORT;
        if (hostPort.startsWith("[")) {
            int closeBracket = hostPort.indexOf(']');
            if (closeBracket < 0) {
                throw new IllegalArgumentException("Invalid IPv6 host in URI");
            }
            host = hostPort.substring(1, closeBracket).trim();
            String after = hostPort.substring(closeBracket + 1).trim();
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) {
                    throw new IllegalArgumentException("Invalid host/port in URI");
                }
                port = parseSocks5Port(after.substring(1).trim());
            }
        } else {
            int firstColon = hostPort.indexOf(':');
            int lastColon = hostPort.lastIndexOf(':');
            if (firstColon < 0) {
                host = hostPort;
            } else if (firstColon == lastColon) {
                host = hostPort.substring(0, firstColon).trim();
                port = parseSocks5Port(hostPort.substring(firstColon + 1).trim());
            } else {
                // 裸 IPv6 地址（未加方括号）
                host = hostPort;
            }
        }

        if (host.isEmpty()) {
            throw new IllegalArgumentException("Missing server host in URI");
        }
        if (host.indexOf('[') != -1 || host.indexOf(']') != -1) {
            throw new IllegalArgumentException("Invalid server host in URI");
        }
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) {
                throw new IllegalArgumentException("Invalid server host in URI");
            }
        }

        res.s5Host = host;
        res.s5Port = port;
        if (!fragment.isEmpty()) {
            res.name = urlDecode(fragment);
        }
        if (res.name == null || res.name.trim().isEmpty()) {
            res.name = "导入节点";
        }
        return res;
    }

    private static int parseSocks5Port(String portStr) {
        if (portStr.isEmpty()) {
            throw new IllegalArgumentException("Invalid port in URI");
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in URI: " + portStr);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port in URI: " + portStr);
        }
        return port;
    }

    private static String urlDecode(String value) {
        if (value == null) {
            return "";
        }
        return Uri.decode(value);
    }

    private static String urlEncode(String value) {
        return Uri.encode(value != null ? value : "");
    }

    public static void applyToProfile(Preferences profilePrefs, ParsedProfile p) {
        profilePrefs.setProtocol(p.protocol);
        if (p.isSocks5()) {
            profilePrefs.setS5Host(p.s5Host);
            profilePrefs.setS5Port(p.s5Port);
            profilePrefs.setS5Username(p.s5Username);
            profilePrefs.setS5Password(p.s5Password);
        } else if (p.isXtunnel()) {
            profilePrefs.setXtServerAddr(p.serverAddr);
            profilePrefs.setXtToken(p.xtToken);
            profilePrefs.setXtRelayNodes(p.xtRelayNodes);
            profilePrefs.setXtConnections(p.xtConnections);
            profilePrefs.setXtDisableEch(p.xtDisableEch);
            profilePrefs.setXtInsecure(p.xtInsecure);
            profilePrefs.setXtEnableHotPair(p.xtEnableHotPair);
            profilePrefs.setXtHotPairCount(p.xtHotPairCount);
            profilePrefs.setXtIpStrategy(p.xtIpStrategy);
        } else {
            profilePrefs.setWorkerHost(p.serverAddr);
            profilePrefs.setPrefIp(p.prefIp);
            if (!p.fallbackIp.isEmpty()) profilePrefs.setFallbackIp(p.fallbackIp);
            profilePrefs.setUserID(p.userId);
            profilePrefs.setDisableEch(p.disableEch);
            profilePrefs.setWsConn(p.wsConn);
            profilePrefs.setEnableDynamicPool(p.enableDynamicPool);
            profilePrefs.setDynamicPoolMax(p.dynamicPoolMax);
        }
    }

    public static String exportUri(Preferences profilePrefs, String profileId, JSONObject effective) {
        String protocolValue = profilePrefs.getProtocol();
        String encodedName = urlEncode(profilePrefs.getProfileName(profileId));

        if (Preferences.PROTOCOL_SOCKS5.equals(protocolValue)) {
            String host = profilePrefs.getS5Host().trim();
            int port = profilePrefs.getS5Port();
            String username = profilePrefs.getS5Username();
            String password = profilePrefs.getS5Password();

            StringBuilder uri = new StringBuilder("socks5://");
            if (username != null && !username.isEmpty()) {
                uri.append(urlEncode(username));
                if (password != null && !password.isEmpty()) {
                    uri.append(":").append(urlEncode(password));
                }
                uri.append("@");
            }
            uri.append(Preferences.formatHostPort(host, port));
            uri.append("#").append(encodedName);
            return uri.toString();
        } else if (Preferences.PROTOCOL_X_TUNNEL.equals(protocolValue)) {
            String xtToken = effective.optString("token");
            String xtRelayNodes = effective.optString("relay_nodes");
            int xtConnections = effective.optInt("connections", Preferences.DEFAULT_XT_CONNECTIONS);
            boolean xtDisableEch = !effective.optBoolean("enable_ech", true);
            boolean xtInsecure = effective.optBoolean("insecure");
            boolean xtEnableHotPair = effective.optBoolean("enable_hot_pair");
            int xtHotPairCount = effective.optInt("hot_pair_count", Preferences.DEFAULT_XT_HOT_PAIR_COUNT);
            String xtIpStrategy = effective.optString("ip_strategy", Preferences.DEFAULT_XT_IP_STRATEGY);
            String xtServerAddr = effective.optString("server_addr");
            if (xtServerAddr.startsWith("wss://")) {
                xtServerAddr = xtServerAddr.substring(6);
            } else if (xtServerAddr.startsWith("ws://")) {
                xtServerAddr = xtServerAddr.substring(5);
            }

            StringBuilder xtQuery = new StringBuilder();
            if (!xtToken.isEmpty()) {
                xtQuery.append("token=").append(xtToken);
            }
            if (!xtRelayNodes.isEmpty()) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("relay_nodes=").append(xtRelayNodes);
            }
            if (xtConnections != Preferences.DEFAULT_XT_CONNECTIONS) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("connections=").append(xtConnections);
            }
            if (xtDisableEch) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("ech=0");
            }
            if (xtInsecure) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("insecure=1");
            }
            if (xtEnableHotPair) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("hotpair=").append(xtHotPairCount);
            }
            if (!Preferences.DEFAULT_XT_IP_STRATEGY.equals(xtIpStrategy)) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("ips=").append(xtIpStrategy);
            }

            String uri = "xtunnel://" + xtServerAddr;
            if (xtQuery.length() > 0) {
                uri += "?" + xtQuery.toString();
            }
            return uri + "#" + encodedName;
        } else {
            String wssAddr = profilePrefs.getWorkerHost();
            if (wssAddr.startsWith("wss://")) {
                wssAddr = wssAddr.substring(6);
            }
            String prefIp = effective.optString("relay_ips");
            String fallbackIp = profilePrefs.getFallbackIp();
            String userId = profilePrefs.getUserID();
            boolean disableEch = profilePrefs.getDisableEch();
            int wsConn = profilePrefs.getWsConn();
            boolean enableDynamicPool = profilePrefs.getEnableDynamicPool();
            int dynamicPoolMax = profilePrefs.getDynamicPoolMax();

            StringBuilder query = new StringBuilder();
            if (!prefIp.isEmpty()) {
                query.append("ip=").append(prefIp);
            }
            if (!fallbackIp.isEmpty()) {
                if (query.length() > 0) query.append("&");
                query.append("fip=").append(fallbackIp);
            }
            if (!userId.isEmpty()) {
                if (query.length() > 0) query.append("&");
                query.append("user_id=").append(userId);
            }
            if (disableEch) {
                if (query.length() > 0) query.append("&");
                query.append("disable_ech=1");
            }
            if (query.length() > 0) query.append("&");
            query.append("ws_conn=").append(wsConn);
            query.append("&enable_dynamic_pool=").append(enableDynamicPool ? 1 : 0);
            query.append("&dynamic_pool_max=").append(dynamicPoolMax);

            String uri = "gcm://" + wssAddr;
            if (query.length() > 0) {
                uri += "?" + query.toString();
            }
            return uri + "#" + encodedName;
        }
    }
}
