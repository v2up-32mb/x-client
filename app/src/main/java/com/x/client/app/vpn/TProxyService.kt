package com.x.client.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.x.client.app.MainActivity
import com.x.client.app.R
import com.x.client.app.data.model.Limits
import com.x.client.app.data.model.LogLevel
import com.x.client.app.data.model.Protocol
import com.x.client.app.data.prefs.GlobalSettingsDataStore
import com.x.client.app.data.prefs.ProfileDataStore
import com.x.client.app.data.repository.XclientBridge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * VPN 代理服务（Kotlin 重写，行为与原 TProxyService.java 1:1 对齐）。
 *
 * 运行在独立 :vpn 进程（见 AndroidManifest android:process=":vpn"）。
 * - 启动 [hev-socks5-tunnel] 原生层把 TUN 流量转发到本地 SOCKS5
 * - 启动 Go 协议后端（GCM / X-Tunnel）通过 gomobile AAR [XclientBridge]
 * - 网络/屏幕/时区变更时重连与日志对齐
 *
 * 关键：跨进程读设置用 runBlocking + MultiProcessDataStore（保证一致性）。
 */
@AndroidEntryPoint
class TProxyService : VpnService() {

    @Inject lateinit var xclientBridge: XclientBridge
    @Inject lateinit var globalStore: GlobalSettingsDataStore
    @Inject lateinit var profileStore: ProfileDataStore

    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var starting = false
    @Volatile private var stopping = false
    @Volatile private var runtimeRunning = false
    private val networkLock = Any()
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultNetwork: Network? = null
    private var networkReconnectPending = false
    private var screenReceiver: BroadcastReceiver? = null
    private var timeZoneReceiver: BroadcastReceiver? = null
    private var screenOffElapsedRealtime = -1L
    private var logRequestOnly = false

