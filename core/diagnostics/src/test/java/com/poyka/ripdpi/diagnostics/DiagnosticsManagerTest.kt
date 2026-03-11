package com.poyka.ripdpi.diagnostics

import android.content.ContextWrapper
import com.poyka.ripdpi.core.NativeRuntimeEvent
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultQueue
import com.poyka.ripdpi.core.testing.FaultSpec
import com.poyka.ripdpi.core.testing.faultThrowable
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.services.ServiceEvent
import com.poyka.ripdpi.services.ServiceStateStore
import com.poyka.ripdpi.services.ServiceTelemetrySnapshot
import com.poyka.ripdpi.diagnostics.CellularNetworkDetails
import com.poyka.ripdpi.diagnostics.WifiNetworkDetails
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsManagerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `raw path scan persists completed report and bridged telemetry events`() =
        runTest {
            val history = FakeDiagnosticsHistoryRepository().apply { seedDefaultProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator()
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = runtimeCoordinator,
                    serviceStateStore = serviceStateStore,
                )

            val sessionId = manager.startScan(ScanPathMode.RAW_PATH)
            waitForCompletion(history, sessionId)

            val request = json.decodeFromString(ScanRequest.serializer(), bridgeFactory.bridge.startedRequestJson!!)
            val session = history.getScanSession(sessionId)
            val results = history.getProbeResults(sessionId)

            assertEquals(1, runtimeCoordinator.rawScanCount.get())
            assertEquals(ScanPathMode.RAW_PATH, request.pathMode)
            assertNull(request.proxyHost)
            assertNull(request.proxyPort)
            assertEquals("completed", session?.status)
            assertEquals(ScanPathMode.RAW_PATH.name, session?.pathMode)
            assertEquals(2, history.snapshotsState.value.size)
            assertEquals(1, results.size)
            assertEquals(2, history.nativeEventsState.value.size)
            assertTrue(history.nativeEventsState.value.any { it.source == "native" })
            assertTrue(history.nativeEventsState.value.any { it.source == "dns" })
        }

    @Test
    fun `in path scan injects proxy settings into request`() =
        runTest {
            val history = FakeDiagnosticsHistoryRepository().apply { seedDefaultProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator()
            val settings = defaultAppSettings().toBuilder().setProxyIp("10.0.0.2").setProxyPort(2080).build()
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(settings),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = runtimeCoordinator,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                )

            val sessionId = manager.startScan(ScanPathMode.IN_PATH)
            waitForCompletion(history, sessionId)

            val request = json.decodeFromString(ScanRequest.serializer(), bridgeFactory.bridge.startedRequestJson!!)

            assertEquals(0, runtimeCoordinator.rawScanCount.get())
            assertEquals(ScanPathMode.IN_PATH, request.pathMode)
            assertEquals("10.0.0.2", request.proxyHost)
            assertEquals(2080, request.proxyPort)
        }

    @Test
    fun `persistServiceNativeEvents stores proxy and tunnel runtime events`() =
        runTest {
            val history = FakeDiagnosticsHistoryRepository()
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            manager.persistServiceNativeEvents(
                ServiceTelemetrySnapshot(
                    proxyTelemetry =
                        com.poyka.ripdpi.core.NativeRuntimeSnapshot(
                            source = "proxy",
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "proxy",
                                        level = "info",
                                        message = "accepted",
                                        createdAt = 10L,
                                    ),
                                ),
                        ),
                    tunnelTelemetry =
                        com.poyka.ripdpi.core.NativeRuntimeSnapshot(
                            source = "tunnel",
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "tunnel",
                                        level = "warn",
                                        message = "slow upstream",
                                        createdAt = 20L,
                                    ),
                                ),
                        ),
                ),
            )

            assertEquals(2, history.nativeEventsState.value.size)
            assertEquals(setOf("proxy", "tunnel"), history.nativeEventsState.value.map { it.source }.toSet())
        }

    @Test
    fun `persisted native events match golden contract`() =
        runTest {
            val history = FakeDiagnosticsHistoryRepository()
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            manager.persistServiceNativeEvents(
                ServiceTelemetrySnapshot(
                    proxyTelemetry =
                        com.poyka.ripdpi.core.NativeRuntimeSnapshot(
                            source = "proxy",
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "proxy",
                                        level = "info",
                                        message = "accepted",
                                        createdAt = 10L,
                                    ),
                                ),
                        ),
                    tunnelTelemetry =
                        com.poyka.ripdpi.core.NativeRuntimeSnapshot(
                            source = "tunnel",
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "tunnel",
                                        level = "warn",
                                        message = "slow upstream",
                                        createdAt = 20L,
                                    ),
                                ),
                        ),
                ),
            )

            GoldenContractSupport.assertJsonGolden(
                "persisted_native_events.json",
                json.encodeToString(history.nativeEventsState.value),
                ::scrubDiagnosticsJson,
            )
        }

    @Test
    fun `passive monitor snapshot serialization matches golden contract`() {
        val snapshot =
            PassiveMonitorSnapshot(
                activeMode = "VPN",
                connectionState = "Running",
                networkType = "wifi",
                publicIp = "198.51.100.8",
                txPackets = 12,
                txBytes = 2_048,
                rxPackets = 18,
                rxBytes = 4_096,
                nativeEvents =
                    listOf(
                        NativeSessionEvent(
                            source = "proxy",
                            level = "info",
                            message = "accepted",
                            createdAt = 321L,
                        ),
                    ),
                capturedAt = 654L,
            )

        GoldenContractSupport.assertJsonGolden(
            "passive_monitor_snapshot.json",
            json.encodeToString(PassiveMonitorSnapshot.serializer(), snapshot),
            ::scrubDiagnosticsJson,
        )
    }

    @Test
    fun `createArchive includes selected session and redacts human readable files`() =
        runTest {
            val cacheDir = Files.createTempDirectory("diagnostics-archive-selected").toFile()
            val history =
                FakeDiagnosticsHistoryRepository().apply {
                    sessionsState.value =
                        listOf(
                            session(
                                id = "session-selected",
                                profileId = "default",
                                pathMode = "IN_PATH",
                                summary = "Blocked DNS",
                            ),
                        )
                    replaceProbeResults(
                        "session-selected",
                        listOf(
                            ProbeResultEntity(
                                id = "probe-1",
                                sessionId = "session-selected",
                                probeType = "dns",
                                target = "blocked.example",
                                outcome = "substituted",
                                detailJson = "[]",
                                createdAt = 30L,
                            ),
                        ),
                    )
                    upsertSnapshot(
                        snapshot(
                            id = "snapshot-selected",
                            sessionId = "session-selected",
                        ),
                    )
                    upsertSnapshot(
                        snapshot(
                            id = "snapshot-passive",
                            sessionId = null,
                        ),
                    )
                    upsertContextSnapshot(context(id = "context-selected", sessionId = "session-selected"))
                    upsertContextSnapshot(context(id = "context-passive", sessionId = null))
                    insertTelemetrySample(sample(id = "telemetry-1", publicIp = "198.51.100.8"))
                    insertNativeSessionEvent(event(id = "event-selected", sessionId = "session-selected", source = "dns", level = "warn"))
                    insertNativeSessionEvent(event(id = "event-global", sessionId = null, source = "proxy", level = "warn"))
                }
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(cacheDir),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            val archive = manager.createArchive("session-selected")
            val entries = unzipEntries(archive.absolutePath)

            assertEquals("session-selected", archive.sessionId)
            assertEquals(setOf("summary.txt", "report.json", "telemetry.csv", "manifest.json"), entries.keys)
            assertTrue(entries.getValue("summary.txt").contains("selectedSession=session-selected"))
            assertTrue(entries.getValue("summary.txt").contains("publicIp=redacted"))
            assertTrue(entries.getValue("summary.txt").contains("dns=redacted(1)"))
            assertFalse(entries.getValue("summary.txt").contains("198.51.100.8"))
            assertFalse(entries.getValue("summary.txt").contains("192.0.2.10"))
            assertFalse(entries.getValue("summary.txt").contains("AS64500"))
            assertTrue(entries.getValue("summary.txt").contains("appVersion=0.0.1"))
            assertTrue(entries.getValue("summary.txt").contains("proxyEndpoint=redacted"))
            assertTrue(entries.getValue("summary.txt").contains("wifiSsid=redacted"))
            assertTrue(entries.getValue("summary.txt").contains("wifiBand=5 GHz"))
            assertTrue(entries.getValue("report.json").contains("\"publicIp\": \"198.51.100.8\""))
            assertTrue(entries.getValue("report.json").contains("1.1.1.1"))
            assertTrue(entries.getValue("report.json").contains("\"sessionContexts\": ["))
            assertTrue(entries.getValue("report.json").contains("127.0.0.1:1080"))
            assertTrue(entries.getValue("report.json").contains("RIPDPI Lab"))
            assertTrue(entries.getValue("telemetry.csv").contains("198.51.100.8"))
            assertTrue(entries.getValue("manifest.json").contains("\"includedSessionId\": \"session-selected\""))
            assertTrue(entries.getValue("manifest.json").contains("\"privacyMode\": \"split_output\""))
            assertTrue(entries.getValue("manifest.json").contains("\"publicIp\": \"redacted\""))
            assertTrue(entries.getValue("manifest.json").contains("\"contextSnapshotCount\": 1"))
            assertTrue(entries.getValue("manifest.json").contains("\"proxyEndpoint\": \"redacted\""))
            assertTrue(entries.getValue("manifest.json").contains("\"ssid\": \"redacted\""))
            assertFalse(entries.getValue("manifest.json").contains("198.51.100.8"))
            assertEquals("session-selected", history.exportsState.value.single().sessionId)
        }

    @Test
    fun `archive telemetry outputs match golden contracts`() =
        runTest {
            val cacheDir = Files.createTempDirectory("diagnostics-archive-golden").toFile()
            val history =
                FakeDiagnosticsHistoryRepository().apply {
                    sessionsState.value =
                        listOf(
                            session(
                                id = "session-selected",
                                profileId = "default",
                                pathMode = "IN_PATH",
                                summary = "Blocked DNS",
                            ),
                        )
                    replaceProbeResults(
                        "session-selected",
                        listOf(
                            ProbeResultEntity(
                                id = "probe-1",
                                sessionId = "session-selected",
                                probeType = "dns",
                                target = "blocked.example",
                                outcome = "substituted",
                                detailJson = "[]",
                                createdAt = 30L,
                            ),
                        ),
                    )
                    upsertSnapshot(snapshot(id = "snapshot-selected", sessionId = "session-selected"))
                    upsertContextSnapshot(context(id = "context-selected", sessionId = "session-selected"))
                    insertTelemetrySample(sample(id = "telemetry-1", publicIp = "198.51.100.8"))
                    insertNativeSessionEvent(event(id = "event-selected", sessionId = "session-selected", source = "dns", level = "warn"))
                    insertNativeSessionEvent(event(id = "event-global", sessionId = null, source = "proxy", level = "warn"))
                }
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(cacheDir),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            val archive = manager.createArchive("session-selected")
            val entries = unzipEntries(archive.absolutePath)

            GoldenContractSupport.assertTextGolden(
                "telemetry_archive.csv",
                entries.getValue("telemetry.csv"),
            )
            GoldenContractSupport.assertJsonGolden(
                "telemetry_archive_manifest.json",
                entries.getValue("manifest.json"),
                ::scrubDiagnosticsJson,
            )
        }

    @Test
    fun `createArchive keeps carrier diagnostics in raw report and share summary`() =
        runTest {
            val cacheDir = Files.createTempDirectory("diagnostics-archive-cellular").toFile()
            val history =
                FakeDiagnosticsHistoryRepository().apply {
                    sessionsState.value = listOf(session(id = "session-cell", profileId = "default", pathMode = "RAW_PATH", summary = "Carrier"))
                    upsertSnapshot(
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
                    )
                    upsertContextSnapshot(context(id = "context-cell", sessionId = "session-cell"))
                }
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(cacheDir),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            val archive = manager.createArchive("session-cell")
            val entries = unzipEntries(archive.absolutePath)

            assertTrue(entries.getValue("summary.txt").contains("carrier=Example Carrier"))
            assertTrue(entries.getValue("summary.txt").contains("dataNetwork=NR"))
            assertTrue(entries.getValue("report.json").contains("310260"))
            assertTrue(entries.getValue("manifest.json").contains("\"carrierName\": \"Example Carrier\""))
        }

    @Test
    fun `createArchive falls back to latest completed session when target omitted`() =
        runTest {
            val cacheDir = Files.createTempDirectory("diagnostics-archive-latest").toFile()
            val history =
                FakeDiagnosticsHistoryRepository().apply {
                    sessionsState.value =
                        listOf(
                            session(
                                id = "session-latest",
                                profileId = "default",
                                pathMode = "RAW_PATH",
                                summary = "Latest completed",
                            ),
                            session(
                                id = "session-running",
                                profileId = "default",
                                pathMode = "IN_PATH",
                                summary = "Still running",
                                status = "running",
                                reportJson = null,
                            ),
                        )
                    replaceProbeResults("session-latest", listOf(ProbeResultEntity("probe-1", "session-latest", "http", "example.org", "ok", "[]", 20L)))
                    upsertSnapshot(snapshot(id = "snapshot-passive", sessionId = null))
                    upsertContextSnapshot(context(id = "context-latest", sessionId = "session-latest"))
                    insertTelemetrySample(sample(id = "telemetry-1", publicIp = "198.51.100.8"))
                    insertNativeSessionEvent(event(id = "event-global", sessionId = null, source = "proxy", level = "info"))
                }
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(cacheDir),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            val archive = manager.createArchive(null)
            val entries = unzipEntries(archive.absolutePath)

            assertEquals("session-latest", archive.sessionId)
            assertTrue(entries.getValue("manifest.json").contains("\"includedSessionId\": \"session-latest\""))
            assertTrue(entries.getValue("report.json").contains("\"id\": \"session-latest\""))
            assertTrue(entries.getValue("report.json").contains("\"contextKind\": \"post_scan\""))
            assertEquals("session-latest", history.exportsState.value.single().sessionId)
        }

    @Test
    fun `approachStats combine validated scan outcomes and runtime usage`() =
        runTest {
            val signature =
                BypassStrategySignature(
                    mode = "VPN",
                    configSource = "ui",
                    desyncMethod = "split",
                    chainSummary = "tcp: split(1)",
                    protocolToggles = listOf("HTTP", "HTTPS"),
                    tlsRecordSplitEnabled = true,
                    tlsRecordMarker = "extlen",
                    splitMarker = "1",
                    fakeSniMode = null,
                    fakeOffsetMarker = null,
                    routeGroup = "3",
                )
            val strategyId = signature.stableId()
            val strategyJson = Json.encodeToString(BypassStrategySignature.serializer(), signature)
            val history =
                FakeDiagnosticsHistoryRepository().apply {
                    sessionsState.value =
                        listOf(
                            session(
                                id = "session-ok",
                                profileId = "default",
                                pathMode = "RAW_PATH",
                                summary = "Validated success",
                                reportJson =
                                    Json.encodeToString(
                                        ScanReport.serializer(),
                                        ScanReport(
                                            sessionId = "session-ok",
                                            profileId = "default",
                                            pathMode = ScanPathMode.RAW_PATH,
                                            startedAt = 10L,
                                            finishedAt = 20L,
                                            summary = "Validated success",
                                            results =
                                                listOf(
                                                    ProbeResult("dns", "example.org", "ok"),
                                                    ProbeResult("tls", "example.org", "ok"),
                                                ),
                                        ),
                                    ),
                            ).copy(
                                approachProfileId = "default",
                                approachProfileName = "Default",
                                strategyId = strategyId,
                                strategyLabel = "VPN Split",
                                strategyJson = strategyJson,
                            ),
                            session(
                                id = "session-fail",
                                profileId = "default",
                                pathMode = "RAW_PATH",
                                summary = "Validated failure",
                                reportJson =
                                    Json.encodeToString(
                                        ScanReport.serializer(),
                                        ScanReport(
                                            sessionId = "session-fail",
                                            profileId = "default",
                                            pathMode = ScanPathMode.RAW_PATH,
                                            startedAt = 30L,
                                            finishedAt = 40L,
                                            summary = "Validated failure",
                                            results =
                                                listOf(
                                                    ProbeResult("dns", "blocked.example", "dns_blocked"),
                                                ),
                                        ),
                                    ),
                            ).copy(
                                approachProfileId = "default",
                                approachProfileName = "Default",
                                strategyId = strategyId,
                                strategyLabel = "VPN Split",
                                strategyJson = strategyJson,
                            ),
                        )
                    usageSessionsState.value =
                        listOf(
                            BypassUsageSessionEntity(
                                id = "usage-1",
                                startedAt = 100L,
                                finishedAt = 1_100L,
                                serviceMode = "VPN",
                                approachProfileId = "default",
                                approachProfileName = "Default",
                                strategyId = strategyId,
                                strategyLabel = "VPN Split",
                                strategyJson = strategyJson,
                                networkType = "wifi",
                                txBytes = 400L,
                                rxBytes = 800L,
                                totalErrors = 2L,
                                routeChanges = 1L,
                                restartCount = 1,
                                endedReason = "stopped",
                            ),
                        )
                }
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            val stats = manager.approachStats.first()
            val strategySummary =
                stats.first { it.approachId.kind == BypassApproachKind.Strategy && it.approachId.value == strategyId }
            val profileSummary =
                stats.first { it.approachId.kind == BypassApproachKind.Profile && it.approachId.value == "default" }

            assertEquals(2, strategySummary.validatedScanCount)
            assertEquals(1, strategySummary.validatedSuccessCount)
            assertEquals(0.5f, strategySummary.validatedSuccessRate)
            assertEquals(1, strategySummary.usageCount)
            assertEquals("validated", strategySummary.verificationState)
            assertTrue(strategySummary.topFailureOutcomes.first().contains("dns_blocked"))
            assertEquals(1, profileSummary.usageCount)
        }

    @Test
    fun `approachStats marks runtime only approach as unverified`() =
        runTest {
            val signature =
                BypassStrategySignature(
                    mode = "Proxy",
                    configSource = "ui",
                    desyncMethod = "fake",
                    chainSummary = "tcp: fake(method+1)",
                    protocolToggles = listOf("HTTP"),
                    tlsRecordSplitEnabled = false,
                    tlsRecordMarker = null,
                    splitMarker = null,
                    fakeSniMode = "custom",
                    fakeOffsetMarker = "method+1",
                    routeGroup = null,
                )
            val strategyId = signature.stableId()
            val strategyJson = Json.encodeToString(BypassStrategySignature.serializer(), signature)
            val history =
                FakeDiagnosticsHistoryRepository().apply {
                    usageSessionsState.value =
                        listOf(
                            BypassUsageSessionEntity(
                                id = "usage-runtime",
                                startedAt = 100L,
                                finishedAt = 200L,
                                serviceMode = "Proxy",
                                approachProfileId = "default",
                                approachProfileName = "Default",
                                strategyId = strategyId,
                                strategyLabel = "Proxy Fake",
                                strategyJson = strategyJson,
                                networkType = "cellular",
                                txBytes = 100L,
                                rxBytes = 120L,
                                totalErrors = 0L,
                                routeChanges = 0L,
                                restartCount = 0,
                                endedReason = "stopped",
                            ),
                        )
                }
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            val summary =
                manager.approachStats.first().first {
                    it.approachId.kind == BypassApproachKind.Strategy && it.approachId.value == strategyId
                }

            assertEquals("unverified", summary.verificationState)
            assertEquals(null, summary.validatedSuccessRate)
            assertEquals(1, summary.usageCount)
        }

    @Test
    fun `bridge start failure marks session failed and destroys bridge`() =
        runTest {
            val history = FakeDiagnosticsHistoryRepository().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.faults.enqueue(
                        FaultSpec(
                            target = DiagnosticsBridgeFaultTarget.START_SCAN,
                            outcome = FaultOutcome.EXCEPTION,
                            message = "bridge start failed",
                        ),
                    )
                }
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            val error = runCatching { manager.startScan(ScanPathMode.RAW_PATH) }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals("failed", history.sessionsState.value.single().status)
            assertEquals("bridge start failed", history.sessionsState.value.single().summary)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
            assertEquals(null, manager.activeScanProgress.value)
        }

    @Test
    fun `progress polling failure marks session failed and clears active progress`() =
        runTest {
            val history = FakeDiagnosticsHistoryRepository().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                    bridge.enqueueProgressFailure(IOException("progress failed"))
                }
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            val sessionId = manager.startScan(ScanPathMode.IN_PATH)
            waitForFailed(history, sessionId)

            assertEquals("progress failed", history.getScanSession(sessionId)?.summary)
            assertEquals(null, manager.activeScanProgress.value)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `report retrieval failure marks session failed after completion signal`() =
        runTest {
            val history = FakeDiagnosticsHistoryRepository().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                    bridge.enqueueProgress(
                        json.encodeToString(
                            ScanProgress.serializer(),
                            ScanProgress(
                                sessionId = "pending",
                                phase = "complete",
                                completedSteps = 1,
                                totalSteps = 1,
                                message = "done",
                                isFinished = true,
                            ),
                        ),
                    )
                    bridge.enqueueReportFailure(IOException("report failed"))
                }
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            val sessionId = manager.startScan(ScanPathMode.IN_PATH)
            waitForFailed(history, sessionId)

            assertEquals("report failed", history.getScanSession(sessionId)?.summary)
            assertTrue(history.getProbeResults(sessionId).isEmpty())
        }

    @Test
    fun `malformed passive events payload marks session failed`() =
        runTest {
            val history = FakeDiagnosticsHistoryRepository().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                    bridge.enqueuePassiveEvents("{bad-json")
                }
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            val sessionId = manager.startScan(ScanPathMode.IN_PATH)
            waitForFailed(history, sessionId)

            assertEquals("failed", history.getScanSession(sessionId)?.status)
            assertTrue((history.getScanSession(sessionId)?.summary ?: "").isNotBlank())
        }

    private suspend fun waitForCompletion(
        history: FakeDiagnosticsHistoryRepository,
        sessionId: String,
    ) {
        kotlinx.coroutines.withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000) {
                while (
                    history.getScanSession(sessionId)?.status != "completed" ||
                    history.snapshotsState.value.size < 2 ||
                    history.nativeEventsState.value.size < 2
                ) {
                    delay(25)
                }
            }
        }
    }

    private suspend fun waitForFailed(
        history: FakeDiagnosticsHistoryRepository,
        sessionId: String,
    ) {
        kotlinx.coroutines.withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000) {
                while (history.getScanSession(sessionId)?.status != "failed") {
                    delay(25)
                }
            }
        }
    }
}

