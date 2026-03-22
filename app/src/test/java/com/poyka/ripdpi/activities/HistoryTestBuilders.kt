package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsHistorySource
import com.poyka.ripdpi.diagnostics.DeviceContextModel
import com.poyka.ripdpi.diagnostics.EnvironmentContextModel
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.PermissionContextModel
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.ServiceContextModel
import com.poyka.ripdpi.diagnostics.WifiNetworkDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun historyConnectionSession(
    id: String = "connection-1",
    state: String = "Running",
    mode: String = "VPN",
    health: String = "healthy",
    networkType: String = "wifi",
    startedAt: Long = 1_000L,
    finishedAt: Long? = 61_000L,
    updatedAt: Long = finishedAt ?: startedAt,
    failureMessage: String? = null,
): DiagnosticConnectionSession =
    DiagnosticConnectionSession(
        id = id,
        startedAt = startedAt,
        finishedAt = finishedAt,
        updatedAt = updatedAt,
        serviceMode = mode,
        connectionState = state,
        health = health,
        approachProfileId = "default",
        approachProfileName = "Default",
        strategyId = "strategy-1",
        strategyLabel = "Strategy 1",
        strategySignature = null,
        networkType = networkType,
        publicIp = "198.51.100.8",
        failureClass = "dns_tampering",
        telemetryNetworkFingerprintHash = "abcdef0123456789fedcba9876543210",
        winningTcpStrategyFamily = "hostfake",
        winningQuicStrategyFamily = "quic_burst",
        proxyRttBand = "low",
        resolverRttBand = "medium",
        proxyRouteRetryCount = 1,
        tunnelRecoveryRetryCount = 2,
        txBytes = 4_000L,
        rxBytes = 8_000L,
        totalErrors = 1L,
        routeChanges = 2L,
        restartCount = 0,
        endedReason = "Completed",
        failureMessage = failureMessage,
    )

internal fun historyScanSession(
    id: String = "scan-1",
    status: String = "completed",
    pathMode: String = "RAW_PATH",
    serviceMode: String? = "VPN",
    summary: String = "Scan summary",
    report: ScanReport? =
        ScanReport(
            sessionId = id,
            profileId = "default",
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 1L,
            finishedAt = 2L,
            summary = summary,
            results =
                listOf(
                    ProbeResult(
                        probeType = "dns",
                        target = "example.org",
                        outcome = "ok",
                        details = listOf(ProbeDetail("resolver", "1.1.1.1")),
                    ),
                ),
        ),
): DiagnosticScanSession =
    DiagnosticScanSession(
        id = id,
        profileId = "default",
        pathMode = pathMode,
        serviceMode = serviceMode,
        status = status,
        summary = summary,
        report = report,
        startedAt = 1L,
        finishedAt = 2L,
    )

internal fun historyEvent(
    id: String = "event-1",
    source: String = "proxy",
    level: String = "warn",
    message: String = "Route changed",
    createdAt: Long = 5L,
    sessionId: String? = null,
    connectionSessionId: String? = null,
): DiagnosticEvent =
    DiagnosticEvent(
        id = id,
        sessionId = sessionId,
        connectionSessionId = connectionSessionId,
        source = source,
        level = level,
        message = message,
        createdAt = createdAt,
    )

internal fun historySnapshot(
    id: String = "snapshot-1",
    sessionId: String? = null,
    connectionSessionId: String? = null,
    transport: String = "wifi",
): DiagnosticNetworkSnapshot =
    DiagnosticNetworkSnapshot(
        id = id,
        sessionId = sessionId,
        connectionSessionId = connectionSessionId,
        snapshotKind = "passive",
        snapshot =
            NetworkSnapshotModel(
                transport = transport,
                capabilities = listOf("validated"),
                dnsServers = listOf("1.1.1.1"),
                privateDnsMode = "strict",
                mtu = 1500,
                localAddresses = listOf("192.168.1.4"),
                publicIp = "198.51.100.8",
                publicAsn = "AS64500",
                captivePortalDetected = false,
                networkValidated = true,
                wifiDetails =
                    WifiNetworkDetails(
                        ssid = "RIPDPI Lab",
                        bssid = "aa:bb:cc:dd:ee:ff",
                        frequencyMhz = 5180,
                        band = "5 GHz",
                        channelWidth = "80 MHz",
                        wifiStandard = "802.11ax",
                        rssiDbm = -53,
                        linkSpeedMbps = 866,
                        rxLinkSpeedMbps = 780,
                        txLinkSpeedMbps = 720,
                        hiddenSsid = false,
                        networkId = 7,
                        isPasspoint = false,
                        isOsuAp = false,
                        gateway = "192.168.1.1",
                        dhcpServer = "192.168.1.2",
                        ipAddress = "192.168.1.4",
                        subnetMask = "255.255.255.0",
                        leaseDurationSeconds = 3600,
                    ),
                cellularDetails = null,
                capturedAt = 10L,
            ),
        capturedAt = 10L,
    )

