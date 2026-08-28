package com.x.client.app.data.repository

import android.util.Log
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 对 gomobile 生成的 [xclient.Xclient] 静态调用的封装。
 *
 * 统一捕获 Throwable，避免 native/Go panic 导致 Android 进程崩溃；并提供
 * 单点日志。所有方法对应当前 Go 入口 API（见 golib/android.go）：
 * startSocksProxy / stopSocksProxy / reconnect / notifyNetworkChanged /
 * getRuntimeLogs / appendRuntimeLog / validateBypassRules / setTimeZone。
 *
 * 注意：AAR 在 CI 通过 gomobile bind 生成，本地不存在时该类对 xclient.Xclient
 * 的引用会在编译期失败 —— 由 CI 保证 AAR 已就绪。
 */
@Singleton
class XclientBridge @Inject constructor() {

    /**
     * 启动指定协议的代理。
     * @param listenAddr SOCKS5 本地监听地址（"127.0.0.1:1080"）
     * @param protocol 协议标识 gcm / xtunnel
     * @param paramsJSON 协议参数 JSON 对象字符串
     * @param verbose 调试日志开关
     */
    fun startSocksProxy(listenAddr: String, protocol: String, paramsJSON: String, verbose: Boolean) {
        xclient.Xclient.startSocksProxy(listenAddr, protocol, paramsJSON, verbose)
    }

    fun stopSocksProxy() {
        try {
            xclient.Xclient.stopSocksProxy()
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to stop socks proxy", error)
        }
    }

    fun reconnect(reason: String) {
        xclient.Xclient.reconnect(reason)
    }

    fun notifyNetworkChanged() {
        xclient.Xclient.notifyNetworkChanged()
    }

    fun getRuntimeLogs(): String =
        try {
            xclient.Xclient.getRuntimeLogs()
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to read runtime logs", error)
            "读取运行日志失败: ${error.message ?: error.javaClass.simpleName}"
        }

    fun appendRuntimeLog(scope: String, message: String) {
        try {
            xclient.Xclient.appendRuntimeLog(scope, message)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to append runtime log", error)
        }
    }

    fun validateBypassRules(rules: String) {
        xclient.Xclient.validateBypassRules(rules)
    }

    fun setTimeZone(timeZoneId: String) {
        xclient.Xclient.setTimeZone(timeZoneId)
    }

    /**
     * 把键值映射序列化为 Go 侧 paramsJSON。布尔/数字统一转成 JSON 标量
     * （Go 侧 parseParamsJSON 已支持 string/float64/bool）。
     */
    fun toParamsJson(params: Map<String, Any?>): String {
        val o = JSONObject()
        params.forEach { (k, v) ->
            when (v) {
                null -> { /* skip */ }
                is Number -> o.put(k, v)
                is Boolean -> o.put(k, v)
                else -> o.put(k, v.toString())
            }
        }
        return o.toString()
    }

    companion object {
        private const val TAG = "XclientBridge"
    }
}
