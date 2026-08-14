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
    const val StartupRecoveryPending = "startup-recovery-pending"
    const val StartupRecoveryFailure = "startup-recovery-failure"
    const val StartupRecoveryRetry = "startup-recovery-retry"
    const val BottomNavBar = "bottom-nav-bar"
    const val BottomNavIndicator = "bottom-nav-indicator"

    const val ConnectionActuatorButton = "connection-actuator-button"
    const val ConnectionActuatorRail = "connection-actuator-rail"
    const val ConnectionActuatorRouteLabel = "connection-actuator-route-label"
    const val ConnectionActuatorActionLabel = "connection-actuator-action-label"
    const val ConnectionActuatorTerminalLabel = "connection-actuator-terminal-label"
    const val RouteStack = "route-stack"
    const val RouteOpportunityPanel = "route-opportunity-panel"
    const val HomeStatusCard = "home-status-card"
    const val HomeDiagnosticsCard = "home-diagnostics-card"
    const val HomeDiagnosticsRunAnalysis = "home-diagnostics-run-analysis"
    const val HomeDiagnosticsVerifiedVpn = "home-diagnostics-verified-vpn"
    const val HomeDiagnosticsPcapToggle = "home-diagnostics-pcap-toggle"
    const val HomeDiagnosticsVpnDetectorsCard = "home-diagnostics-vpn-detectors-card"
    const val HomeDiagnosticsDetectionSummary = "home-diagnostics-detection-summary"
    const val HomeDiagnosticsAnalysisSheet = "home-diagnostics-analysis-sheet"
    const val HomeDiagnosticsVerificationSheet = "home-diagnostics-verification-sheet"
    const val HomeDiagnosticsShareAction = "home-diagnostics-share-action"
    const val SimpleAboutAction = "simple-about-action"
    const val HomeDiagnosticsOpenDiagnosticsAction = "home-diagnostics-open-diagnostics-action"
    const val HomeDiagnosticsVerificationOpenDiagnosticsAction = "home-diagnostics-verification-open-diagnostics-action"
    const val HomeDiagnosticsRemediationCard = "home-diagnostics-remediation-card"
    const val HomeDiagnosticsRemediationAction = "home-diagnostics-remediation-action"
    const val HomeControlPlaneHealthCard = "home-control-plane-health-card"
    const val HomeControlPlaneHealthAction = "home-control-plane-health-action"
    const val HomeConnectionHealthAction = "home-connection-health-action"
    const val HomeApproachCard = "home-approach-card"
    const val HomeHistoryCard = "home-history-card"
    const val HomeStatsGrid = "home-stats-grid"
    const val WarningBannerDismiss = "warning-banner-dismiss"
    const val HomeErrorBanner = "home-error-banner"
    const val HomePermissionRecommendationBanner = "home-permission-recommendation-banner"
    const val HomeBackgroundGuidanceBanner = "home-background-guidance-banner"
    const val HomeHardKillSwitchBanner = "home-hard-kill-switch-banner"
    const val HomeSetupHealthRow = "home-setup-health-row"
    const val HomeSetupHealthDetails = "home-setup-health-details"
    const val HomeSetupHealthAction = "home-setup-health-action"
    const val HomeModesDiagnosticsHeader = "home-modes-diagnostics-header"
    const val HomeModesDiagnosticsCollapsed = "home-modes-diagnostics-collapsed"
    const val HomeModesDiagnosticsExpanded = "home-modes-diagnostics-expanded"
    const val HomeModeDisabledHint = "home-mode-disabled-hint"
    const val HomeNetworkConditionBanner = "home-network-condition-banner"

    const val ConfigEditCurrentButton = "config-edit-current"
    const val ConfigSectionNavigation = "config-section-navigation"
    const val ConfigTrafficEndpointSelection = "config-traffic-endpoint-selection"
    const val ConfigPresetsLoading = "config-presets-loading"
    const val ConfigDnsSettings = "config-dns-settings"
    const val ConfigLocalBypassSummary = "config-local-bypass-summary"
    const val ConfigLocalBypassSimple = "config-local-bypass-simple"
    const val ConfigLocalBypassToggle = "config-local-bypass-toggle"
    const val ConfigLocalBypassToggleState = "config-local-bypass-toggle-state"
    const val ConfigLocalBypassStrategyAuto = "config-local-bypass-strategy-auto"
    const val ConfigLocalBypassRetest = "config-local-bypass-retest"
    const val ConfigLocalBypassPrecedenceNote = "config-local-bypass-precedence-note"
    const val ConfigLocalBypassMode = "config-local-bypass-mode"
    const val ConfigLocalBypassListenAddress = "config-local-bypass-listen-address"
    const val ConfigLocalBypassDesync = "config-local-bypass-desync"
    const val ConfigVpnSummary = "config-vpn-summary"
    const val ConfigVpnSimple = "config-vpn-simple"
    const val ConfigVpnToggle = "config-vpn-toggle"
    const val ConfigVpnToggleState = "config-vpn-toggle-state"
    const val ConfigVpnAddServerPaste = "config-vpn-add-server-paste"
    const val ConfigVpnAddServerScan = "config-vpn-add-server-scan"
    const val ConfigVpnProfileList = "config-vpn-profile-list"
    val configVpnProfileRow: (String) -> String = { profileId -> "config-vpn-profile-row-${sanitize(profileId)}" }
    const val ProfileShareQrCard = "profile-share-qr-card"
    const val ProfileShareQrImage = "profile-share-qr-image"
    const val ProfileShareLinkCard = "profile-share-link-card"
    const val ProfileShareLinkField = "profile-share-link-field"
    const val ProfileShareCopyLink = "profile-share-copy-link"
    const val ProfileShareShareLink = "profile-share-share-link"
    const val ProfileShareSheetCard = "profile-share-sheet-card"
    const val ProfileShareSheetText = "profile-share-sheet-text"
    const val ProfileShareCopySheet = "profile-share-copy-sheet"
    const val ProfileShareShareSheet = "profile-share-share-sheet"
    const val ConfigVpnRelay = "config-vpn-relay"
    const val ConfigVpnProtocol = "config-vpn-protocol"
    const val ConfigVpnCredentials = "config-vpn-credentials"
    const val ConfigOverflowMenuButton = "config-overflow-menu-button"
    const val ConfigImportClipboardMenuItem = "config-import-clipboard-menu-item"
    const val AwgCohortPicker = "awg-cohort-picker"
    const val AwgCarrierPicker = "awg-carrier-picker"
    const val AwgConnectAction = "awg-connect-action"
    const val ModeEditorCancel = "mode-editor-cancel"
    const val ModeEditorSave = "mode-editor-save"
    const val ModeEditorProxyIp = "mode-editor-proxy-ip"
    const val ModeEditorProxyPort = "mode-editor-proxy-port"
    const val ModeEditorMaxConnections = "mode-editor-max-connections"
    const val ModeEditorBufferSize = "mode-editor-buffer-size"
    const val ModeEditorChainVisual = "mode-editor-chain-visual"
    const val ModeEditorChainBlockList = "mode-editor-chain-block-list"
    const val ModeEditorChainBlockPrefix = "mode-editor-chain-block-"
    const val ModeEditorChainAddPrefix = "mode-editor-chain-add-"
    const val ModeEditorChainMoveUpPrefix = "mode-editor-chain-move-up-"
    const val ModeEditorChainMoveDownPrefix = "mode-editor-chain-move-down-"
    const val ModeEditorChainRemovePrefix = "mode-editor-chain-remove-"
    const val ModeEditorChainRawToggle = "mode-editor-chain-raw-toggle"
    const val ModeEditorChainValidation = "mode-editor-chain-validation"
    const val ModeEditorChainDsl = "mode-editor-chain-dsl"
    const val ModeEditorDefaultTtl = "mode-editor-default-ttl"
    const val ModeEditorAdvanced = "mode-editor-advanced"
    const val ModeEditorCommandLineToggle = "mode-editor-command-line-toggle"
    const val ModeEditorCommandLineArgs = "mode-editor-command-line-args"
    const val ModeEditorValidationSnackbar = "mode-editor-validation-snackbar"
    const val ModeEditorLoading = "mode-editor-loading"
    const val UnsavedChangesDialog = "unsaved-changes-dialog"
    const val UnsavedChangesKeepEditing = "unsaved-changes-keep-editing"
    const val UnsavedChangesDiscard = "unsaved-changes-discard"

    const val SettingsDnsSettings = "settings-dns-settings"
    const val SettingsAdvancedConnectivity = "settings-advanced-connectivity"
    const val SettingsAdvancedSettings = "settings-advanced-settings"
    const val SettingsWebRtcProtection = "settings-webrtc-protection"
    const val SettingsStartOnBoot = "settings-start-on-boot"
    const val SettingsBiometric = "settings-biometric"
    const val SettingsBiometricConfirmDialog = "settings-biometric-confirm-dialog"
    const val SettingsBiometricConfirmEnable = "settings-biometric-confirm-enable"
    const val SettingsBiometricConfirmCancel = "settings-biometric-confirm-cancel"
    const val SettingsBiometricPinRequiredDialog = "settings-biometric-pin-required-dialog"
    const val SettingsBiometricPinRequiredOk = "settings-biometric-pin-required-ok"
    const val SettingsBackupPinField = "settings-backup-pin-field"
    const val SettingsBackupPinSave = "settings-backup-pin-save"
    const val SettingsBackupPinClear = "settings-backup-pin-clear"
    const val SettingsBackupPinWarning = "settings-backup-pin-warning"
    const val SettingsThemeDropdown = "settings-theme-dropdown"
    const val SettingsPersona = "settings-persona"
    const val SettingsCustomization = "settings-customization"
    const val SettingsBackgroundGuidanceBanner = "settings-background-guidance-banner"
    const val SettingsSupportBundle = "settings-support-bundle"
    const val SettingsLogs = "settings-logs"
    const val SettingsSubscriptionFailover = "settings-subscription-failover"
    const val SubscriptionLifecycleBanner = "subscription-lifecycle-banner"
    const val SettingsSubscriptionStatus = "settings-subscription-status"
    const val HomeSubscriptionExpiryBanner = "home-subscription-expiry-banner"
    const val SettingsDataTransparency = "settings-data-transparency"
    const val SettingsAbout = "settings-about"
    const val SettingsStrategyConfig = "settings-strategy-config"
    const val SettingsBlockcheck = "settings-blockcheck"
    const val SettingsDomainBypass = "settings-domain-bypass"
    const val DomainBypassEditor = "domain-bypass-editor"
    const val SettingsAssetProvider = "settings-asset-provider"
    const val SettingsBackupRestore = "settings-backup-restore"
    const val BackupExportButton = "backup-export-button"
    const val BackupExportSnackbar = "backup-export-snackbar"
    const val BackupVariantSheet = "backup-variant-sheet"
    const val BackupVariantShare = "backup-variant-share"
    const val BackupVariantFull = "backup-variant-full"
    const val BackupImportButton = "backup-import-button"
    const val BackupImportPreviewSheet = "backup-import-preview-sheet"
    const val BackupImportToggleProfiles = "backup-import-toggle-profiles"
    const val BackupImportToggleRoutes = "backup-import-toggle-routes"
    const val BackupImportToggleSettings = "backup-import-toggle-settings"
    const val BackupImportConfirm = "backup-import-confirm"
    const val BackupShareButton = "backup-share-button"
    const val BackupShareReminderDialog = "backup-share-reminder-dialog"
    const val BackupResetButton = "backup-reset-button"
    const val BackupResetDialog = "backup-reset-dialog"
    const val BackupResetConfirmationField = "backup-reset-confirmation-field"
    const val BackupResetConfirm = "backup-reset-confirm"
    const val AssetProviderDropdown = "asset-provider-dropdown"
    const val AssetProviderCheckUpdates = "asset-provider-check-updates"
    const val AssetProviderCustomUrl = "asset-provider-custom-url"
    const val AssetProviderImport = "asset-provider-import"
    const val AssetProviderImportGeosite = "asset-provider-import-geosite"
    const val SettingsRoutingRules = "settings-routing-rules"
    const val RoutesList = "routes-list"
    const val RoutesAddRule = "routes-add-rule"
    const val RuleEditorName = "rule-editor-name"
    const val RuleEditorSave = "rule-editor-save"
    const val RuleEditorOutbound = "rule-editor-outbound"
    const val AppPickerSheet = "app-picker-sheet"
    const val AppPickerSearch = "app-picker-search"
    const val SettingsSplitTunnel = "settings-split-tunnel"
    const val SettingsRootModeStrategies = "settings-root-mode-strategies"
    const val RootModeStrategiesDisabled = "root-mode-strategies-disabled"
    const val RootModeStrategiesConfigure = "root-mode-strategies-configure"
    const val SplitTunnelModeSelector = "split-tunnel-mode-selector"
    const val SplitTunnelEditApps = "split-tunnel-edit-apps"
    const val StrategyConfigSource = "strategy-config-source"
    const val StrategyConfigEditor = "strategy-config-editor"
    const val StrategyConfigLuaPath = "strategy-config-lua-path"
    const val StrategyConfigLuaFunction = "strategy-config-lua-function"

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
    const val OnboardingSkipTest = "onboarding-skip-test"
    const val OnboardingSkipConfirmDialog = "onboarding-skip-confirm-dialog"
    const val OnboardingSkipConfirmContinue = "onboarding-skip-confirm-continue"
    const val OnboardingSkipConfirmSetUpLater = "onboarding-skip-confirm-set-up-later"
    const val OnboardingContinue = "onboarding-continue"
    const val OnboardingPersonaSimple = "onboarding-persona-simple"
    const val OnboardingPersonaAdvanced = "onboarding-persona-advanced"
    const val OnboardingModeVpn = "onboarding-mode-vpn"
    const val OnboardingModeProxy = "onboarding-mode-proxy"
    const val OnboardingValidateAction = "onboarding-validate-action"
    const val OnboardingValidationStatus = "onboarding-validation-status"
    const val OnboardingFinishKeepRunning = "onboarding-finish-keep-running"
    const val OnboardingFinishDisconnected = "onboarding-finish-disconnected"
    const val OnboardingFinishAnyway = "onboarding-finish-anyway"
    const val OnboardingSwitchSuggestedMode = "onboarding-switch-suggested-mode"
    const val OnboardingChangeDns = "onboarding-change-dns"
    const val OnboardingAdvancedDns = "onboarding-advanced-dns"

    val onboardingDnsProvider: (String) -> String = { providerId -> "onboarding-dns-${sanitize(providerId)}" }

    const val BiometricPromptPrimaryAction = "biometric-prompt-primary-action"
    const val BiometricPromptSecondaryAction = "biometric-prompt-secondary-action"
    const val BiometricPromptPinField = "biometric-prompt-pin-field"

    const val DiagnosticsTopHistoryAction = "diagnostics-top-history-action"
    const val DiagnosticsConnectionHealthAction = "diagnostics-connection-health-action"
    const val DiagnosticsStrategyTunerAction = "diagnostics-strategy-tuner-action"
    const val DiagnosticsOverviewHistoryAction = "diagnostics-overview-history-action"
    const val ReplayHistoryEmptyState = "replay-history-empty-state"
    const val ReplayHistoryRunScanAction = "replay-history-run-scan-action"
    const val DiagnosticsOverviewAutomaticProbeCard = "diagnostics-overview-automatic-probe-card"
    const val DiagnosticsOverviewHero = "diagnostics-overview-hero"
    const val DiagnosticsSimpleFunnel = "diagnostics-simple-funnel"
    const val DiagnosticsSimpleApply = "diagnostics-simple-apply"
    const val DiagnosticsOverviewRunScanAction = "diagnostics-overview-run-scan-action"
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
    const val DiagnosticsRemediationLadderCard = "diagnostics-remediation-ladder-card"
    const val DiagnosticsRemediationLadderAction = "diagnostics-remediation-ladder-action"
    const val DiagnosticsWorkflowRestrictionCard = "diagnostics-workflow-restriction-card"
    const val DiagnosticsWorkflowRestrictionAction = "diagnostics-workflow-restriction-action"
    const val DiagnosticsSharePreviewCard = "diagnostics-share-preview-card"
    const val DiagnosticsArchiveStateIndicator = "diagnostics-archive-state-indicator"
    const val DiagnosticsDeviceAcceptanceCard = "diagnostics-device-acceptance-card"
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
    const val DiagnosticsScanPolicyNotice = "diagnostics-scan-policy-notice"
    const val DiagnosticsHiddenProbeConflictDialog = "diagnostics-hidden-probe-conflict-dialog"
    const val DiagnosticsHiddenProbeConflictWait = "diagnostics-hidden-probe-conflict-wait"
    const val DiagnosticsHiddenProbeConflictCancelAndRun = "diagnostics-hidden-probe-conflict-cancel-and-run"
    const val DiagnosticsHiddenProbeConflictDismiss = "diagnostics-hidden-probe-conflict-dismiss"
    const val DiagnosticsSensitiveProfileConsentDialog = "diagnostics-sensitive-profile-consent-dialog"
    const val DiagnosticsSensitiveProfileConsentConfirm = "diagnostics-sensitive-profile-consent-confirm"
    const val DiagnosticsSensitiveProfileConsentDismiss = "diagnostics-sensitive-profile-consent-dismiss"
    const val DiagnosticsSessionSensitiveToggle = "diagnostics-session-sensitive-toggle"
    const val DiagnosticsSessionsSearch = "diagnostics-sessions-search"
    const val DiagnosticsEventsSearch = "diagnostics-events-search"
    const val DiagnosticsEventsAutoScroll = "diagnostics-events-auto-scroll"
    const val DiagnosticsShareArchive = "diagnostics-share-archive"
    const val DiagnosticsSaveArchive = "diagnostics-save-archive"
    const val DiagnosticsSaveLogs = "diagnostics-save-logs"
    const val DiagnosticsOpenLogs = "diagnostics-open-logs"
    const val DiagnosticsShareSummary = "diagnostics-share-summary"
    const val DiagnosticsStatusSnackbar = "diagnostics-status-snackbar"
    const val DiagnosticsSessionsStateEmpty = "diagnostics-sessions-state-empty"
    const val DiagnosticsSessionsStateContent = "diagnostics-sessions-state-content"
    const val DiagnosticsEventsStateEmpty = "diagnostics-events-state-empty"
    const val DiagnosticsEventsStateContent = "diagnostics-events-state-content"
    const val XrayProviderStatusCard = "xray-provider-status-card"
    const val XrayProviderProbeRun = "xray-provider-probe-run"
    const val HomeXrayProviderBanner = "home-xray-provider-banner"
    const val SettingsXrayProviderStatus = "settings-xray-provider-status"
    const val StaticStrategyImportScreen = "strategy_import-screen"
    const val StaticProfileVariantsScreen = "profile_variants-screen"

    const val LogsScreen = "logs-screen"
    const val LogsAutoScroll = "logs-auto-scroll"
    const val LogsSave = "logs-save"
    const val LogsClear = "logs-clear"
    const val LogsStream = "logs-stream"

    const val AdvancedClearRememberedNetworks = "advanced-clear-remembered-networks"
    const val AdvancedInspectRememberedNetworks = "advanced-inspect-remembered-networks"
    const val AdvancedCommandLineWarning = "advanced-command-line-warning"
    const val AdvancedNoticeBanner = "advanced-notice-banner"

    const val RememberedNetworksEmpty = "remembered-networks-empty"
    const val RememberedNetworksClearAll = "remembered-networks-clear-all"
    val rememberedNetworkCard: (Long) -> String = { id -> "remembered-network-card-$id" }
    val rememberedNetworkDelete: (Long) -> String = { id -> "remembered-network-delete-$id" }

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

    const val DetectionRunCheck = "detection_run_check"
    const val DetectionStopCheck = "detection_stop_check"
    const val DetectionVerdict = "detection_verdict"
    const val DetectionVisibilityScale = "detection_visibility_scale"
    const val DetectionCopy = "detection_copy"
    const val DetectionShare = "detection_share"
    const val DetectionApplyFixes = "detection_apply_fixes"

    const val HostPackApplyDialog = "host-pack-apply-dialog"
    const val HostPackApplyDismiss = "host-pack-apply-dismiss"
    const val HostPackApplyConfirm = "host-pack-apply-confirm"
    const val HostPackTargetDropdown = "host-pack-target-dropdown"
    const val HostPackApplyModeDropdown = "host-pack-apply-mode-dropdown"

    val screen: (Route) -> String = { route -> "${route.stableRoute}-screen" }

    val bottomNav: (Route) -> String = { route -> "bottom-nav-${route.stableRoute}" }

    val configPreset: (String) -> String = { presetId -> "config-preset-${sanitize(presetId)}" }

    val configMode: (String) -> String = { modeKey -> "config-mode-${sanitize(modeKey)}" }

    val configModeSection: (String) -> String = { sectionKey -> "config-mode-section-${sanitize(sectionKey)}" }
    val configVpnProfile: (String) -> String = { profileId -> "config-vpn-profile-${sanitize(profileId)}" }
    val configVpnProfileShare: (String) -> String = { profileId -> "config-vpn-profile-share-${sanitize(profileId)}" }

    val settingsPermission: (PermissionKind) -> String = { kind -> "settings-permission-${sanitize(kind.name)}" }

    val dnsResolver: (String) -> String = { providerId -> "dns-resolver-${sanitize(providerId)}" }

    val dnsMode: (String) -> String = { modeKey -> "dns-mode-${sanitize(modeKey)}" }

    val dnsProtocol: (String) -> String = { protocolKey -> "dns-protocol-${sanitize(protocolKey)}" }

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
    val logsEntry: (String) -> String = { id -> "logs-entry-${sanitize(id)}" }
    val logsEntryCopy: (String) -> String = { id -> "logs-entry-copy-${sanitize(id)}" }

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

    val hostPackPreset: (String) -> String = { presetId -> "host-pack-preset-${sanitize(presetId)}" }

    fun homeConnectionStage(stage: String): String = "home-connection-stage-${sanitize(stage)}"

    fun homeModeCard(mode: String): String = "home-mode-card-${sanitize(mode)}"

    fun homeModeCardBody(mode: String): String = "home-mode-card-body-${sanitize(mode)}"

    fun homeModePrimaryAction(mode: String): String = "home-mode-primary-${sanitize(mode)}"

    fun homeModeConfigureAction(mode: String): String = "home-mode-configure-${sanitize(mode)}"

    val modeEditorRelaySection: (String) -> String = { section -> "mode-editor-relay-section-${sanitize(section)}" }

    val modeEditorRelayChip: (String) -> String = { kind -> "mode-editor-relay-chip-${sanitize(kind)}" }

    fun routeProfile(routeId: String): String = "route-profile-${sanitize(routeId)}"

    fun routeCapability(kind: String): String = "route-capability-${sanitize(kind)}"

    fun dropdownOption(
        tag: String,
        value: String,
    ): String = "$tag-option-${sanitize(value)}"

    fun awgField(field: String): String = "awg-field-${sanitize(field)}"

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
