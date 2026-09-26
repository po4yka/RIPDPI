package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsShareWarningSummaryTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `selected summary includes warning older than fifty information events`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "warning-session",
                    profileId = "default",
                    pathMode = ScanPathMode.RAW_PATH.name,
                    summary = "Warnings",
                )
            stores.sessionsState.value = listOf(session)
            seedWarningBehindInformation(stores, session.id, "older failure evidence", "WARN")

            val summary = shareService(stores).buildShareSummary(session.id)

            assertTrue(summary.body.contains("dns: older failure evidence"))
        }

    @Test
    fun `live summary includes warning older than fifty information events`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedWarningBehindInformation(stores, null, "older live failure evidence", "error")

            val summary = shareService(stores).buildShareSummary(null)

            assertTrue(summary.body.contains("dns: older live failure evidence"))
        }

    @Test
    fun `warning fake orders unsorted matching events before limit`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.nativeEventsState.value =
                listOf(
                    NativeSessionEventEntity(
                        id = "older-warning",
                        sessionId = "scan-1",
                        source = "dns",
                        level = "warn",
                        message = "older",
                        createdAt = 10L,
                    ),
                    NativeSessionEventEntity(
                        id = "newer-error",
                        sessionId = "scan-1",
                        source = "dns",
                        level = "error",
                        message = "newer",
                        createdAt = 20L,
                    ),
                )

            assertEquals("newer-error", stores.getWarningNativeEventsForSession("scan-1", 1).single().id)
            assertEquals("newer-error", stores.observeWarningNativeEvents(1).first().single().id)
        }

    private fun seedWarningBehindInformation(
        stores: FakeDiagnosticsHistoryStores,
        sessionId: String?,
        warningMessage: String,
        warningLevel: String,
    ) {
        val informationEvents =
            List(50) { index ->
                NativeSessionEventEntity(
                    id = "info-$index",
                    sessionId = sessionId,
                    source = "native",
                    level = "info",
                    message = "routine $index",
                    createdAt = 100L + index,
                )
            }
        val warning =
            NativeSessionEventEntity(
                id = "older-warning",
                sessionId = sessionId,
                source = "dns",
                level = warningLevel,
                message = warningMessage,
                createdAt = 50L,
            )
        stores.nativeEventsState.value = (informationEvents + warning).sortedByDescending { it.createdAt }
    }

    private fun shareService(stores: FakeDiagnosticsHistoryStores) =
        DefaultDiagnosticsShareService(
            scanRecordStore = stores,
            artifactReadStore = stores,
            artifactQueryStore = stores,
            archiveExporter =
                object : DiagnosticsArchiveExporter {
                    override suspend fun cleanupCache() = Unit

                    override suspend fun createArchive(request: DiagnosticsArchiveRequest): DiagnosticsArchive =
                        error("Archive export is unused by this summary test")
                },
            json = json,
            serviceStateStore = FakeServiceStateStore(),
        )
}
