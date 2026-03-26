package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DiagnosticsDetailAndShareServicesTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `detail loader loads session detail and approach detail from repository slices`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-1",
                    profileId = "default",
                    pathMode = ScanPathMode.RAW_PATH.name,
                    summary = "finished",
                ).copy(
                    approachProfileId = "profile-fast",
                    approachProfileName = "Profile Fast",
                    strategyId = "strategy-fast",
                    strategyLabel = "Strategy Fast",
                )
            stores.sessionsState.value = listOf(session)
            stores.replaceProbeResults(
                sessionId = session.id,
                results =
                    listOf(
                        ProbeResultEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = session.id,
                            probeType = "dns",
                            target = "blocked.example",
                            outcome = "dns_blocked",
                            detailJson = "[]",
                            createdAt = 20L,
                        ),
                    ),
            )
            stores.contextsState.value =
                listOf(
                    DiagnosticContextEntity(
                        id = "ctx-1",
                        sessionId = session.id,
                        contextKind = "post_scan",
                        payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), captureContextForTest()),
                        capturedAt = 21L,
                    ),
                )
            stores.nativeEventsState.value =
                listOf(
                    NativeSessionEventEntity(
                        id = "ev-1",
                        sessionId = session.id,
                        source = "native",
                        level = "warn",
                        message = "fallback",
                        createdAt = 22L,
                    ),
                )
            stores.usageSessionsState.value =
                listOf(
                    diagnosticsUsageSession(
                        id = "usage-1",
                        approachProfileId = "profile-fast",
                        approachProfileName = "Profile Fast",
                        strategyId = "strategy-fast",
                        strategyLabel = "Strategy Fast",
                    ),
                )

            val loader = DefaultDiagnosticsDetailLoader(stores, stores, stores, json)
            val detail = loader.loadSessionDetail(session.id)
            val approach = loader.loadApproachDetail(BypassApproachKind.Strategy, "strategy-fast")

            assertEquals(session.id, detail.session.id)
            assertEquals(1, detail.results.size)
            assertEquals("ctx-1", detail.context?.id)
            assertEquals("ev-1", detail.events.single().id)
            assertEquals("strategy-fast", approach.summary.approachId.value)
            assertEquals(1, approach.recentValidatedSessions.size)
            assertEquals(1, approach.recentUsageSessions.size)
        }

    @Test
    fun `share service builds summary and delegates archive creation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-1",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Blocked DNS",
                ).copy(
                    reportJson =
                        json.encodeToString(
                            ScanReport.serializer(),
                            ScanReport(
                                sessionId = "session-1",
                                profileId = "default",
                                pathMode = ScanPathMode.IN_PATH,
                                startedAt = 10L,
                                finishedAt = 15L,
                                summary = "Blocked DNS",
                                results = emptyList(),
                                diagnoses =
                                    listOf(
                                        Diagnosis(
                                            code = "dns_tampering",
                                            summary = "DNS answers were substituted",
                                            target = "blocked.example",
                                        ),
                                    ),
                                classifierVersion = "ru_ooni_v1",
                                packVersions = mapOf("ru-independent-media" to 1),
                            ),
                        ),
                )
            stores.sessionsState.value = listOf(session)
            stores.replaceProbeResults(
                sessionId = session.id,
                results =
                    listOf(
                        ProbeResultEntity(
                            id = "probe-1",
                            sessionId = session.id,
                            probeType = "dns",
                            target = "blocked.example",
                            outcome = "dns_blocked",
                            detailJson = "[]",
                            createdAt = 20L,
                        ),
                    ),
            )
            stores.snapshotsState.value =
                listOf(
                    NetworkSnapshotEntity(
                        id = "snap-1",
                        sessionId = session.id,
                        snapshotKind = "post_scan",
                        payloadJson =
                            json.encodeToString(
                                NetworkSnapshotModel.serializer(),
                                networkSnapshotModelForTest(),
                            ),
                        capturedAt = 20L,
                    ),
                )
            stores.contextsState.value =
                listOf(
                    DiagnosticContextEntity(
                        id = "ctx-1",
                        sessionId = session.id,
                        contextKind = "post_scan",
                        payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), captureContextForTest()),
                        capturedAt = 21L,
                    ),
                )
            stores.telemetryState.value =
                listOf(
                    TelemetrySampleEntity(
                        id = "telemetry-1",
                        sessionId = session.id,
                        activeMode = "VPN",
                        connectionState = "Running",
                        networkType = "wifi",
                        publicIp = "198.51.100.8",
                        txPackets = 1L,
                        txBytes = 64L,
                        rxPackets = 2L,
                        rxBytes = 128L,
                        createdAt = 22L,
                    ),
                )
            stores.nativeEventsState.value =
                listOf(
                    NativeSessionEventEntity(
                        id = "warn-1",
                        sessionId = session.id,
                        source = "dns",
                        level = "warn",
                        message = "substitution",
                        createdAt = 23L,
                    ),
                )
            val expectedArchive =
                DiagnosticsArchive(
                    fileName = "diagnostics.zip",
                    absolutePath = "/tmp/diagnostics.zip",
                    sessionId = session.id,
                    createdAt = 42L,
                    scope = "hybrid",
                    schemaVersion = 2,
                    privacyMode = "split_output",
                )
            val archiveExporter = RecordingDiagnosticsArchiveExporter(expectedArchive)
            val shareService = DefaultDiagnosticsShareService(stores, stores, archiveExporter, json)

            val summary = shareService.buildShareSummary(session.id)
            val archive = shareService.createArchive(session.id)

            assertTrue(summary.title.startsWith("RIPDPI diagnostics"))
            assertTrue(summary.body.contains("summary=Blocked DNS"))
            assertTrue(summary.body.contains("dns:blocked.example=dns_blocked"))
            assertTrue(summary.body.contains("classifierVersion=ru_ooni_v1"))
            assertTrue(summary.body.contains("Diagnoses:"))
            assertTrue(summary.body.contains("dns_tampering=DNS answers were substituted"))
            assertTrue(summary.body.contains("pack.ru-independent-media=1"))
            assertEquals("Path", summary.compactMetrics.first().label)
            assertSame(expectedArchive, archive)
            assertEquals(session.id, archiveExporter.requestedSessionId)
        }

    @Test
    fun `share summary stays scoped to the requested session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val selectedSession =
                diagnosticsSession(
                    id = "session-1",
                    profileId = "default",
                    pathMode = ScanPathMode.RAW_PATH.name,
                    summary = "Selected session",
                )
            val newerSession =
                diagnosticsSession(
                    id = "session-2",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Newer session",
                )
            stores.sessionsState.value = listOf(newerSession, selectedSession)
            stores.replaceProbeResults(
                sessionId = selectedSession.id,
                results =
                    listOf(
                        ProbeResultEntity(
                            id = "probe-1",
                            sessionId = selectedSession.id,
                            probeType = "dns",
                            target = "blocked.example",
                            outcome = "dns_blocked",
                            detailJson = "[]",
                            createdAt = 20L,
                        ),
                    ),
            )
            stores.snapshotsState.value =
                listOf(
                    NetworkSnapshotEntity(
                        id = "snap-1",
                        sessionId = selectedSession.id,
                        snapshotKind = "post_scan",
                        payloadJson =
                            json.encodeToString(
                                NetworkSnapshotModel.serializer(),
                                networkSnapshotModelForTest(),
                            ),
                        capturedAt = 20L,
                    ),
                )
            stores.contextsState.value =
                listOf(
                    DiagnosticContextEntity(
                        id = "ctx-1",
                        sessionId = selectedSession.id,
                        contextKind = "post_scan",
                        payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), captureContextForTest()),
                        capturedAt = 21L,
                    ),
                )
            stores.telemetryState.value =
                listOf(
                    TelemetrySampleEntity(
                        id = "telemetry-new",
                        sessionId = newerSession.id,
                        activeMode = "VPN",
                        connectionState = "Running",
                        networkType = "cellular",
                        publicIp = "198.51.100.9",
                        txPackets = 9L,
                        txBytes = 999L,
                        rxPackets = 10L,
                        rxBytes = 1111L,
                        createdAt = 30L,
                    ),
                    TelemetrySampleEntity(
                        id = "telemetry-selected",
                        sessionId = selectedSession.id,
                        activeMode = "VPN",
                        connectionState = "Running",
                        networkType = "wifi",
                        publicIp = "198.51.100.8",
                        txPackets = 1L,
                        txBytes = 64L,
                        rxPackets = 2L,
                        rxBytes = 128L,
                        createdAt = 22L,
                    ),
                )
            stores.nativeEventsState.value =
                listOf(
                    NativeSessionEventEntity(
                        id = "warn-new",
                        sessionId = newerSession.id,
                        source = "proxy",
                        level = "warn",
                        message = "newer warning",
                        createdAt = 31L,
                    ),
                    NativeSessionEventEntity(
                        id = "warn-selected",
                        sessionId = selectedSession.id,
                        source = "dns",
                        level = "warn",
                        message = "selected warning",
                        createdAt = 23L,
                    ),
                )
            val shareService =
                DefaultDiagnosticsShareService(
                    scanRecordStore = stores,
                    artifactReadStore = stores,
                    archiveExporter = RecordingDiagnosticsArchiveExporter(unusedArchive(selectedSession.id)),
                    json = json,
                )

            val summary = shareService.buildShareSummary(selectedSession.id)

            assertTrue(summary.body.contains("session=${selectedSession.id}"))
            assertTrue(summary.body.contains("txBytes=64"))
            assertFalse(summary.body.contains("txBytes=999"))
            assertTrue(summary.body.contains("dns: selected warning"))
            assertFalse(summary.body.contains("proxy: newer warning"))
        }

    @Test
    fun `share summary reports requested session as unavailable instead of falling back`() =
        runTest {
            val stores =
                FakeDiagnosticsHistoryStores().apply {
                    sessionsState.value =
                        listOf(
                            diagnosticsSession(
                                id = "session-2",
                                profileId = "default",
                                pathMode = ScanPathMode.IN_PATH.name,
                                summary = "Newest session",
                            ),
                        )
                    telemetryState.value =
                        listOf(
                            TelemetrySampleEntity(
                                id = "telemetry-new",
                                sessionId = "session-2",
                                activeMode = "VPN",
                                connectionState = "Running",
                                networkType = "cellular",
                                publicIp = "198.51.100.9",
                                txPackets = 9L,
                                txBytes = 999L,
                                rxPackets = 10L,
                                rxBytes = 1111L,
                                createdAt = 30L,
                            ),
                        )
                }
            val shareService =
                DefaultDiagnosticsShareService(
                    scanRecordStore = stores,
                    artifactReadStore = stores,
                    archiveExporter = RecordingDiagnosticsArchiveExporter(unusedArchive("session-2")),
                    json = json,
                )

            val summary = shareService.buildShareSummary("missing-session")

            assertEquals("RIPDPI diagnostics missing-", summary.title)
            assertTrue(summary.body.contains("session=missing-session"))
            assertTrue(summary.body.contains("status=session_unavailable"))
            assertFalse(summary.body.contains("session=session-2"))
            assertFalse(summary.body.contains("txBytes=999"))
            assertTrue(summary.compactMetrics.isEmpty())
        }
}

