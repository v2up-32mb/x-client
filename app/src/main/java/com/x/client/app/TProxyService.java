/*
 ============================================================================
 Name        : TProxyService.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 xyz
 Description : TProxy Service
 ============================================================================
 */

package com.x.client.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import xclient.Xclient;

public class TProxyService extends VpnService {
    private static final String TAG = "TProxyService";
    private static final String CHANNEL_ID = "socks5";
    private static final int NOTIFICATION_ID = 1;
    private static final long SCREEN_RECONNECT_THRESHOLD_MS = 60_000L;

    private static native boolean TProxyStartService(String configPath, int fd);
    private static native boolean TProxyStopService();
    private static native boolean TProxyIsRunning();
    private static native long[] TProxyGetStats();

    public static final String ACTION_CONNECT = "com.x.client.app.CONNECT";
    public static final String ACTION_DISCONNECT = "com.x.client.app.DISCONNECT";
    public static final String ACTION_STATUS = "com.x.client.app.STATUS";
    public static final String ACTION_REQUEST_RUNTIME_LOGS = "com.x.client.app.REQUEST_RUNTIME_LOGS";
    public static final String ACTION_RUNTIME_LOGS = "com.x.client.app.RUNTIME_LOGS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_RUNTIME_LOGS = "runtime_logs";
    public static final String STATUS_STARTING = "starting";
    public static final String STATUS_STARTED = "started";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_ERROR = "error";

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private volatile ParcelFileDescriptor tunFd;
    private volatile boolean starting;
    private volatile boolean stopping;
    private volatile boolean runtimeRunning;
    private final Object networkLock = new Object();
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network defaultNetwork;
    private boolean networkReconnectPending;
    private BroadcastReceiver screenReceiver;
    private long screenOffElapsedRealtime = -1L;
    private boolean logRequestOnly;

    @Override
    public void onCreate() {
        super.onCreate();
        initNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_REQUEST_RUNTIME_LOGS.equals(intent.getAction())) {
            sendRuntimeLogs();
            if (!starting && !runtimeRunning && tunFd == null) {
                logRequestOnly = true;
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            return START_STICKY;
        }
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            new Thread(this::stopService, "gcm-vpn-stop").start();
            return START_NOT_STICKY;
        }

        synchronized (this) {
            if (starting || tunFd != null) {
                return START_STICKY;
            }
            starting = true;
            stopping = false;
        }

        new Preferences(this).setEnable(false);
        startForegroundNotification("正在启动 VPN");
        sendStatus(STATUS_STARTING, null);