private class FakeAppSettingsRepository(
    initialSettings: AppSettings = defaultAppSettings(),
) : AppSettingsRepository {
    private val state = MutableStateFlow(initialSettings)

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value = state.value.toBuilder().apply(transform).build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private class TestContext(
    private val testCacheDir: File = Files.createTempDirectory("diagnostics-manager-test").toFile(),
) : ContextWrapper(null) {
    override fun getCacheDir(): File = testCacheDir
}

private class FakeDiagnosticsHistoryRepository : DiagnosticsHistoryRepository {
    val profilesState = MutableStateFlow<List<DiagnosticProfileEntity>>(emptyList())
    val sessionsState = MutableStateFlow<List<ScanSessionEntity>>(emptyList())
    val snapshotsState = MutableStateFlow<List<NetworkSnapshotEntity>>(emptyList())
    val contextsState = MutableStateFlow<List<DiagnosticContextEntity>>(emptyList())
    val telemetryState = MutableStateFlow<List<TelemetrySampleEntity>>(emptyList())
    val nativeEventsState = MutableStateFlow<List<NativeSessionEventEntity>>(emptyList())
    val exportsState = MutableStateFlow<List<ExportRecordEntity>>(emptyList())
    val usageSessionsState = MutableStateFlow<List<BypassUsageSessionEntity>>(emptyList())
    private val packVersions = mutableMapOf<String, TargetPackVersionEntity>()
    private val probeResults = mutableMapOf<String, List<ProbeResultEntity>>()

    override fun observeProfiles(): Flow<List<DiagnosticProfileEntity>> = profilesState

    override fun observeRecentScanSessions(limit: Int): Flow<List<ScanSessionEntity>> = sessionsState

    override fun observeSnapshots(limit: Int): Flow<List<NetworkSnapshotEntity>> = snapshotsState

    override fun observeContexts(limit: Int): Flow<List<DiagnosticContextEntity>> = contextsState

    override fun observeTelemetry(limit: Int): Flow<List<TelemetrySampleEntity>> = telemetryState

    override fun observeNativeEvents(limit: Int): Flow<List<NativeSessionEventEntity>> = nativeEventsState

    override fun observeExportRecords(limit: Int): Flow<List<ExportRecordEntity>> = exportsState

    override fun observeBypassUsageSessions(limit: Int): Flow<List<BypassUsageSessionEntity>> = usageSessionsState

    override suspend fun getProfile(id: String): DiagnosticProfileEntity? = profilesState.value.find { it.id == id }

    override suspend fun getPackVersion(packId: String): TargetPackVersionEntity? = packVersions[packId]

    override suspend fun getScanSession(sessionId: String): ScanSessionEntity? =
        sessionsState.value.find { it.id == sessionId }

    override suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity> = probeResults[sessionId].orEmpty()

    override suspend fun upsertProfile(profile: DiagnosticProfileEntity) {
        profilesState.value = profilesState.value.upsertById(profile) { it.id }
    }

    override suspend fun upsertPackVersion(version: TargetPackVersionEntity) {
        packVersions[version.packId] = version
    }

    override suspend fun upsertScanSession(session: ScanSessionEntity) {
        sessionsState.value = sessionsState.value.upsertById(session) { it.id }
    }

    override suspend fun replaceProbeResults(
        sessionId: String,
        results: List<ProbeResultEntity>,
    ) {
        probeResults[sessionId] = results
    }

    override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) {
        snapshotsState.value = snapshotsState.value + snapshot
    }

    override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) {
        contextsState.value = contextsState.value + snapshot
    }

    override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) {
        telemetryState.value = telemetryState.value + sample
    }

    override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) {
        nativeEventsState.value = nativeEventsState.value + event
    }

    override suspend fun insertExportRecord(record: ExportRecordEntity) {
        exportsState.value = exportsState.value + record
    }

    override suspend fun upsertBypassUsageSession(session: BypassUsageSessionEntity) {
        usageSessionsState.value = usageSessionsState.value.upsertById(session) { it.id }
    }

    override suspend fun trimOldData(retentionDays: Int) = Unit

    fun seedDefaultProfile(json: Json) {
        profilesState.value =
            listOf(
                DiagnosticProfileEntity(
                    id = "default",
                    name = "Default",
                    source = "bundled",
                    version = 1,
                    requestJson =
                        json.encodeToString(
                            ScanRequest.serializer(),
                            ScanRequest(
                                profileId = "default",
                                displayName = "Default",
                                pathMode = ScanPathMode.RAW_PATH,
                                domainTargets = listOf(DomainTarget(host = "example.org")),
                                dnsTargets = listOf(DnsTarget(domain = "blocked.example")),
                            ),
                        ),
                    updatedAt = 1L,
                ),
            )
    }
}

