package com.x.client.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.x.client.app.ui.applist.AppListScreen
import com.x.client.app.ui.log.RuntimeLogScreen
import com.x.client.app.ui.profiles.ProfileEditScreen
import com.x.client.app.ui.profiles.ProfileListScreen
import com.x.client.app.ui.scan.ScanScreen
import com.x.client.app.ui.settings.SettingsScreen

/**
 * 应用导航图。单 Activity 多屏。
 */
object Routes {
    const val PROFILES = "profiles"
    const val PROFILE_EDIT = "profile_edit/{profileId}?isNew={isNew}"
    const val SETTINGS = "settings"
    const val RUNTIME_LOG = "runtime_log"
    const val APP_LIST = "app_list"
    const val SCAN = "scan?returnTarget={returnTarget}"

    fun profileEdit(profileId: String, isNew: Boolean = false): String =
        "profile_edit/$profileId?isNew=$isNew"

    fun scan(returnTarget: String): String = "scan?returnTarget=$returnTarget"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.PROFILES) {
        composable(Routes.PROFILES) {
            ProfileListScreen(
                scanResult = it.savedStateHandle.get<String>("scan_result"),
                onScanConsumed = { it.savedStateHandle.remove<String>("scan_result") },
                onEditProfile = { id, isNew -> navController.navigate(Routes.profileEdit(id, isNew)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenRuntimeLog = { navController.navigate(Routes.RUNTIME_LOG) },
                onOpenScan = { navController.navigate(Routes.scan("profiles")) },
            )
        }
        composable(
            route = Routes.PROFILE_EDIT,
            arguments = listOf(
                navArgument("profileId") { type = NavType.StringType },
                navArgument("isNew") { type = NavType.StringType; defaultValue = "false" },
            ),
        ) { entry ->
            val profileId = entry.arguments?.getString("profileId").orEmpty()
            val isNew = entry.arguments?.getString("isNew")?.toBoolean() ?: false
            ProfileEditScreen(
                profileId = profileId,
                isNew = isNew,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
                onOpenScan = { navController.navigate(Routes.scan("edit")) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenAppList = { navController.navigate(Routes.APP_LIST) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.RUNTIME_LOG) {
            RuntimeLogScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.APP_LIST) {
            AppListScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.SCAN,
            arguments = listOf(navArgument("returnTarget") { type = NavType.StringType; defaultValue = "profiles" }),
        ) { entry ->
            ScanScreen(
                onScanned = { code ->
                    navController.previousBackStackEntry?.savedStateHandle?.set("scan_result", code)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
