package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

internal class DiagnosticsArchiveLogcatCoverageTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `deferred share warns when retained logcat starts after the diagnostic window`() =
        runTest {
            val sessionStartedAt = 1_700_000_000_000L
            val oldestRetainedLogAt = sessionStartedAt + 21 * 60 * 1_000L
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-deferred-share",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Deferred share",
                ).copy(startedAt = sessionStartedAt, finishedAt = sessionStartedAt + 60_000L)
            seedSingleSessionStore(stores, session)
            val logcat = "1700001260.000 123 456 I ripdpi: retained tail\n"
            val exporter =
                createArchiveExporterForTest(
                    stores = stores,
                    context = TestContext(),
                    rootModeEnabled = false,
                    compositeRunService = compositeRunService,
                    json = json,
                    logcatSnapshotCollector =
                        FakeLogcatSnapshotCollector(
                            snapshot =
                                LogcatSnapshot(
                                    content = logcat,
                                    captureScope = LogcatSnapshotCollector.TimeBoundSnapshotScope,
                                    byteCount = logcat.toByteArray().size,
                                    truncated = false,
                                ),
                        ),
                )

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = oldestRetainedLogAt,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val completeness =
                    json.decodeFromString(
                        DiagnosticsArchiveCompletenessPayload.serializer(),
                        zip.getInputStream(zip.getEntry("completeness.json")).bufferedReader().readText(),
                    )
                assertTrue(
                    completeness.collectionWarnings.contains(
                        "logcat_history_incomplete:missing_prefix_ms=1260000",
                    ),
                )
                assertFalse(completeness.truncation.logcat)
                assertNotNull(zip.getEntry("logcat.txt"))
            }
        }
}