private class FakeNetworkMetadataProvider : NetworkMetadataProvider {
    override suspend fun captureSnapshot(): NetworkSnapshotModel =
        NetworkSnapshotModel(
            transport = "wifi",
            capabilities = listOf("validated"),
            dnsServers = listOf("1.1.1.1"),
            privateDnsMode = "system",
            mtu = 1500,
            localAddresses = listOf("192.0.2.10"),
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
                    gateway = "192.0.2.1",
                    dhcpServer = "192.0.2.2",
                    ipAddress = "192.0.2.10",
                    subnetMask = "255.255.255.0",
                    leaseDurationSeconds = 3600,
                ),
            capturedAt = 123L,
        )
}

private class FakeDiagnosticsContextProvider : DiagnosticsContextProvider {
    override suspend fun captureContext(): DiagnosticContextModel = captureContextForTest()

    fun captureContextForTest(): DiagnosticContextModel =
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
                    sessionUptimeMs = 15_000L,
                    lastNativeErrorHeadline = "none",
                    restartCount = 2,
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
        )
}

private class FakeNetworkDiagnosticsBridgeFactory(
    private val json: Json,
) : NetworkDiagnosticsBridgeFactory {
    val bridge = FakeNetworkDiagnosticsBridge(json)

    override fun create(): NetworkDiagnosticsBridge = bridge
}

