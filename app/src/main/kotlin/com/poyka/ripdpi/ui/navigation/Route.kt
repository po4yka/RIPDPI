package com.poyka.ripdpi.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import kotlinx.serialization.SerialName
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
    abstract val stableRoute: String

    @get:StringRes
    abstract val titleRes: Int

    abstract val icon: ImageVector?

    @Serializable
    data object Onboarding : Route() {
        override val stableRoute = "onboarding"
        override val titleRes = R.string.title_onboarding
        override val icon: ImageVector? = null
    }

    @Serializable
    data object Home : Route() {
        override val stableRoute = "home"
        override val titleRes = R.string.home
        override val icon: ImageVector = RipDpiIcons.Home
    }

    @Serializable
    data object Config : Route() {
        override val stableRoute = "config"
        override val titleRes = R.string.config
        override val icon: ImageVector = RipDpiIcons.Config
    }

    @Serializable
    data object LocalBypassConfig : Route() {
        override val stableRoute = "config/local_bypass"
        override val titleRes = R.string.title_local_bypass_config
        override val icon: ImageVector? = null
    }

    @Serializable
    data object VpnConfig : Route() {
        override val stableRoute = "config/vpn"
        override val titleRes = R.string.title_vpn_config
        override val icon: ImageVector? = null
    }

    @Serializable
    data object Settings : Route() {
        override val stableRoute = "settings"
        override val titleRes = R.string.settings
        override val icon: ImageVector = RipDpiIcons.Settings
    }

    @Serializable
    data class Diagnostics(
        @SerialName("auto_start_scan")
        val autoStartScan: Boolean = false,
    ) : Route() {
        @kotlinx.serialization.Transient
        override val stableRoute = "diagnostics"

        @kotlinx.serialization.Transient
        override val titleRes = R.string.diagnostics

        @kotlinx.serialization.Transient
        override val icon: ImageVector = RipDpiIcons.Logs
    }

    @Serializable
    data object History : Route() {
        override val stableRoute = "history"
        override val titleRes = R.string.history_title
        override val icon: ImageVector? = null
    }

    @Serializable
    data object Logs : Route() {
        override val stableRoute = "logs"
        override val titleRes = R.string.logs
        override val icon: ImageVector? = null
    }

    @Serializable
    data object ModeEditor : Route() {
        override val stableRoute = "mode_editor"
        override val titleRes = R.string.title_mode_editor
        override val icon: ImageVector? = null
    }

    @Serializable
    data object DnsSettings : Route() {
        override val stableRoute = "dns_settings"
        override val titleRes = R.string.title_dns_settings
        override val icon: ImageVector? = null
    }

    @Serializable
    data object AdvancedSettings : Route() {
        override val stableRoute = "advanced_settings"
        override val titleRes = R.string.title_advanced_settings
        override val icon: ImageVector? = null
    }

    @Serializable
    data object StrategyConfig : Route() {
        override val stableRoute = "strategy_config"
        override val titleRes = R.string.title_strategy_config
        override val icon: ImageVector? = null
    }

    @Serializable
    data object DomainBypassList : Route() {
        override val stableRoute = "domain_bypass_list"
        override val titleRes = R.string.title_domain_bypass_list
        override val icon: ImageVector? = null
    }

    @Serializable
    data object Routes : Route() {
        override val stableRoute = "routes"
        override val titleRes = R.string.title_routes
        override val icon: ImageVector = RipDpiIcons.Config
    }

    @Serializable
    data class RuleEditor(
        val ruleId: Long = 0L,
    ) : Route() {
        @kotlinx.serialization.Transient
        override val stableRoute = "rule_editor"

        @kotlinx.serialization.Transient
        override val titleRes = R.string.title_rule_editor

        @kotlinx.serialization.Transient
        override val icon: ImageVector? = null
    }

    @Serializable
    data object Blockcheck : Route() {
        override val stableRoute = "blockcheck"
        override val titleRes = R.string.title_blockcheck
        override val icon: ImageVector? = null
    }

    @Serializable
    data object BiometricPrompt : Route() {
        override val stableRoute = "biometric_prompt"
        override val titleRes = R.string.title_biometric_prompt
        override val icon: ImageVector? = null
    }

    @Serializable
    data object AppCustomization : Route() {
        override val stableRoute = "app_customization"
        override val titleRes = R.string.title_app_icon
        override val icon: ImageVector? = null
    }

    @Serializable
    data object About : Route() {
        override val stableRoute = "about"
        override val titleRes = R.string.about_category
        override val icon: ImageVector? = null
    }

    @Serializable
    data object DataTransparency : Route() {
        override val stableRoute = "data_transparency"
        override val titleRes = R.string.title_data_transparency
        override val icon: ImageVector? = null
    }

    @Serializable
    data object DetectionCheck : Route() {
        override val stableRoute = "detection_check"
        override val titleRes = R.string.title_detection_check
        override val icon: ImageVector? = null
    }

    @Serializable
    data object DetectionSettings : Route() {
        override val stableRoute = "detection_settings"
        override val titleRes = R.string.title_detection_check
        override val icon: ImageVector? = null
    }

    @Serializable
    data object PcapViewer : Route() {
        override val stableRoute = "pcap_viewer"
        override val titleRes = R.string.title_pcap_viewer
        override val icon: ImageVector? = null
    }

    @Serializable
    data object PcapCaptureList : Route() {
        override val stableRoute = "pcap_capture_list"
        override val titleRes = R.string.title_pcap_capture_list
        override val icon: ImageVector? = null
    }

    @Serializable
    data object ReplayFailure : Route() {
        override val stableRoute = "replay_failure"
        override val titleRes = R.string.title_replay_failure
        override val icon: ImageVector? = null
    }

    @Serializable
    data object ReplayHistory : Route() {
        override val stableRoute = "replay_history"
        override val titleRes = R.string.title_replay_history
        override val icon: ImageVector? = null
    }

    @Serializable
    data object HandshakeTimeline : Route() {
        override val stableRoute = "handshake_timeline"
        override val titleRes = R.string.title_handshake_timeline
        override val icon: ImageVector? = null
    }

    @Serializable
    data object ThroughputGraph : Route() {
        override val stableRoute = "throughput_graph"
        override val titleRes = R.string.title_throughput_graph
        override val icon: ImageVector? = null
    }

    @Serializable
    data object LatencyGraph : Route() {
        override val stableRoute = "latency_graph"
        override val titleRes = R.string.title_latency_graph
        override val icon: ImageVector? = null
    }

    @Serializable
    data object StateMachine : Route() {
        override val stableRoute = "state_machine"
        override val titleRes = R.string.title_state_machine
        override val icon: ImageVector? = null
    }

    @Serializable
    data object OomRecovery : Route() {
        override val stableRoute = "oom_recovery"
        override val titleRes = R.string.title_oom_recovery
        override val icon: ImageVector? = null
    }

    @Serializable
    data object StrategyAb : Route() {
        override val stableRoute = "strategy_ab"
        override val titleRes = R.string.title_strategy_ab
        override val icon: ImageVector? = null
    }

    @Serializable
    data object StrategyImport : Route() {
        override val stableRoute = "strategy_import"
        override val titleRes = R.string.title_strategy_import
        override val icon: ImageVector? = null
    }

    @Serializable
    data object ProfileVariants : Route() {
        override val stableRoute = "profile_variants"
        override val titleRes = R.string.title_profile_variants
        override val icon: ImageVector? = null
    }

    @Serializable
    data class SharedDiagnosticResult(
        val fragment: String = "",
    ) : Route() {
        @kotlinx.serialization.Transient
        override val stableRoute = "shared_diagnostic_result"

        @kotlinx.serialization.Transient
        override val titleRes = R.string.title_shared_diagnostic_result

        @kotlinx.serialization.Transient
        override val icon: ImageVector? = null
    }

    @Serializable
    data class OwnedStackBrowser(
        val initialUrl: String = "",
    ) : Route() {
        @kotlinx.serialization.Transient
        override val stableRoute = "owned_stack_browser"

        @kotlinx.serialization.Transient
        override val titleRes = R.string.title_owned_stack_browser

        @kotlinx.serialization.Transient
        override val icon: ImageVector? = RipDpiIcons.Public
    }

    @Serializable
    data class ProfileImportConfirm(
        val profileJson: String = "",
    ) : Route() {
        @kotlinx.serialization.Transient
        override val stableRoute = "import/profile_confirm"

        @kotlinx.serialization.Transient
        override val titleRes = R.string.import_profile_confirm_title

        @kotlinx.serialization.Transient
        override val icon: ImageVector? = null
    }

    @Serializable
    data class SubscriptionImportConfirm(
        val url: String = "",
        val name: String = "",
        val bootstrap: Boolean = false,
    ) : Route() {
        @kotlinx.serialization.Transient
        override val stableRoute = "import/subscription_confirm"

        @kotlinx.serialization.Transient
        override val titleRes = R.string.import_subscription_confirm_title

        @kotlinx.serialization.Transient
        override val icon: ImageVector? = null
    }

    @Serializable
    data object QrScanner : Route() {
        override val stableRoute = "scanner"
        override val titleRes = R.string.scanner_title
        override val icon: ImageVector? = RipDpiIcons.QrCodeScanner
    }

    @Serializable
    data object AmneziaWgProfile : Route() {
        override val stableRoute = "profile/amneziawg"
        override val titleRes = R.string.awg_editor_title
        override val icon: ImageVector? = null
    }

    companion object {
        val topLevel: List<Route>
            get() = listOf(Home, Config, Diagnostics(), Settings)

        val all: List<Route>
            get() =
                listOf(
                    Onboarding,
                    Home,
                    Config,
                    LocalBypassConfig,
                    VpnConfig,
                    Diagnostics(),
                    History,
                    Logs,
                    Settings,
                    ModeEditor,
                    DnsSettings,
                    AdvancedSettings,
                    StrategyConfig,
                    DomainBypassList,
                    Routes,
                    RuleEditor(),
                    Blockcheck,
                    BiometricPrompt,
                    AppCustomization,
                    About,
                    DataTransparency,
                    DetectionCheck,
                    DetectionSettings,
                    PcapViewer,
                    PcapCaptureList,
                    ReplayFailure,
                    ReplayHistory,
                    HandshakeTimeline,
                    ThroughputGraph,
                    LatencyGraph,
                    StateMachine,
                    OomRecovery,
                    StrategyAb,
                    StrategyImport,
                    ProfileVariants,
                    SharedDiagnosticResult(),
                    OwnedStackBrowser(),
                    ProfileImportConfirm(),
                    SubscriptionImportConfirm(),
                    QrScanner,
                    AmneziaWgProfile,
                )

        fun fromStableRoute(route: String?): Route? = route?.let { key -> all.firstOrNull { it.stableRoute == key } }
    }
}

internal val topLevelStableRoutes: Set<String> =
    Route.topLevel.map(Route::stableRoute).toSet()

internal fun String?.isTopLevelRoute(): Boolean = this != null && this in topLevelStableRoutes
