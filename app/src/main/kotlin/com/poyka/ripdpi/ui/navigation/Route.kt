package com.poyka.ripdpi.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import kotlinx.serialization.Serializable

/**
 * Navigation destinations for [RipDpiNavHost].
 *
 * Each leaf is a `@Serializable data object`, which Navigation Compose 2.8+ consumes as
 * a type-safe route. The sealed hierarchy keeps `titleRes` + `icon` metadata attached so
 * [BottomNavBar] and [com.poyka.ripdpi.ui.testing.RipDpiTestTags] can continue to read them
 * from the same source of truth.
 *
 * `route` is preserved as a stable string key for tests, telemetry, and top-level-tab
 * identification. It is not the serialized route key consumed by the navigation graph.
 */
sealed class Route {
    abstract val route: String

    @get:StringRes
    abstract val titleRes: Int

    abstract val icon: ImageVector?

    @Serializable
    data object Onboarding : Route() {
        override val route = "onboarding"
        override val titleRes = R.string.title_onboarding
        override val icon: ImageVector? = null
    }

    @Serializable
    data object Home : Route() {
        override val route = "home"
        override val titleRes = R.string.home
        override val icon: ImageVector = RipDpiIcons.Home
    }

    @Serializable
    data object Config : Route() {
        override val route = "config"
        override val titleRes = R.string.config
        override val icon: ImageVector = RipDpiIcons.Config
    }

    @Serializable
    data object Settings : Route() {
        override val route = "settings"
        override val titleRes = R.string.settings
        override val icon: ImageVector = RipDpiIcons.Settings
    }

    @Serializable
    data object Diagnostics : Route() {
        override val route = "diagnostics"
        override val titleRes = R.string.diagnostics
        override val icon: ImageVector = RipDpiIcons.Logs
    }

    @Serializable
    data object History : Route() {
        override val route = "history"
        override val titleRes = R.string.history_title
        override val icon: ImageVector? = null
    }

    @Serializable
    data object Logs : Route() {
        override val route = "logs"
        override val titleRes = R.string.logs
        override val icon: ImageVector? = null
    }

    @Serializable
    data object ModeEditor : Route() {
        override val route = "mode_editor"
        override val titleRes = R.string.title_mode_editor
        override val icon: ImageVector? = null
    }

    @Serializable
    data object DnsSettings : Route() {
        override val route = "dns_settings"
        override val titleRes = R.string.title_dns_settings
        override val icon: ImageVector? = null
    }

    @Serializable
    data object AdvancedSettings : Route() {
        override val route = "advanced_settings"
        override val titleRes = R.string.title_advanced_settings
        override val icon: ImageVector? = null
    }

    @Serializable
    data object BiometricPrompt : Route() {
        override val route = "biometric_prompt"
        override val titleRes = R.string.title_biometric_prompt
        override val icon: ImageVector? = null
    }

    @Serializable
    data object AppCustomization : Route() {
        override val route = "app_customization"
        override val titleRes = R.string.title_app_icon
        override val icon: ImageVector? = null
    }

    @Serializable
    data object About : Route() {
        override val route = "about"
        override val titleRes = R.string.about_category
        override val icon: ImageVector? = null
    }

    @Serializable
    data object DataTransparency : Route() {
        override val route = "data_transparency"
        override val titleRes = R.string.title_data_transparency
        override val icon: ImageVector? = null
    }

    @Serializable
    data object DetectionCheck : Route() {
        override val route = "detection_check"
        override val titleRes = R.string.title_detection_check
        override val icon: ImageVector? = null
    }

    companion object {
        val topLevel: List<Route>
            get() = listOf(Home, Config, Diagnostics, Settings)

        val all: List<Route>
            get() =
                listOf(
                    Onboarding,
                    Home,
                    Config,
                    Diagnostics,
                    History,
                    Logs,
                    Settings,
                    ModeEditor,
                    DnsSettings,
                    AdvancedSettings,
                    BiometricPrompt,
                    AppCustomization,
                    About,
                    DataTransparency,
                    DetectionCheck,
                )

        fun fromStableRoute(route: String?): Route? = route?.let { key -> all.firstOrNull { it.route == key } }
    }
}

internal val topLevelStableRoutes: Set<String> =
    Route.topLevel.map(Route::route).toSet()

internal fun String?.isTopLevelRoute(): Boolean = this != null && this in topLevelStableRoutes