private enum class DiagnosticsBridgeFaultTarget {
    START_SCAN,
    CANCEL,
    POLL_PROGRESS,
    TAKE_REPORT,
    PASSIVE_EVENTS,
    DESTROY,
}

private sealed interface DiagnosticsBridgeStep {
    data class Payload(
        val value: String?,
    ) : DiagnosticsBridgeStep

    data class Failure(
        val error: Throwable,
    ) : DiagnosticsBridgeStep
}

private class FakeNetworkDiagnosticsBridge(
    private val json: Json,
) : NetworkDiagnosticsBridge {
    var startedRequestJson: String? = null
    private var startedSessionId: String? = null
    var autoCompleteOnStart: Boolean = true
    var destroyCount: Int = 0
    val faults = FaultQueue<DiagnosticsBridgeFaultTarget>()
    private val passiveEventsPayloads = ArrayDeque<String>()
    private val scriptedProgress = ArrayDeque<DiagnosticsBridgeStep>()
    private val scriptedReports = ArrayDeque<DiagnosticsBridgeStep>()
    private val scriptedPassiveEvents = ArrayDeque<DiagnosticsBridgeStep>()
    private var reportJson: String? = null
    private var progressJson: String? = null

    override suspend fun startScan(
        requestJson: String,
        sessionId: String,
    ) {
        faults.next(DiagnosticsBridgeFaultTarget.START_SCAN)?.throwOrIgnore()
        startedRequestJson = requestJson
        startedSessionId = sessionId
        if (autoCompleteOnStart) {
            progressJson =
                json.encodeToString(
                    ScanProgress.serializer(),
                    ScanProgress(
                        sessionId = sessionId,
                        phase = "complete",
                        completedSteps = 1,
                        totalSteps = 1,
                        message = "done",
                        isFinished = true,
                    ),
                )
            reportJson =
                json.encodeToString(
                    ScanReport.serializer(),
                    ScanReport(
                        sessionId = sessionId,
                        profileId = "default",
                        pathMode =
                            json.decodeFromString(ScanRequest.serializer(), requestJson).pathMode,
                        startedAt = 10L,
                        finishedAt = 20L,
                        summary = "Finished",
                        results =
                            listOf(
                                ProbeResult(
                                    probeType = "dns",
                                    target = "blocked.example",
                                    outcome = "substituted",
                                ),
                            ),
                    ),
                )
            passiveEventsPayloads.clear()
            passiveEventsPayloads.addLast(
                json.encodeToString(
                    listOf(
                        NativeSessionEvent(
                            source = "native",
                            level = "info",
                            message = "scan started",
                            createdAt = 15L,
                        ),
                    ),
                ),
            )
            passiveEventsPayloads.addLast("[]")
        }
    }

    override suspend fun cancelScan() {
        faults.next(DiagnosticsBridgeFaultTarget.CANCEL)?.throwOrIgnore()
    }

    override suspend fun pollProgressJson(): String? {
        faults.next(DiagnosticsBridgeFaultTarget.POLL_PROGRESS)?.throwOrIgnore()
        return scriptedProgress.removeFirstOrNull().resolve(progressJson)
    }

    override suspend fun takeReportJson(): String? {
        faults.next(DiagnosticsBridgeFaultTarget.TAKE_REPORT)?.throwOrIgnore()
        return scriptedReports.removeFirstOrNull().resolve(reportJson).also {
            if (scriptedReports.isEmpty()) {
                reportJson = null
            }
        }
    }

    override suspend fun pollPassiveEventsJson(): String? {
        faults.next(DiagnosticsBridgeFaultTarget.PASSIVE_EVENTS)?.throwOrIgnore()
        val scripted = scriptedPassiveEvents.removeFirstOrNull()
        val defaultValue = if (scripted == null) passiveEventsPayloads.removeFirstOrNull() else passiveEventsPayloads.firstOrNull()
        return scripted.resolve(defaultValue)
    }

    override suspend fun destroy() {
        faults.next(DiagnosticsBridgeFaultTarget.DESTROY)?.throwOrIgnore()
        destroyCount += 1
        startedSessionId = null
    }

    fun enqueueProgress(value: String?) {
        scriptedProgress.addLast(DiagnosticsBridgeStep.Payload(value))
    }

    fun enqueueProgressFailure(error: Throwable) {
        scriptedProgress.addLast(DiagnosticsBridgeStep.Failure(error))
    }

    fun enqueueReport(value: String?) {
        scriptedReports.addLast(DiagnosticsBridgeStep.Payload(value))
    }

    fun enqueueReportFailure(error: Throwable) {
        scriptedReports.addLast(DiagnosticsBridgeStep.Failure(error))
    }

    fun enqueuePassiveEvents(value: String?) {
        scriptedPassiveEvents.addLast(DiagnosticsBridgeStep.Payload(value))
    }

    fun enqueuePassiveEventsFailure(error: Throwable) {
        scriptedPassiveEvents.addLast(DiagnosticsBridgeStep.Failure(error))
    }
}

