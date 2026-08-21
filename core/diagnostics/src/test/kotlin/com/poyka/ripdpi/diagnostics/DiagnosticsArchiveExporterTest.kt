package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.StartupJournal
import com.poyka.ripdpi.data.StartupJournalSnapshot
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipFile

internal abstract class DiagnosticsArchiveExporterTestBase {
    protected val json = diagnosticsTestJson()
    protected val compositeRunService = ArchiveCompositeRunService()

    protected fun createArchiveExporter(stores: FakeDiagnosticsHistoryStores): DefaultDiagnosticsArchiveExporter {
        val context = TestContext()
        return createArchiveExporter(stores, context = context, rootModeEnabled = false)
    }

    protected fun createArchiveExporter(
        stores: FakeDiagnosticsHistoryStores,
        developerAnalyticsSource: DeveloperAnalyticsSource,
    ): DefaultDiagnosticsArchiveExporter {
        val context = TestContext()
        return createArchiveExporterForTest(
            stores = stores,
            context = context,
            rootModeEnabled = false,
            compositeRunService = compositeRunService,
            json = json,
            developerAnalyticsSource = developerAnalyticsSource,
        )
    }

    protected fun createArchiveExporter(
        stores: FakeDiagnosticsHistoryStores,
        context: TestContext,
        rootModeEnabled: Boolean,
    ): DefaultDiagnosticsArchiveExporter =
        createArchiveExporterForTest(
            stores = stores,
            context = context,
            rootModeEnabled = rootModeEnabled,
            compositeRunService = compositeRunService,
            json = json,
        )

    protected fun createArchiveExporter(
        stores: FakeDiagnosticsHistoryStores,
        startupJournal: StartupJournal,
    ): DefaultDiagnosticsArchiveExporter {
        val context = TestContext()
        val appSettings = defaultDiagnosticsAppSettings().toBuilder().setRootModeEnabled(false).build()
        return DefaultDiagnosticsArchiveExporter(
            exportRecordStore = stores,
            sourceLoader =
                DiagnosticsArchiveSourceLoader(
                    appSettingsRepository = FakeAppSettingsRepository(appSettings),
                    scanRecordStore = stores,
                    artifactReadStore = stores,
                    artifactQueryStore = stores,
                    archiveEventQueryStore = stores,
                    bypassUsageHistoryStore = stores,
                    logcatSnapshotCollector = FakeLogcatSnapshotCollector(snapshot = null),
                    fileLogWriter =
                        FileLogWriter(
                            java.nio.file.Files
                                .createTempDirectory("file-log-test")
                                .toFile(),
                        ),
                    startupJournal = startupJournal,
                    buildInfoProvider =
                        object : DiagnosticsArchiveBuildInfoProvider {
                            override fun buildProvenance(): DiagnosticsArchiveBuildProvenance =
                                DiagnosticsArchiveBuildProvenance(
                                    applicationId = "com.poyka.ripdpi",
                                    appVersionName = "0.0.2-test",
                                    appVersionCode = 2L,
                                    buildType = "debug",
                                    gitCommit = "test-commit",
                                    nativeLibraries = emptyList(),
                                )
                        },
                    diagnosticsHomeCompositeRunService = compositeRunService,
                    replayResultStore = ReplayResultStore(),
                    json = json,
                ),
            sessionSelector = DiagnosticsArchiveSessionSelector(DiagnosticsArchiveRedactor(json), json),
            renderer =
                DiagnosticsArchiveRenderer(
                    DiagnosticsArchiveRedactor(json),
                    DiagnosticsSummaryProjector(),
                    ReplayArchiveEntryBuilder(
                        ReplayArchiveRedactor(),
                        DiagnosticsArchiveClock { System.currentTimeMillis() },
                        json,
                    ),
                    json,
                ),
            fileStore =
                DiagnosticsArchiveFileStore(
                    cacheDir = context.cacheDir,
                    clock = DiagnosticsArchiveClock { 1_700_000_000_000L },
                ),
            zipWriter = DiagnosticsArchiveZipWriter(),
            idGenerator = DiagnosticsArchiveIdGenerator { "export-startup-journal" },
            developerAnalyticsSource = NoopDeveloperAnalyticsSource,
        )
    }

    protected fun probeResultEntity(
        sessionId: String,
        target: String,
    ): ProbeResultEntity = probeResultEntityForArchiveTest(sessionId, target)

