package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID
import java.util.zip.ZipFile

class DiagnosticsArchiveExporterTest {
    private val json = diagnosticsTestJson()

    private val compositeRunService =
        object : DiagnosticsHomeCompositeRunService {
            private val completedRuns = mutableMapOf<String, DiagnosticsHomeCompositeOutcome>()

            override suspend fun startHomeAnalysis(options: DiagnosticsHomeRunOptions): DiagnosticsHomeCompositeRunStarted = error("unused")

            override suspend fun startQuickAnalysis(options: DiagnosticsHomeRunOptions): DiagnosticsHomeCompositeRunStarted = error("unused")

            override fun observeHomeRun(runId: String) = error("unused")

            override suspend fun finalizeHomeRun(runId: String): DiagnosticsHomeCompositeOutcome =
                requireNotNull(completedRuns[runId]) { "Missing completed run $runId" }

            override suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome? = completedRuns[runId]

            override suspend fun lookupCachedOutcome(fingerprintHash: String): CachedProbeOutcome? = null

            override suspend fun evictCachedOutcome(fingerprintHash: String) = Unit

            fun putCompletedRun(outcome: DiagnosticsHomeCompositeOutcome) {
                completedRuns[outcome.runId] = outcome
            }
        }

    @Test
    fun `createArchive persists requested session export and writes schema v3 archive`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-selected",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Selected session",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            val exporter = createArchiveExporter(stores)

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 24L,
                    ),
                )

            assertEquals(session.id, archive.sessionId)
            assertEquals(3, archive.schemaVersion)
            assertEquals(1, stores.exportsState.value.size)
            assertEquals(
                session.id,
                stores.exportsState.value
                    .single()
                    .sessionId,
            )

            ZipFile(archive.absolutePath).use { zip ->
                assertSingleSessionArchiveContents(zip, session.id)
            }
        }

    private suspend fun seedSingleSessionStore(
        stores: FakeDiagnosticsHistoryStores,
        session: com.poyka.ripdpi.data.diagnostics.ScanSessionEntity,
    ) {
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
                    capturedAt = 21L,
                ),
            )
        stores.contextsState.value =
            listOf(
                DiagnosticContextEntity(
                    id = "ctx-1",
                    sessionId = session.id,
                    contextKind = "post_scan",
                    payloadJson =
                        json.encodeToString(
                            DiagnosticContextModel.serializer(),
                            FakeDiagnosticsContextProvider().captureContextForTest(),
                        ),
                    capturedAt = 22L,
                ),
            )
        stores.nativeEventsState.value =
            listOf(
                NativeSessionEventEntity(
                    id = "event-1",
                    sessionId = session.id,
                    source = "proxy",
                    level = "warn",
                    message = "fallback",
                    createdAt = 23L,
                ),
            )
    }

    private fun assertSingleSessionArchiveContents(
        zip: java.util.zip.ZipFile,
        sessionId: String,
    ) {
        val manifest =
            json.decodeFromString(
                DiagnosticsArchiveManifest.serializer(),
                zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText(),
            )
        val provenance =
            json.decodeFromString(
                DiagnosticsArchiveProvenancePayload.serializer(),
                zip.getInputStream(zip.getEntry("archive-provenance.json")).bufferedReader().readText(),
            )
        assertEquals(DiagnosticsArchiveReason.SHARE_ARCHIVE, manifest.archiveReason)
        assertEquals(sessionId, manifest.requestedSessionId)
        assertEquals(sessionId, manifest.selectedSessionId)
        assertEquals(
            DiagnosticsArchiveSessionSelectionStatus.REQUESTED_SESSION,
            provenance.sessionSelectionStatus,
        )
        assertEquals(DiagnosticsArchiveFormat.includedFiles(logcatIncluded = false), manifest.includedFiles)
        assertNull(zip.getEntry("logcat.txt"))
    }

    @Test
    fun `createArchive records support bundle reason and fallback selection in provenance`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val latestCompleted =
                diagnosticsSession(
                    id = "session-latest",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Latest session",
                ).copy(serviceMode = "vpn")
            stores.sessionsState.value = listOf(latestCompleted)
            val exporter = createArchiveExporter(stores)

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = null,
                        reason = DiagnosticsArchiveReason.SHARE_DEBUG_BUNDLE,
                        requestedAt = 25L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val provenance =
                    json.decodeFromString(
                        DiagnosticsArchiveProvenancePayload.serializer(),
                        zip.getInputStream(zip.getEntry("archive-provenance.json")).bufferedReader().readText(),
                    )

                assertEquals(DiagnosticsArchiveReason.SHARE_DEBUG_BUNDLE, provenance.archiveReason)
                assertEquals(latestCompleted.id, provenance.selectedSessionId)
                assertEquals(
                    DiagnosticsArchiveSessionSelectionStatus.SUPPORT_BUNDLE,
                    provenance.sessionSelectionStatus,
                )
            }
        }

    @Test
    fun `createArchive rejects missing requested session and does not persist export record`() =
        runTest {
            val stores =
                FakeDiagnosticsHistoryStores().apply {
                    sessionsState.value =
                        listOf(
                            diagnosticsSession(
                                id = "session-available",
                                profileId = "default",
                                pathMode = ScanPathMode.IN_PATH.name,
                                summary = "Available session",
                            ).copy(serviceMode = "vpn"),
                        )
                }
            val exporter = createArchiveExporter(stores)

            try {
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = "missing-session",
                        reason = DiagnosticsArchiveReason.SAVE_ARCHIVE,
                        requestedAt = 26L,
                    ),
                )
                fail("Expected createArchive to reject a missing requested session")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("missing-session"))
            }

            assertTrue(stores.exportsState.value.isEmpty())
        }

    @Test
    fun `createArchive writes composite home analysis bundle with staged files`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val outcome = buildSampleCompositeOutcome()
            compositeRunService.putCompletedRun(outcome)
            val exporter = createArchiveExporter(stores)

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 27L,
                    ),
                )

            assertEquals("audit-session", archive.sessionId)
            assertEquals(3, archive.schemaVersion)
            ZipFile(archive.absolutePath).use { zip ->
                assertCompositeArchiveContents(zip, outcome)
            }
        }

    private suspend fun seedCompositeSessionStores(stores: FakeDiagnosticsHistoryStores) {
        val auditSession =
            diagnosticsSession(
                id = "audit-session",
                profileId = "automatic-audit",
                pathMode = ScanPathMode.RAW_PATH.name,
                summary = "Audit complete",
            ).copy(serviceMode = "vpn")
        val defaultSession =
            diagnosticsSession(
                id = "default-session",
                profileId = "default",
                pathMode = ScanPathMode.RAW_PATH.name,
                summary = "Default diagnostics complete",
            ).copy(serviceMode = "vpn")
        val dpiSession =
            diagnosticsSession(
                id = "dpi-session",
                profileId = "ru-dpi-full",
                pathMode = ScanPathMode.RAW_PATH.name,
                summary = "DPI full diagnostics complete",
            ).copy(serviceMode = "vpn")
        stores.sessionsState.value = listOf(auditSession, defaultSession, dpiSession)
        stores.replaceProbeResults("audit-session", listOf(probeResultEntity("audit-session", "blocked.example")))
        stores.replaceProbeResults("default-session", listOf(probeResultEntity("default-session", "default.example")))
        stores.replaceProbeResults("dpi-session", listOf(probeResultEntity("dpi-session", "dpi.example")))
    }

    private fun buildSampleCompositeOutcome(): DiagnosticsHomeCompositeOutcome =
        DiagnosticsHomeCompositeOutcome(
            runId = "home-run-1",
            fingerprintHash = "fp-home",
            actionable = true,
            headline = "Analysis complete and settings applied",
            summary = "Composite diagnostics finished.",
            recommendationSummary = "TCP split + QUIC fake",
            confidenceSummary = "Confidence high",
            coverageSummary = "Coverage 92%",
            appliedSettings = listOf(DiagnosticsAppliedSetting("TCP/TLS lane", "Split")),
            recommendedSessionId = "audit-session",
            stageSummaries =
                listOf(
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "automatic_audit",
                        stageLabel = "Automatic audit",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH,
                        sessionId = "audit-session",
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                        headline = "Audit complete",
                        summary = "Found a reusable recommendation.",
                        recommendationContributor = true,
                    ),
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "default_connectivity",
                        stageLabel = "Default diagnostics",
                        profileId = "default",
                        pathMode = ScanPathMode.RAW_PATH,
                        sessionId = "default-session",
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                        headline = "Default diagnostics complete",
                        summary = "General connectivity checks passed.",
                    ),
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "dpi_full",
                        stageLabel = "DPI detector full",
                        profileId = "ru-dpi-full",
                        pathMode = ScanPathMode.RAW_PATH,
                        sessionId = "dpi-session",
                        status = DiagnosticsHomeCompositeStageStatus.FAILED,
                        headline = "DPI full partial failure",
                        summary = "Some extended checks were unavailable.",
                    ),
                ),
            completedStageCount = 2,
            failedStageCount = 1,
            skippedStageCount = 0,
            bundleSessionIds = listOf("audit-session", "default-session", "dpi-session"),
        )

    private fun assertCompositeArchiveContents(
        zip: java.util.zip.ZipFile,
        outcome: DiagnosticsHomeCompositeOutcome,
    ) {
        val manifest =
            json.decodeFromString(
                DiagnosticsArchiveManifest.serializer(),
                zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText(),
            )
        assertEquals(DiagnosticsArchiveRunType.HOME_COMPOSITE, manifest.runType)
        assertEquals(outcome.runId, manifest.homeRunId)
        assertEquals(outcome.recommendedSessionId, manifest.recommendedSessionId)
        assertNotNull(zip.getEntry("home-analysis.json"))
        assertNotNull(zip.getEntry("stage-index.json"))
        assertNotNull(zip.getEntry("stage-summaries.json"))
        assertNotNull(zip.getEntry("stages/automatic_audit/report.json"))
        assertNotNull(zip.getEntry("stages/default_connectivity/report.json"))
        assertNotNull(zip.getEntry("stages/dpi_full/report.json"))
        GoldenContractSupport.assertJsonGolden(
            "archive/manifest_home_composite_v3.json",
            zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/home_analysis_composite_v3.json",
            zip.getInputStream(zip.getEntry("home-analysis.json")).bufferedReader().readText(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/stage_index_composite_v3.json",
            zip.getInputStream(zip.getEntry("stage-index.json")).bufferedReader().readText(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/stage_summaries_composite_v3.json",
            zip.getInputStream(zip.getEntry("stage-summaries.json")).bufferedReader().readText(),
        )
    }

    private fun createArchiveExporter(stores: FakeDiagnosticsHistoryStores): DefaultDiagnosticsArchiveExporter {
        val context = TestContext()
        return DefaultDiagnosticsArchiveExporter(
            artifactWriteStore = stores,
            sourceLoader =
                DiagnosticsArchiveSourceLoader(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    scanRecordStore = stores,
                    artifactReadStore = stores,
                    bypassUsageHistoryStore = stores,
                    logcatSnapshotCollector = FakeLogcatSnapshotCollector(snapshot = null),
                    fileLogWriter =
                        FileLogWriter(
                            java.nio.file.Files
                                .createTempDirectory("file-log-test")
                                .toFile(),
                        ),
                    buildInfoProvider = testBuildInfoProvider(),
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    json = json,
                ),
            sessionSelector = DiagnosticsArchiveSessionSelector(DiagnosticsArchiveRedactor(json), json),
            renderer =
                DiagnosticsArchiveRenderer(
                    DiagnosticsArchiveRedactor(json),
                    DiagnosticsSummaryProjector(),
                    json,
                ),
            fileStore =
                DiagnosticsArchiveFileStore(
                    cacheDir = context.cacheDir,
                    clock = DiagnosticsArchiveClock { 1_700_000_000_000L },
                ),
            zipWriter = DiagnosticsArchiveZipWriter(),
            idGenerator = DiagnosticsArchiveIdGenerator { "export-1" },
        )
    }

    private fun testBuildInfoProvider(): DiagnosticsArchiveBuildInfoProvider =
        object : DiagnosticsArchiveBuildInfoProvider {
            override fun buildProvenance(): DiagnosticsArchiveBuildProvenance =
                DiagnosticsArchiveBuildProvenance(
                    applicationId = "com.poyka.ripdpi",
                    appVersionName = "0.0.2-test",
                    appVersionCode = 2L,
                    buildType = "debug",
                    gitCommit = "test-commit",
                    nativeLibraries =
                        listOf(
                            DiagnosticsArchiveNativeLibraryProvenance(
                                name = "libripdpi.so",
                                version = "test-native",
                            ),
                            DiagnosticsArchiveNativeLibraryProvenance(
                                name = "libripdpi-tunnel.so",
                                version = "test-native",
                            ),
                        ),
                )
        }

    private fun probeResultEntity(
        sessionId: String,
        target: String,
    ): ProbeResultEntity =
        ProbeResultEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            probeType = "https",
            target = target,
            outcome = "ok",
            detailJson = "[]",
            createdAt = 30L,
        )
}