internal fun historyContext(
    id: String = "context-1",
    sessionId: String? = null,
    connectionSessionId: String? = null,
): DiagnosticContextSnapshot =
    DiagnosticContextSnapshot(
        id = id,
        sessionId = sessionId,
        connectionSessionId = connectionSessionId,
        contextKind = if (sessionId == null && connectionSessionId == null) "passive" else "post_scan",
        context =
            DiagnosticContextModel(
                service =
                    ServiceContextModel(
                        serviceStatus = "Running",
                        configuredMode = "VPN",
                        activeMode = "VPN",
                        selectedProfileId = "default",
                        selectedProfileName = "Default",
                        configSource = "ui",
                        proxyEndpoint = "127.0.0.1:1080",
                        desyncMethod = "split",
                        chainSummary = "tcp: split(1)",
                        routeGroup = "3",
                        sessionUptimeMs = 20_000L,
                        lastNativeErrorHeadline = "none",
                        restartCount = 2,
                        hostAutolearnEnabled = "enabled",
                        learnedHostCount = 3,
                        penalizedHostCount = 1,
                        lastAutolearnHost = "example.org",
                        lastAutolearnGroup = "2",
                        lastAutolearnAction = "host_promoted",
                    ),
                permissions =
                    PermissionContextModel(
                        vpnPermissionState = "enabled",
                        notificationPermissionState = "enabled",
                        batteryOptimizationState = "disabled",
                        dataSaverState = "disabled",
                    ),
                device =
                    DeviceContextModel(
                        appVersionName = "0.0.1",
                        appVersionCode = 1L,
                        buildType = "debug",
                        androidVersion = "16",
                        apiLevel = 36,
                        manufacturer = "Google",
                        model = "Pixel",
                        primaryAbi = "arm64-v8a",
                        locale = "en-US",
                        timezone = "UTC",
                    ),
                environment =
                    EnvironmentContextModel(
                        batterySaverState = "disabled",
                        powerSaveModeState = "disabled",
                        networkMeteredState = "disabled",
                        roamingState = "disabled",
                    ),
            ),
        capturedAt = 12L,
    )

internal fun historyTelemetry(
    id: String = "telemetry-1",
    connectionSessionId: String? = "connection-1",
    createdAt: Long = 30L,
    failureClass: String? = "dns_tampering",
): DiagnosticTelemetrySample =
    DiagnosticTelemetrySample(
        id = id,
        sessionId = null,
        connectionSessionId = connectionSessionId,
        activeMode = "VPN",
        connectionState = "Running",
        networkType = "wifi",
        publicIp = "198.51.100.8",
        failureClass = failureClass,
        telemetryNetworkFingerprintHash = "abcdef0123456789fedcba9876543210",
        winningTcpStrategyFamily = "hostfake",
        winningQuicStrategyFamily = "quic_burst",
        proxyRttBand = "low",
        resolverRttBand = "medium",
        proxyRouteRetryCount = 1,
        tunnelRecoveryRetryCount = 2,
        resolverId = "resolver-1",
        resolverProtocol = "DoH",
        resolverEndpoint = "https://example.org/dns-query",
        resolverLatencyMs = 42L,
        dnsFailuresTotal = 3L,
        resolverFallbackActive = false,
        resolverFallbackReason = null,
        networkHandoverClass = null,
        lastFailureClass = failureClass,
        lastFallbackAction = "resolver_override",
        txPackets = 3L,
        txBytes = 4_000L,
        rxPackets = 5L,
        rxBytes = 6_000L,
        createdAt = createdAt,
    )

internal fun historyProbeResult(
    outcome: String = "ok",
): ProbeResult =
    ProbeResult(
        probeType = "dns",
        target = "example.org",
        outcome = outcome,
        details = listOf(ProbeDetail("resolver", "1.1.1.1")),
    )

internal fun historyDiagnosticsDetail(
    sessionId: String = "scan-1",
): DiagnosticSessionDetail =
    DiagnosticSessionDetail(
        session = historyScanSession(id = sessionId),
        results = listOf(historyProbeResult()),
        snapshots = listOf(historySnapshot(id = "snapshot-$sessionId", sessionId = sessionId)),
        events = listOf(historyEvent(id = "event-$sessionId", sessionId = sessionId)),
        context = historyContext(id = "context-$sessionId", sessionId = sessionId),
    )

