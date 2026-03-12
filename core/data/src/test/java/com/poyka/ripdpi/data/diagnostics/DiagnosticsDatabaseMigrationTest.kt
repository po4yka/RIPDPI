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

    @Test
    fun `migration 6 to 7 preserves rows and initializes field telemetry columns`() {
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
                        object : SupportSQLiteOpenHelper.Callback(6) {
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
                    resolverId TEXT,
                    resolverProtocol TEXT,
                    resolverEndpoint TEXT,
                    resolverLatencyMs INTEGER,
                    dnsFailuresTotal INTEGER NOT NULL DEFAULT 0,
                    resolverFallbackActive INTEGER NOT NULL DEFAULT 0,
                    resolverFallbackReason TEXT,
                    networkHandoverClass TEXT,
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
                    resolverId, resolverProtocol, resolverEndpoint, resolverLatencyMs, dnsFailuresTotal,
                    resolverFallbackActive, resolverFallbackReason, networkHandoverClass,
                    txPackets, txBytes, rxPackets, rxBytes, createdAt
                ) VALUES (
                    'sample-1', 'session-1', 'connection-1', 'VPN', 'Running', 'wifi', '198.51.100.9',
                    'cloudflare', 'doh', 'https://cloudflare-dns.com/dns-query', 42, 3,
                    1, 'udp_blocked', 'transport_switch',
                    10, 20, 30, 40, 50
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS bypass_usage_sessions (
                    id TEXT NOT NULL PRIMARY KEY,
                    startedAt INTEGER NOT NULL,
                    finishedAt INTEGER,
                    updatedAt INTEGER NOT NULL DEFAULT 0,
                    serviceMode TEXT NOT NULL,
                    connectionState TEXT NOT NULL DEFAULT 'Running',
                    health TEXT NOT NULL DEFAULT 'idle',
                    approachProfileId TEXT,
                    approachProfileName TEXT,
                    strategyId TEXT NOT NULL,
                    strategyLabel TEXT NOT NULL,
                    strategyJson TEXT NOT NULL,
                    networkType TEXT NOT NULL,
                    publicIp TEXT,
                    txBytes INTEGER NOT NULL,
                    rxBytes INTEGER NOT NULL,
                    totalErrors INTEGER NOT NULL,
                    routeChanges INTEGER NOT NULL,
                    restartCount INTEGER NOT NULL,
                    endedReason TEXT,
                    failureMessage TEXT
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO bypass_usage_sessions (
                    id, startedAt, finishedAt, updatedAt, serviceMode, connectionState, health,
                    approachProfileId, approachProfileName, strategyId, strategyLabel, strategyJson,
                    networkType, publicIp, txBytes, rxBytes, totalErrors, routeChanges, restartCount,
                    endedReason, failureMessage
                ) VALUES (
                    'usage-1', 10, 20, 20, 'VPN', 'Failed', 'degraded',
                    'default', 'Default', 'strategy-1', 'VPN Split', '{}',
                    'wifi', '198.51.100.9', 100, 200, 2, 1, 3,
                    'failed:vpn', 'boom'
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remembered_network_policies (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fingerprintHash TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    summaryJson TEXT NOT NULL,
                    proxyConfigJson TEXT NOT NULL,
                    vpnDnsPolicyJson TEXT,
                    strategySignatureJson TEXT,
                    source TEXT NOT NULL,
                    status TEXT NOT NULL,
                    successCount INTEGER NOT NULL,
                    failureCount INTEGER NOT NULL,
                    consecutiveFailureCount INTEGER NOT NULL,
                    firstObservedAt INTEGER NOT NULL,
                    lastValidatedAt INTEGER,
                    lastAppliedAt INTEGER,
                    suppressedUntil INTEGER,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO remembered_network_policies (
                    id, fingerprintHash, mode, summaryJson, proxyConfigJson, vpnDnsPolicyJson,
                    strategySignatureJson, source, status, successCount, failureCount,
                    consecutiveFailureCount, firstObservedAt, lastValidatedAt, lastAppliedAt,
                    suppressedUntil, updatedAt
                ) VALUES (
                    1, 'abc', 'vpn', '{}', '{}', NULL,
                    NULL, 'strategy_probe', 'validated', 2, 0,
                    0, 10, 20, 30,
                    NULL, 40
                )
                """.trimIndent(),
            )

            DiagnosticsDatabaseMigration6To7.migrate(database)

            val telemetryColumns =
                database.query("PRAGMA table_info(`telemetry_samples`)").use { cursor ->
                    buildSet {
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) {
                            add(cursor.getString(nameIndex))
                        }
                    }
                }
            assertTrue(telemetryColumns.contains("failureClass"))
            assertTrue(telemetryColumns.contains("telemetryNetworkFingerprintHash"))
            assertTrue(telemetryColumns.contains("winningTcpStrategyFamily"))
            assertTrue(telemetryColumns.contains("winningQuicStrategyFamily"))
            assertTrue(telemetryColumns.contains("proxyRttBand"))
            assertTrue(telemetryColumns.contains("resolverRttBand"))
            assertTrue(telemetryColumns.contains("proxyRouteRetryCount"))
            assertTrue(telemetryColumns.contains("tunnelRecoveryRetryCount"))

            database.query(
                """
                SELECT failureClass, telemetryNetworkFingerprintHash, winningTcpStrategyFamily,
                       winningQuicStrategyFamily, proxyRttBand, resolverRttBand,
                       proxyRouteRetryCount, tunnelRecoveryRetryCount
                FROM telemetry_samples
                WHERE id = 'sample-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertEquals("unknown", cursor.getString(4))
                assertEquals("unknown", cursor.getString(5))
                assertEquals(0L, cursor.getLong(6))
                assertEquals(0L, cursor.getLong(7))
            }

            database.query(
                """
                SELECT failureClass, telemetryNetworkFingerprintHash, winningTcpStrategyFamily,
                       winningQuicStrategyFamily, proxyRttBand, resolverRttBand,
                       proxyRouteRetryCount, tunnelRecoveryRetryCount
                FROM bypass_usage_sessions
                WHERE id = 'usage-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertEquals("unknown", cursor.getString(4))
                assertEquals("unknown", cursor.getString(5))
                assertEquals(0L, cursor.getLong(6))
                assertEquals(0L, cursor.getLong(7))
            }

            database.query(
                """
                SELECT winningTcpStrategyFamily, winningQuicStrategyFamily
                FROM remembered_network_policies
                WHERE id = 1
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
        } finally {
            helper.close()
            databaseFile.delete()
            java.io.File(databaseFile.parentFile, "${databaseFile.name}-journal").delete()
        }
    }
}
