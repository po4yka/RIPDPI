package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID
import java.util.zip.ZipFile

class DiagnosticsArchiveExporterTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `createArchive persists requested session export and writes schema v2 archive`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-selected",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Selected session",
                ).copy(serviceMode = "vpn")
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
            assertEquals(2, archive.schemaVersion)
            assertEquals(1, stores.exportsState.value.size)
            assertEquals(
                session.id,
                stores.exportsState.value
                    .single()
                    .sessionId,
            )

            ZipFile(archive.absolutePath).use { zip ->
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
                assertEquals(session.id, manifest.requestedSessionId)
                assertEquals(session.id, manifest.selectedSessionId)
                assertEquals(
                    DiagnosticsArchiveSessionSelectionStatus.REQUESTED_SESSION,
                    provenance.sessionSelectionStatus,
                )
                assertEquals(DiagnosticsArchiveFormat.includedFiles(logcatIncluded = false), manifest.includedFiles)
                assertNull(zip.getEntry("logcat.txt"))
            }
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
                    buildInfoProvider = testBuildInfoProvider(),
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
}
