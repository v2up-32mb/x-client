/*
 ============================================================================
 Name        : Perferences.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 xyz
 Description : Perferences
 ============================================================================
 */

package com.x.client.app;

import java.util.Set;
import java.util.HashSet;
import android.content.Context;
import android.content.SharedPreferences;

public class Preferences
{
        public static final String PREFS_NAME = "SocksPrefs";
        public static final String SOCKS_ADDR = "SocksAddr";
        public static final String SOCKS_UDP_ADDR = "SocksUdpAddr";
        public static final String SOCKS_PORT = "SocksPort";
        public static final String SOCKS_USER = "SocksUser";
        public static final String SOCKS_PASS = "SocksPass";
        public static final String DNS_IPV4 = "DnsIpv4";
        public static final String DNS_IPV6 = "DnsIpv6";
        public static final String IPV4 = "Ipv4";
        public static final String IPV6 = "Ipv6";
        public static final String GLOBAL = "Global";
        public static final String UDP_IN_TCP = "UdpInTcp";
        public static final String REMOTE_DNS = "RemoteDNS";
        public static final String APPS = "Apps";
        public static final String ENABLE = "Enable";
        public static final String THEME_MODE = "ThemeMode";
        public static final int THEME_SYSTEM = 0;
        public static final int THEME_LIGHT = 1;
        public static final int THEME_DARK = 2;
        public static final String BYPASS_PRIVATE = "BypassPrivate";
        public static final String BYPASS_GEOIP_CN = "BypassGeoIpCn";
        public static final String BYPASS_GEOSITE_CN = "BypassGeoSiteCn";
        public static final String BYPASS_RULES = "BypassRules";

        // GCM tunnel 相关参数
        public static final String WORKER_HOST = "WorkerHost";
        public static final String PREF_IP = "PrefIp";
        public static final String FALLBACK_IP = "FallbackIp";
        public static final String USER_ID = "UserId";
        public static final String ECH_DNS = "EchDns";
        public static final String ECH_DOMAIN = "EchDomain";
        public static final String DISABLE_ECH = "DisableEch";
        public static final String ENABLE_DNS_WARMUP = "EnableDnsWarmup";
        public static final String WS_CONN = "WsConn";
        public static final String ENABLE_DYNAMIC_POOL = "EnableDynamicPool";
        public static final String DYNAMIC_POOL_MAX = "DynamicPoolMax";
        public static final int DEFAULT_WS_CONN = 3;
        public static final int DEFAULT_DYNAMIC_POOL_MAX = 16;
        public static final int MAX_DYNAMIC_POOL_LIMIT = 64;
        // IPv6 路由禁用
        public static final String DISABLE_IPV6_ROUTE = "DisableIpv6Route";
        
        // Profile Management
        public static final String CURRENT_PROFILE_ID = "CurrentProfileId";
        public static final String PROFILES = "Profiles"; // Set<String> of profile IDs
        public static final String PROFILE_NAME_PREFIX = "ProfileName_";

        private SharedPreferences prefs;
        private String currentProfileId; // Current Profile ID (e.g., "default", "uuid...")

        public Preferences(Context context) {
                prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
                currentProfileId = prefs.getString(CURRENT_PROFILE_ID, null);
                migrateGlobalNetworkSettings();
        }

        private void migrateGlobalNetworkSettings() {
                if (currentProfileId == null) {
                        return;
                }

                String suffix = "_" + currentProfileId;
                SharedPreferences.Editor editor = prefs.edit();
                boolean changed = false;
                if (!prefs.contains(ECH_DNS) && prefs.contains(ECH_DNS + suffix)) {
                        editor.putString(ECH_DNS, prefs.getString(ECH_DNS + suffix, ""));
                        changed = true;
                }
                if (!prefs.contains(ECH_DOMAIN) && prefs.contains(ECH_DOMAIN + suffix)) {
                        editor.putString(ECH_DOMAIN, prefs.getString(ECH_DOMAIN + suffix, ""));
                        changed = true;
                }
                if (!prefs.contains(WS_CONN) && prefs.contains(WS_CONN + suffix)) {
                        editor.putInt(WS_CONN, prefs.getInt(WS_CONN + suffix, DEFAULT_WS_CONN));
                        changed = true;
                }
                if (!prefs.contains(ENABLE_DNS_WARMUP) && prefs.contains(ENABLE_DNS_WARMUP + suffix)) {
                        editor.putBoolean(ENABLE_DNS_WARMUP, prefs.getBoolean(ENABLE_DNS_WARMUP + suffix, false));
                        changed = true;
                }
                if (changed) {
                        editor.commit();
                }
        }

