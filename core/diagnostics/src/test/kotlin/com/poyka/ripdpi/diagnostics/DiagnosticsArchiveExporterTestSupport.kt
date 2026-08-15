package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NoopStartupJournal
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

internal suspend fun seedSingleSessionStoreForArchiveTest(
    stores: FakeDiagnosticsHistoryStores,
    session: ScanSessionEntity,
    json: Json,
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

internal fun assertSingleSessionArchiveContentsForTest(
    zip: ZipFile,
    sessionId: String,
    json: Json,
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
    assertEquals(
        manifest.includedFiles,
        zip
            .entries()
            .asSequence()
            .map { it.name }
            .toList(),
    )
    assertNull(zip.getEntry("logcat.txt"))
}

internal fun createArchiveExporterForTest(
    stores: FakeDiagnosticsHistoryStores,
    context: TestContext,
    rootModeEnabled: Boolean,
    compositeRunService: DiagnosticsHomeCompositeRunService,
    json: Json,
    logcatSnapshotCollector: LogcatSnapshotCollector = FakeLogcatSnapshotCollector(snapshot = null),
    fileLogWriter: FileLogWriter =
        FileLogWriter(
            java.nio.file.Files
                .createTempDirectory("file-log-test")
                .toFile(),
        ),
): DefaultDiagnosticsArchiveExporter {
    val exportSequence = AtomicInteger()
    val appSettings =
        defaultDiagnosticsAppSettings()
            .toBuilder()
            .setRootModeEnabled(rootModeEnabled)
            .build()
    return DefaultDiagnosticsArchiveExporter(
        exportRecordStore = stores,
        sourceLoader =
            DiagnosticsArchiveSourceLoader(
                appSettingsRepository = FakeAppSettingsRepository(appSettings),
                scanRecordStore = stores,
                artifactReadStore = stores,
                artifactQueryStore = stores,
                bypassUsageHistoryStore = stores,
                logcatSnapshotCollector = logcatSnapshotCollector,
                fileLogWriter = fileLogWriter,
                startupJournal = NoopStartupJournal,
                buildInfoProvider = archiveTestBuildInfoProvider(),
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
        idGenerator = DiagnosticsArchiveIdGenerator { "export-${exportSequence.incrementAndGet()}" },
        developerAnalyticsSource = NoopDeveloperAnalyticsSource,
    )
}

private fun archiveTestBuildInfoProvider(): DiagnosticsArchiveBuildInfoProvider =
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

internal fun probeResultEntityForArchiveTest(
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