        new Thread(() -> {
            try {
                startVpn();
            } finally {
                starting = false;
            }
        }, "gcm-vpn-start").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (logRequestOnly) {
            logRequestOnly = false;
            super.onDestroy();
            return;
        }
        if (!stopping) {
            cleanupRuntime();
            new Preferences(this).setEnable(false);
            sendStatus(STATUS_STOPPED, null);
        }
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopService();
        super.onRevoke();
    }

    private void startVpn() {
        Preferences prefs = new Preferences(this);
        try {
            VpnService.Builder builder = buildVpnInterface(prefs);
            tunFd = builder.establish();
            if (tunFd == null) {
                throw new IllegalStateException("系统未能建立 VPN 接口");
            }

            File configFile = writeTProxyConfig(prefs);
            startProxy(prefs);

            if (!TProxyStartService(configFile.getAbsolutePath(), tunFd.getFd())) {
                throw new IllegalStateException("hev-socks5-tunnel 启动失败");
            }
            Thread.sleep(200);
            if (!TProxyIsRunning()) {
                throw new IllegalStateException("hev-socks5-tunnel 未进入运行状态");
            }

            runtimeRunning = true;
            appendRuntimeLog("VPN 与本地隧道已启动");
            registerScreenReceiver();
            registerNetworkCallback();
            prefs.setEnable(true);
            updateNotification("VPN 已连接");
            sendStatus(STATUS_STARTED, null);
            monitorNativeTunnel(prefs);
        } catch (Throwable error) {
            failStartup(prefs, error);
        }
    }

    private VpnService.Builder buildVpnInterface(Preferences prefs) throws NameNotFoundException {
        String session = "";
        VpnService.Builder builder = new VpnService.Builder();
        builder.setBlocking(false);
        builder.setMtu(prefs.getTunnelMtu());

        if (prefs.getIpv4()) {
            builder.addAddress(prefs.getTunnelIpv4Address(), prefs.getTunnelIpv4Prefix());
            builder.addRoute("0.0.0.0", 0);
            if (!prefs.getRemoteDns() && !prefs.getDnsIpv4().isEmpty()) {
                builder.addDnsServer(prefs.getDnsIpv4());
            }
            session += "IPv4";
        }
        if (prefs.getIpv6() && !prefs.getDisableIpv6Route()) {
            builder.addAddress(prefs.getTunnelIpv6Address(), prefs.getTunnelIpv6Prefix());
            builder.addRoute("::", 0);
            if (!prefs.getRemoteDns() && !prefs.getDnsIpv6().isEmpty()) {
                builder.addDnsServer(prefs.getDnsIpv6());
            }
            if (!session.isEmpty()) {
                session += " + ";
            }
            session += "IPv6";
        }
        if (prefs.getRemoteDns()) {
            builder.addDnsServer(prefs.getMappedDns());
        }

        boolean disallowSelf = true;
        if (prefs.getGlobal()) {
            session += "/Global";
        } else {
            for (String appName : prefs.getApps()) {
                try {
                    builder.addAllowedApplication(appName);
                    disallowSelf = false;
                } catch (NameNotFoundException ignored) {
                }
            }
            session += "/per-App";
        }
        if (disallowSelf) {
            builder.addDisallowedApplication(getApplicationContext().getPackageName());
        }
        builder.setSession(session);
        return builder;
    }

    private File writeTProxyConfig(Preferences prefs) throws IOException {
        File configFile = new File(getCacheDir(), "tproxy.conf");
        StringBuilder config = new StringBuilder()
                .append("misc:\n")
                .append("  task-stack-size: ").append(prefs.getTaskStackSize()).append("\n")
                .append("tunnel:\n")
                .append("  mtu: ").append(prefs.getTunnelMtu()).append("\n")
                .append("socks5:\n")
                .append("  port: ").append(prefs.getSocksPort()).append("\n")
                .append("  address: '").append(prefs.getSocksAddress()).append("'\n")
                .append("  udp: '").append(prefs.getUdpInTcp() ? "tcp" : "udp").append("'\n");

        if (!prefs.getSocksUdpAddress().isEmpty()) {
            config.append("  udp-address: '").append(prefs.getSocksUdpAddress()).append("'\n");
        }
        if (!prefs.getSocksUsername().isEmpty() && !prefs.getSocksPassword().isEmpty()) {
            config.append("  username: '").append(prefs.getSocksUsername()).append("'\n");
            config.append("  password: '").append(prefs.getSocksPassword()).append("'\n");
        }
        if (prefs.getRemoteDns()) {
            config.append("mapdns:\n")
                    .append("  address: ").append(prefs.getMappedDns()).append("\n")
                    .append("  port: 53\n")
                    .append("  network: 240.0.0.0\n")
                    .append("  netmask: 240.0.0.0\n")
                    .append("  cache-size: 10000\n");
        }

        try (FileOutputStream output = new FileOutputStream(configFile, false)) {
            output.write(config.toString().getBytes(StandardCharsets.UTF_8));
        }
        return configFile;
    }

    private void startProxy(Preferences prefs) throws Exception {
        String protocol = prefs.getProtocol();
        if (protocol == null || protocol.trim().isEmpty()) {
            protocol = Preferences.PROTOCOL_GCM;
        }

        JSONObject params;
        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol)) {
            params = buildXtunnelParams(prefs);
        } else {
            protocol = Preferences.PROTOCOL_GCM;
            params = buildGCMParams(prefs);
        }

        Xclient.startSocksProxy(
                prefs.getSocksAddress() + ":" + prefs.getSocksPort(),
                protocol,
                params.toString(),
                true
        );
    }

    private JSONObject buildGCMParams(Preferences prefs) throws Exception {
        String workerHost = prefs.getWorkerHost().trim();
        if (workerHost.startsWith("wss://")) {
            workerHost = workerHost.substring(6);
        } else if (workerHost.startsWith("https://")) {
            workerHost = workerHost.substring(8);
        }
        workerHost = workerHost.replaceAll("/+$", "");

        JSONObject params = new JSONObject();
        params.put("worker_host", workerHost);
        params.put("ws_conn", prefs.getWsConn());
        params.put("relay_ips", prefs.getPrefIp());
        params.put("user_id", prefs.getUserID());
        params.put("proxy_ip", prefs.getFallbackIp());
        params.put("ech_domain", prefs.getEchDomain());
        params.put("ech_dns", prefs.getEchDns());
        params.put("enable_ech", !prefs.getDisableEch());
        params.put("disable_ipv6_route", prefs.getDisableIpv6Route());
        params.put("enable_dns_warmup", prefs.getEnableDnsWarmup());
        params.put("bypass_private", prefs.getBypassPrivate());
        params.put("bypass_geoip_cn", prefs.getBypassGeoIpCn());
        params.put("bypass_geosite_cn", prefs.getBypassGeoSiteCn());
        params.put("bypass_rules", prefs.getBypassRules());
        params.put("enable_dynamic_pool", prefs.getEnableDynamicPool());
        params.put("dynamic_pool_max", prefs.getDynamicPoolMax());
        return params;
    }

    private JSONObject buildXtunnelParams(Preferences prefs) throws Exception {
        JSONObject params = new JSONObject();
        params.put("server_addr", prefs.getXtServerAddr());
        params.put("token", prefs.getXtToken());
        params.put("connections", prefs.getXtConnections());
        params.put("relay_nodes", prefs.getXtRelayNodes());
        // ECH 域名与 DoH 服务器复用全局设置（与 GCM 协议一致）
        params.put("enable_ech", !prefs.getXtDisableEch());
        params.put("ech_domain", prefs.getEchDomain());
        params.put("dns_server", prefs.getEchDns());
        params.put("insecure", prefs.getXtInsecure());
        params.put("enable_hot_pair", prefs.getXtEnableHotPair());
        return params;
    }

    private void failStartup(Preferences prefs, Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        Log.e(TAG, "VPN startup failed: " + message, error);
        appendRuntimeLog("VPN 启动失败: " + message);
        stopping = true;
        cleanupRuntime();
        prefs.setEnable(false);
        sendStatus(STATUS_ERROR, message);
        stopForeground(true);
        stopSelf();
    }

    private void monitorNativeTunnel(Preferences prefs) {
        new Thread(() -> {
            while (!stopping && prefs.getEnable()) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!stopping && !TProxyIsRunning()) {
                    appendRuntimeLog("hev-socks5-tunnel 意外停止");
                    failStartup(prefs, new IllegalStateException("hev-socks5-tunnel 意外停止"));
                    return;
                }
            }
        }, "gcm-vpn-monitor").start();
    }

    private void stopService() {
        synchronized (this) {
            if (stopping) {
                return;
            }
            stopping = true;
        }
        appendRuntimeLog("收到停止 VPN 请求");
        cleanupRuntime();
        new Preferences(this).setEnable(false);
        sendStatus(STATUS_STOPPED, null);
        stopForeground(true);
        stopSelf();
    }

    private void cleanupRuntime() {
        appendRuntimeLog("正在清理 VPN runtime");
        runtimeRunning = false;
        unregisterScreenReceiver();
        unregisterNetworkCallback();
        try {
            TProxyStopService();
        } catch (Throwable error) {
            Log.w(TAG, "Failed to stop native tunnel", error);
        }
        try {
            Xclient.stopSocksProxy();
        } catch (Throwable error) {
            Log.w(TAG, "Failed to stop GCM proxy", error);
        }
        ParcelFileDescriptor currentTunFd = tunFd;
        tunFd = null;
        if (currentTunFd != null) {
            try {
                currentTunFd.close();
            } catch (IOException error) {
                Log.w(TAG, "Failed to close TUN fd", error);
            }
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            Log.w(TAG, "ConnectivityManager unavailable; network change recovery is disabled");
            return;
        }

        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                boolean changed;
                synchronized (networkLock) {
                    changed = defaultNetwork != null && !defaultNetwork.equals(network);
                    defaultNetwork = network;
                }
                appendRuntimeLog(changed ? "默认网络已切换" : "默认网络已可用");
                if (changed) {
                    scheduleReconnect("Android default network changed");
                }
            }

            @Override
            public void onLost(Network network) {
                boolean lost;
                synchronized (networkLock) {
                    lost = defaultNetwork != null && defaultNetwork.equals(network);
                    if (lost) {
                        defaultNetwork = null;
                    }
                }
                if (lost) {
                    appendRuntimeLog("默认网络已断开");
                    scheduleReconnect("Android default network lost");
                }
            }
        };

        synchronized (networkLock) {
            connectivityManager = manager;
            networkCallback = callback;
            defaultNetwork = null;
        }
        try {
            manager.registerDefaultNetworkCallback(callback);
        } catch (RuntimeException error) {
            synchronized (networkLock) {
                connectivityManager = null;
                networkCallback = null;
                defaultNetwork = null;
            }
            Log.w(TAG, "Failed to register network callback", error);
            appendRuntimeLog("注册默认网络监听失败: " + error.getMessage());
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager manager;
        ConnectivityManager.NetworkCallback callback;
        synchronized (networkLock) {
            manager = connectivityManager;
            callback = networkCallback;
            connectivityManager = null;
            networkCallback = null;
            defaultNetwork = null;
            networkReconnectPending = false;
        }
        if (manager != null && callback != null) {
            try {
                manager.unregisterNetworkCallback(callback);
            } catch (RuntimeException error) {
                Log.w(TAG, "Failed to unregister network callback", error);
            }
        }
    }

    private void registerScreenReceiver() {
        if (screenReceiver != null) {
            return;
        }

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    synchronized (networkLock) {
                        screenOffElapsedRealtime = SystemClock.elapsedRealtime();
                    }
                    appendRuntimeLog("屏幕已关闭");
                    return;
                }
                if (!Intent.ACTION_SCREEN_ON.equals(action)) {
                    return;
                }

                long screenOffDuration;
                synchronized (networkLock) {
                    if (screenOffElapsedRealtime < 0) {
                        screenOffDuration = -1L;
                    } else {
                        screenOffDuration = SystemClock.elapsedRealtime() - screenOffElapsedRealtime;
                    }
                    screenOffElapsedRealtime = -1L;
                }

                if (screenOffDuration < 0) {
                    appendRuntimeLog("屏幕已点亮");
                } else {
                    long seconds = screenOffDuration / 1000L;
                    appendRuntimeLog("屏幕已点亮，息屏 " + seconds + " 秒");
                    if (screenOffDuration >= SCREEN_RECONNECT_THRESHOLD_MS) {
                        scheduleReconnect("Android screen resumed after " + seconds + "s");
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
            screenReceiver = receiver;
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && !powerManager.isInteractive()) {
                synchronized (networkLock) {
                    screenOffElapsedRealtime = SystemClock.elapsedRealtime();
                }
            }
            appendRuntimeLog("屏幕状态监听已启动");
        } catch (RuntimeException error) {
            Log.w(TAG, "Failed to register screen receiver", error);
            appendRuntimeLog("注册屏幕状态监听失败: " + error.getMessage());
        }
    }

    private void unregisterScreenReceiver() {
        BroadcastReceiver receiver = screenReceiver;
        screenReceiver = null;
        synchronized (networkLock) {
            screenOffElapsedRealtime = -1L;
        }
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (RuntimeException error) {
                Log.w(TAG, "Failed to unregister screen receiver", error);
            }
        }
    }

    private void scheduleReconnect(String reason) {
        synchronized (networkLock) {
            if (!runtimeRunning || networkReconnectPending) {
                return;
            }
            networkReconnectPending = true;
        }
        appendRuntimeLog("计划重建 GCM 连接: " + reason);

        new Thread(() -> {
            try {
                Thread.sleep(300);
                if (runtimeRunning) {
                    Xclient.reconnect(reason);
                    appendRuntimeLog("已触发 GCM 连接重建: " + reason);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Throwable error) {
                Log.w(TAG, "Failed to reconnect after network change", error);
                appendRuntimeLog("GCM 连接重建失败: " + error.getMessage());
            } finally {
                synchronized (networkLock) {
                    networkReconnectPending = false;
                }
            }
        }, "gcm-network-reconnect").start();
    }

    private void appendRuntimeLog(String message) {
        Log.i(TAG, message);
        try {
            Xclient.appendRuntimeLog("AndroidVPN", message);
        } catch (Throwable error) {
            Log.w(TAG, "Failed to append runtime log", error);
        }
    }

    private void sendRuntimeLogs() {
        String logs = "";
        try {
            logs = Xclient.getRuntimeLogs();
        } catch (Throwable error) {
            Log.w(TAG, "Failed to read runtime logs", error);
            logs = "读取运行日志失败: " + error.getMessage();
        }
        Intent response = new Intent(ACTION_RUNTIME_LOGS);
        response.setPackage(getPackageName());
        response.putExtra(EXTRA_RUNTIME_LOGS, logs);
        sendBroadcast(response);
    }

    private void sendStatus(String status, String error) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        if (error != null) {
            intent.putExtra(EXTRA_ERROR, error);
        }
        sendBroadcast(intent);
    }

    private Notification buildNotification(String statusText) {
        Intent intent = new Intent(this, ProfileListActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void startForegroundNotification(String statusText) {
        Notification notification = buildNotification(statusText);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
    }

    private void updateNotification(String statusText) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(statusText));
    }

    private void initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            manager.createNotificationChannel(channel);
        }
    }
}