    override fun onCreate() {
        super.onCreate()
        initNotificationChannel()
        syncSystemTimeZone()
        registerTimeZoneReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_REQUEST_RUNTIME_LOGS == intent.action) {
            sendRuntimeLogs()
            if (!starting && !runtimeRunning && tunFd == null) {
                logRequestOnly = true
                stopSelf(startId)
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        if (intent != null && ACTION_DISCONNECT == intent.action) {
            Thread { stopService() }.start()
            return START_NOT_STICKY
        }

        synchronized(this) {
            if (starting || tunFd != null) {
                return START_STICKY
            }
            starting = true
            stopping = false
        }

        startForegroundNotification("正在启动 VPN")
        sendStatus(STATUS_STARTING, null)

        Thread({
            try {
                startVpn()
            } finally {
                starting = false
            }
        }, "vpn-start").start()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterTimeZoneReceiver()
        if (logRequestOnly) {
            logRequestOnly = false
            super.onDestroy()
            return
        }
        if (!stopping) {
            cleanupRuntime()
            sendStatus(STATUS_STOPPED, null)
        }
        super.onDestroy()
        // 正常停止路径结束：结束 :vpn 进程，保证下一次 VPN 会话以全新进程
        // 从磁盘重新加载 DataStore（Android 多进程缓存不自动刷新）。
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onRevoke() {
        stopService()
        super.onRevoke()
    }

    private fun startVpn() {
        try {
            // 需在 try 内：任何一步失败都走 failStartup（写日志 + 广播 STATUS_ERROR），
            // 避免 vpn-start 线程静默死亡导致“未启动且日志为空”。
            val settings = runBlocking { globalStore.snapshot() }
            val currentId = settings.currentProfileId
            if (currentId.isNullOrBlank() || currentId !in settings.profileIds) {
                throw IllegalStateException("未选择有效的配置节点")
            }
            val builder = buildVpnInterface(settings)
            tunFd = builder.establish()
                ?: throw IllegalStateException("系统未能建立 VPN 接口")

            val configFile = writeTProxyConfig(settings)
            startProxy(settings)

            if (!TProxyStartService(configFile.absolutePath, tunFd!!.fd)) {
                throw IllegalStateException("hev-socks5-tunnel 启动失败")
            }
            Thread.sleep(200)
            if (!TProxyIsRunning()) {
                throw IllegalStateException("hev-socks5-tunnel 未进入运行状态")
            }

            runtimeRunning = true
            appendRuntimeLog("VPN 与本地隧道已启动")
            registerScreenReceiver()
            registerNetworkCallback()
            updateNotification("VPN 已连接")
            sendStatus(STATUS_STARTED, null)
            monitorNativeTunnel()
        } catch (error: Throwable) {
            failStartup(error)
        }
    }

    private fun buildVpnInterface(settings: com.x.client.app.data.model.GlobalSettings): Builder {
        var session = ""
        val builder = Builder()
        builder.setBlocking(false)
        builder.setMtu(Limits.TUNNEL_MTU)

        // IPv4 路由（始终启用，原 getIpv4 默认 true 且 UI 不再暴露关闭）
        builder.addAddress(Limits.TUNNEL_IPV4, Limits.TUNNEL_IPV4_PREFIX)
        builder.addRoute("0.0.0.0", 0)
        // remoteDns 固定 true（原 getRemoteDns 默认 true），映射 DNS
        builder.addDnsServer(Limits.MAPPED_DNS)
        session += "IPv4"

        // IPv6 路由：原 getIpv6 默认 true，受 disableIpv6Route 控制
        val profile = runBlocking {
            profileStore.getProfile(settings.currentProfileId ?: "")
        }
        if (profile.disableIpv6Route.not()) {
            builder.addAddress(Limits.TUNNEL_IPV6, Limits.TUNNEL_IPV6_PREFIX)
            builder.addRoute("::", 0)
            if (session.isNotEmpty()) session += " + "
            session += "IPv6"
        }

        var disallowSelf = true
        if (settings.global) {
            session += "/Global"
        } else {
            for (appName in settings.apps) {
                try {
                    builder.addAllowedApplication(appName)
                    disallowSelf = false
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                }
            }
            session += "/per-App"
        }
        if (disallowSelf) {
            builder.addDisallowedApplication(applicationContext.packageName)
        }
        builder.setSession(session)
        return builder
    }

    private fun writeTProxyConfig(settings: com.x.client.app.data.model.GlobalSettings): File {
        val configFile = File(cacheDir, "tproxy.conf")
        val config = StringBuilder()
            .append("misc:\n")
            .append("  task-stack-size: ${Limits.TASK_STACK_SIZE}\n")
            .append("tunnel:\n")
            .append("  mtu: ${Limits.TUNNEL_MTU}\n")
            .append("socks5:\n")
            .append("  port: ${settings.socksPort}\n")
            .append("  address: '${Limits.SOCKS_ADDR}'\n")
            .append("  udp: 'udp'\n") // 原 getUdpInTcp 固定 false → udp
        if (settings.remoteDns()) {
            config.append("mapdns:\n")
                .append("  address: ${Limits.MAPPED_DNS}\n")
                .append("  port: 53\n")
                .append("  network: 240.0.0.0\n")
                .append("  netmask: 240.0.0.0\n")
                .append("  cache-size: 10000\n")
        }
        FileOutputStream(configFile, false).use {
            it.write(config.toString().toByteArray(StandardCharsets.UTF_8))
        }
        return configFile
    }

    private fun startProxy(settings: com.x.client.app.data.model.GlobalSettings) {
        val profile = runBlocking {
            profileStore.getProfile(settings.currentProfileId ?: "")
        }
        var protocol = profile.protocol
        if (protocol.isBlank()) protocol = Protocol.GCM
        val params = if (Protocol.XTUNNEL == protocol) {
            buildXtunnelParams(settings, profile)
        } else {
            protocol = Protocol.GCM
            buildGCMParams(settings, profile)
        }
        appendRuntimeLog("启动参数($protocol): $params")
        xclientBridge.startSocksProxy(
            "${Limits.SOCKS_ADDR}:${settings.socksPort}",
            protocol,
            params,
            true,
        )
    }

    private fun buildGCMParams(
        settings: com.x.client.app.data.model.GlobalSettings,
        profile: com.x.client.app.data.model.ProfileConfig,
    ): String {
        var workerHost = profile.workerHost.trim()
        if (workerHost.startsWith("wss://")) workerHost = workerHost.substring(6)
        else if (workerHost.startsWith("https://")) workerHost = workerHost.substring(8)
        workerHost = workerHost.replace("/+$".toRegex(), "")

        val o = JSONObject()
        o.put("worker_host", workerHost)
        o.put("ws_conn", profile.wsConn)
        o.put("relay_ips", profile.prefIp)
        o.put("user_id", profile.userId)
        o.put("proxy_ip", profile.fallbackIp)
        o.put("ech_domain", settings.echDomain)
        o.put("ech_dns", settings.echDns)
        o.put("enable_ech", !profile.disableEch)
        o.put("disable_ipv6_route", profile.disableIpv6Route)
        o.put("enable_dns_warmup", settings.enableDnsWarmup)
        o.put("bypass_private", settings.bypassPrivate)
        o.put("bypass_geoip_cn", settings.bypassGeoIpCn)
        o.put("bypass_geosite_cn", settings.bypassGeoSiteCn)
        o.put("bypass_rules", settings.bypassRules)
        o.put("enable_dynamic_pool", profile.enableDynamicPool)
        o.put("dynamic_pool_max", profile.dynamicPoolMax)
        o.put("log_level", settings.logLevel)
        return o.toString()
    }

    private fun buildXtunnelParams(
        settings: com.x.client.app.data.model.GlobalSettings,
        profile: com.x.client.app.data.model.ProfileConfig,
    ): String {
        val o = JSONObject()
        o.put("server_addr", profile.xtServerAddr)
        o.put("token", profile.xtToken)
        o.put("connections", profile.xtConnections)
        o.put("relay_nodes", profile.xtRelayNodes)
        o.put("enable_ech", !profile.xtDisableEch)
        o.put("ech_domain", settings.echDomain)
        o.put("dns_server", settings.echDns)
        o.put("insecure", profile.xtInsecure)
        o.put("enable_hot_pair", profile.xtEnableHotPair)
        o.put("hot_pair_count", profile.xtHotPairCount)
        o.put("log_level", settings.logLevel)
        o.put("bypass_private", settings.bypassPrivate)
        o.put("bypass_geoip_cn", settings.bypassGeoIpCn)
        o.put("bypass_geosite_cn", settings.bypassGeoSiteCn)
        o.put("bypass_rules", settings.bypassRules)
        // 高级参数覆盖（优先级最高）
        profile.xtAdvancedParams.toParamsMap().forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    private fun syncSystemTimeZone() {
        try {
            xclientBridge.setTimeZone(java.util.TimeZone.getDefault().id)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to sync system timezone", error)
        }
    }

    private fun registerTimeZoneReceiver() {
        if (timeZoneReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (Intent.ACTION_TIMEZONE_CHANGED != intent.action) return
                syncSystemTimeZone()
                appendRuntimeLog("系统时区已变更: ${java.util.TimeZone.getDefault().id}")
            }
        }
        try {
            ContextCompat.registerReceiver(
                this, receiver,
                IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            timeZoneReceiver = receiver
        } catch (error: RuntimeException) {
            Log.w(TAG, "Failed to register timezone receiver", error)
        }
    }

    private fun unregisterTimeZoneReceiver() {
        val receiver = timeZoneReceiver
        timeZoneReceiver = null
        if (receiver != null) {
            try {
                unregisterReceiver(receiver)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Failed to unregister timezone receiver", error)
            }
        }
    }

    private fun failStartup(error: Throwable) {
        var message = error.message
        if (message.isNullOrBlank()) message = error.javaClass.simpleName
        Log.e(TAG, "VPN startup failed: $message", error)
        appendRuntimeLog("VPN 启动失败: $message")
        stopping = true
        cleanupRuntime()
        sendStatus(STATUS_ERROR, message)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun monitorNativeTunnel() {
        Thread({
            while (!stopping && runtimeRunning) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                if (!stopping && !TProxyIsRunning()) {
                    appendRuntimeLog("hev-socks5-tunnel 意外停止")
                    failStartup(IllegalStateException("hev-socks5-tunnel 意外停止"))
                    return@Thread
                }
            }
        }, "vpn-monitor").start()
    }

    private fun stopService() {
        synchronized(this) {
            if (stopping) return
            stopping = true
        }
        appendRuntimeLog("收到停止 VPN 请求")
        cleanupRuntime()
        sendStatus(STATUS_STOPPED, null)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupRuntime() {
        appendRuntimeLog("正在清理 VPN runtime")
        runtimeRunning = false
        unregisterScreenReceiver()
        unregisterNetworkCallback()
        try {
            TProxyStopService()
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to stop native tunnel", error)
        }
        xclientBridge.stopSocksProxy()
        val currentTunFd = tunFd
        tunFd = null
        currentTunFd?.let {
            try {
                it.close()
            } catch (error: IOException) {
                Log.w(TAG, "Failed to close TUN fd", error)
            }
        }
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            Log.w(TAG, "ConnectivityManager unavailable; network change recovery is disabled")
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val changed: Boolean
                synchronized(networkLock) {
                    changed = defaultNetwork != null && defaultNetwork != network
                    defaultNetwork = network
                }
                appendRuntimeLog(if (changed) "默认网络已切换" else "默认网络已可用")
                if (changed) scheduleReconnect("Android default network changed")
            }

            override fun onLost(network: Network) {
                val lost: Boolean
                synchronized(networkLock) {
                    lost = defaultNetwork != null && defaultNetwork == network
                    if (lost) defaultNetwork = null
                }
                if (lost) {
                    appendRuntimeLog("默认网络已断开")
                    scheduleReconnect("Android default network lost")
                }
            }
        }
        synchronized(networkLock) {
            connectivityManager = manager
            networkCallback = callback
            defaultNetwork = null
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (error: RuntimeException) {
            synchronized(networkLock) {
                connectivityManager = null
                networkCallback = null
                defaultNetwork = null
            }
            Log.w(TAG, "Failed to register network callback", error)
            appendRuntimeLog("注册默认网络监听失败: ${error.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val manager: ConnectivityManager?
        val callback: ConnectivityManager.NetworkCallback?
        synchronized(networkLock) {
            manager = connectivityManager
            callback = networkCallback
            connectivityManager = null
            networkCallback = null
            defaultNetwork = null
            networkReconnectPending = false
        }
        if (manager != null && callback != null) {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Failed to unregister network callback", error)
            }
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                if (Intent.ACTION_SCREEN_OFF == action) {
                    synchronized(networkLock) { screenOffElapsedRealtime = SystemClock.elapsedRealtime() }
                    appendRuntimeLog("屏幕已关闭")
                    return
                }
                if (Intent.ACTION_SCREEN_ON != action) return
                val screenOffDuration: Long
                synchronized(networkLock) {
                    screenOffDuration = if (screenOffElapsedRealtime < 0) {
                        -1L
                    } else {
                        SystemClock.elapsedRealtime() - screenOffElapsedRealtime
                    }
                    screenOffElapsedRealtime = -1L
                }
                if (screenOffDuration < 0) {
                    appendRuntimeLog("屏幕已点亮")
                } else {
                    val seconds = screenOffDuration / 1000L
                    appendRuntimeLog("屏幕已点亮，息屏 $seconds 秒")
                    if (screenOffDuration >= SCREEN_RECONNECT_THRESHOLD_MS) {
                        scheduleReconnect("Android screen resumed after ${seconds}s")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            screenReceiver = receiver
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) {
                synchronized(networkLock) { screenOffElapsedRealtime = SystemClock.elapsedRealtime() }
            }
            appendRuntimeLog("屏幕状态监听已启动")
        } catch (error: RuntimeException) {
            Log.w(TAG, "Failed to register screen receiver", error)
            appendRuntimeLog("注册屏幕状态监听失败: ${error.message}")
        }
    }

    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver
        screenReceiver = null
        synchronized(networkLock) { screenOffElapsedRealtime = -1L }
        if (receiver != null) {
            try {
                unregisterReceiver(receiver)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Failed to unregister screen receiver", error)
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        synchronized(networkLock) {
            if (!runtimeRunning || networkReconnectPending) return
            networkReconnectPending = true
        }
        appendRuntimeLog("计划重建连接: $reason")
        Thread({
            try {
                Thread.sleep(300)
                if (runtimeRunning) {
                    xclientBridge.reconnect(reason)
                    appendRuntimeLog("已触发连接重建: $reason")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to reconnect after network change", error)
                appendRuntimeLog("连接重建失败: ${error.message}")
            } finally {
                synchronized(networkLock) { networkReconnectPending = false }
            }
        }, "vpn-reconnect").start()
    }

    private fun appendRuntimeLog(message: String) {
        Log.i(TAG, message)
        xclientBridge.appendRuntimeLog("AndroidVPN", message)
    }

    private fun sendRuntimeLogs() {
        val logs = xclientBridge.getRuntimeLogs()
        val response = Intent(ACTION_RUNTIME_LOGS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNTIME_LOGS, logs)
        }
        sendBroadcast(response)
    }

    private fun sendStatus(status: String, error: String?) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            error?.let { putExtra(EXTRA_ERROR, it) }
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNotification(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "TProxyService"
        private const val CHANNEL_ID = "socks5"
        private const val NOTIFICATION_ID = 1
        private const val SCREEN_RECONNECT_THRESHOLD_MS = 60_000L

        const val ACTION_CONNECT = "com.x.client.app.CONNECT"
        const val ACTION_DISCONNECT = "com.x.client.app.DISCONNECT"
        const val ACTION_STATUS = "com.x.client.app.STATUS"
        const val ACTION_REQUEST_RUNTIME_LOGS = "com.x.client.app.REQUEST_RUNTIME_LOGS"
        const val ACTION_RUNTIME_LOGS = "com.x.client.app.RUNTIME_LOGS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR = "error"
        const val EXTRA_RUNTIME_LOGS = "runtime_logs"
        const val STATUS_STARTING = "starting"
        const val STATUS_STARTED = "started"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        // ---- hev-socks5-tunnel native 接口（static，与原 Java 一致） ----
        @JvmStatic private external fun TProxyStartService(configPath: String, fd: Int): Boolean
        @JvmStatic private external fun TProxyStopService(): Boolean
        @JvmStatic private external fun TProxyIsRunning(): Boolean
        @JvmStatic private external fun TProxyGetStats(): LongArray
    }
}

/** GlobalSettings 辅助扩展：remoteDns 固定 true（与原 Preferences.getRemoteDns 一致）。 */
private fun com.x.client.app.data.model.GlobalSettings.remoteDns(): Boolean = true