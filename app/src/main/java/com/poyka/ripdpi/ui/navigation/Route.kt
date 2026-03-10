package com.poyka.ripdpi.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.theme.RipDpiIcons

internal val topLevelRouteOrder =
    listOf(
        "home",
        "config",
        "diagnostics",
        "settings",
    )

sealed class Route(
    val route: String,
    @param:StringRes val titleRes: Int,
    val icon: ImageVector? = null,
) {
    data object Onboarding : Route(
        route = "onboarding",
        titleRes = R.string.title_onboarding,
    )

    data object Home : Route(
        route = "home",
        titleRes = R.string.home,
        icon = RipDpiIcons.Home,
    )

    data object Config : Route(
        route = "config",
        titleRes = R.string.config,
        icon = RipDpiIcons.Config,
    )

    data object Settings : Route(
        route = "settings",
        titleRes = R.string.settings,
        icon = RipDpiIcons.Settings,
    )

    data object Diagnostics : Route(
        route = "diagnostics",
        titleRes = R.string.diagnostics,
        icon = RipDpiIcons.Logs,
    )

    data object Logs : Route(
        route = "logs",
        titleRes = R.string.logs,
        icon = RipDpiIcons.Logs,
    )

    data object ModeEditor : Route(
        route = "mode_editor",
        titleRes = R.string.title_mode_editor,
    )

    data object DnsSettings : Route(
        route = "dns_settings",
        titleRes = R.string.title_dns_settings,
    )

    data object AdvancedSettings : Route(
        route = "advanced_settings",
        titleRes = R.string.title_advanced_settings,
    )

    data object VpnPermission : Route(
        route = "vpn_permission",
        titleRes = R.string.title_vpn_permission,
    )

    data object BiometricPrompt : Route(
        route = "biometric_prompt",
        titleRes = R.string.title_biometric_prompt,
    )

    data object AppCustomization : Route(
        route = "app_customization",
        titleRes = R.string.title_app_icon,
    )

    data object About : Route(
        route = "about",
        titleRes = R.string.about_category,
    )

    companion object {
        val topLevel = listOf(Home, Config, Diagnostics, Settings)

        val all =
            listOf(
                Onboarding,
                Home,
                Config,
                Diagnostics,
                Logs,
                Settings,
                ModeEditor,
                DnsSettings,
                AdvancedSettings,
                VpnPermission,
                BiometricPrompt,
                AppCustomization,
                About,
            )
    }
}
