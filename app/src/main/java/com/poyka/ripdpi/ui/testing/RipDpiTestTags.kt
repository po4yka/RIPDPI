package com.poyka.ripdpi.ui.testing

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.LogType
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.settings.ActivationWindowDimension
import com.poyka.ripdpi.ui.screens.settings.AdvancedOptionSetting
import com.poyka.ripdpi.ui.screens.settings.AdvancedTextSetting
import com.poyka.ripdpi.ui.screens.settings.AdvancedToggleSetting
import java.util.Locale

internal object RipDpiTestTags {
    const val VpnPermissionDialog = "vpn-permission-dialog"
    const val VpnPermissionDialogContinue = "vpn-permission-dialog-continue"
    const val VpnPermissionDialogDismiss = "vpn-permission-dialog-dismiss"
    const val MainErrorSnackbar = "main-error-snackbar"

    const val HomeConnectionButton = "home-connection-button"
    const val HomeApproachCard = "home-approach-card"
    const val HomeHistoryCard = "home-history-card"
    const val HomeStatsGrid = "home-stats-grid"
    const val HomePermissionIssueBanner = "home-permission-issue-banner"
    const val HomePermissionRecommendationBanner = "home-permission-recommendation-banner"

    const val ConfigEditCurrentButton = "config-edit-current"
    const val ConfigDnsSettings = "config-dns-settings"
    const val ModeEditorCancel = "mode-editor-cancel"
    const val ModeEditorSave = "mode-editor-save"
    const val ModeEditorProxyIp = "mode-editor-proxy-ip"
    const val ModeEditorProxyPort = "mode-editor-proxy-port"
    const val ModeEditorMaxConnections = "mode-editor-max-connections"
    const val ModeEditorBufferSize = "mode-editor-buffer-size"
    const val ModeEditorChainDsl = "mode-editor-chain-dsl"
    const val ModeEditorDefaultTtl = "mode-editor-default-ttl"
    const val ModeEditorCommandLineToggle = "mode-editor-command-line-toggle"
    const val ModeEditorCommandLineArgs = "mode-editor-command-line-args"
    const val ModeEditorValidationSnackbar = "mode-editor-validation-snackbar"

    const val SettingsDnsSettings = "settings-dns-settings"
    const val SettingsAdvancedSettings = "settings-advanced-settings"
    const val SettingsWebRtcProtection = "settings-webrtc-protection"
    const val SettingsBiometric = "settings-biometric"
    const val SettingsBackupPinField = "settings-backup-pin-field"
    const val SettingsBackupPinSave = "settings-backup-pin-save"
    const val SettingsBackupPinClear = "settings-backup-pin-clear"
    const val SettingsBackupPinWarning = "settings-backup-pin-warning"
    const val SettingsThemeDropdown = "settings-theme-dropdown"
    const val SettingsCustomization = "settings-customization"
    const val SettingsSupportBundle = "settings-support-bundle"
    const val SettingsDataTransparency = "settings-data-transparency"
    const val SettingsAbout = "settings-about"

    const val DnsPlainAddress = "dns-plain-address"
    const val DnsPlainSave = "dns-plain-save"
    const val DnsCustomDohUrl = "dns-custom-doh-url"
    const val DnsCustomHost = "dns-custom-host"
    const val DnsCustomPort = "dns-custom-port"
    const val DnsCustomTlsServerName = "dns-custom-tls-server-name"
    const val DnsCustomBootstrap = "dns-custom-bootstrap"
    const val DnsCustomDnsCryptProvider = "dns-custom-dnscrypt-provider"
    const val DnsCustomDnsCryptPublicKey = "dns-custom-dnscrypt-public-key"
    const val DnsCustomSave = "dns-custom-save"

    const val CustomizationShapeInfo = "customization-shape-info"
    const val CustomizationThemedIcon = "customization-themed-icon"
    const val AboutSourceCode = "about-source-code"
    const val AboutReadme = "about-readme"

    const val OnboardingSkip = "onboarding-skip"
    const val OnboardingContinue = "onboarding-continue"

    const val BiometricPromptPrimaryAction = "biometric-prompt-primary-action"
    const val BiometricPromptSecondaryAction = "biometric-prompt-secondary-action"
    const val BiometricPromptPinField = "biometric-prompt-pin-field"

    const val DiagnosticsTopHistoryAction = "diagnostics-top-history-action"
    const val DiagnosticsOverviewHistoryAction = "diagnostics-overview-history-action"
    const val DiagnosticsOverviewHero = "diagnostics-overview-hero"
    const val DiagnosticsResolverKeepSession = "diagnostics-resolver-keep-session"
    const val DiagnosticsResolverSaveSetting = "diagnostics-resolver-save-setting"
    const val DiagnosticsResolverRecommendationCard = "diagnostics-resolver-recommendation-card"
    const val DiagnosticsStrategyProbeReport = "diagnostics-strategy-probe-report"
    const val DiagnosticsStrategyProbeSummary = "diagnostics-strategy-probe-summary"
    const val DiagnosticsSharePreviewCard = "diagnostics-share-preview-card"
    const val DiagnosticsArchiveStateIndicator = "diagnostics-archive-state-indicator"
    const val DiagnosticsSessionDetailSheet = "diagnostics-session-detail-sheet"
    const val DiagnosticsProbeDetailSheet = "diagnostics-probe-detail-sheet"
    const val DiagnosticsStrategyCandidateDetailSheet = "diagnostics-strategy-candidate-detail-sheet"
    const val DiagnosticsStrategyCandidateNotesSection = "diagnostics-strategy-candidate-notes-section"
    const val DiagnosticsStrategyCandidateSignatureSection = "diagnostics-strategy-candidate-signature-section"
    const val DiagnosticsStrategyCandidateResultsSection = "diagnostics-strategy-candidate-results-section"
    const val DiagnosticsSessionsSearch = "diagnostics-sessions-search"
    const val DiagnosticsEventsSearch = "diagnostics-events-search"
    const val DiagnosticsEventsAutoScroll = "diagnostics-events-auto-scroll"
    const val DiagnosticsShareArchive = "diagnostics-share-archive"
    const val DiagnosticsSaveArchive = "diagnostics-save-archive"
    const val DiagnosticsSaveLogs = "diagnostics-save-logs"
    const val DiagnosticsShareSummary = "diagnostics-share-summary"
    const val DiagnosticsStatusSnackbar = "diagnostics-status-snackbar"

