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
    data object BackupRestore : Route() {
        override val stableRoute = "backup_restore"
        override val titleRes = R.string.title_backup_restore
        override val icon: ImageVector? = null
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
    data object ConnectionHealth : Route() {
        override val stableRoute = "connection_health"
        override val titleRes = R.string.title_connection_health
        override val icon: ImageVector? = RipDpiIcons.NetworkCheck
    }

    @Serializable
    data object SubscriptionFailover : Route() {
        override val stableRoute = "subscription_failover"
        override val titleRes = R.string.title_subscription_failover
        override val icon: ImageVector? = RipDpiIcons.NetworkCheck
    }

    @Serializable
    data object SubscriptionStatus : Route() {
        override val stableRoute = "subscription_status"
        override val titleRes = R.string.subscription_status_title
        override val icon: ImageVector? = RipDpiIcons.NetworkCheck
    }

    @Serializable
    data object StrategyTuner : Route() {
        override val stableRoute = "strategy_tuner"
        override val titleRes = R.string.title_strategy_tuner
        override val icon: ImageVector? = RipDpiIcons.Advanced
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
    data object RememberedNetworks : Route() {
        override val stableRoute = "remembered_networks"
        override val titleRes = R.string.title_remembered_networks
        override val icon: ImageVector? = null
    }

    @Serializable
    data object RootModeStrategies : Route() {
        override val stableRoute = "root_mode_strategies"
        override val titleRes = R.string.title_root_mode_strategies
        override val icon: ImageVector? = null
    }

    @Serializable
    data object DomainBypassList : Route() {
        override val stableRoute = "domain_bypass_list"
        override val titleRes = R.string.title_domain_bypass_list
        override val icon: ImageVector? = null
    }

    @Serializable
    data object AssetProvider : Route() {
        override val stableRoute = "asset_provider"
        override val titleRes = R.string.title_asset_provider
        override val icon: ImageVector? = null
    }

    @Serializable
    data object SplitTunnel : Route() {
        override val stableRoute = "split_tunnel"
        override val titleRes = R.string.title_split_tunnel
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
    data object ReplayHistory : Route() {
        override val stableRoute = "replay_history"
        override val titleRes = R.string.title_replay_history
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
        val importToken: String = "",
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
        val importToken: String = "",
    ) : Route() {
        @kotlinx.serialization.Transient
        override val stableRoute = "import/subscription_confirm"

        @kotlinx.serialization.Transient
        override val titleRes = R.string.import_subscription_confirm_title

        @kotlinx.serialization.Transient
        override val icon: ImageVector? = null
    }

    @Serializable
    data class SupportSettings(
        val packageJson: String = "",
    ) : Route() {
        @kotlinx.serialization.Transient
        override val stableRoute = "support/settings"

        @kotlinx.serialization.Transient
        override val titleRes = R.string.support_settings_title

        @kotlinx.serialization.Transient
        override val icon: ImageVector? = null
    }

    @Serializable
    data class ProfileShare(
        val profileId: String = "",
    ) : Route() {
        @kotlinx.serialization.Transient
        override val stableRoute = "profile/share"

        @kotlinx.serialization.Transient
        override val titleRes = R.string.profile_share_title

        @kotlinx.serialization.Transient
        override val icon: ImageVector? = RipDpiIcons.Share
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

    @Serializable
    data object XrayImport : Route() {
        override val stableRoute = "xray/import"
        override val titleRes = R.string.xray_import_title
        override val icon: ImageVector? = null
    }

    @Serializable
    data object AnyTlsProfile : Route() {
        override val stableRoute = "profile/anytls"
        override val titleRes = R.string.anytls_editor_title
        override val icon: ImageVector? = null
    }

    @Serializable
    data object MieruProfile : Route() {
        override val stableRoute = "profile/mieru"
        override val titleRes = R.string.mieru_editor_title
        override val icon: ImageVector? = null
    }

    @Serializable
    data object SshProfile : Route() {
        override val stableRoute = "profile/ssh"
        override val titleRes = R.string.ssh_editor_title
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
                    ConnectionHealth,
                    SubscriptionFailover,
                    SubscriptionStatus,
                    StrategyTuner,
                    Settings,
                    BackupRestore,
                    ModeEditor,
                    DnsSettings,
                    AdvancedSettings,
                    StrategyConfig,
                    RememberedNetworks,
                    RootModeStrategies,
                    DomainBypassList,
                    AssetProvider,
                    SplitTunnel,
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
                    ReplayHistory,
                    SharedDiagnosticResult(),
                    OwnedStackBrowser(),
                    ProfileImportConfirm(),
                    SubscriptionImportConfirm(),
                    SupportSettings(),
                    ProfileShare(),
                    QrScanner,
                    AmneziaWgProfile,
                    XrayImport,
                    AnyTlsProfile,
                    MieruProfile,
                    SshProfile,
                )

        fun fromStableRoute(route: String?): Route? = route?.let { key -> all.firstOrNull { it.stableRoute == key } }
    }
}

internal val topLevelStableRoutes: Set<String> =
    Route.topLevel.map(Route::stableRoute).toSet()

internal fun String?.isTopLevelRoute(): Boolean = this != null && this in topLevelStableRoutes