internal fun historyConnectionDetailUi(
    id: String = "connection-1",
): HistoryConnectionDetailUiModel =
    HistoryConnectionDetailUiModel(
        session =
            HistoryConnectionRowUiModel(
                id = id,
                title = "VPN running",
                subtitle = "wifi · Jan 1",
                serviceMode = "VPN",
                connectionState = "Running",
                networkType = "wifi",
                startedAtLabel = "Jan 1",
                summary = "VPN on wifi",
                metrics = listOf(DiagnosticsMetricUiModel("Duration", "1m 0s")),
                tone = DiagnosticsTone.Positive,
            ),
        highlights = listOf(DiagnosticsMetricUiModel("Health", "Healthy")),
        contextGroups = emptyList(),
        snapshots = emptyList(),
        events = emptyList(),
    )

internal fun historyDiagnosticsDetailUi(
    id: String = "scan-1",
): DiagnosticsSessionDetailUiModel =
    DiagnosticsSessionDetailUiModel(
        session =
            DiagnosticsSessionRowUiModel(
                id = id,
                profileId = "default",
                title = "Scan summary",
                subtitle = "RAW_PATH · VPN · Jan 1",
                pathMode = "RAW_PATH",
                serviceMode = "VPN",
                status = "completed",
                startedAtLabel = "Jan 1",
                summary = "Scan summary",
                metrics = emptyList(),
                tone = DiagnosticsTone.Positive,
            ),
        probeGroups = emptyList(),
        snapshots = emptyList(),
        events = emptyList(),
        contextGroups = emptyList(),
        hasSensitiveDetails = false,
        sensitiveDetailsVisible = false,
    )

internal class FakeDiagnosticsHistorySource : DiagnosticsHistorySource {
    val connectionSessions = MutableStateFlow<List<DiagnosticConnectionSession>>(emptyList())
    val diagnosticsSessions = MutableStateFlow<List<DiagnosticScanSession>>(emptyList())
    val nativeEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val connectionDetails = mutableMapOf<String, DiagnosticConnectionDetail?>()

    override fun observeConnectionSessions(limit: Int): Flow<List<DiagnosticConnectionSession>> = connectionSessions

    override fun observeDiagnosticsSessions(limit: Int): Flow<List<DiagnosticScanSession>> = diagnosticsSessions

    override fun observeNativeEvents(limit: Int): Flow<List<DiagnosticEvent>> = nativeEvents

    override suspend fun loadConnectionDetail(sessionId: String): DiagnosticConnectionDetail? = connectionDetails[sessionId]
}

internal class FakeHistoryDetailLoader : HistoryDetailLoader {
    val connectionDetails = mutableMapOf<String, HistoryConnectionDetailUiModel?>()
    val diagnosticsDetails = mutableMapOf<String, DiagnosticsSessionDetailUiModel?>()

    override suspend fun loadConnectionDetail(sessionId: String): HistoryConnectionDetailUiModel? =
        connectionDetails[sessionId]

    override suspend fun loadDiagnosticsDetail(sessionId: String): DiagnosticsSessionDetailUiModel? =
        diagnosticsDetails[sessionId]
}

internal class FakeDiagnosticsSessionDetailUiMapper : DiagnosticsSessionDetailUiMapper {
    var nextResult: DiagnosticsSessionDetailUiModel? = null
    var lastDetail: DiagnosticSessionDetail? = null
    var lastSensitiveDetailsFlag: Boolean? = null

    override fun toSessionDetailUiModel(
        detail: DiagnosticSessionDetail,
        showSensitiveDetails: Boolean,
    ): DiagnosticsSessionDetailUiModel {
        lastDetail = detail
        lastSensitiveDetailsFlag = showSensitiveDetails
        return requireNotNull(nextResult) { "Missing fake diagnostics detail UI model" }
    }
}

internal class FakeHistoryDiagnosticsBootstrapper : DiagnosticsBootstrapper {
    var initializeCalls: Int = 0
        private set

    override suspend fun initialize() {
        initializeCalls += 1
    }
}

internal class FakeHistoryDiagnosticsDetailLoader : DiagnosticsDetailLoader {
    var nextDetail: DiagnosticSessionDetail? = null

    override suspend fun loadSessionDetail(sessionId: String): DiagnosticSessionDetail =
        requireNotNull(nextDetail) { "Missing fake detail for $sessionId" }

    override suspend fun loadApproachDetail(
        kind: BypassApproachKind,
        id: String,
    ): BypassApproachDetail = throw UnsupportedOperationException("Unused in history tests")
}
