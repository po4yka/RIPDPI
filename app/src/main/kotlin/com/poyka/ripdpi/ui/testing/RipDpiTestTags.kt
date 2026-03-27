package com.poyka.ripdpi.ui.testing

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
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
    const val WarningBannerDismiss = "warning-banner-dismiss"
    const val HomeErrorBanner = "home-error-banner"
    const val HomePermissionIssueBanner = "home-permission-issue-banner"
    const val HomePermissionRecommendationBanner = "home-permission-recommendation-banner"
    const val HomeBackgroundGuidanceBanner = "home-background-guidance-banner"

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
    const val SettingsBiometricConfirmDialog = "settings-biometric-confirm-dialog"
    const val SettingsBiometricConfirmEnable = "settings-biometric-confirm-enable"
    const val SettingsBiometricConfirmCancel = "settings-biometric-confirm-cancel"
    const val SettingsBackupPinField = "settings-backup-pin-field"
    const val SettingsBackupPinSave = "settings-backup-pin-save"
    const val SettingsBackupPinClear = "settings-backup-pin-clear"
    const val SettingsBackupPinWarning = "settings-backup-pin-warning"
    const val SettingsThemeDropdown = "settings-theme-dropdown"
    const val SettingsCustomization = "settings-customization"
    const val SettingsBackgroundGuidanceBanner = "settings-background-guidance-banner"
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
    const val DiagnosticsOverviewAutomaticProbeCard = "diagnostics-overview-automatic-probe-card"
    const val DiagnosticsOverviewHero = "diagnostics-overview-hero"
    const val DiagnosticsResolverKeepSession = "diagnostics-resolver-keep-session"
    const val DiagnosticsResolverSaveSetting = "diagnostics-resolver-save-setting"
    const val DiagnosticsResolverRecommendationCard = "diagnostics-resolver-recommendation-card"
    const val DiagnosticsStrategyProbeReport = "diagnostics-strategy-probe-report"
    const val DiagnosticsStrategyProbeSummary = "diagnostics-strategy-probe-summary"
    const val DiagnosticsStrategyWinningPath = "diagnostics-strategy-winning-path"
    const val DiagnosticsStrategyWinningTcpAction = "diagnostics-strategy-winning-tcp-action"
    const val DiagnosticsStrategyWinningQuicAction = "diagnostics-strategy-winning-quic-action"
    const val DiagnosticsStrategyFullMatrixToggle = "diagnostics-strategy-full-matrix-toggle"
    const val DiagnosticsStrategyAuditAssessment = "diagnostics-strategy-audit-assessment"
    const val DiagnosticsStrategyAuditLowConfidenceBanner = "diagnostics-strategy-audit-low-confidence-banner"
    const val DiagnosticsStrategyAuditMediumConfidenceNote = "diagnostics-strategy-audit-medium-confidence-note"
    const val DiagnosticsWorkflowRestrictionCard = "diagnostics-workflow-restriction-card"
    const val DiagnosticsWorkflowRestrictionAction = "diagnostics-workflow-restriction-action"
    const val DiagnosticsSharePreviewCard = "diagnostics-share-preview-card"
    const val DiagnosticsArchiveStateIndicator = "diagnostics-archive-state-indicator"
    const val DiagnosticsSessionDetailSheet = "diagnostics-session-detail-sheet"
    const val DiagnosticsEventDetailSheet = "diagnostics-event-detail-sheet"
    const val DiagnosticsApproachDetailSheet = "diagnostics-approach-detail-sheet"
    const val DiagnosticsProbeDetailSheet = "diagnostics-probe-detail-sheet"
    const val DiagnosticsStrategyCandidateDetailSheet = "diagnostics-strategy-candidate-detail-sheet"
    const val DiagnosticsStrategyCandidateNotesSection = "diagnostics-strategy-candidate-notes-section"
    const val DiagnosticsStrategyCandidateSignatureSection = "diagnostics-strategy-candidate-signature-section"
    const val DiagnosticsStrategyCandidateResultsSection = "diagnostics-strategy-candidate-results-section"
    const val DiagnosticsScanStateIdle = "diagnostics-scan-state-idle"
    const val DiagnosticsScanStateProgress = "diagnostics-scan-state-progress"
    const val DiagnosticsScanStateContent = "diagnostics-scan-state-content"
    const val DiagnosticsScanProgressCard = "diagnostics-scan-progress-card"
    const val DiagnosticsScanRunRawAction = "diagnostics-scan-run-raw"
    const val DiagnosticsScanRunInPathAction = "diagnostics-scan-run-in-path"
    const val DiagnosticsScanCancelAction = "diagnostics-scan-cancel"
    const val DiagnosticsHiddenProbeConflictDialog = "diagnostics-hidden-probe-conflict-dialog"
    const val DiagnosticsHiddenProbeConflictWait = "diagnostics-hidden-probe-conflict-wait"
    const val DiagnosticsHiddenProbeConflictCancelAndRun = "diagnostics-hidden-probe-conflict-cancel-and-run"
    const val DiagnosticsHiddenProbeConflictDismiss = "diagnostics-hidden-probe-conflict-dismiss"
    const val DiagnosticsSessionSensitiveToggle = "diagnostics-session-sensitive-toggle"
    const val DiagnosticsSessionsSearch = "diagnostics-sessions-search"
    const val DiagnosticsEventsSearch = "diagnostics-events-search"
    const val DiagnosticsEventsAutoScroll = "diagnostics-events-auto-scroll"
    const val DiagnosticsShareArchive = "diagnostics-share-archive"
    const val DiagnosticsSaveArchive = "diagnostics-save-archive"
    const val DiagnosticsSaveLogs = "diagnostics-save-logs"
    const val DiagnosticsShareSummary = "diagnostics-share-summary"
    const val DiagnosticsStatusSnackbar = "diagnostics-status-snackbar"
    const val DiagnosticsSessionsStateEmpty = "diagnostics-sessions-state-empty"
    const val DiagnosticsSessionsStateContent = "diagnostics-sessions-state-content"
    const val DiagnosticsEventsStateEmpty = "diagnostics-events-state-empty"
    const val DiagnosticsEventsStateContent = "diagnostics-events-state-content"

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
    const val HistoryFilterClearAll = "history-filter-clear-all"
    const val HistoryConnectionsStateEmpty = "history-connections-state-empty"
    const val HistoryConnectionsStateContent = "history-connections-state-content"
    const val HistoryDiagnosticsStateEmpty = "history-diagnostics-state-empty"
    const val HistoryDiagnosticsStateContent = "history-diagnostics-state-content"
    const val HistoryEventsStateEmpty = "history-events-state-empty"
    const val HistoryEventsStateContent = "history-events-state-content"
    const val HistoryConnectionDetailSheet = "history-connection-detail-sheet"
    const val HistoryDiagnosticsDetailSheet = "history-diagnostics-detail-sheet"
    const val HistoryEventDetailSheet = "history-event-detail-sheet"

    const val CustomizationShapeInfoSheet = "customization-shape-info-sheet"
    const val CustomizationShapeInfoSheetConfirm = "customization-shape-info-sheet-confirm"

    const val HostPackApplyDialog = "host-pack-apply-dialog"
    const val HostPackApplyDismiss = "host-pack-apply-dismiss"
    const val HostPackApplyConfirm = "host-pack-apply-confirm"
    const val HostPackTargetDropdown = "host-pack-target-dropdown"
    const val HostPackApplyModeDropdown = "host-pack-apply-mode-dropdown"

    val screen: (Route) -> String = { route -> "${route.route}-screen" }

    val bottomNav: (Route) -> String = { route -> "bottom-nav-${route.route}" }

    val configPreset: (String) -> String = { presetId -> "config-preset-${sanitize(presetId)}" }

    val configMode: (String) -> String = { modeKey -> "config-mode-${sanitize(modeKey)}" }

    val settingsPermission: (PermissionKind) -> String = { kind -> "settings-permission-${sanitize(kind.name)}" }

    val dnsResolver: (String) -> String = { providerId -> "dns-resolver-${sanitize(providerId)}" }

    val customizationIcon: (String) -> String = { key -> "customization-icon-${sanitize(key)}" }

    val diagnosticsSection: (DiagnosticsSection) -> String =
        { section -> "diagnostics-section-${sanitize(section.name)}" }

    val diagnosticsApproachMode: (DiagnosticsApproachMode) -> String =
        { mode -> "diagnostics-approach-mode-${sanitize(mode.name)}" }

    val diagnosticsStrategyCandidate: (String) -> String =
        { candidateId -> "diagnostics-strategy-candidate-${sanitize(candidateId)}" }

    val diagnosticsProfile: (String) -> String =
        { profileId -> "diagnostics-profile-${sanitize(profileId)}" }

    val diagnosticsSession: (String) -> String =
        { sessionId -> "diagnostics-session-${sanitize(sessionId)}" }

    val diagnosticsProbe: (String) -> String =
        { probeId -> "diagnostics-probe-${sanitize(probeId)}" }

    val diagnosticsEvent: (String) -> String =
        { eventId -> "diagnostics-event-${sanitize(eventId)}" }

    val diagnosticsLiveProbe: (String) -> String =
        { probeKey -> "diagnostics-live-probe-${sanitize(probeKey)}" }

    val diagnosticsSessionPathFilter: (String) -> String =
        { pathMode -> "diagnostics-session-path-${sanitize(pathMode)}" }

    val diagnosticsSessionStatusFilter: (String) -> String =
        { status -> "diagnostics-session-status-${sanitize(status)}" }

    val diagnosticsEventSourceFilter: (String) -> String =
        { source -> "diagnostics-event-source-${sanitize(source)}" }

    val diagnosticsEventSeverityFilter: (String) -> String =
        { severity -> "diagnostics-event-severity-${sanitize(severity)}" }

    val logsFilter: (LogType) -> String = { type -> "logs-filter-${sanitize(type.name)}" }
    val logsSubsystemFilter: (LogSubsystem) -> String = { type -> "logs-subsystem-${sanitize(type.name)}" }
    val logsSeverityFilter: (LogSeverity) -> String = { severity -> "logs-severity-${sanitize(severity.name)}" }

    val historySection: (HistorySection) -> String = { section -> "history-section-${sanitize(section.name)}" }

    val historyConnectionsModeFilter: (String) -> String =
        { mode -> "history-connections-mode-${sanitize(mode)}" }

    val historyConnection: (String) -> String =
        { sessionId -> "history-connection-${sanitize(sessionId)}" }

    val historyConnectionRememberedBadge: (String) -> String =
        { sessionId -> "history-connection-remembered-badge-${sanitize(sessionId)}" }

    val historyDiagnosticsSession: (String) -> String =
        { sessionId -> "history-diagnostics-${sanitize(sessionId)}" }

    val historyDiagnosticsAutomaticBadge: (String) -> String =
        { sessionId -> "history-diagnostics-automatic-badge-${sanitize(sessionId)}" }

    val historyEvent: (String) -> String =
        { eventId -> "history-event-${sanitize(eventId)}" }

    val historyConnectionsStatusFilter: (String) -> String =
        { status -> "history-connections-status-${sanitize(status)}" }

    val historyDiagnosticsPathFilter: (String) -> String =
        { pathMode -> "history-diagnostics-path-${sanitize(pathMode)}" }

    val historyDiagnosticsStatusFilter: (String) -> String =
        { status -> "history-diagnostics-status-${sanitize(status)}" }

    val historyEventSourceFilter: (String) -> String =
        { source -> "history-event-source-${sanitize(source)}" }

    val historyEventSeverityFilter: (String) -> String =
        { severity -> "history-event-severity-${sanitize(severity)}" }

    val advancedSection: (String) -> String = { sectionKey -> "advanced-section-${sanitize(sectionKey)}" }

    val advancedTitle: (String) -> String = { key -> "advanced-title-${sanitize(key)}" }

    val advancedDescription: (String) -> String = { key -> "advanced-description-${sanitize(key)}" }

    val advancedToggle: (AdvancedToggleSetting) -> String =
        { setting -> "advanced-toggle-${sanitize(setting.name)}" }

    val advancedInput: (AdvancedTextSetting) -> String =
        { setting -> "advanced-input-${sanitize(setting.name)}" }

    val advancedSave: (AdvancedTextSetting) -> String =
        { setting -> "advanced-save-${sanitize(setting.name)}" }

    val advancedOption: (AdvancedOptionSetting) -> String =
        { setting -> "advanced-option-${sanitize(setting.name)}" }

    val activationStart: (ActivationWindowDimension) -> String =
        { dimension -> "advanced-${sanitize(dimension.name)}-from" }

    val activationEnd: (ActivationWindowDimension) -> String =
        { dimension -> "advanced-${sanitize(dimension.name)}-to" }

    val activationSave: (ActivationWindowDimension) -> String =
        { dimension -> "advanced-${sanitize(dimension.name)}-save" }

    val advancedSummaryLabel: (String) -> String =
        { key -> "advanced-summary-label-${sanitize(key)}" }

    val advancedSummaryValue: (String) -> String =
        { key -> "advanced-summary-value-${sanitize(key)}" }

    val advancedCapsule: (String) -> String = { key -> "advanced-capsule-${sanitize(key)}" }

    val hostPackTargetOption: (String) -> String =
        { value -> "host-pack-target-${sanitize(value)}" }

    val hostPackApplyModeOption: (String) -> String =
        { value -> "host-pack-apply-mode-${sanitize(value)}" }

    fun dropdownOption(
        tag: String,
        value: String,
    ): String = "$tag-option-${sanitize(value)}"

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
