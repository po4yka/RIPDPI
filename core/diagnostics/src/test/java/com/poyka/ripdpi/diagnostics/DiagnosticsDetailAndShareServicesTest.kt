@file:Suppress("LongMethod", "MaxLineLength")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            assertEquals("Path", summary.compactMetrics.first().label)
            assertSame(expectedArchive, archive)
            assertEquals(session.id, archiveExporter.requestedSessionId)
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
