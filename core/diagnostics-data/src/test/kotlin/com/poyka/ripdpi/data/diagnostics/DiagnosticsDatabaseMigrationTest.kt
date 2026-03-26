package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
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
    }

    @After
    fun tearDown() {
        context.deleteDatabase(Migration1To2DatabaseName)
        context.deleteDatabase(Migration2To3DatabaseName)
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
                                db.execSQL("INSERT INTO `probe_results` (`id`, `sessionId`) VALUES ('probe-1', 'scan-1')")
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `network_snapshots` (
                                        `id` TEXT NOT NULL,
                                        `sessionId` TEXT,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL("INSERT INTO `network_snapshots` (`id`, `sessionId`) VALUES ('snapshot-1', 'scan-1')")
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
                                db.execSQL("INSERT INTO `telemetry_samples` (`id`, `sessionId`) VALUES ('telemetry-1', 'scan-1')")
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS `export_records` (
                                        `id` TEXT NOT NULL,
                                        `sessionId` TEXT,
                                        PRIMARY KEY(`id`)
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL("INSERT INTO `export_records` (`id`, `sessionId`) VALUES ('export-1', 'scan-1')")
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
            assertEquals(0, writable.rowCount("remembered_network_policies"))
            assertEquals(1, writable.rowCount("scan_sessions"))
            assertEquals(1, writable.rowCount("probe_results"))
            assertEquals(1, writable.rowCount("network_snapshots"))
            assertEquals(1, writable.rowCount("diagnostic_context_snapshots"))
            assertEquals(1, writable.rowCount("telemetry_samples"))
            assertEquals(1, writable.rowCount("export_records"))
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

            db.query(
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

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val Migration1To2DatabaseName = "diagnostics-migration-1-to-2.db"
        const val Migration2To3DatabaseName = "diagnostics-migration-2-to-3.db"
    }
}
