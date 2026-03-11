package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassOutcomeBreakdown
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.CellularNetworkDetails
import com.poyka.ripdpi.diagnostics.DeviceContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.EnvironmentContextModel
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.PermissionContextModel
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.ServiceContextModel
import com.poyka.ripdpi.diagnostics.ShareSummary
import com.poyka.ripdpi.diagnostics.SummaryMetric
import com.poyka.ripdpi.diagnostics.WifiNetworkDetails
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val json = Json

    @Test
    fun `ui state groups overview live sessions and share models`() =
        runTest {
            val manager = FakeDiagnosticsManager().apply {
                profilesState.value =
                    listOf(
                        DiagnosticProfileEntity(
                            id = "default",
                            name = "Default",
                            source = "bundled",
                            version = 1,
                            requestJson = "{}",
                            updatedAt = 1L,
                        ),
                    )
                sessionsState.value =
                    listOf(
                        session(
                            id = "session-1",
                            profileId = "default",
                            pathMode = "IN_PATH",
                            summary = "Latest report",
                        ),
                    )
                snapshotsState.value =
                    listOf(
                        snapshot(
                            id = "snapshot-1",
                            sessionId = "session-1",
                        ),
                    )
                telemetryState.value =
                    listOf(
                        TelemetrySampleEntity(
                            id = "telemetry-1",
                            sessionId = null,
                            activeMode = "VPN",
                            connectionState = "Running",
                            networkType = "wifi",
                            publicIp = "198.51.100.8",
                            txPackets = 3,
                            txBytes = 4_000,
                            rxPackets = 5,
                            rxBytes = 6_000,
                            createdAt = 20L,
                        ),
                    )
                contextsState.value =
                    listOf(
                        context(
                            id = "context-1",
                            sessionId = null,
                        ),
                    )
                nativeEventsState.value =
                    listOf(
                        NativeSessionEventEntity(
                            id = "event-1",
                            sessionId = "session-1",
                            source = "proxy",
                            level = "warn",
                            message = "Route advanced",
                            createdAt = 30L,
                        ),
                    )
                exportsState.value =
                    listOf(
                        ExportRecordEntity(
                            id = "export-1",
                            sessionId = "session-1",
                            uri = "/tmp/report.zip",
                            fileName = "report.zip",
                            createdAt = 40L,
                        ),
                    )
            }

            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(1, manager.initializeCalls)
            assertEquals(DiagnosticsSection.Overview, state.selectedSection)
            assertEquals("Default", state.overview.activeProfile?.name)
            assertEquals("Running", state.live.statusLabel)
            assertEquals(1, state.sessions.sessions.size)
            assertEquals("report.zip", state.share.latestArchiveFileName)
            assertEquals("Support context", state.overview.contextSummary?.title)
            assertTrue(
                state.overview.contextSummary
                    ?.fields
                    ?.any { it.label == "Host learning" && it.value.contains("Active") } == true,
            )
            assertTrue(state.events.events.first().severity.contains("WARN"))
            collector.cancel()
        }

    @Test
    fun `active scan forces scan section and profile selection updates manager`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    profilesState.value =
                        listOf(
                            DiagnosticProfileEntity(
                                id = "default",
                                name = "Default",
                                source = "bundled",
                                version = 1,
                                requestJson = "{}",
                                updatedAt = 1L,
                            ),
                            DiagnosticProfileEntity(
                                id = "custom",
                                name = "Custom",
                                source = "local",
                                version = 1,
                                requestJson = "{}",
                                updatedAt = 2L,
                            ),
                        )
                    progressState.value =
                        ScanProgress(
                            sessionId = "session-running",
                            phase = "dns",
                            completedSteps = 1,
                            totalSteps = 3,
                            message = "Checking DNS",
                        )
                }

            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.selectSection(DiagnosticsSection.Events)
            viewModel.selectProfile("custom")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(DiagnosticsSection.Scan, state.selectedSection)
            assertEquals("custom", manager.lastActiveProfileId)
            assertEquals("custom", state.scan.selectedProfileId)
            assertNotNull(state.scan.activeProgress)
            collector.cancel()
        }

    @Test
    fun `select session loads grouped detail model`() =
        runTest {
            val detail =
                DiagnosticSessionDetail(
                    session = session(id = "session-1", profileId = "default", pathMode = "RAW_PATH", summary = "Session"),
                    results =
                        listOf(
                            ProbeResultEntity(
                                id = "probe-1",
                                sessionId = "session-1",
                                probeType = "dns",
                                target = "example.org",
                                outcome = "blocked",
                                detailJson = json.encodeToString(listOf(ProbeDetail("resolver", "1.1.1.1"))),
                                createdAt = 1L,
                            ),
                        ),
                    snapshots = listOf(snapshot(id = "snapshot-1", sessionId = "session-1")),
                    context = context(id = "context-1", sessionId = "session-1"),
                    events =
                        listOf(
                            NativeSessionEventEntity(
                                id = "event-1",
                                sessionId = "session-1",
                                source = "proxy",
                                level = "info",
                                message = "accepted",
                                createdAt = 2L,
                            ),
                        ),
                )
            val manager = FakeDiagnosticsManager(detail = detail)
            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.selectSession("session-1")
            advanceUntilIdle()

            val selected = viewModel.uiState.value.selectedSessionDetail
            assertNotNull(selected)
            assertEquals("Session", selected?.session?.title)
            assertEquals(1, selected?.probeGroups?.first()?.items?.size)
            assertEquals("example.org", selected?.probeGroups?.first()?.items?.first()?.target)
            assertEquals("Service", selected?.contextGroups?.first()?.title)
            collector.cancel()
        }

    @Test
    fun `approaches section switches modes and loads detail`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    sessionsState.value =
                        listOf(
                            session(
                                id = "session-1",
                                profileId = "default",
                                pathMode = "RAW_PATH",
                                summary = "Validated success",
                            ),
                        )
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Profile,
                                id = "default",
                            ).copy(displayName = "Default", secondaryLabel = "Profile"),
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-1",
                            ),
                        )
                }
            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            val beforeDetail = viewModel.uiState.value
            assertEquals(DiagnosticsSection.Approaches, beforeDetail.selectedSection)
            assertEquals(1, beforeDetail.approaches.rows.size)
            assertEquals("VPN Split", beforeDetail.approaches.rows.first().title)

            viewModel.selectApproach("strategy-1")
            advanceUntilIdle()

            val detail = viewModel.uiState.value.selectedApproachDetail
            assertNotNull(detail)
            assertEquals("VPN Split", detail?.approach?.title)
            assertTrue(detail?.signature?.any { it.label == "Mode" } == true)
            collector.cancel()
        }

    @Test
    fun `approaches detail humanizes fake tls signature`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-fake-tls",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "enabled",
                            desyncMethod = "fake",
                            chainSummary = "tcp: fake(host)",
                            protocolToggles = listOf("HTTPS"),
                            tlsRecordSplitEnabled = false,
                            tlsRecordMarker = null,
                            splitMarker = "host",
                            fakeSniMode = "fixed",
                            fakeSniValue = "alt.example.org",
                            fakeTlsBaseMode = "original",
                            fakeTlsMods = listOf("rand", "dupsid", "padencap"),
                            fakeTlsSize = -24,
                            fakeOffsetMarker = "host+1",
                            routeGroup = "2",
                        )
                }
            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-fake-tls")
            advanceUntilIdle()

            val signature = viewModel.uiState.value.selectedApproachDetail?.signature.orEmpty()
            assertTrue(signature.any { it.label == "Fake TLS base" && it.value == "Original ClientHello" })
            assertTrue(signature.any { it.label == "Fake TLS SNI" && it.value == "Fixed (alt.example.org)" })
            assertTrue(
                signature.any {
                    it.label == "Fake TLS mods" &&
                        it.value == "Randomize TLS material, Copy Session ID, Padding camouflage"
                },
            )
            assertTrue(signature.any { it.label == "Fake TLS size" && it.value == "Input minus 24 bytes" })
            collector.cancel()
        }

    @Test
    fun `snapshot detail shows wifi and cellular transport fields`() =
        runTest {
            val wifiDetail =
                DiagnosticSessionDetail(
                    session = session(id = "session-wifi", profileId = "default", pathMode = "RAW_PATH", summary = "Wi-Fi"),
                    results = emptyList(),
                    snapshots =
                        listOf(
                            snapshot(
                                id = "snapshot-wifi",
                                sessionId = "session-wifi",
                                transport = "wifi",
                            ),
                        ),
                    context = context(id = "context-wifi", sessionId = "session-wifi"),
                    events = emptyList(),
                )
            val manager = FakeDiagnosticsManager(detail = wifiDetail)
            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.selectSession("session-wifi")
            advanceUntilIdle()

            val wifiFields = viewModel.uiState.value.selectedSessionDetail?.snapshots?.first()?.fields.orEmpty()
            assertTrue(wifiFields.any { it.label == "Wi-Fi band" && it.value == "5 GHz" })
            assertTrue(wifiFields.any { it.label == "Wi-Fi SSID" && it.value == "redacted" })

            manager.detail =
                wifiDetail.copy(
                    session = session(id = "session-cell", profileId = "default", pathMode = "RAW_PATH", summary = "Cell"),
                    snapshots =
                        listOf(
                            snapshot(
                                id = "snapshot-cell",
                                sessionId = "session-cell",
                                transport = "cellular",
                                cellularDetails =
                                    CellularNetworkDetails(
                                        carrierName = "Example Carrier",
                                        simOperatorName = "Example Carrier",
                                        networkOperatorName = "Example Carrier LTE",
                                        networkCountryIso = "us",
                                        simCountryIso = "us",
                                        operatorCode = "310260",
                                        simOperatorCode = "310260",
                                        dataNetworkType = "NR",
                                        voiceNetworkType = "LTE",
                                        dataState = "connected",
                                        serviceState = "in_service",
                                        isNetworkRoaming = false,
                                        carrierId = 42,
                                        simCarrierId = 42,
                                        signalLevel = 4,
                                        signalDbm = -95,
                                    ),
                            ),
                        ),
                )
            viewModel.selectSession("session-cell")
            advanceUntilIdle()

            val cellFields = viewModel.uiState.value.selectedSessionDetail?.snapshots?.first()?.fields.orEmpty()
            assertTrue(cellFields.any { it.label == "Carrier" && it.value == "Example Carrier" })
            assertTrue(cellFields.any { it.label == "Data network" && it.value == "NR" })
            collector.cancel()
        }

    @Test
    fun `share summary emits effect and archive actions use selected target session`() =
        runTest {
            val manager = FakeDiagnosticsManager().apply {
                sessionsState.value =
                    listOf(
                        session(
                            id = "session-1",
                            profileId = "default",
                            pathMode = "RAW_PATH",
                            summary = "Selected",
                        ),
                    )
            }
            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val shareEffect = async { viewModel.effects.first() }
            viewModel.shareSummary("session-1")
            advanceUntilIdle()

            val effect = shareEffect.await() as DiagnosticsEffect.ShareSummaryRequested
            assertEquals("RIPDPI summary", effect.title)
            assertTrue(effect.body.contains("session-1"))

            val shareArchiveEffect = async { viewModel.effects.first() }
            viewModel.shareArchive("session-1")
            advanceUntilIdle()

            val shareArchive = shareArchiveEffect.await() as DiagnosticsEffect.ShareArchiveRequested
            assertEquals("session-1", manager.lastArchiveSessionId)
            assertEquals("/tmp/archive-session-1.zip", shareArchive.absolutePath)

            val saveArchiveEffect = async { viewModel.effects.first() }
            viewModel.saveArchive("session-1")
            advanceUntilIdle()

            val saveArchive = saveArchiveEffect.await() as DiagnosticsEffect.SaveArchiveRequested
            assertEquals("session-1", manager.lastArchiveSessionId)
            assertEquals("/tmp/archive-session-1.zip", saveArchive.absolutePath)
            collector.cancel()
        }

    @Test
    fun `event filter narrows the visible stream`() =
        runTest {
            val manager = FakeDiagnosticsManager().apply {
                nativeEventsState.value =
                    listOf(
                        NativeSessionEventEntity(
                            id = "event-1",
                            sessionId = null,
                            source = "proxy",
                            level = "warn",
                            message = "First warning",
                            createdAt = 1L,
                        ),
                        NativeSessionEventEntity(
                            id = "event-2",
                            sessionId = null,
                            source = "tunnel",
                            level = "info",
                            message = "Healthy session",
                            createdAt = 2L,
                        ),
                    )
            }
            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.toggleEventFilter(source = "Proxy")
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.events.events.size)
            assertEquals("Proxy", viewModel.uiState.value.events.events.first().source)
            collector.cancel()
        }

    @Test
    fun `archive failure updates share state`() =
        runTest {
            val manager =
                FakeDiagnosticsManager(
                    archiveFailure = IllegalStateException("boom"),
                ).apply {
                    sessionsState.value =
                        listOf(
                            session(
                                id = "session-1",
                                profileId = "default",
                                pathMode = "RAW_PATH",
                                summary = "Selected",
                            ),
                        )
                }
            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.shareArchive("session-1")
            advanceUntilIdle()

            val shareState = viewModel.uiState.value.share
            assertEquals("Failed to generate archive", shareState.archiveStateMessage)
            assertEquals(DiagnosticsTone.Negative, shareState.archiveStateTone)
            assertFalse(shareState.isArchiveBusy)
            collector.cancel()
        }

    private fun session(
        id: String,
        profileId: String,
        pathMode: String,
        summary: String,
    ): ScanSessionEntity =
        ScanSessionEntity(
            id = id,
            profileId = profileId,
            pathMode = pathMode,
            serviceMode = "VPN",
            status = "completed",
            summary = summary,
            reportJson =
                json.encodeToString(
                    ScanReport(
                        sessionId = id,
                        profileId = profileId,
                        pathMode = ScanPathMode.valueOf(pathMode),
                        startedAt = 1L,
                        finishedAt = 2L,
                        summary = summary,
                        results =
                            listOf(
                                com.poyka.ripdpi.diagnostics.ProbeResult(
                                    probeType = "dns",
                                    target = "example.org",
                                    outcome = "ok",
                                    details = listOf(ProbeDetail("resolver", "1.1.1.1")),
                                ),
                            ),
                    ),
                ),
            startedAt = 1L,
            finishedAt = 2L,
        )

    private fun snapshot(
        id: String,
        sessionId: String?,
        transport: String = "wifi",
        cellularDetails: CellularNetworkDetails? = null,
    ): NetworkSnapshotEntity =
        NetworkSnapshotEntity(
            id = id,
            sessionId = sessionId,
            snapshotKind = "passive",
            payloadJson =
                json.encodeToString(
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
                            if (transport == "wifi") {
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
                                )
                            } else {
                                null
                            },
                        cellularDetails = if (transport == "cellular") cellularDetails else null,
                        capturedAt = 10L,
                    ),
                ),
            capturedAt = 10L,
        )

    private fun context(
        id: String,
        sessionId: String?,
    ): DiagnosticContextEntity =
        DiagnosticContextEntity(
            id = id,
            sessionId = sessionId,
            contextKind = if (sessionId == null) "passive" else "post_scan",
            payloadJson =
                json.encodeToString(
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
                ),
            capturedAt = 12L,
        )
}

