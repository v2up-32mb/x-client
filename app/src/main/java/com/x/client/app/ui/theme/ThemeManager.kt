package com.x.client.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.x.client.app.data.model.ThemeMode

/**
 * 主题管理（Compose 版）。
 *
 * 模式持久化由 [com.x.client.app.data.repository.SettingsRepository] /
 * [com.x.client.app.data.prefs.GlobalSettingsDataStore] 负责；UI 通过
 * [com.x.client.app.ui.user.AppViewModel] 读取模式 Flow 并传入 [XClientTheme]。
 * system/light/dark 三档，对应原 ThemeManager 行为。
 */
object ThemeManager {

    @Volatile
    private var cachedMode: Int = ThemeMode.SYSTEM

    /** Application 启动时由 ViewModel 初始化缓存（避免主线程磁盘读取）。 */
    fun cacheMode(mode: Int) {
        cachedMode = mode
    }

    fun currentMode(): Int = cachedMode
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0BA3F3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE9FB),
    onPrimaryContainer = Color(0xFF001E2E),
    secondary = Color(0xFF2B63A5),
    onSecondary = Color.White,
    background = Color(0xFFFAFBFC),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C1A),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6CC8FB),
    onPrimary = Color(0xFF003547),
    primaryContainer = Color(0xFF004D6B),
    onPrimaryContainer = Color(0xFFCFE9FB),
    secondary = Color(0xFFA5C8F0),
    onSecondary = Color(0xFF0B2B4D),
    background = Color(0xFF101214),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF181B1E),
    onSurface = Color(0xFFE1E3DF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

/**
 * 应用主题。themeMode 为当前生效模式（system 跟随系统明暗）。
 */
@Composable
fun XClientTheme(
    themeMode: Int = ThemeManager.currentMode(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }
    MaterialTheme(
        colorScheme = if (isDark) DarkColors else LightColors,
        content = content,
    )
}
