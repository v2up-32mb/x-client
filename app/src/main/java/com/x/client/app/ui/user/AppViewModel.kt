package com.x.client.app.ui.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.x.client.app.data.model.ThemeMode
import com.x.client.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用级 ViewModel：提供全局主题模式 StateFlow（供 MainActivity 选择明暗主题）。
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** 当前主题模式（system/light/dark），UI 实时跟随。 */
    val themeMode: StateFlow<Int> = settingsRepository.settings
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    fun setThemeMode(mode: Int) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }
}