private class FakeDiagnosticsManager(
    var detail: DiagnosticSessionDetail? = null,
    private val archiveFailure: Throwable? = null,
) : DiagnosticsManager {
    private val _progressState = MutableStateFlow<ScanProgress?>(null)
    val progressState: MutableStateFlow<ScanProgress?> = _progressState
    val profilesState = MutableStateFlow<List<DiagnosticProfileEntity>>(emptyList())
    val sessionsState = MutableStateFlow<List<ScanSessionEntity>>(emptyList())
    val snapshotsState = MutableStateFlow<List<NetworkSnapshotEntity>>(emptyList())
    val contextsState = MutableStateFlow<List<DiagnosticContextEntity>>(emptyList())
    val telemetryState = MutableStateFlow<List<TelemetrySampleEntity>>(emptyList())
    val nativeEventsState = MutableStateFlow<List<NativeSessionEventEntity>>(emptyList())
    val exportsState = MutableStateFlow<List<ExportRecordEntity>>(emptyList())
    val approachStatsState = MutableStateFlow<List<BypassApproachSummary>>(emptyList())
    var initializeCalls = 0
    var lastArchiveSessionId: String? = null
    var lastActiveProfileId: String? = null
    var strategySignatureOverride: BypassStrategySignature? = null

    override val activeScanProgress: StateFlow<ScanProgress?> = _progressState.asStateFlow()
    override val profiles: Flow<List<DiagnosticProfileEntity>> = profilesState
    override val sessions: Flow<List<ScanSessionEntity>> = sessionsState
    override val approachStats: Flow<List<BypassApproachSummary>> = approachStatsState
    override val snapshots: Flow<List<NetworkSnapshotEntity>> = snapshotsState
    override val contexts: Flow<List<DiagnosticContextEntity>> = contextsState
    override val telemetry: Flow<List<TelemetrySampleEntity>> = telemetryState
    override val nativeEvents: Flow<List<NativeSessionEventEntity>> = nativeEventsState
    override val exports: Flow<List<ExportRecordEntity>> = exportsState

    override suspend fun initialize() {
        initializeCalls += 1
    }

    override suspend fun startScan(pathMode: ScanPathMode): String = "session-${pathMode.name}"

    override suspend fun cancelActiveScan() {
        progressState.value = null
    }

    override suspend fun setActiveProfile(profileId: String) {
        lastActiveProfileId = profileId
    }

    override suspend fun loadSessionDetail(sessionId: String): DiagnosticSessionDetail =
        requireNotNull(detail) { "Missing fake detail for $sessionId" }

    override suspend fun loadApproachDetail(
        kind: BypassApproachKind,
        id: String,
    ): BypassApproachDetail =
        BypassApproachDetail(
            summary =
                approachStatsState.value.firstOrNull { it.approachId.kind == kind && it.approachId.value == id }
                    ?: sampleApproachSummary(kind = kind, id = id),
            strategySignature =
                strategySignatureOverride ?:
                    BypassStrategySignature(
                        mode = "VPN",
                        configSource = "ui",
                        hostAutolearn = "enabled",
                        desyncMethod = "split",
                        chainSummary = "tcp: split(1)",
                        protocolToggles = listOf("HTTP", "HTTPS"),
                        tlsRecordSplitEnabled = true,
                        tlsRecordMarker = "extlen",
                        splitMarker = "1",
                        fakeSniMode = null,
                        fakeSniValue = null,
                        fakeTlsBaseMode = null,
                        fakeTlsMods = emptyList(),
                        fakeTlsSize = null,
                        fakeOffsetMarker = null,
                        routeGroup = "3",
                    ),
            recentValidatedSessions = sessionsState.value.take(2),
            recentUsageSessions = emptyList(),
            commonProbeFailures = listOf("dns_blocked (2)"),
            recentFailureNotes = listOf("dns:example.org=blocked"),
        )

    override suspend fun buildShareSummary(sessionId: String?): ShareSummary =
        ShareSummary(
            title = "RIPDPI summary",
            body = "Summary for ${sessionId ?: "latest"}",
            compactMetrics = listOf(SummaryMetric("Path", "RAW_PATH")),
        )

    override suspend fun createArchive(sessionId: String?): DiagnosticsArchive {
        archiveFailure?.let { throw it }
        lastArchiveSessionId = sessionId
        return DiagnosticsArchive(
            fileName = "archive.zip",
            absolutePath = "/tmp/archive-${sessionId ?: "all"}.zip",
            sessionId = sessionId,
            createdAt = 42L,
            scope = "hybrid",
            schemaVersion = 2,
            privacyMode = "split_output",
        )
    }
}

private fun sampleApproachSummary(
    kind: BypassApproachKind,
    id: String,
): BypassApproachSummary =
    BypassApproachSummary(
        approachId = BypassApproachId(kind = kind, value = id),
        displayName = "VPN Split",
        secondaryLabel = "Strategy",
        verificationState = "validated",
        validatedScanCount = 3,
        validatedSuccessCount = 2,
        validatedSuccessRate = 0.66f,
        lastValidatedResult = "Latest report",
        usageCount = 4,
        totalRuntimeDurationMs = 30_000L,
        recentRuntimeHealth = BypassRuntimeHealthSummary(totalErrors = 1, routeChanges = 2, restartCount = 1),
        lastUsedAt = 42L,
        topFailureOutcomes = listOf("dns_blocked (1)"),
        outcomeBreakdown = listOf(BypassOutcomeBreakdown("dns", 2, 0, 1, "dns_blocked")),
    )