private class RecordingDiagnosticsArchiveExporter(
    private val archive: DiagnosticsArchive,
) : DiagnosticsArchiveExporter {
    var requestedSessionId: String? = null

    override fun cleanupCache() = Unit

    override suspend fun createArchive(sessionId: String?): DiagnosticsArchive {
        requestedSessionId = sessionId
        return archive
    }
}

private fun captureContextForTest(): DiagnosticContextModel = FakeDiagnosticsContextProvider().captureContextForTest()

private fun unusedArchive(sessionId: String?) =
    DiagnosticsArchive(
        fileName = "unused.zip",
        absolutePath = "/tmp/unused.zip",
        sessionId = sessionId,
        createdAt = 0L,
        scope = "hybrid",
        schemaVersion = 2,
        privacyMode = "split_output",
    )

private fun diagnosticsUsageSession(
    id: String,
    approachProfileId: String?,
    approachProfileName: String?,
    strategyId: String,
    strategyLabel: String,
) = com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity(
    id = id,
    startedAt = 10L,
    finishedAt = 20L,
    updatedAt = 20L,
    serviceMode = "VPN",
    approachProfileId = approachProfileId,
    approachProfileName = approachProfileName,
    strategyId = strategyId,
    strategyLabel = strategyLabel,
    strategyJson = "",
    networkType = "wifi",
    txBytes = 100L,
    rxBytes = 200L,
    totalErrors = 0L,
    routeChanges = 1L,
    restartCount = 0,
    endedReason = null,
)
