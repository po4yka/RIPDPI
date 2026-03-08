package com.poyka.ripdpi.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.theme.RipDpiIcons

sealed class Route(
    val route: String,
    @param:StringRes val titleRes: Int,
    val icon: ImageVector? = null,
) {
    data object Splash : Route(
        route = "splash",
        titleRes = R.string.title_splash,
    )

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

    data object AppCustomization : Route(
        route = "app_customization",
        titleRes = R.string.title_app_customization,
    )

    data object About : Route(
        route = "about",
        titleRes = R.string.about_category,
    )

    companion object {
        val topLevel = listOf(Home, Config, Settings, Logs)

        val all = listOf(
            Splash,
            Onboarding,
            Home,
            Config,
            Settings,
            Logs,
            ModeEditor,
            DnsSettings,
            AppCustomization,
            About,
        )
    }
}
