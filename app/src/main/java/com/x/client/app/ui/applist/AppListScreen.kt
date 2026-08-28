package com.x.client.app.ui.applist

import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.x.client.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 已安装应用信息。 */
data class AppItem(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _allApps = MutableStateFlow<List<AppItem>>(emptyList())
    val allApps: StateFlow<List<AppItem>> = _allApps.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    var changed = false
        private set

    fun initInstalled(context: android.content.Context) {
        viewModelScope.launch {
            // 先加载已选集合
            _selected.value = settingsRepository.snapshot().apps
            val pm = context.packageManager
            val items = withContext(Dispatchers.IO) {
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                    .filter { it.packageName != context.packageName }
                    .filter { info ->
                        info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
                    }
                    .map { info ->
                        AppItem(
                            packageName = info.packageName,
                            label = info.applicationInfo?.loadLabel(pm)?.toString() ?: info.packageName,
                            icon = info.applicationInfo?.loadIcon(pm),
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }
            _allApps.value = items
        }
    }

    fun toggle(packageName: String) {
        _selected.value = if (packageName in _selected.value) {
            _selected.value - packageName
        } else {
            _selected.value + packageName
        }
        changed = true
    }

    fun save() {
        viewModelScope.launch {
            settingsRepository.setApps(_selected.value)
            changed = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(onBack: () -> Unit, viewModel: AppListViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val allApps by viewModel.allApps.collectAsState()
    val selected by viewModel.selected.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.initInstalled(context) }

    // 退出时保存
    DisposableEffect(Unit) {
        onDispose {
            if (viewModel.changed) viewModel.save()
        }
    }

    val filtered = remember(allApps, query, selected) {
        val base = if (query.isBlank()) allApps
        else allApps.filter { it.label.contains(query, ignoreCase = true) }
        // 已勾选的 APP 置顶（选中优先，其次按名称排序）
        base.sortedWith(
            compareBy<AppItem> { it.packageName !in selected }
                .thenBy { it.label.lowercase() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分应用代理") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (viewModel.changed) viewModel.save()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索应用") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            LazyColumn {
                items(filtered, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = app.icon
                        if (icon is BitmapDrawable && icon.bitmap != null) {
                            Image(
                                bitmap = icon.bitmap.asImageBitmap(),
                                contentDescription = app.label,
                                modifier = Modifier.size(36.dp)
                            )
                        } else {
                            Spacer(Modifier.size(36.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            app.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Checkbox(
                            checked = app.packageName in selected,
                            onCheckedChange = { viewModel.toggle(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}