    protected suspend fun seedSingleSessionStore(
        stores: FakeDiagnosticsHistoryStores,
        session: com.poyka.ripdpi.data.diagnostics.ScanSessionEntity,
    ) = seedSingleSessionStoreForArchiveTest(stores, session, json)

    protected suspend fun seedCompositeSessionStores(stores: FakeDiagnosticsHistoryStores) {
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

    protected fun buildSampleCompositeOutcome(): DiagnosticsHomeCompositeOutcome =
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
            connectivityAssessment =
                ConnectivityAssessment(
                    assessmentCode = ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING,
                    assessmentSummary =
                        "Observed pattern: raw-path controls passed while affected targets failed. " +
                            "This is consistent with target-specific direct-path interference; " +
                            "cause is not established.",
                    confidence = "high",
                    rawPathEvidence =
                        ConnectivityEvidence(
                            sessionIds = listOf("default-session", "dpi-session"),
                            controls = listOf("cloudflare.com", "www.google.com"),
                            affectedTargets = listOf("www.youtube.com", "telegram.org"),
                            controlSuccessCount = 2,
                            affectedTargetFailureCount = 2,
                        ),
                    controlOutcome = "raw_controls_passed",
                    affectedTargets = listOf("www.youtube.com", "telegram.org"),
                    recommendedNextAction =
                        "Run a paired raw-path and in-path reproduction and inspect control and " +
                            "affected-target evidence before attributing a cause.",
                ),
        )
}

internal class ArchiveCompositeRunService : DiagnosticsHomeCompositeRunService {
    private val completedRuns = mutableMapOf<String, DiagnosticsHomeCompositeOutcome>()

    override suspend fun startHomeAnalysis(options: DiagnosticsHomeRunOptions): DiagnosticsHomeCompositeRunStarted =
        error("unused")

    override suspend fun startQuickAnalysis(options: DiagnosticsHomeRunOptions): DiagnosticsHomeCompositeRunStarted =
        error("unused")

    override fun observeHomeRun(runId: String) = error("unused")

    override suspend fun cancelHomeRun(runId: String) = error("unused")

    override suspend fun finalizeHomeRun(runId: String): DiagnosticsHomeCompositeOutcome =
        requireNotNull(completedRuns[runId]) { "Missing completed run $runId" }

    override suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome? = completedRuns[runId]

    override suspend fun lookupCachedOutcome(fingerprintHash: String): CachedProbeOutcome? = null

    override suspend fun evictCachedOutcome(fingerprintHash: String) = Unit

    fun putCompletedRun(outcome: DiagnosticsHomeCompositeOutcome) {
        completedRuns[outcome.runId] = outcome
    }
}

internal class DiagnosticsArchiveExporterTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `createArchive inventories nonempty startup journal across zip manifest and completeness`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    "session-startup-journal",
                    "default",
                    ScanPathMode.IN_PATH.name,
                    "Startup journal",
                )
            seedSingleSessionStore(stores, session)
            val startupJournal =
                object : StartupJournal {
                    override fun recordServiceStarted(
                        mode: com.poyka.ripdpi.data.Mode,
                        occurredAtMillis: Long,
                    ) = Unit

                    override fun recordServiceFailed(
                        mode: com.poyka.ripdpi.data.Mode,
                        failureKind: String,
                        occurredAtMillis: Long,
                    ) = Unit

                    override fun snapshot(): StartupJournalSnapshot =
                        StartupJournalSnapshot(
                            content = "1700000000000 service_started mode=vpn\n",
                            byteCount = 39,
                            truncated = false,
                        )
                }

            val archive =
                createArchiveExporter(stores, startupJournal)
                    .createArchive(archiveRequestFor(session.id, 29L))

            ZipFile(archive.absolutePath).use { zip ->
                val manifest =
                    json.decodeFromString(
                        DiagnosticsArchiveManifest.serializer(),
                        zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText(),
                    )
                val completeness =
                    json.decodeFromString(
                        DiagnosticsArchiveCompletenessPayload.serializer(),
                        zip.getInputStream(zip.getEntry("completeness.json")).bufferedReader().readText(),
                    )
                val inventoryLocations =
                    buildSet {
                        if (zip.getEntry("startup-journal.txt") != null) add("zip")
                        if ("startup-journal.txt" in manifest.includedFiles) add("manifest")
                        if ("startup-journal.txt" in completeness.sectionStatuses) add("completeness")
                    }

                assertEquals(setOf("zip", "manifest", "completeness"), inventoryLocations)
            }
        }

    @Test
    fun `writeArchive reports io failure without leaving a cache archive`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    "session-write-failure",
                    "default",
                    ScanPathMode.IN_PATH.name,
                    "Write failure",
                )
            seedSingleSessionStore(stores, session)
            val context = TestContext()

            val failure =
                runCatching {
                    createArchiveExporter(stores, context, rootModeEnabled = false).writeArchive(
                        archiveRequestFor(session.id, 28L),
                        object : OutputStream() {
                            override fun write(value: Int): Unit = throw IOException("destination refused")
                        },
                    )
                }.exceptionOrNull()

            assertTrue(failure is DiagnosticsArchiveException)
            assertEquals(
                DiagnosticsArchiveFailureCode.IO,
                (failure as DiagnosticsArchiveException).failureCode,
            )
            assertTrue(
                context.cacheDir
                    .resolve(DiagnosticsArchiveFormat.directoryName)
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun `createArchive exports an incomplete session with available artifacts`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    "session-incomplete",
                    "default",
                    ScanPathMode.IN_PATH.name,
                    "Partial",
                    status = "failed",
                )
            seedSingleSessionStore(stores, session)

            val archive =
                createArchiveExporter(stores).createArchive(archiveRequestFor(session.id, 29L))

            assertEquals(session.id, archive.sessionId)
            ZipFile(archive.absolutePath).use { zip -> assertNotNull(zip.getEntry("manifest.json")) }
        }

    @Test
    fun `createArchive persists requested session export and writes schema v12 archive`() =
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
            assertEquals(12, archive.schemaVersion)
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

    @Test
    fun `createArchive preserves completed file when export record persistence fails`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-failed-export",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Failed export",
                )
            seedSingleSessionStore(stores, session)
            stores.beforeInsertExportRecord = { error("record write failed") }
            val context = TestContext()
            val exporter = createArchiveExporter(stores, context, rootModeEnabled = false)

            runCatching {
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 24L,
                    ),
                )
            }.onSuccess { fail("Expected export record failure") }

            val archiveDir = context.cacheDir.resolve(DiagnosticsArchiveFormat.directoryName)
            assertEquals(1, archiveDir.listFiles().orEmpty().size)
            assertTrue(stores.exportsState.value.isEmpty())
        }

    @Test
    fun `createArchive creates distinct files when clock timestamps collide`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-same-timestamp",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Same timestamp",
                )
            seedSingleSessionStore(stores, session)
            val exporter = createArchiveExporter(stores)

            val first = exporter.createArchive(archiveRequestFor(session.id, requestedAt = 25L))
            val second = exporter.createArchive(archiveRequestFor(session.id, requestedAt = 26L))

            assertFalse(first.absolutePath == second.absolutePath)
            assertTrue(java.io.File(first.absolutePath).exists())
            assertTrue(java.io.File(second.absolutePath).exists())
            assertEquals(
                2,
                stores.exportsState.value
                    .map { it.uri }
                    .distinct()
                    .size,
            )
        }

    @Test
    fun `createArchive compensates file and record after cancellation post insert`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-cancel-compensation",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Cancellation compensation",
                )
            seedSingleSessionStore(stores, session)
            stores.afterInsertExportRecord = {
                currentCoroutineContext().cancel()
                yield()
            }
            val context = TestContext()
            val exporter = createArchiveExporter(stores, context, rootModeEnabled = false)

            val attempt = backgroundScope.async { exporter.createArchive(archiveRequestFor(session.id, 27L)) }
            val failure = runCatching { attempt.await() }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(stores.exportsState.value.isEmpty())
            val archiveDir = context.cacheDir.resolve(DiagnosticsArchiveFormat.directoryName)
            assertTrue(archiveDir.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `cleanupCache reconciles orphan files temporary files and missing records`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-reconcile",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Reconcile export",
                )
            seedSingleSessionStore(stores, session)
            val context = TestContext()
            val exporter = createArchiveExporter(stores, context, rootModeEnabled = false)
            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 24L,
                    ),
                )
            val archiveDir = context.cacheDir.resolve(DiagnosticsArchiveFormat.directoryName)
            val orphan =
                archiveDir.resolve("${DiagnosticsArchiveFormat.fileNamePrefix}orphan.zip").apply {
                    writeText("orphan")
                }
            val temporary =
                archiveDir.resolve("${DiagnosticsArchiveFormat.fileNamePrefix}.interrupted.tmp").apply {
                    writeText("partial")
                }
            stores.exportsState.value =
                stores.exportsState.value +
                ExportRecordEntity(
                    id = "missing-export",
                    sessionId = session.id,
                    uri = archiveDir.resolve("missing.zip").absolutePath,
                    fileName = "missing.zip",
                    createdAt = 23L,
                )

            exporter.cleanupCache()

            assertTrue(java.io.File(archive.absolutePath).exists())
            assertFalse(orphan.exists())
            assertFalse(temporary.exists())
            assertEquals(listOf("export-1"), stores.exportsState.value.map { it.id })
        }

    @Test
    fun `cleanupCache removes dangling record when cancelled after file cleanup`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val context = TestContext()
            val archiveDir = context.cacheDir.resolve(DiagnosticsArchiveFormat.directoryName).apply { mkdirs() }
            val expiredArchive =
                archiveDir.resolve("${DiagnosticsArchiveFormat.fileNamePrefix}expired.zip").apply {
                    writeText("expired")
                    assertTrue(setLastModified(0L))
                }
            stores.exportsState.value =
                listOf(
                    ExportRecordEntity(
                        id = "expired-record",
                        sessionId = null,
                        uri = expiredArchive.absolutePath,
                        fileName = expiredArchive.name,
                        createdAt = 0L,
                    ),
                )
            val exporter = createArchiveExporter(stores, context, rootModeEnabled = false)
            lateinit var cleanupJob: Job
            stores.beforeGetExportRecords = {
                cleanupJob.cancel()
                yield()
            }

            cleanupJob = backgroundScope.launch { exporter.cleanupCache() }
            cleanupJob.join()

            assertFalse(expiredArchive.exists())
            assertTrue(stores.exportsState.value.isEmpty())
        }

    @Test
    fun `createArchive reserves retention slot and preserves file record parity`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-retention",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Retention export",
                )
            seedSingleSessionStore(stores, session)
            val context = TestContext()
            val archiveDir = context.cacheDir.resolve(DiagnosticsArchiveFormat.directoryName).apply { mkdirs() }
            stores.exportsState.value =
                List(DiagnosticsArchiveFormat.maxArchiveFiles) { index ->
                    val file =
                        archiveDir.resolve("${DiagnosticsArchiveFormat.fileNamePrefix}old-$index.zip").apply {
                            writeText("old-$index")
                            setLastModified(1_700_000_000_000L - index)
                        }
                    ExportRecordEntity(
                        id = "old-$index",
                        sessionId = session.id,
                        uri = file.absolutePath,
                        fileName = file.name,
                        createdAt = 1_700_000_000_000L - index,
                    )
                }
            val exporter = createArchiveExporter(stores, context, rootModeEnabled = false)

            exporter.createArchive(
                DiagnosticsArchiveRequest(
                    requestedSessionId = session.id,
                    reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                    requestedAt = 25L,
                ),
            )

            val files =
                archiveDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.extension == "zip" }
            assertEquals(DiagnosticsArchiveFormat.maxArchiveFiles, files.size)
            assertEquals(DiagnosticsArchiveFormat.maxArchiveFiles, stores.exportsState.value.size)
            assertEquals(
                files.mapTo(mutableSetOf()) { it.absolutePath },
                stores.exportsState.value.mapTo(mutableSetOf()) { it.uri },
            )
        }

    @Test
    fun `requested session artifacts survive the global recent window`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-outside-window",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Selected session",
                )
            seedSingleSessionStore(stores, session)
            val selectedSnapshot = stores.snapshotsState.value.single()
            val selectedContext = stores.contextsState.value.single()
            stores.snapshotsState.value =
                List(DiagnosticsArchiveFormat.snapshotLimit) { index ->
                    selectedSnapshot.copy(
                        id = "newer-snapshot-$index",
                        sessionId = "newer-session",
                        capturedAt =
                            100L + index,
                    )
                } + selectedSnapshot
            stores.contextsState.value =
                List(DiagnosticsArchiveFormat.snapshotLimit) { index ->
                    selectedContext.copy(
                        id = "newer-context-$index",
                        sessionId = "newer-session",
                        capturedAt =
                            100L + index,
                    )
                } + selectedContext

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 24L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val manifest =
                    json.decodeFromString(
                        DiagnosticsArchiveManifest.serializer(),
                        zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText(),
                    )
                assertEquals(1, manifest.sessionSnapshotCount)
                assertEquals(1, manifest.contextSnapshotCount)
            }
        }

    @Test
    fun `completeness preserves primary session source counts before collection limits`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-over-limit",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Selected session exceeds collection limits",
                )
            seedSingleSessionStore(stores, session)
            val snapshot = stores.snapshotsState.value.single()
            val context = stores.contextsState.value.single()
            val sourceCount = DiagnosticsArchiveFormat.snapshotLimit + 1
            stores.snapshotsState.value =
                List(sourceCount) { index ->
                    snapshot.copy(id = "selected-snapshot-$index", capturedAt = index.toLong())
                }
            stores.contextsState.value =
                List(sourceCount) { index ->
                    context.copy(id = "selected-context-$index", capturedAt = index.toLong())
                }

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 24L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val completeness =
                    json.decodeFromString(
                        DiagnosticsArchiveCompletenessPayload.serializer(),
                        zip.getInputStream(zip.getEntry("completeness.json")).bufferedReader().readText(),
                    )
                assertEquals(sourceCount, completeness.sourceCounts.primarySession.snapshots)
                assertEquals(sourceCount, completeness.sourceCounts.primarySession.contexts)
                assertEquals(
                    DiagnosticsArchiveFormat.snapshotLimit,
                    completeness.includedCounts.primarySession.snapshots,
                )
                assertEquals(
                    DiagnosticsArchiveFormat.snapshotLimit,
                    completeness.includedCounts.primarySession.contexts,
                )
            }
        }

    private fun assertSingleSessionArchiveContents(
        zip: java.util.zip.ZipFile,
        sessionId: String,
    ) = assertSingleSessionArchiveContentsForTest(zip, sessionId, json)

    private fun archiveRequestFor(
        sessionId: String,
        requestedAt: Long,
    ) = DiagnosticsArchiveRequest(
        requestedSessionId = sessionId,
        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
        requestedAt = requestedAt,
    )

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

            val failure =
                runCatching {
                    exporter.createArchive(
                        DiagnosticsArchiveRequest(
                            requestedSessionId = "missing-session",
                            reason = DiagnosticsArchiveReason.SAVE_ARCHIVE,
                            requestedAt = 26L,
                        ),
                    )
                }.exceptionOrNull()

            assertTrue(failure is DiagnosticsArchiveException)
            assertEquals(
                DiagnosticsArchiveFailureCode.INCONSISTENT_RESULT,
                (failure as DiagnosticsArchiveException).failureCode,
            )
            assertTrue(failure.cause is IllegalArgumentException)
            assertTrue(
                failure.cause
                    ?.message
                    .orEmpty()
                    .contains("missing-session"),
            )

            assertTrue(stores.exportsState.value.isEmpty())
        }
}

internal class DiagnosticsArchiveCompositeExporterTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `createArchive rejects unavailable requested home run without falling back`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val fallback =
                diagnosticsSession(
                    id = "fallback-session",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Must not be exported",
                )
            seedSingleSessionStore(stores, fallback)
            val context = TestContext()
            val exporter = createArchiveExporter(stores, context, rootModeEnabled = false)

            val failure =
                runCatching {
                    exporter.createArchive(
                        DiagnosticsArchiveRequest(
                            sessionIds = listOf(fallback.id),
                            homeRunId = "missing-home-run",
                            reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                            requestedAt = 24L,
                        ),
                    )
                }.exceptionOrNull()

            assertTrue(failure is DiagnosticsArchiveException)
            assertEquals(
                DiagnosticsArchiveFailureCode.INCONSISTENT_RESULT,
                (failure as DiagnosticsArchiveException).failureCode,
            )
            assertTrue(failure.cause is IllegalArgumentException)
            assertTrue(
                failure.cause
                    ?.message
                    .orEmpty()
                    .contains("missing-home-run"),
            )
            assertTrue(stores.exportsState.value.isEmpty())
            assertTrue(
                context.cacheDir
                    .resolve(DiagnosticsArchiveFormat.directoryName)
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
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
            assertEquals(12, archive.schemaVersion)
            ZipFile(archive.absolutePath).use { zip ->
                assertCompositeArchiveContents(zip, outcome)
            }
        }

    @Test
    fun `createArchive rejects caller session contamination for home analysis`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val outcome = buildSampleCompositeOutcome()
            compositeRunService.putCompletedRun(outcome)
            val exporter = createArchiveExporter(stores)

            val failure =
                runCatching {
                    exporter.createArchive(
                        DiagnosticsArchiveRequest(
                            sessionIds = outcome.bundleSessionIds + "caller-session",
                            homeRunId = outcome.runId,
                            reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                            requestedAt = 28L,
                        ),
                    )
                }.exceptionOrNull()

            assertTrue(failure is DiagnosticsArchiveException)
            assertEquals(
                DiagnosticsArchiveFailureCode.INCONSISTENT_RESULT,
                (failure as DiagnosticsArchiveException).failureCode,
            )
            assertTrue(failure.cause is IllegalArgumentException)
            assertTrue(
                failure.cause
                    ?.message
                    .orEmpty()
                    .contains("caller-session"),
            )
            assertTrue(stores.exportsState.value.isEmpty())
        }

    @Test
    fun `createArchive rejects unsafe or duplicate home stage keys`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val exporter = createArchiveExporter(stores)
            val base = buildSampleCompositeOutcome()
            val outcomes =
                listOf(
                    base.copy(
                        runId = "unsafe-stage-run",
                        stageSummaries =
                            base.stageSummaries.mapIndexed { index, stage ->
                                if (index == 0) stage.copy(stageKey = "../escape") else stage
                            },
                    ),
                    base.copy(
                        runId = "duplicate-stage-run",
                        stageSummaries = base.stageSummaries.map { it.copy(stageKey = "duplicate") },
                    ),
                )

            outcomes.forEach { outcome ->
                compositeRunService.putCompletedRun(outcome)
                val failure =
                    runCatching {
                        exporter.createArchive(
                            DiagnosticsArchiveRequest(
                                sessionIds = outcome.bundleSessionIds,
                                homeRunId = outcome.runId,
                                reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                                requestedAt = 28L,
                            ),
                        )
                    }.exceptionOrNull()
                assertNotNull(outcome.runId, failure)
            }
            assertTrue(stores.exportsState.value.isEmpty())
        }

    @Test
    fun `createArchive uses first completed stage for non actionable home analysis`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val outcome =
                buildSampleCompositeOutcome().copy(
                    runId = "home-no-recommendation",
                    actionable = false,
                    recommendedSessionId = null,
                    stageSummaries =
                        buildSampleCompositeOutcome().stageSummaries.map {
                            it.copy(recommendationContributor = false)
                        },
                )
            compositeRunService.putCompletedRun(outcome)

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 29L,
                    ),
                )

            assertEquals("audit-session", archive.sessionId)
        }

    @Test
    fun `createArchive skips purged completed stage when choosing home primary`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            stores.sessionsState.value = stores.sessionsState.value.filterNot { it.id == "audit-session" }
            val outcome = nonActionableCompositeOutcome(runId = "home-purged-first-stage")
            compositeRunService.putCompletedRun(outcome)

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 29L,
                    ),
                )

            assertEquals("default-session", archive.sessionId)
        }

    @Test
    fun `createArchive reports unavailable when home run has no available completed session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val baseOutcome = nonActionableCompositeOutcome(runId = "home-no-available-stage")
            val outcome =
                baseOutcome.copy(
                    stageSummaries =
                        baseOutcome.stageSummaries.map { stage ->
                            stage.copy(status = DiagnosticsHomeCompositeStageStatus.FAILED)
                        },
                )
            compositeRunService.putCompletedRun(outcome)

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 29L,
                    ),
                )

            assertNull(archive.sessionId)
            ZipFile(archive.absolutePath).use { zip ->
                val manifest =
                    json.decodeFromString(
                        DiagnosticsArchiveManifest.serializer(),
                        zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText(),
                    )
                val summary = zip.getInputStream(zip.getEntry("summary.txt")).bufferedReader().readText()
                assertEquals(DiagnosticsArchiveSessionSelectionStatus.UNAVAILABLE, manifest.sessionSelectionStatus)
                assertNull(manifest.includedSessionId)
                assertTrue(summary.contains("selectedSession=unavailable"))
                assertFalse(summary.contains("selectedSession=latest-live"))
            }
        }

    @Test
    fun `createArchive retains the only failed stage report at the archive root`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            stores.sessionsState.value = stores.sessionsState.value.filter { it.id == "dpi-session" }
            val baseOutcome = nonActionableCompositeOutcome(runId = "home-failed-stage-evidence")
            val outcome =
                baseOutcome.copy(
                    stageSummaries =
                        baseOutcome.stageSummaries.map { stage ->
                            stage.copy(status = DiagnosticsHomeCompositeStageStatus.FAILED)
                        },
                )
            compositeRunService.putCompletedRun(outcome)

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 29L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val rootReport =
                    json.decodeFromString(
                        DiagnosticsArchivePayload.serializer(),
                        zip.getInputStream(zip.getEntry("report.json")).bufferedReader().readText(),
                    )
                assertEquals("dpi-session", rootReport.primaryReport?.sessionId)
            }
        }

    @Test
    fun `createArchive rejects home analysis with an invalid bundled recommendation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val exporter = createArchiveExporter(stores)

            listOf(
                buildSampleCompositeOutcome().copy(
                    runId = "home-failed-recommendation",
                    stageSummaries =
                        buildSampleCompositeOutcome().stageSummaries.map { stage ->
                            if (stage.sessionId == "audit-session") {
                                stage.copy(status = DiagnosticsHomeCompositeStageStatus.FAILED)
                            } else {
                                stage
                            }
                        },
                ),
                buildSampleCompositeOutcome().copy(
                    runId = "home-external-recommendation",
                    recommendedSessionId = "external-session",
                ),
                buildSampleCompositeOutcome().copy(
                    runId = "home-missing-recommendation",
                    recommendedSessionId = "missing-session",
                    bundleSessionIds = buildSampleCompositeOutcome().bundleSessionIds + "missing-session",
                ),
            ).forEach { outcome ->
                compositeRunService.putCompletedRun(outcome)
                val failure =
                    runCatching {
                        exporter.createArchive(
                            DiagnosticsArchiveRequest(
                                sessionIds = outcome.bundleSessionIds,
                                homeRunId = outcome.runId,
                                reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                                requestedAt = 29L,
                            ),
                        )
                    }.exceptionOrNull()

                assertNotNull(outcome.runId, failure)
            }
            assertTrue(stores.exportsState.value.isEmpty())
        }

    private fun nonActionableCompositeOutcome(runId: String): DiagnosticsHomeCompositeOutcome =
        buildSampleCompositeOutcome().copy(
            runId = runId,
            actionable = false,
            recommendedSessionId = null,
            stageSummaries =
                buildSampleCompositeOutcome().stageSummaries.map { stage ->
                    stage.copy(recommendationContributor = false)
                },
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
        assertTrue(
            Regex(
                "ripdpi-diagnostics-1700000000000-correlation-[0-9]+\\.zip",
            ).matches(manifest.fileName),
        )
        assertNotNull(zip.getEntry("home-analysis.json"))
        assertNotNull(zip.getEntry("stage-index.json"))
        assertNotNull(zip.getEntry("stage-summaries.json"))
        assertNotNull(zip.getEntry("stages/automatic_audit/report.json"))
        assertNotNull(zip.getEntry("stages/automatic_audit/execution-plan.json"))
        assertNotNull(zip.getEntry("stages/default_connectivity/report.json"))
        assertNotNull(zip.getEntry("stages/default_connectivity/execution-plan.json"))
        assertNotNull(zip.getEntry("stages/dpi_full/report.json"))
        assertNotNull(zip.getEntry("stages/dpi_full/execution-plan.json"))
        GoldenContractSupport.assertJsonGolden(
            "archive/manifest_home_composite_v12.json",
            zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText(),
            scrub = { manifest ->
                JsonObject(
                    manifest.jsonObject +
                        ("fileName" to JsonPrimitive("ripdpi-diagnostics-1700000000000.zip")),
                )
            },
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/home_analysis_composite_v7.json",
            zip.getInputStream(zip.getEntry("home-analysis.json")).bufferedReader().readText(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/stage_index_composite_v7.json",
            zip.getInputStream(zip.getEntry("stage-index.json")).bufferedReader().readText(),
        )
        GoldenContractSupport.assertJsonGolden(
            "archive/stage_summaries_composite_v7.json",
            zip.getInputStream(zip.getEntry("stage-summaries.json")).bufferedReader().readText(),
        )
    }

    @Test
    fun `createArchive excludes pcap from share archive when root mode disabled`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-share",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Share session",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            val context = TestContext()
            seedPcapFile(context)
            val exporter = createArchiveExporter(stores, context = context, rootModeEnabled = false)

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 100L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                assertNoPcapEntries(zip)
            }
        }

    @Test
    fun `createArchive excludes pcap from save archive when root mode disabled`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-save",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Save session",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            val context = TestContext()
            seedPcapFile(context)
            val exporter = createArchiveExporter(stores, context = context, rootModeEnabled = false)

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SAVE_ARCHIVE,
                        requestedAt = 101L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                assertNoPcapEntries(zip)
            }
        }

    @Test
    fun `createArchive excludes pcap from support bundle when root mode disabled`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-bundle",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Bundle session",
                ).copy(serviceMode = "vpn")
            stores.sessionsState.value = listOf(session)
            val context = TestContext()
            seedPcapFile(context)
            val exporter = createArchiveExporter(stores, context = context, rootModeEnabled = false)

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = null,
                        reason = DiagnosticsArchiveReason.SHARE_DEBUG_BUNDLE,
                        requestedAt = 102L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                assertNoPcapEntries(zip)
            }
        }

    @Test
    fun `createArchive excludes pcap from home composite when root mode disabled`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val outcome = buildSampleCompositeOutcome()
            compositeRunService.putCompletedRun(outcome)
            val context = TestContext()
            seedPcapFile(context)
            val exporter = createArchiveExporter(stores, context = context, rootModeEnabled = false)

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 103L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                assertNoPcapEntries(zip)
            }
        }

    @Test
    fun `createArchive rejects pcap embedding in redacted archive`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-root",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Root session",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            val context = TestContext()
            seedPcapFile(context)
            val exporter = createArchiveExporter(stores, context = context, rootModeEnabled = true)

            val failure =
                runCatching {
                    exporter.createArchive(
                        DiagnosticsArchiveRequest(
                            requestedSessionId = session.id,
                            reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                            requestedAt = 104L,
                            includePcap = true,
                        ),
                    )
                }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure?.message.orEmpty().contains("separate raw artifact"))
            assertTrue(stores.exportsState.value.isEmpty())
        }

    @Test
    fun `createArchive never exposes logcat collection exception text`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-logcat-failure",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Logcat failure",
                )
            seedSingleSessionStore(stores, session)
            val context = TestContext()
            val exporter =
                createArchiveExporterForTest(
                    stores = stores,
                    context = context,
                    rootModeEnabled = false,
                    compositeRunService = compositeRunService,
                    json = json,
                    logcatSnapshotCollector =
                        FakeLogcatSnapshotCollector(
                            failure = IllegalStateException("secret-logcat.example 203.0.113.201"),
                        ),
                )

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 105L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val content =
                    zip
                        .entries()
                        .asSequence()
                        .filterNot { it.isDirectory }
                        .joinToString("\n") { entry ->
                            zip.getInputStream(entry).bufferedReader().readText()
                        }
                assertTrue(content.contains("logcat_capture_failed:IllegalStateException"))
                assertFalse(content.contains("secret-logcat.example"))
                assertFalse(content.contains("203.0.113.201"))
            }
        }

    @Test
    fun `createArchive excludes pcap when root mode enabled without explicit opt-in`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-root-default",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Root default session",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            val context = TestContext()
            seedPcapFile(context)
            val exporter = createArchiveExporter(stores, context = context, rootModeEnabled = true)

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 105L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                assertNoPcapEntries(zip)
            }
        }

    private fun seedPcapFile(context: TestContext) {
        val pcapDir = java.io.File(context.cacheDir, "diagnostics").apply { mkdirs() }
        java.io.File(pcapDir, "capture-fixture.pcap").writeBytes(byteArrayOf(0xA1.toByte(), 0xB2.toByte()))
    }

    private fun assertNoPcapEntries(zip: java.util.zip.ZipFile) {
        val pcapEntries =
            zip
                .entries()
                .asSequence()
                .filter { entry ->
                    entry.name.endsWith(".pcap") || entry.name.startsWith("pcap/")
                }.toList()
        assertTrue(
            "Expected zero pcap entries but found: ${pcapEntries.map { it.name }}",
            pcapEntries.isEmpty(),
        )
    }
}
