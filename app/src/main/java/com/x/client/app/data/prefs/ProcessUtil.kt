package com.x.client.app.data.prefs

import android.content.Context
import java.io.BufferedReader
import java.io.FileReader

/**
 * 判断当前是否为主进程（进程名 == 包名；:vpn 服务进程名为 "包名:vpn"）。
 *
 * 与原 Preferences.isMainProcess 一致：读 /proc/self/cmdline 取进程名。
 * 用于"全局网络设置迁移"等只在主进程执行、避免 :vpn 进程用陈旧缓存全量写回
 * 覆盖主进程保存的全局设置的场景。
 */
fun isMainProcess(context: Context): Boolean {
    val packageName = context.packageName
    var processName: String? = null
    try {
        BufferedReader(FileReader("/proc/self/cmdline")).use { reader ->
            processName = reader.readLine()
        }
    } catch (_: Exception) {
        // 读不到时保守返回 true（迁移只在旧版本升级时触发，幂等）
        return true
    }
    processName?.let {
        val nul = it.indexOf('\u0000')
        val name = if (nul > 0) it.substring(0, nul) else it
        return packageName == name
    }
    return true
}
