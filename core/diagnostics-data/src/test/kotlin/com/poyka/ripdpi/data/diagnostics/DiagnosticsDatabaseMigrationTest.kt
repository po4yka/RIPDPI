package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.LegacyRememberedNetworkPolicySourceStrategyProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        context.deleteDatabase(Migration1To2DatabaseName)
        context.deleteDatabase(Migration2To3DatabaseName)
        context.deleteDatabase(Migration3To4DatabaseName)
        context.deleteDatabase(Migration4To5DatabaseName)
        context.deleteDatabase(Migration5To6DatabaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(Migration1To2DatabaseName)
        context.deleteDatabase(Migration2To3DatabaseName)
        context.deleteDatabase(Migration3To4DatabaseName)
        context.deleteDatabase(Migration4To5DatabaseName)
        context.deleteDatabase(Migration5To6DatabaseName)
    }

    @Test
    fun `migration 1 to 2 clears remembered policies but preserves diagnostics history`() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(Migration1To2DatabaseName)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `remembered_network_policies` (
                                        `fingerprintHash` TEXT NOT NULL,
                                        `mode` TEXT NOT NULL,
                                        `summaryJson` TEXT NOT NULL,
                                        PRIMARY KEY(`fingerprintHash`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    "INSERT INTO `remembered_network_policies` " +
                                        "(`fingerprintHash`, `mode`, `summaryJson`) VALUES ('fp-1', 'vpn', '{}')",
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `scan_sessions` (
                                        `id` TEXT NOT NULL,
                                        `summary` TEXT NOT NULL,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL("INSERT INTO `scan_sessions` (`id`, `summary`) VALUES ('scan-1', 'summary')")
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `probe_results` (
                                        `id` TEXT NOT NULL,
                                        `sessionId` TEXT NOT NULL,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    "INSERT INTO `probe_results` (`id`, `sessionId`) VALUES ('probe-1', 'scan-1')",
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `network_snapshots` (
                                        `id` TEXT NOT NULL,
                                        `sessionId` TEXT,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    "INSERT INTO `network_snapshots` (`id`, `sessionId`) VALUES ('snapshot-1', 'scan-1')",
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `diagnostic_context_snapshots` (
                                        `id` TEXT NOT NULL,
                                        `sessionId` TEXT,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    "INSERT INTO `diagnostic_context_snapshots` (`id`, `sessionId`) VALUES ('context-1', 'scan-1')",
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `telemetry_samples` (
                                        `id` TEXT NOT NULL,
                                        `sessionId` TEXT,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    "INSERT INTO `telemetry_samples` (`id`, `sessionId`) VALUES ('telemetry-1', 'scan-1')",
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `export_records` (
                                        `id` TEXT NOT NULL,
                                        `sessionId` TEXT,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    "INSERT INTO `export_records` (`id`, `sessionId`) VALUES ('export-1', 'scan-1')",
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )

        try {
            val writable = helper.writableDatabase
            DiagnosticsMigration1To2.migrate(writable)
            assertEquals(0, writable.rowCount("SELECT COUNT(*) FROM remembered_network_policies"))
            assertEquals(1, writable.rowCount("SELECT COUNT(*) FROM scan_sessions"))
            assertEquals(1, writable.rowCount("SELECT COUNT(*) FROM probe_results"))
            assertEquals(1, writable.rowCount("SELECT COUNT(*) FROM network_snapshots"))
            assertEquals(1, writable.rowCount("SELECT COUNT(*) FROM diagnostic_context_snapshots"))
            assertEquals(1, writable.rowCount("SELECT COUNT(*) FROM telemetry_samples"))
            assertEquals(1, writable.rowCount("SELECT COUNT(*) FROM export_records"))
        } finally {
            helper.close()
        }
    }

    @Test
    fun `migration 2 to 3 adds native correlation columns with null defaults`() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(Migration2To3DatabaseName)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(2) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `native_session_events` (
                                        `id` TEXT NOT NULL,
                                        `sessionId` TEXT,
                                        `connectionSessionId` TEXT,
                                        `source` TEXT NOT NULL,
                                        `level` TEXT NOT NULL,
                                        `message` TEXT NOT NULL,
                                        `createdAt` INTEGER NOT NULL,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    INSERT INTO `native_session_events`
                                        (`id`, `sessionId`, `connectionSessionId`, `source`, `level`, `message`, `createdAt`)
                                    VALUES
                                        ('event-1', 'scan-1', 'connection-1', 'proxy', 'warn', 'route changed', 42)
                                    """.trimIndent(),
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )

        try {
            val db = helper.writableDatabase
            DiagnosticsMigration2To3.migrate(db)

            val columns =
                db.query("PRAGMA table_info(`native_session_events`)").use { cursor ->
                    buildSet {
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) {
                            add(cursor.getString(nameIndex))
                        }
                    }
                }

            assertTrue(columns.contains("runtimeId"))
            assertTrue(columns.contains("mode"))
            assertTrue(columns.contains("policySignature"))
            assertTrue(columns.contains("fingerprintHash"))
            assertTrue(columns.contains("subsystem"))

            db
                .query(
                    "SELECT source, level, message, runtimeId, mode, policySignature, fingerprintHash, subsystem " +
                        "FROM native_session_events WHERE id = 'event-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("proxy", cursor.getString(0))
                    assertEquals("warn", cursor.getString(1))
                    assertEquals("route changed", cursor.getString(2))
                    assertNull(cursor.getString(3))
                    assertNull(cursor.getString(4))
                    assertNull(cursor.getString(5))
                    assertNull(cursor.getString(6))
                    assertNull(cursor.getString(7))
                }
        } finally {
            helper.close()
        }
    }

    @Test
    fun `migration 3 to 4 deletes legacy strategy probe remembered policies only`() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(Migration3To4DatabaseName)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(3) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `remembered_network_policies` (
                                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                        `fingerprintHash` TEXT NOT NULL,
                                        `mode` TEXT NOT NULL,
                                        `summaryJson` TEXT NOT NULL,
                                        `proxyConfigJson` TEXT NOT NULL,
                                        `vpnDnsPolicyJson` TEXT,
                                        `strategySignatureJson` TEXT,
                                        `winningTcpStrategyFamily` TEXT,
                                        `winningQuicStrategyFamily` TEXT,
                                        `source` TEXT NOT NULL,
                                        `status` TEXT NOT NULL,
                                        `successCount` INTEGER NOT NULL,
                                        `failureCount` INTEGER NOT NULL,
                                        `consecutiveFailureCount` INTEGER NOT NULL,
                                        `firstObservedAt` INTEGER NOT NULL,
                                        `lastValidatedAt` INTEGER,
                                        `lastAppliedAt` INTEGER,
                                        `suppressedUntil` INTEGER,
                                        `updatedAt` INTEGER NOT NULL
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    INSERT INTO `remembered_network_policies`
                                        (`fingerprintHash`, `mode`, `summaryJson`, `proxyConfigJson`, `source`, `status`,
                                         `successCount`, `failureCount`, `consecutiveFailureCount`, `firstObservedAt`, `updatedAt`)
                                    VALUES
                                        ('fp-legacy', 'vpn', '{}', '{}', '$LegacyRememberedNetworkPolicySourceStrategyProbe',
                                         'validated', 1, 0, 0, 10, 10),
                                        ('fp-manual', 'vpn', '{}', '{}', 'manual_session', 'validated', 1, 0, 0, 20, 20),
                                        ('fp-background', 'vpn', '{}', '{}', 'automatic_probing_background',
                                         'validated', 1, 0, 0, 30, 30)
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `scan_sessions` (
                                        `id` TEXT NOT NULL,
                                        `summary` TEXT NOT NULL,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    "INSERT INTO `scan_sessions` (`id`, `summary`) VALUES ('scan-keep', 'summary')",
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )

        try {
            val db = helper.writableDatabase
            DiagnosticsMigration3To4.migrate(db)

            assertEquals(
                0,
                db.rowCount(
                    "SELECT COUNT(*) FROM remembered_network_policies " +
                        "WHERE source = '$LegacyRememberedNetworkPolicySourceStrategyProbe'",
                ),
            )
            assertEquals(
                1,
                db.rowCount("SELECT COUNT(*) FROM remembered_network_policies WHERE source = 'manual_session'"),
            )
            assertEquals(
                1,
                db.rowCount(
                    "SELECT COUNT(*) FROM remembered_network_policies WHERE source = 'automatic_probing_background'",
                ),
            )
            assertEquals(1, db.rowCount("SELECT COUNT(*) FROM scan_sessions"))
        } finally {
            helper.close()
        }
    }

    @Test
    fun `migration 4 to 5 preserves scan history and adds nullable launch metadata columns`() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(Migration4To5DatabaseName)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(4) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `scan_sessions` (
                                        `id` TEXT NOT NULL,
                                        `profileId` TEXT NOT NULL,
                                        `approachProfileId` TEXT,
                                        `approachProfileName` TEXT,
                                        `strategyId` TEXT,
                                        `strategyLabel` TEXT,
                                        `strategyJson` TEXT,
                                        `pathMode` TEXT NOT NULL,
                                        `serviceMode` TEXT,
                                        `status` TEXT NOT NULL,
                                        `summary` TEXT NOT NULL,
                                        `reportJson` TEXT,
                                        `startedAt` INTEGER NOT NULL,
                                        `finishedAt` INTEGER,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    INSERT INTO `scan_sessions`
                                        (`id`, `profileId`, `pathMode`, `serviceMode`, `status`, `summary`, `reportJson`,
                                         `startedAt`, `finishedAt`)
                                    VALUES
                                        ('scan-1', 'default', 'RAW_PATH', 'VPN', 'completed', 'summary', '{}', 10, 20)
                                    """.trimIndent(),
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )

        try {
            val db = helper.writableDatabase
            DiagnosticsMigration4To5.migrate(db)

            val columns =
                db.query("PRAGMA table_info(`scan_sessions`)").use { cursor ->
                    buildSet {
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) {
                            add(cursor.getString(nameIndex))
                        }
                    }
                }

            assertTrue(columns.contains("launchOrigin"))
            assertTrue(columns.contains("triggerType"))
            assertTrue(columns.contains("triggerClassification"))
            assertTrue(columns.contains("triggerOccurredAt"))
            assertTrue(columns.contains("triggerPreviousFingerprintHash"))
            assertTrue(columns.contains("triggerCurrentFingerprintHash"))

            db
                .query(
                    """
                    SELECT summary, launchOrigin, triggerType, triggerClassification, triggerOccurredAt,
                           triggerPreviousFingerprintHash, triggerCurrentFingerprintHash
                    FROM scan_sessions
                    WHERE id = 'scan-1'
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("summary", cursor.getString(0))
                    assertNull(cursor.getString(1))
                    assertNull(cursor.getString(2))
                    assertNull(cursor.getString(3))
                    assertTrue(cursor.isNull(4))
                    assertNull(cursor.getString(5))
                    assertNull(cursor.getString(6))
                }
        } finally {
            helper.close()
        }
    }

    @Test
    fun `migration 5 to 6 preserves telemetry rows and creates fingerprint mode index`() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(Migration5To6DatabaseName)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(5) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `telemetry_samples` (
                                        `id` TEXT NOT NULL,
                                        `sessionId` TEXT,
                                        `connectionSessionId` TEXT,
                                        `activeMode` TEXT,
                                        `connectionState` TEXT NOT NULL,
                                        `networkType` TEXT NOT NULL,
                                        `publicIp` TEXT,
                                        `failureClass` TEXT,
                                        `telemetryNetworkFingerprintHash` TEXT,
                                        `createdAt` INTEGER NOT NULL,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    INSERT INTO `telemetry_samples`
                                        (`id`, `sessionId`, `connectionSessionId`, `activeMode`, `connectionState`,
                                         `networkType`, `publicIp`, `failureClass`, `telemetryNetworkFingerprintHash`, `createdAt`)
                                    VALUES
                                        ('telemetry-1', 'scan-1', 'connection-1', 'VPN', 'Failed', 'wifi',
                                         '198.51.100.10', 'dns_tampering', 'fp-1', 42)
                                    """.trimIndent(),
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )

        try {
            val db = helper.writableDatabase
            DiagnosticsMigration5To6.migrate(db)

            assertEquals(1, db.rowCount("SELECT COUNT(*) FROM telemetry_samples"))

            val indexNames =
                db.query("PRAGMA index_list(`telemetry_samples`)").use { cursor ->
                    buildSet {
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) {
                            add(cursor.getString(nameIndex))
                        }
                    }
                }

            assertTrue(indexNames.contains("index_telemetry_samples_fingerprint_mode_createdAt"))
        } finally {
            helper.close()
        }
    }

    private fun SupportSQLiteDatabase.rowCount(query: String): Int =
        query(query).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val Migration1To2DatabaseName = "diagnostics-migration-1-to-2.db"
        const val Migration2To3DatabaseName = "diagnostics-migration-2-to-3.db"
        const val Migration3To4DatabaseName = "diagnostics-migration-3-to-4.db"
        const val Migration4To5DatabaseName = "diagnostics-migration-4-to-5.db"
        const val Migration5To6DatabaseName = "diagnostics-migration-5-to-6.db"
    }
}