        // Helper to get key with profile suffix
        private String getKey(String key) {
            if (currentProfileId == null) {
                return key;
            }
            return key + "_" + currentProfileId;
        }

        public Set<String> getProfileIds() {
            return prefs.getStringSet(PROFILES, new HashSet<String>());
        }
        
        public String getCurrentProfileId() {
            return currentProfileId;
        }

        public void setCurrentProfileId(String id) {
            this.currentProfileId = id;
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(CURRENT_PROFILE_ID, id);
            editor.commit();
        }

        public String getProfileName(String id) {
            return prefs.getString(PROFILE_NAME_PREFIX + id, "Node " + id);
        }

        public void setProfileName(String id, String name) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PROFILE_NAME_PREFIX + id, name);
            editor.commit();
        }

        public void addProfile(String id, String name) {
            Set<String> profiles = new HashSet<>(getProfileIds());
            profiles.add(id);
            
            SharedPreferences.Editor editor = prefs.edit();
            editor.putStringSet(PROFILES, profiles);
            editor.putString(PROFILE_NAME_PREFIX + id, name);
            editor.commit();
        }

        public void removeProfile(String id) {
            // Logic moved to MainActivity to check size before calling or allow calling freely if size > 1
            // Here we just remove whatever ID is passed, assuming upper layer checked constraints.
            // Previously: if ("default".equals(id)) return; 
            // Now: Allow deleting default if it's not the last one (handled by caller)

            Set<String> profiles = new HashSet<>(getProfileIds());
            if (!profiles.contains(id)) return;

            profiles.remove(id);
            
            SharedPreferences.Editor editor = prefs.edit();
            editor.putStringSet(PROFILES, profiles);
            editor.remove(PROFILE_NAME_PREFIX + id);
            
            // Clean up all keys for this profile
            String[] keys = {WORKER_HOST, PREF_IP, FALLBACK_IP, USER_ID, ECH_DNS, ECH_DOMAIN, DISABLE_ECH, ENABLE_DNS_WARMUP, WS_CONN, DISABLE_IPV6_ROUTE};
            for (String k : keys) {
                editor.remove(k + "_" + id);
            }
            editor.commit();
        }

        public String getSocksAddress() {
                return "127.0.0.1";
        }

    public String getSocksUdpAddress() { return ""; }
    public void setSocksUdpAddress(String addr) { }

	public int getSocksPort() {
		return prefs.getInt(SOCKS_PORT, 1080);
	}

	public void setSocksPort(int port) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(SOCKS_PORT, port);
		editor.commit();
	}

    public String getSocksUsername() { return ""; }
    public void setSocksUsername(String user) { }

    public String getSocksPassword() { return ""; }
    public void setSocksPassword(String pass) { }

    public String getDnsIpv4() { return ""; }
    public void setDnsIpv4(String addr) { }

    public String getDnsIpv6() { return ""; }
    public void setDnsIpv6(String addr) { }

	public String getMappedDns() {
		return "198.18.0.2";
	}

    public boolean getUdpInTcp() { return false; }
    public void setUdpInTcp(boolean enable) { }

    public boolean getRemoteDns() { return true; }
    public void setRemoteDns(boolean enable) { }

	public boolean getIpv4() {
		return prefs.getBoolean(IPV4, true);
	}

	public void setIpv4(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(IPV4, enable);
		editor.commit();
	}

	public boolean getIpv6() {
		return prefs.getBoolean(IPV6, true);
	}

	public void setIpv6(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(IPV6, enable);
		editor.commit();
	}

    public boolean getGlobal() { return prefs.getBoolean(GLOBAL, true); }

	public void setGlobal(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(GLOBAL, enable);
		editor.commit();
	}

	public Set<String> getApps() {
		return prefs.getStringSet(APPS, new HashSet<String>());
	}

	public void setApps(Set<String> apps) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putStringSet(APPS, apps);
		editor.commit();
	}

	public boolean getEnable() {
		return prefs.getBoolean(ENABLE, false);
	}

	public void setEnable(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(ENABLE, enable);
		editor.commit();
	}

	public int getThemeMode() {
		return prefs.getInt(THEME_MODE, THEME_SYSTEM);
	}

	public void setThemeMode(int mode) {
		if (mode != THEME_LIGHT && mode != THEME_DARK) {
			mode = THEME_SYSTEM;
		}
		prefs.edit().putInt(THEME_MODE, mode).apply();
	}

	public boolean getBypassPrivate() {
		return prefs.getBoolean(BYPASS_PRIVATE, false);
	}

	public void setBypassPrivate(boolean enabled) {
		prefs.edit().putBoolean(BYPASS_PRIVATE, enabled).apply();
	}

	public boolean getBypassGeoIpCn() {
		return prefs.getBoolean(BYPASS_GEOIP_CN, false);
	}

	public void setBypassGeoIpCn(boolean enabled) {
		prefs.edit().putBoolean(BYPASS_GEOIP_CN, enabled).apply();
	}

	public boolean getBypassGeoSiteCn() {
		return prefs.getBoolean(BYPASS_GEOSITE_CN, false);
	}

	public void setBypassGeoSiteCn(boolean enabled) {
		prefs.edit().putBoolean(BYPASS_GEOSITE_CN, enabled).apply();
	}

	public String getBypassRules() {
		return prefs.getString(BYPASS_RULES, "");
	}

	public void setBypassRules(String rules) {
		prefs.edit().putString(BYPASS_RULES, rules).apply();
	}

	public int getTunnelMtu() {
		return 8500;
	}

	public String getTunnelIpv4Address() {
		return "198.18.0.1";
	}

	public int getTunnelIpv4Prefix() {
		return 32;
	}

	public String getTunnelIpv6Address() {
		return "fc00::1";
	}

	public int getTunnelIpv6Prefix() {
		return 128;
	}

        public int getTaskStackSize() {
                return 81920;
        }

        // GCM tunnel: Worker 域名
        public String getWorkerHost() {
                return prefs.getString(getKey(WORKER_HOST), "");
        }

        public void setWorkerHost(String addr) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(getKey(WORKER_HOST), addr);
                editor.commit();
        }

