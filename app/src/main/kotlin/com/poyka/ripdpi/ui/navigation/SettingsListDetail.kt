package com.poyka.ripdpi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.screens.settings.LocalSelectedSettingsDetail
import com.poyka.ripdpi.ui.screens.settings.SettingsRoute
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.RipDpiWidthClass

/** Settings destinations that open in the detail pane beside the Settings list on wide windows. */
internal val settingsDetailRoutes: List<Route> =
    listOf(
        Route.DnsSettings,
        Route.SubscriptionStatus,
        Route.ProfileUtility,
        Route.SubscriptionFailover,
        Route.AdvancedSettings,
        Route.DomainBypassList,
        Route.Routes,
        Route.SplitTunnel,
        Route.RootModeStrategies,
        Route.DataTransparency,
        Route.AppCustomization,
        Route.DetectionCheck,
        Route.About,
        Route.BackupRestore,
    )

/** The Settings list-detail pair on the back stack; [selected] is null while Settings itself is current. */
internal data class SettingsListDetail(
    val selected: Route?,
)

/**
 * Returns the list-detail pair when [current] is Settings, or a Settings detail opened from the
 * Settings list ([previous] is Settings). Deep links and launch routes that open a detail directly
 * have no Settings entry below them, so they keep the one-pane layout.
 */
internal fun settingsListDetail(
    current: NavDestination?,
    previous: NavDestination?,
): SettingsListDetail? =
    when {
        current == null -> null
        current.hasRoute<Route.Settings>() -> SettingsListDetail(selected = null)
        previous?.hasRoute<Route.Settings>() != true -> null
        else -> settingsDetailRoutes.firstOrNull { current.hasRoute(it::class) }?.let(::SettingsListDetail)
    }

/** The pair to lay out side by side for [entry]: only on expanded and wider windows, else null for one pane. */
@Composable
internal fun NavHostController.settingsListDetailFor(entry: NavBackStackEntry?): SettingsListDetail? {
    val previous = remember(entry) { previousBackStackEntry?.destination }
    return settingsListDetail(entry?.destination, previous)
        ?.takeIf { RipDpiThemeTokens.layout.widthClass >= RipDpiWidthClass.Expanded }
}

/** Opens a Settings detail; selecting another item beside the list replaces the detail instead of stacking it. */
internal fun <T : Route> NavHostController.navigateSettingsDetail(route: T) {
    navigate(route) {
        popUpTo<Route.Settings>()
        launchSingleTop = true
    }
}

/** The Settings list shown in the list pane beside a Settings detail. */
@Composable
internal fun SettingsListPane(
    navController: NavHostController,
    selected: Route?,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    permissionSummary: PermissionSummaryUiState,
) {
    // The graph entry leaves the back stack before the pane finishes its exit; show nothing then.
    val settingsGraphEntry = runCatching { navController.getBackStackEntry<SettingsGraph>() }.getOrNull() ?: return
    CompositionLocalProvider(LocalSelectedSettingsDetail provides selected) {
        SettingsHomeRoute(navController, settingsGraphEntry, actions, mainViewModel, permissionSummary)
    }
}

@Composable
internal fun SettingsHomeRoute(
    navController: NavHostController,
    settingsGraphEntry: NavBackStackEntry,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    permissionSummary: PermissionSummaryUiState,
) {
    val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
    SettingsRoute(
        onOpenDnsSettings = { navController.navigateSettingsDetail(Route.DnsSettings) },
        onOpenAdvancedSettings = { navController.navigateSettingsDetail(Route.AdvancedSettings) },
        onOpenCustomization = { navController.navigateSettingsDetail(Route.AppCustomization) },
        onOpenAbout = { navController.navigateSettingsDetail(Route.About) },
        onOpenDataTransparency = { navController.navigateSettingsDetail(Route.DataTransparency) },
        onOpenDetectionCheck = { navController.navigateSettingsDetail(Route.DetectionCheck) },
        onOpenSubscriptionFailover = { navController.navigateSettingsDetail(Route.SubscriptionFailover) },
        onOpenSubscriptionStatus = { navController.navigateSettingsDetail(Route.SubscriptionStatus) },
        onOpenProfileUtility = { navController.navigateSettingsDetail(Route.ProfileUtility) },
        onOpenDomainBypass = { navController.navigateSettingsDetail(Route.DomainBypassList) },
        onOpenRoutingRules = { navController.navigateSettingsDetail(Route.Routes) },
        onOpenSplitTunnel = { navController.navigateSettingsDetail(Route.SplitTunnel) },
        onOpenRootModeStrategies = { navController.navigateSettingsDetail(Route.RootModeStrategies) },
        onOpenBackupRestore = { navController.navigateSettingsDetail(Route.BackupRestore) },
        onOpenLogs = { navController.navigate(Route.Logs) { launchSingleTop = true } },
        onShareDebugBundle = actions.onShareDebugBundle,
        permissionSummary = permissionSummary,
        onRepairPermission = actions.onRepairPermission,
        onOpenVpnPermissionDialog = mainViewModel::onOpenVpnPermissionRequested,
        onDismissBackgroundGuidance = mainViewModel::onDismissBackgroundGuidance,
        viewModel = settingsViewModel,
    )
}