private class FakeDiagnosticsRuntimeCoordinator : DiagnosticsRuntimeCoordinator {
    val rawScanCount = AtomicInteger(0)

    override suspend fun runRawPathScan(block: suspend () -> Unit) {
        rawScanCount.incrementAndGet()
        block()
    }
}

private fun DiagnosticsBridgeStep?.resolve(defaultValue: String?): String? =
    when (this) {
        null -> defaultValue
        is DiagnosticsBridgeStep.Payload -> value
        is DiagnosticsBridgeStep.Failure -> throw error
    }

private fun <T> FaultSpec<T>.throwOrIgnore() {
    when (outcome) {
        FaultOutcome.MALFORMED_PAYLOAD,
        FaultOutcome.BLANK_PAYLOAD,
        -> Unit

        else -> throw faultThrowable(outcome, message)
    }
}

private class FakeServiceStateStore(
    initialStatus: Pair<AppStatus, Mode> = AppStatus.Halted to Mode.VPN,
) : ServiceStateStore {
    private val statusState = MutableStateFlow(initialStatus)
    private val eventFlow = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = eventFlow.asSharedFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState.asStateFlow()

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        statusState.value = status to mode
    }

    override fun emitFailed(sender: Sender) {
        eventFlow.tryEmit(ServiceEvent.Failed(sender))
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        telemetryState.value = snapshot
    }
}