// 优选中转节点
        public String getPrefIp() { return prefs.getString(getKey(PREF_IP), ""); }

        public void setPrefIp(String ip) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(getKey(PREF_IP), ip);
                editor.commit();
        }

        // GCM tunnel: 用户 ID
        public String getUserID() { return prefs.getString(getKey(USER_ID), ""); }

        public void setUserID(String t) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(getKey(USER_ID), t);
                editor.commit();
        }

        // GCM tunnel: WebSocket 连接数
        public int getWsConn() {
                int count = prefs.getInt(WS_CONN, DEFAULT_WS_CONN);
                return Math.max(1, Math.min(count, MAX_DYNAMIC_POOL_LIMIT));
        }

        public void setWsConn(int n) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putInt(WS_CONN, Math.max(1, Math.min(n, MAX_DYNAMIC_POOL_LIMIT)));
                editor.commit();
        }

        // 出口代理 IP
        public String getFallbackIp() {
                return prefs.getString(getKey(FALLBACK_IP), "");
        }

        public void setFallbackIp(String ip) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(getKey(FALLBACK_IP), ip);
                editor.commit();
        }

        // ECH DoH 服务器（查询 ECH 公钥用）
        public String getEchDns() {
                return prefs.getString(ECH_DNS, "https://doh.pub/dns-query");
        }

        public void setEchDns(String addr) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(ECH_DNS, addr);
                editor.commit();
        }

        // ECH 查询域名
        public String getEchDomain() {
                return prefs.getString(ECH_DOMAIN, "cloudflare-ech.com");
        }

        public void setEchDomain(String d) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(ECH_DOMAIN, d);
                editor.commit();
        }

        // 禁用 ECH（fallback 标准 TLS 1.3）
        public boolean getDisableEch() {
                return prefs.getBoolean(getKey(DISABLE_ECH), false);
        }

        public void setDisableEch(boolean disable) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean(getKey(DISABLE_ECH), disable);
                editor.commit();
        }

        // DNS 预热开关（默认关闭）
        public boolean getEnableDnsWarmup() {
                return prefs.getBoolean(ENABLE_DNS_WARMUP, false);
        }

        public void setEnableDnsWarmup(boolean enable) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean(ENABLE_DNS_WARMUP, enable);
                editor.commit();
        }

        // 连接池动态扩容（全局设置，默认关闭）
        public boolean getEnableDynamicPool() {
                return prefs.getBoolean(ENABLE_DYNAMIC_POOL, false);
        }

        public void setEnableDynamicPool(boolean enable) {
                prefs.edit().putBoolean(ENABLE_DYNAMIC_POOL, enable).apply();
        }

        public int getDynamicPoolMax() {
                int limit = prefs.getInt(DYNAMIC_POOL_MAX, DEFAULT_DYNAMIC_POOL_MAX);
                return Math.max(1, Math.min(limit, MAX_DYNAMIC_POOL_LIMIT));
        }

        public void setDynamicPoolMax(int limit) {
                prefs.edit().putInt(DYNAMIC_POOL_MAX,
                        Math.max(1, Math.min(limit, MAX_DYNAMIC_POOL_LIMIT))).apply();
        }

        // IPv6 路由禁用
        public boolean getDisableIpv6Route() {
                return prefs.getBoolean(getKey(DISABLE_IPV6_ROUTE), false);
        }

        public void setDisableIpv6Route(boolean disable) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean(getKey(DISABLE_IPV6_ROUTE), disable);
                editor.commit();
        }

        // ======================== 辅助方法（用于配置列表页） ========================

        // 获取指定配置的 Worker 域名（不切换当前配置）
        public String getWorkerHostForProfile(String profileId) {
                return prefs.getString(WORKER_HOST + "_" + profileId, "");
        }

        // 检查配置名称是否已存在（用于验证）
        public boolean profileNameExists(String name, String excludeId) {
                for (String id : getProfileIds()) {
                        if (!id.equals(excludeId) && getProfileName(id).equals(name)) {
                                return true;
                        }
                }
                return false;
        }

        // 获取所有配置的列表（用于 RecyclerView）
        public java.util.List<ProfileInfo> getProfileList() {
                java.util.Set<String> ids = getProfileIds();
                java.util.List<ProfileInfo> profiles = new java.util.ArrayList<>();
                for (String id : ids) {
                        profiles.add(new ProfileInfo(id, getProfileName(id), getWorkerHostForProfile(id)));
                }
                // 按名称排序
                java.util.Collections.sort(profiles, new java.util.Comparator<ProfileInfo>() {
                        @Override
                        public int compare(ProfileInfo a, ProfileInfo b) {
                                return a.name.compareToIgnoreCase(b.name);
                        }
                });
                return profiles;
        }

        // 配置信息类（用于列表展示）
        public static class ProfileInfo {
                public String id;
                public String name;
                public String serverAddr;

                public ProfileInfo(String id, String name, String wssAddr) {
                        this.id = id;
                        this.name = name;
                        // 提取服务器地址（移除 wss:// 前缀和路径）
                        if (wssAddr != null && wssAddr.startsWith("wss://")) {
                                wssAddr = wssAddr.substring(6);
                        }
                        if (wssAddr != null) {
                                int slashIdx = wssAddr.indexOf('/');
                                if (slashIdx > 0) {
                                        wssAddr = wssAddr.substring(0, slashIdx);
                                }
                        }
                        this.serverAddr = wssAddr != null && !wssAddr.isEmpty() ? wssAddr : "未配置";
                }
        }
}
