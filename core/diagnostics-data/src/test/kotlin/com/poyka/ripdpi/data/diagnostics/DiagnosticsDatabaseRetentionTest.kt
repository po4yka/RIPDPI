package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsDatabaseRetentionTest {
    @Test
    fun `database open defers cleanup to the configured retention store`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "diagnostics-retention-${System.nanoTime()}.db"
            val now = System.currentTimeMillis()
            context.deleteDatabase(databaseName)

            var database = DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, databaseName)
            try {
                val dao = database.diagnosticsDao()
                RoomDiagnosticsArtifactStore(dao).insertTelemetrySample(
                    telemetrySample(id = "eight-days-old", createdAt = now - 8L * DiagnosticsHistoryDayMillis),
                )
                RoomDiagnosticsScanRecordStore(database, dao).upsertScanSession(
                    scanSession(id = "thirty-one-days-old", finishedAt = now - 31L * DiagnosticsHistoryDayMillis),
                )
                database.close()

                database = DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, databaseName)
                database.openHelper.writableDatabase
                assertEquals(1, rowCount(database, "telemetry_samples"))
                assertEquals(1, rowCount(database, "scan_sessions"))

                val retentionStore =
                    RoomDiagnosticsHistoryRetentionStore(
                        dao = database.diagnosticsDao(),
                        clock = DiagnosticsHistoryClock { now },
                    )
                retentionStore.trimOldData(retentionDays = 365)
                assertEquals(1, rowCount(database, "telemetry_samples"))
                assertEquals(1, rowCount(database, "scan_sessions"))

                retentionStore.trimOldData(retentionDays = 1)
                assertEquals(0, rowCount(database, "telemetry_samples"))
                assertEquals(0, rowCount(database, "scan_sessions"))
            } finally {
                database.close()
                context.deleteDatabase(databaseName)
            }
        }

    private fun rowCount(
        database: DiagnosticsDatabase,
        table: String,
    ): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun telemetrySample(
        id: String,
        createdAt: Long,
    ): TelemetrySampleEntity =
        TelemetrySampleEntity(
            id = id,
            sessionId = null,
            activeMode = "VPN",
            connectionState = "Running",
            networkType = "wifi",
            publicIp = null,
            txPackets = 1L,
            txBytes = 64L,
            rxPackets = 2L,
            rxBytes = 128L,
            createdAt = createdAt,
        )

    private fun scanSession(
        id: String,
        finishedAt: Long,
    ): ScanSessionEntity =
        ScanSessionEntity(
            id = id,
            profileId = "default",
            pathMode = "RAW_PATH",
            serviceMode = "VPN",
            status = "completed",
            summary = id,
            reportJson = "{}",
            startedAt = finishedAt - 1_000L,
            finishedAt = finishedAt,
        )
}
