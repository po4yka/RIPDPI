package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsDatabaseMigrationTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(TestDatabaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TestDatabaseName)
    }

    @Test
    fun `migration 1 to 2 clears remembered policies but preserves diagnostics history`() {
        val seededDatabase =
            Room
                .databaseBuilder(context, DiagnosticsDatabase::class.java, TestDatabaseName)
                .allowMainThreadQueries()
                .build()
        try {
            seededDatabase.seedMigrationFixtures()
        } finally {
            seededDatabase.close()
        }

        val rawDatabase =
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(TestDatabaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
        try {
            rawDatabase.version = 1
        } finally {
            rawDatabase.close()
        }

        val migrated =
            Room
                .databaseBuilder(context, DiagnosticsDatabase::class.java, TestDatabaseName)
                .addMigrations(DiagnosticsMigration1To2)
                .allowMainThreadQueries()
                .build()
        try {
            val writable = migrated.openHelper.writableDatabase
            assertEquals(0, writable.rowCount("remembered_network_policies"))
            assertEquals(1, writable.rowCount("scan_sessions"))
            assertEquals(1, writable.rowCount("probe_results"))
            assertEquals(1, writable.rowCount("network_snapshots"))
            assertEquals(1, writable.rowCount("diagnostic_context_snapshots"))
            assertEquals(1, writable.rowCount("telemetry_samples"))
            assertEquals(1, writable.rowCount("export_records"))
        } finally {
            migrated.close()
        }
    }

    @Suppress("LongMethod")
    private fun DiagnosticsDatabase.seedMigrationFixtures() {
        runBlocking {
            val dao = diagnosticsDao()
            dao.upsertProfile(
                DiagnosticProfileEntity(
                    id = "profile-1",
                    name = "Profile",
                    source = "test",
                    version = 1,
                    requestJson = "{}",
                    updatedAt = 10,
                ),
            )
            dao.upsertScanSession(
                ScanSessionEntity(
                    id = "scan-1",
                    profileId = "profile-1",
                    pathMode = "RAW_PATH",
                    serviceMode = "VPN",
                    status = "completed",
                    summary = "summary",
                    reportJson = "{}",
                    startedAt = 10,
                    finishedAt = 20,
                ),
            )
            dao.insertProbeResults(
                listOf(
                    ProbeResultEntity(
                        id = "probe-1",
                        sessionId = "scan-1",
                        probeType = "dns",
                        target = "blocked.example",
                        outcome = "dns_blocked",
                        detailJson = "[]",
                        createdAt = 11,
                    ),
                ),
            )
            dao.upsertNetworkSnapshot(
                NetworkSnapshotEntity(
                    id = "snapshot-1",
                    sessionId = "scan-1",
                    snapshotKind = "post_scan",
                    payloadJson = "{}",
                    capturedAt = 12,
                ),
            )
            dao.upsertContextSnapshot(
                DiagnosticContextEntity(
                    id = "context-1",
                    sessionId = "scan-1",
                    contextKind = "post_scan",
                    payloadJson = "{}",
                    capturedAt = 13,
                ),
            )
            dao.insertTelemetrySample(
                TelemetrySampleEntity(
                    id = "telemetry-1",
                    sessionId = "scan-1",
                    activeMode = "VPN",
                    connectionState = "Running",
                    networkType = "wifi",
                    publicIp = "198.51.100.10",
                    txPackets = 1,
                    txBytes = 64,
                    rxPackets = 2,
                    rxBytes = 128,
                    createdAt = 14,
                ),
            )
            dao.insertExportRecord(
                ExportRecordEntity(
                    id = "export-1",
                    sessionId = "scan-1",
                    uri = "content://test/export-1",
                    fileName = "export-1.zip",
                    createdAt = 15,
                ),
            )
            dao.upsertRememberedNetworkPolicy(
                RememberedNetworkPolicyEntity(
                    fingerprintHash = "fp-1",
                    mode = "vpn",
                    summaryJson = "{}",
                    proxyConfigJson = "{}",
                    winningTcpStrategyFamily = "split",
                    winningQuicStrategyFamily = "quic_burst",
                    source = "strategy_probe",
                    status = "validated",
                    successCount = 1,
                    failureCount = 0,
                    consecutiveFailureCount = 0,
                    firstObservedAt = 16,
                    lastValidatedAt = 17,
                    lastAppliedAt = 18,
                    updatedAt = 19,
                ),
            )
        }
    }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val TestDatabaseName = "diagnostics-migration-test.db"
    }
}