private fun <T, K> List<T>.upsertById(
    item: T,
    keySelector: (T) -> K,
): List<T> {
    val key = keySelector(item)
    val remaining = filterNot { keySelector(it) == key }
    return remaining + item
}

private fun defaultAppSettings(): AppSettings =
    AppSettings
        .newBuilder()
        .setProxyIp("127.0.0.1")
        .setProxyPort(1080)
        .setDiagnosticsMonitorEnabled(true)
        .setDiagnosticsSampleIntervalSeconds(15)
        .setDiagnosticsDefaultScanPathMode("raw_path")
        .setDiagnosticsAutoResumeAfterRawScan(true)
        .setDiagnosticsActiveProfileId("default")
        .setDiagnosticsHistoryRetentionDays(14)
        .setDiagnosticsExportIncludeHistory(true)
        .build()

private fun session(
    id: String,
    profileId: String,
    pathMode: String,
    summary: String,
    status: String = "completed",
    reportJson: String? =
        Json.encodeToString(
            ScanReport.serializer(),
            ScanReport(
                sessionId = id,
                profileId = profileId,
                pathMode = ScanPathMode.valueOf(pathMode),
                startedAt = 10L,
                finishedAt = 20L,
                summary = summary,
                results = emptyList(),
            ),
        ),
): ScanSessionEntity =
    ScanSessionEntity(
        id = id,
        profileId = profileId,
        pathMode = pathMode,
        serviceMode = "VPN",
        status = status,
        summary = summary,
        reportJson = reportJson,
        startedAt = 10L,
        finishedAt = if (status == "completed") 20L else null,
    )

