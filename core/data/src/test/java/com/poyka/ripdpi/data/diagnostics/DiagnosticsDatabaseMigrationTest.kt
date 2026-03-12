package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticsDatabaseMigrationTest {
    @Test
    fun `migration 4 to 5 preserves telemetry rows and initializes resolver columns`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "diagnostics-migration-${System.nanoTime()}.db"
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        if (databaseFile.exists()) {
            databaseFile.delete()
        }

        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(databaseName)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(4) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )

        try {
            val database = helper.writableDatabase
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS telemetry_samples (
                    id TEXT NOT NULL PRIMARY KEY,
                    sessionId TEXT,
                    connectionSessionId TEXT,
                    activeMode TEXT,
                    connectionState TEXT NOT NULL,
                    networkType TEXT NOT NULL,
                    publicIp TEXT,
                    txPackets INTEGER NOT NULL,
                    txBytes INTEGER NOT NULL,
                    rxPackets INTEGER NOT NULL,
                    rxBytes INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO telemetry_samples (
                    id, sessionId, connectionSessionId, activeMode, connectionState, networkType, publicIp,
                    txPackets, txBytes, rxPackets, rxBytes, createdAt
                ) VALUES (
                    'sample-1', 'session-1', NULL, 'VPN', 'Running', 'wifi', '198.51.100.9',
                    10, 20, 30, 40, 50
                )
                """.trimIndent(),
            )

            DiagnosticsDatabaseMigration4To5.migrate(database)

            val columns =
                database.query("PRAGMA table_info(`telemetry_samples`)").use { cursor ->
                    buildSet {
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) {
                            add(cursor.getString(nameIndex))
                        }
                    }
                }

            assertTrue(columns.contains("resolverId"))
            assertTrue(columns.contains("resolverProtocol"))
            assertTrue(columns.contains("resolverEndpoint"))
            assertTrue(columns.contains("resolverLatencyMs"))
            assertTrue(columns.contains("dnsFailuresTotal"))
            assertTrue(columns.contains("resolverFallbackActive"))
            assertTrue(columns.contains("resolverFallbackReason"))
            assertTrue(columns.contains("networkHandoverClass"))

            database.query(
                """
                SELECT resolverId, resolverProtocol, resolverEndpoint, resolverLatencyMs,
                       dnsFailuresTotal, resolverFallbackActive, resolverFallbackReason, networkHandoverClass
                FROM telemetry_samples
                WHERE id = 'sample-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertEquals(0L, cursor.getLong(4))
                assertEquals(0L, cursor.getLong(5))
                assertTrue(cursor.isNull(6))
                assertTrue(cursor.isNull(7))
            }
        } finally {
            helper.close()
            databaseFile.delete()
            java.io.File(databaseFile.parentFile, "${databaseFile.name}-journal").delete()
        }
    }
}
