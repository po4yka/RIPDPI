package com.poyka.ripdpi.data.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsLatestCompletedSessionRoomTest : DiagnosticsRoomStoreTestBase() {
    @Test
    fun `latest completion beats later starts even with an oversized report`() =
        runTest {
            val store = RoomDiagnosticsScanRecordStore(db, dao)
            val oversizedJson = "{\"summary\":\"${"x".repeat(1_500_001)}\"}"
            dao.upsertScanSession(scanSession("old-completion", startedAt = 15, finishedAt = 20))
            dao.upsertScanSession(
                scanSession("latest-completion", startedAt = 10, finishedAt = 25).copy(reportJson = oversizedJson),
            )
            dao.upsertScanSession(
                scanSession(
                    "local-collection",
                    startedAt = 26,
                    finishedAt = 27,
                ).copy(launchOrigin = "home_detection_local"),
            )
            dao.upsertScanSession(scanSession("failed-scan", startedAt = 28, finishedAt = 29).copy(status = "failed"))
            repeat(51) { index ->
                dao.upsertScanSession(
                    scanSession("running-$index", startedAt = 100L + index, finishedAt = null).copy(status = "running"),
                )
            }

            val selected = store.getLatestCompletedScanSession()

            assertEquals("latest-completion", selected?.id)
            assertEquals(25L, selected?.finishedAt)
            assertNull(selected?.reportJson)
        }
}