private fun snapshot(
    id: String,
    sessionId: String?,
    transport: String = "wifi",
    wifiDetails: WifiNetworkDetails? =
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
            gateway = "192.0.2.1",
            dhcpServer = "192.0.2.2",
            ipAddress = "192.0.2.10",
            subnetMask = "255.255.255.0",
            leaseDurationSeconds = 3600,
        ),
    cellularDetails: CellularNetworkDetails? = null,
): NetworkSnapshotEntity =
    NetworkSnapshotEntity(
        id = id,
        sessionId = sessionId,
        snapshotKind = if (sessionId == null) "passive" else "post_scan",
        payloadJson =
            Json.encodeToString(
                NetworkSnapshotModel.serializer(),
                NetworkSnapshotModel(
                    transport = transport,
                    capabilities = listOf("validated"),
                    dnsServers = listOf("1.1.1.1"),
                    privateDnsMode = "system",
                    mtu = 1500,
                    localAddresses = listOf("192.0.2.10"),
                    publicIp = "198.51.100.8",
                    publicAsn = "AS64500",
                    captivePortalDetected = false,
                    networkValidated = true,
                    wifiDetails = if (transport == "wifi") wifiDetails else null,
                    cellularDetails = if (transport == "cellular") cellularDetails else null,
                    capturedAt = 123L,
                ),
        ),
        capturedAt = 123L,
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
            Json.encodeToString(
                DiagnosticContextModel.serializer(),
                FakeDiagnosticsContextProvider().captureContextForTest(),
            ),
        capturedAt = 124L,
    )