    const val LogsScreen = "logs-screen"
    const val LogsAutoScroll = "logs-auto-scroll"
    const val LogsSave = "logs-save"
    const val LogsClear = "logs-clear"
    const val LogsStream = "logs-stream"

    const val AdvancedClearRememberedNetworks = "advanced-clear-remembered-networks"
    const val AdvancedCommandLineWarning = "advanced-command-line-warning"
    const val AdvancedNoticeBanner = "advanced-notice-banner"

    const val HistoryConnectionsSearch = "history-connections-search"
    const val HistoryDiagnosticsSearch = "history-diagnostics-search"
    const val HistoryEventsSearch = "history-events-search"
    const val HistoryEventsAutoScroll = "history-events-auto-scroll"

    fun screen(route: Route): String = "${route.route}-screen"

    fun bottomNav(route: Route): String = "bottom-nav-${route.route}"

    fun configPreset(presetId: String): String = "config-preset-${sanitize(presetId)}"

    fun configMode(modeKey: String): String = "config-mode-${sanitize(modeKey)}"

    fun settingsPermission(kind: PermissionKind): String = "settings-permission-${sanitize(kind.name)}"

    fun dnsResolver(providerId: String): String = "dns-resolver-${sanitize(providerId)}"

    fun customizationIcon(key: String): String = "customization-icon-${sanitize(key)}"

    fun diagnosticsSection(section: DiagnosticsSection): String = "diagnostics-section-${sanitize(section.name)}"

    fun diagnosticsApproachMode(mode: DiagnosticsApproachMode): String =
        "diagnostics-approach-mode-${sanitize(mode.name)}"

    fun diagnosticsStrategyCandidate(candidateId: String): String =
        "diagnostics-strategy-candidate-${sanitize(candidateId)}"

    fun diagnosticsSessionPathFilter(pathMode: String): String = "diagnostics-session-path-${sanitize(pathMode)}"

    fun diagnosticsSessionStatusFilter(status: String): String = "diagnostics-session-status-${sanitize(status)}"

    fun diagnosticsEventSourceFilter(source: String): String = "diagnostics-event-source-${sanitize(source)}"

    fun diagnosticsEventSeverityFilter(severity: String): String = "diagnostics-event-severity-${sanitize(severity)}"

    fun logsFilter(type: LogType): String = "logs-filter-${sanitize(type.name)}"

    fun historySection(section: HistorySection): String = "history-section-${sanitize(section.name)}"

    fun historyConnectionsModeFilter(mode: String): String = "history-connections-mode-${sanitize(mode)}"

    fun historyConnectionsStatusFilter(status: String): String = "history-connections-status-${sanitize(status)}"

    fun historyDiagnosticsPathFilter(pathMode: String): String = "history-diagnostics-path-${sanitize(pathMode)}"

    fun historyDiagnosticsStatusFilter(status: String): String = "history-diagnostics-status-${sanitize(status)}"

    fun historyEventSourceFilter(source: String): String = "history-event-source-${sanitize(source)}"

    fun historyEventSeverityFilter(severity: String): String = "history-event-severity-${sanitize(severity)}"

    fun advancedSection(sectionKey: String): String = "advanced-section-${sanitize(sectionKey)}"

    fun advancedTitle(key: String): String = "advanced-title-${sanitize(key)}"

    fun advancedDescription(key: String): String = "advanced-description-${sanitize(key)}"

    fun advancedToggle(setting: AdvancedToggleSetting): String = "advanced-toggle-${sanitize(setting.name)}"

    fun advancedInput(setting: AdvancedTextSetting): String = "advanced-input-${sanitize(setting.name)}"

    fun advancedSave(setting: AdvancedTextSetting): String = "advanced-save-${sanitize(setting.name)}"

    fun advancedOption(setting: AdvancedOptionSetting): String = "advanced-option-${sanitize(setting.name)}"

    fun activationStart(dimension: ActivationWindowDimension): String = "advanced-${sanitize(dimension.name)}-from"

    fun activationEnd(dimension: ActivationWindowDimension): String = "advanced-${sanitize(dimension.name)}-to"

    fun activationSave(dimension: ActivationWindowDimension): String = "advanced-${sanitize(dimension.name)}-save"

    fun advancedSummaryLabel(key: String): String = "advanced-summary-label-${sanitize(key)}"

    fun advancedSummaryValue(key: String): String = "advanced-summary-value-${sanitize(key)}"

    fun advancedCapsule(key: String): String = "advanced-capsule-${sanitize(key)}"

    private fun sanitize(value: String): String =
        value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-')
            .lowercase(Locale.ROOT)
}

internal fun Modifier.ripDpiTestTag(tag: String?): Modifier =
    if (tag.isNullOrBlank()) {
        this
    } else {
        this.testTag(tag)
    }

internal fun Modifier.ripDpiAutomationTreeRoot(): Modifier =
    semantics {
        testTagsAsResourceId = true
    }
