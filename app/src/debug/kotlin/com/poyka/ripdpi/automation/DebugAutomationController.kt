package com.poyka.ripdpi.automation

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.activities.MainActivityHostCommand
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.activities.StrategyProbeSuiteFullMatrixV1
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryResetStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidence
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidenceLevel
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditCoverage
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceAutomationController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private val AutomationIntentAction = "${BuildConfig.APPLICATION_ID}.automation.ACTION"
private val AutomationIntentKindExtra = "${BuildConfig.APPLICATION_ID}.automation.KIND"
private const val AutomaticAuditProfileId = "automatic-audit"
private const val AutomationReportDemoSessionId = "automation-report-demo-session"

private enum class AutomationIntentKind {
    VpnConsent,
    AppSettings,
    BatteryOptimization,
}

private data class AutomationRuntimeState(
    val config: AutomationLaunchConfig = AutomationLaunchConfig(),
    val permissionSnapshot: PermissionSnapshot = PermissionSnapshot(),
)

@Singleton
class DebugAutomationController
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val serviceStateStore: ServiceStateStore,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val historyResetStore: DiagnosticsHistoryResetStore,
        @param:Named("diagnosticsJson")
        private val diagnosticsJson: Json,
    ) : AutomationController,
        ServiceAutomationController {
        private val state = AtomicReference(AutomationRuntimeState())

        override fun prepareLaunch(intent: Intent?): AutomationLaunchConfig {
            val previousState = state.get()
            val config =
                resolveAutomationLaunchConfig(
                    instrumentationArgs = instrumentationArgs(),
                    intentArgs = intentArgs(intent),
                )

            val nextState =
                if (config.enabled) {
                    AutomationRuntimeState(
                        config = config,
                        permissionSnapshot = permissionSnapshotFor(config.permissionPreset),
                    )
                } else {
                    AutomationRuntimeState()
                }
            state.set(nextState)

            System.setProperty("ripdpi.staticMotion", config.disableMotion.toString())

            runBlocking {
                if (config.enabled) {
                    if (config.resetState) {
                        historyResetStore.clearRuntimeHistory()
                        appSettingsRepository.replace(AppSettingsSerializer.defaultValue)
                    }

                    val seededSettings = buildSeedSettings(config)
                    appSettingsRepository.replace(seededSettings)
                    applyServicePreset(
                        preset = config.servicePreset,
                        configuredMode = Mode.fromString(seededSettings.ripdpiMode),
                    )
                    if (config.dataPreset == AutomationDataPreset.DiagnosticsReportDemo) {
                        seedDiagnosticsReportDemo()
                    }
                } else if (
                    previousState.config.enabled &&
                    previousState.config.servicePreset != AutomationServicePreset.Live
                ) {
                    applyIdleServiceState(serviceStateStore.status.value.second)
                }
            }

            return config
        }

        override fun currentLaunchConfig(): AutomationLaunchConfig = state.get().config

        override fun currentPermissionSnapshot(defaultSnapshot: PermissionSnapshot): PermissionSnapshot =
            state.get().takeIf { it.config.enabled }?.permissionSnapshot ?: defaultSnapshot

        override fun prepareVpnPermissionIntent(defaultIntent: Intent?): Intent? {
            val currentState = state.get()
            if (!currentState.config.enabled) {
                return defaultIntent
            }

            return if (currentState.permissionSnapshot.vpnConsent == PermissionStatus.Granted) {
                null
            } else {
                automationIntent(AutomationIntentKind.VpnConsent)
            }
        }

        override fun createAppSettingsIntent(defaultIntent: Intent): Intent {
            val currentState = state.get()
            if (!currentState.config.enabled) {
                return defaultIntent
            }

            return automationIntent(AutomationIntentKind.AppSettings)
        }

        override fun createBatteryOptimizationIntent(defaultIntent: Intent): Intent {
            val currentState = state.get()
            if (!currentState.config.enabled) {
                return defaultIntent
            }

            return automationIntent(AutomationIntentKind.BatteryOptimization)
        }

        override fun interceptHostCommand(
            command: Any,
            viewModel: MainViewModel,
        ): Boolean {
            val currentState = state.get()
            if (!currentState.config.enabled) {
                return false
            }

            return when (command) {
                MainActivityHostCommand.RequestNotificationsPermission -> {
                    grantPermission(
                        kind = PermissionKind.Notifications,
                        viewModel = viewModel,
                        result = PermissionResult.Granted,
                    )
                    true
                }

                is MainActivityHostCommand.RequestVpnConsent -> {
                    grantPermission(
                        kind = PermissionKind.VpnConsent,
                        viewModel = viewModel,
                        result = PermissionResult.Granted,
                    )
                    true
                }

                is MainActivityHostCommand.RequestBatteryOptimization -> {
                    grantPermission(
                        kind = PermissionKind.BatteryOptimization,
                        viewModel = viewModel,
                        result = PermissionResult.ReturnedFromSettings,
                    )
                    true
                }

                is MainActivityHostCommand.OpenIntent -> {
                    val permissionSnapshot = currentState.permissionSnapshot
                    when {
                        permissionSnapshot.notifications != PermissionStatus.Granted -> {
                            grantPermission(
                                kind = PermissionKind.Notifications,
                                viewModel = viewModel,
                                result = PermissionResult.ReturnedFromSettings,
                            )
                            true
                        }

                        permissionSnapshot.alwaysOnVpn != PermissionStatus.Granted -> {
                            grantPermission(
                                kind = PermissionKind.AlwaysOnVpn,
                                viewModel = viewModel,
                                result = PermissionResult.ReturnedFromSettings,
                            )
                            true
                        }

                        permissionSnapshot.vpnLockdown != PermissionStatus.Granted -> {
                            grantPermission(
                                kind = PermissionKind.VpnLockdown,
                                viewModel = viewModel,
                                result = PermissionResult.ReturnedFromSettings,
                            )
                            true
                        }

                        permissionSnapshot.batteryOptimization != PermissionStatus.Granted &&
                            permissionSnapshot.batteryOptimization != PermissionStatus.NotApplicable
                        -> {
                            grantPermission(
                                kind = PermissionKind.BatteryOptimization,
                                viewModel = viewModel,
                                result = PermissionResult.ReturnedFromSettings,
                            )
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }

                else -> {
                    false
                }
            }
        }

        override fun interceptStart(mode: Mode): Boolean {
            val config = state.get().config
            if (!config.enabled || config.servicePreset == AutomationServicePreset.Live) {
                return false
            }

            applyRunningServiceState(mode)
            return true
        }

        override fun interceptStop(currentMode: Mode): Boolean {
            val config = state.get().config
            if (!config.enabled || config.servicePreset == AutomationServicePreset.Live) {
                return false
            }

            applyIdleServiceState(currentMode)
            return true
        }

        private fun buildSeedSettings(config: AutomationLaunchConfig): AppSettings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setAppTheme(config.themePreset.wireValue)
                .setOnboardingComplete(config.dataPreset != AutomationDataPreset.CleanHome)
                .setBiometricEnabled(false)
                .setBackupPin("")
                .apply {
                    when (config.dataPreset) {
                        AutomationDataPreset.CleanHome -> {
                            setRipdpiMode(Mode.VPN.preferenceValue)
                            setWebrtcProtectionEnabled(false)
                        }

                        AutomationDataPreset.SettingsReady -> {
                            setRipdpiMode(Mode.VPN.preferenceValue)
                            setWebrtcProtectionEnabled(true)
                            setDnsProviderId("cloudflare")
                        }

                        AutomationDataPreset.DiagnosticsDemo -> {
                            setRipdpiMode(Mode.VPN.preferenceValue)
                            setDiagnosticsMonitorEnabled(true)
                            setDiagnosticsActiveProfileId("default")
                            setNetworkStrategyMemoryEnabled(true)
                        }

                        AutomationDataPreset.DiagnosticsReportDemo -> {
                            setRipdpiMode(Mode.VPN.preferenceValue)
                            setDiagnosticsMonitorEnabled(true)
                            setDiagnosticsActiveProfileId(AutomaticAuditProfileId)
                            setNetworkStrategyMemoryEnabled(true)
                        }

                        AutomationDataPreset.BiometricLocked -> {
                            setRipdpiMode(Mode.VPN.preferenceValue)
                            setWebrtcProtectionEnabled(true)
                            setDnsProviderId("cloudflare")
                            setBiometricEnabled(true)
                        }

                        AutomationDataPreset.BiometricLockedWithPin -> {
                            setRipdpiMode(Mode.VPN.preferenceValue)
                            setWebrtcProtectionEnabled(true)
                            setDnsProviderId("cloudflare")
                            setBiometricEnabled(true)
                            setBackupPin("0000")
                        }
                    }
                }.build()

        private suspend fun seedDiagnosticsReportDemo() {
            val finishedAt = System.currentTimeMillis()
            val startedAt = finishedAt - 12_000
            val report =
                EngineScanReportWire(
                    sessionId = AutomationReportDemoSessionId,
                    profileId = AutomaticAuditProfileId,
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    summary = "Automation diagnostics report ready",
                    results = automationProbeResults(),
                    resolverRecommendation = automationResolverRecommendation(),
                    strategyProbeReport = automationStrategyProbeReport(),
                    engineAnalysisVersion = "automation-fixture-v1",
                    classifierVersion = "automation-fixture-v1",
                )
            val reportJson = diagnosticsJson.encodeToString(EngineScanReportWire.serializer(), report)
            scanRecordStore.replaceProbeResults(
                sessionId = AutomationReportDemoSessionId,
                results =
                    report.results.mapIndexed { index, result ->
                        ProbeResultEntity(
                            id = "$AutomationReportDemoSessionId-probe-$index",
                            sessionId = AutomationReportDemoSessionId,
                            probeType = result.probeType,
                            target = result.target,
                            outcome = result.outcome,
                            detailJson =
                                diagnosticsJson.encodeToString(
                                    ListSerializer(ProbeDetail.serializer()),
                                    result.details,
                                ),
                            createdAt = finishedAt,
                        )
                    },
            )
            scanRecordStore.upsertScanSession(
                ScanSessionEntity(
                    id = AutomationReportDemoSessionId,
                    profileId = AutomaticAuditProfileId,
                    pathMode = ScanPathMode.RAW_PATH.name,
                    serviceMode = Mode.VPN.name,
                    status = "completed",
                    summary = report.summary,
                    reportJson = reportJson,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    launchOrigin = "user_initiated",
                    triggerType = "automation_fixture",
                    triggerClassification = "debug",
                    triggerOccurredAt = startedAt,
                ),
            )
        }

        private fun automationProbeResults(): List<EngineProbeResultWire> =
            listOf(
                EngineProbeResultWire(
                    probeType = "dns",
                    target = "example.test",
                    outcome = "success",
                    details =
                        listOf(
                            ProbeDetail("resolver", "adguard"),
                            ProbeDetail("protocol", "doh"),
                            ProbeDetail("latencyMs", "24"),
                        ),
                ),
                EngineProbeResultWire(
                    probeType = "web",
                    target = "example.test",
                    outcome = "success",
                    details =
                        listOf(
                            ProbeDetail("status", "200"),
                            ProbeDetail("pathMode", ScanPathMode.RAW_PATH.name),
                        ),
                ),
            )

        private fun automationResolverRecommendation(): ResolverRecommendation =
            ResolverRecommendation(
                triggerOutcome = "dns_fallback_success",
                selectedResolverId = "adguard",
                selectedProtocol = "doh",
                selectedEndpoint = "https://dns.adguard-dns.com/dns-query",
                selectedHost = "dns.adguard-dns.com",
                selectedPort = 443,
                selectedTlsServerName = "dns.adguard-dns.com",
                selectedDohUrl = "https://dns.adguard-dns.com/dns-query",
                rationale = "Automation fixture provides a deterministic encrypted resolver recommendation.",
                appliedTemporarily = true,
                persistable = true,
            )

        private fun automationStrategyProbeReport(): StrategyProbeReport =
            StrategyProbeReport(
                suiteId = StrategyProbeSuiteFullMatrixV1,
                tcpCandidates =
                    listOf(
                        automationCandidate(
                            id = "tlsrec_split_host",
                            label = "TLS record split",
                            family = "tls_record_split",
                            outcome = "success",
                        ),
                        automationCandidate(
                            id = "split_host",
                            label = "Host split",
                            family = "split",
                            outcome = "partial",
                            succeededTargets = 1,
                        ),
                    ),
                quicCandidates =
                    listOf(
                        automationCandidate(
                            id = "quic_sni_split",
                            label = "QUIC SNI split",
                            family = "quic_sni_split",
                            outcome = "success",
                        ),
                        automationCandidate(
                            id = "quic_disabled",
                            label = "QUIC disabled",
                            family = "quic_disabled",
                            outcome = "skipped",
                            succeededTargets = 0,
                            skipped = true,
                        ),
                    ),
                recommendation =
                    StrategyProbeRecommendation(
                        tcpCandidateId = "tlsrec_split_host",
                        tcpCandidateLabel = "TLS record split",
                        tcpCandidateFamily = "tls_record_split",
                        quicCandidateId = "quic_sni_split",
                        quicCandidateLabel = "QUIC SNI split",
                        quicCandidateFamily = "quic_sni_split",
                        dnsStrategyFamily = "encrypted_dns",
                        dnsStrategyLabel = "Encrypted DNS",
                        rationale = "Automation fixture uses successful TCP and QUIC candidates.",
                        recommendedProxyConfigJson = "{}",
                    ),
                completionKind = StrategyProbeCompletionKind.NORMAL,
                auditAssessment =
                    StrategyProbeAuditAssessment(
                        coverage =
                            StrategyProbeAuditCoverage(
                                tcpCandidatesPlanned = 2,
                                tcpCandidatesExecuted = 2,
                                tcpCandidatesSkipped = 0,
                                tcpCandidatesNotApplicable = 0,
                                quicCandidatesPlanned = 2,
                                quicCandidatesExecuted = 1,
                                quicCandidatesSkipped = 1,
                                quicCandidatesNotApplicable = 0,
                                tcpWinnerSucceededTargets = 2,
                                tcpWinnerTotalTargets = 2,
                                quicWinnerSucceededTargets = 2,
                                quicWinnerTotalTargets = 2,
                                matrixCoveragePercent = 75,
                                winnerCoveragePercent = 100,
                                tcpWinnerCoveragePercent = 100,
                                quicWinnerCoveragePercent = 100,
                            ),
                        confidence =
                            StrategyProbeAuditConfidence(
                                level = StrategyProbeAuditConfidenceLevel.MEDIUM,
                                score = 82,
                                rationale = "Automation fixture covers the report, audit, and winning-path UI states.",
                            ),
                    ),
            )

        private fun automationCandidate(
            id: String,
            label: String,
            family: String,
            outcome: String,
            succeededTargets: Int = 2,
            skipped: Boolean = false,
        ): StrategyProbeCandidateSummary =
            StrategyProbeCandidateSummary(
                id = id,
                label = label,
                family = family,
                outcome = outcome,
                rationale = "Automation fixture candidate",
                succeededTargets = succeededTargets,
                totalTargets = 2,
                weightedSuccessScore = succeededTargets * 50,
                totalWeight = 100,
                qualityScore = if (skipped) 0 else 80,
                skipped = skipped,
                averageLatencyMs = if (skipped) null else 42,
            )

        private fun instrumentationArgs(): Map<String, Any?> =
            runCatching {
                val arguments = InstrumentationRegistry.getArguments()
                arguments.keySet().associateWith(arguments::getString)
            }.getOrDefault(emptyMap())

        private fun intentArgs(intent: Intent?): Map<String, Any?> =
            intent
                ?.let { source ->
                    buildMap {
                        val isExternalAutomationLaunch =
                            source.action == AutomationLaunchContract.LaunchAction ||
                                (
                                    source.data?.scheme == AutomationLaunchContract.LaunchUriScheme &&
                                        source.data?.host == AutomationLaunchContract.LaunchUriHost &&
                                        source.data?.path == AutomationLaunchContract.LaunchUriPath
                                )
                        if (source.hasExtra(AutomationLaunchContract.Enabled)) {
                            put(
                                AutomationLaunchContract.Enabled,
                                source.getBooleanExtra(AutomationLaunchContract.Enabled, false),
                            )
                        } else if (isExternalAutomationLaunch) {
                            put(AutomationLaunchContract.Enabled, true)
                        }
                        if (source.hasExtra(AutomationLaunchContract.ResetState)) {
                            put(
                                AutomationLaunchContract.ResetState,
                                source.getBooleanExtra(AutomationLaunchContract.ResetState, false),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.StartRoute)) {
                            put(
                                AutomationLaunchContract.StartRoute,
                                source.getStringExtra(AutomationLaunchContract.StartRoute),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.DisableMotion)) {
                            put(
                                AutomationLaunchContract.DisableMotion,
                                source.getBooleanExtra(AutomationLaunchContract.DisableMotion, false),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.PermissionPreset)) {
                            put(
                                AutomationLaunchContract.PermissionPreset,
                                source.getStringExtra(AutomationLaunchContract.PermissionPreset),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.ServicePreset)) {
                            put(
                                AutomationLaunchContract.ServicePreset,
                                source.getStringExtra(AutomationLaunchContract.ServicePreset),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.DataPreset)) {
                            put(
                                AutomationLaunchContract.DataPreset,
                                source.getStringExtra(AutomationLaunchContract.DataPreset),
                            )
                        }
                        if (source.hasExtra(AutomationLaunchContract.Theme)) {
                            put(
                                AutomationLaunchContract.Theme,
                                source.getStringExtra(AutomationLaunchContract.Theme),
                            )
                        }
                        source.data?.let { deepLink ->
                            deepLink.getQueryParameter(AutomationLaunchContract.LaunchParamEnabled)?.let {
                                put(AutomationLaunchContract.Enabled, it)
                            }
                            deepLink.getQueryParameter(AutomationLaunchContract.LaunchParamResetState)?.let {
                                put(AutomationLaunchContract.ResetState, it)
                            }
                            deepLink.getQueryParameter(AutomationLaunchContract.LaunchParamStartRoute)?.let {
                                put(AutomationLaunchContract.StartRoute, it)
                            }
                            deepLink.getQueryParameter(AutomationLaunchContract.LaunchParamDisableMotion)?.let {
                                put(AutomationLaunchContract.DisableMotion, it)
                            }
                            deepLink.getQueryParameter(AutomationLaunchContract.LaunchParamPermissionPreset)?.let {
                                put(AutomationLaunchContract.PermissionPreset, it)
                            }
                            deepLink.getQueryParameter(AutomationLaunchContract.LaunchParamServicePreset)?.let {
                                put(AutomationLaunchContract.ServicePreset, it)
                            }
                            deepLink.getQueryParameter(AutomationLaunchContract.LaunchParamDataPreset)?.let {
                                put(AutomationLaunchContract.DataPreset, it)
                            }
                            deepLink.getQueryParameter(AutomationLaunchContract.LaunchParamTheme)?.let {
                                put(AutomationLaunchContract.Theme, it)
                            }
                        }
                    }
                }.orEmpty()

        private fun permissionSnapshotFor(preset: AutomationPermissionPreset): PermissionSnapshot =
            when (preset) {
                AutomationPermissionPreset.Granted -> {
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.Granted,
                        alwaysOnVpn = PermissionStatus.Granted,
                        vpnLockdown = PermissionStatus.Granted,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.Granted,
                    )
                }

                AutomationPermissionPreset.NotificationsMissing -> {
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.Granted,
                        alwaysOnVpn = PermissionStatus.Granted,
                        vpnLockdown = PermissionStatus.Granted,
                        notifications = PermissionStatus.RequiresSystemPrompt,
                        batteryOptimization = PermissionStatus.Granted,
                    )
                }

                AutomationPermissionPreset.VpnMissing -> {
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.RequiresSystemPrompt,
                        alwaysOnVpn = PermissionStatus.Granted,
                        vpnLockdown = PermissionStatus.Granted,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.Granted,
                    )
                }

                AutomationPermissionPreset.BatteryReview -> {
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.Granted,
                        alwaysOnVpn = PermissionStatus.Granted,
                        vpnLockdown = PermissionStatus.Granted,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.RequiresSettings,
                    )
                }
            }

        private fun grantPermission(
            kind: PermissionKind,
            viewModel: MainViewModel,
            result: PermissionResult,
        ) {
            state.updateAndGet { current ->
                current.copy(
                    permissionSnapshot =
                        when (kind) {
                            PermissionKind.LocalNetwork -> {
                                current.permissionSnapshot.copy(localNetwork = PermissionStatus.Granted)
                            }

                            PermissionKind.Notifications -> {
                                current.permissionSnapshot.copy(
                                    notifications = PermissionStatus.Granted,
                                )
                            }

                            PermissionKind.VpnConsent -> {
                                current.permissionSnapshot.copy(
                                    vpnConsent = PermissionStatus.Granted,
                                )
                            }

                            PermissionKind.AlwaysOnVpn -> {
                                current.permissionSnapshot.copy(
                                    alwaysOnVpn = PermissionStatus.Granted,
                                )
                            }

                            PermissionKind.VpnLockdown -> {
                                current.permissionSnapshot.copy(
                                    vpnLockdown = PermissionStatus.Granted,
                                )
                            }

                            PermissionKind.BatteryOptimization -> {
                                current.permissionSnapshot.copy(
                                    batteryOptimization = PermissionStatus.Granted,
                                )
                            }
                        },
                )
            }
            viewModel.onPermissionResult(kind = kind, result = result)
        }

        private fun applyServicePreset(
            preset: AutomationServicePreset,
            configuredMode: Mode,
        ) {
            when (preset) {
                AutomationServicePreset.Idle -> {
                    applyIdleServiceState(configuredMode)
                }

                AutomationServicePreset.Live -> {
                }

                AutomationServicePreset.ConnectedProxy -> {
                    applyRunningServiceState(Mode.Proxy)
                }

                AutomationServicePreset.ConnectedVpn -> {
                    applyRunningServiceState(Mode.VPN)
                }
            }
        }

        private fun applyIdleServiceState(mode: Mode) {
            serviceStateStore.setStatus(AppStatus.Halted, mode)
            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = mode,
                    status = AppStatus.Halted,
                    proxyTelemetry = NativeRuntimeSnapshot.idle(source = "proxy"),
                    tunnelTelemetry = NativeRuntimeSnapshot.idle(source = "tunnel"),
                ),
            )
        }

        private fun applyRunningServiceState(mode: Mode) {
            val now = System.currentTimeMillis()
            val proxyTelemetry =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    health = "healthy",
                    activeSessions = 1,
                    totalSessions = 4,
                    listenerAddress = "127.0.0.1:1080",
                    upstreamAddress = "demo-gateway",
                    lastHost = "example.com",
                    capturedAt = now,
                )
            val tunnelTelemetry =
                if (mode == Mode.VPN) {
                    NativeRuntimeSnapshot(
                        source = "tunnel",
                        state = "running",
                        health = "healthy",
                        activeSessions = 1,
                        totalSessions = 2,
                        resolverId = "cloudflare",
                        resolverProtocol = "doh",
                        resolverEndpoint = "cloudflare-dns.com:443",
                        resolverLatencyMs = 24,
                        tunnelStats =
                            TunnelStats(
                                txPackets = 18,
                                txBytes = 6_912,
                                rxPackets = 21,
                                rxBytes = 8_448,
                            ),
                        capturedAt = now,
                    )
                } else {
                    NativeRuntimeSnapshot.idle(source = "tunnel")
                }

            serviceStateStore.setStatus(AppStatus.Running, mode)
            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = mode,
                    status = AppStatus.Running,
                    proxyTelemetry = proxyTelemetry,
                    tunnelTelemetry = tunnelTelemetry,
                    tunnelStats = tunnelTelemetry.tunnelStats,
                    serviceStartedAt = now,
                    updatedAt = now,
                ),
            )
        }

        private fun automationIntent(kind: AutomationIntentKind): Intent =
            Intent(AutomationIntentAction)
                .setPackage(BuildConfig.APPLICATION_ID)
                .putExtra(AutomationIntentKindExtra, kind.name)
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugAutomationControllerModule {
    @Binds
    @Singleton
    abstract fun bindAutomationController(controller: DebugAutomationController): AutomationController

    @Binds
    @Singleton
    abstract fun bindServiceAutomationController(controller: DebugAutomationController): ServiceAutomationController
}