private fun sample(
    id: String,
    publicIp: String,
): TelemetrySampleEntity =
    TelemetrySampleEntity(
        id = id,
        sessionId = null,
        activeMode = "VPN",
        connectionState = "Running",
        networkType = "wifi",
        publicIp = publicIp,
        txPackets = 12,
        txBytes = 2048,
        rxPackets = 18,
        rxBytes = 4096,
        createdAt = 321L,
    )

private fun event(
    id: String,
    sessionId: String?,
    source: String,
    level: String,
): NativeSessionEventEntity =
    NativeSessionEventEntity(
        id = id,
        sessionId = sessionId,
        source = source,
        level = level,
        message = "$source-$level",
        createdAt = 333L,
    )

private fun unzipEntries(absolutePath: String): Map<String, String> =
    ZipFile(absolutePath).use { zip ->
        buildMap {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                put(entry.name, zip.getInputStream(entry).bufferedReader().use { it.readText() })
            }
        }
    }

private fun scrubDiagnosticsJson(value: JsonElement): JsonElement =
    when (value) {
        is JsonArray -> JsonArray(value.map(::scrubDiagnosticsJson))
        is JsonObject ->
            JsonObject(
                value.mapValues { (key, element) ->
                    when (key) {
                        "id" -> Json.parseToJsonElement("\"<id>\"")
                        "createdAt",
                        "capturedAt",
                        "startedAt",
                        "finishedAt",
                        -> Json.parseToJsonElement("0")

                        "fileName" -> Json.parseToJsonElement("\"ripdpi-diagnostics-<timestamp>.zip\"")
                        else -> scrubDiagnosticsJson(element)
                    }
                },
            )

        else -> value
    }
