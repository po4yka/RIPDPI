package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsDatabaseMigrationTest {
    /** Creates databases from the exported schema JSONs exposed as test assets; closes them after each test. */
    @get:Rule
    val schemaHelper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            DiagnosticsDatabase::class.java,
        )

    /**
     * Opens a fresh database through the production builder with allowDestructiveFallback = false.
     * Room creates the database at the current schema version directly (no migration path needed
     * for a brand-new DB). This proves the production builder does not throw when destructive
     * fallback is disabled and the schema is in sync.
     */
    @Test
    fun opensCurrentSchemaWithoutDestruction() {
        val dbName = "migration-test-open-${System.nanoTime()}.db"
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)

        val db =
            DiagnosticsDatabaseModule.buildDiagnosticsDatabase(
                context,
                dbName,
                allowDestructiveFallback = false,
            )
        try {
            // Opening writableDatabase triggers Room schema validation — must not throw.
            db.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM scan_sessions")
                .close()
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    /**
     * Guard test: DiagnosticsDatabaseMigrations.ALL must have one Migration object for every
     * schema version from 4 (the first version under migration management). When a developer
     * bumps @Database(version = N), they MUST append Migration(N-1, N) to ALL before this test
     * will pass again. The correct fix is never to re-enable destructive fallback.
     */
    @Test
    fun migrationRegistryCoversVersionGap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "version-probe-${System.nanoTime()}.db"
        val probeDb =
            DiagnosticsDatabaseModule.buildDiagnosticsDatabase(
                context,
                dbName,
                allowDestructiveFallback = false,
            )
        val currentDbVersion: Int
        try {
            currentDbVersion = probeDb.openHelper.readableDatabase.version
        } finally {
            probeDb.close()
            context.deleteDatabase(dbName)
        }

        // Schema 4 shipped in v0.1.0 (the oldest public release) and must migrate in place.
        // Only the pre-release schemas below it may take the scoped destructive path.
        val firstManagedVersion = 4
        assertEquals(
            "Only pre-release schemas below the first managed version may be recreated destructively",
            (1 until firstManagedVersion).toList(),
            DiagnosticsDatabaseMigrations.DESTRUCTIVE_FROM_VERSIONS.sorted(),
        )
        val requiredMigrations = (currentDbVersion - firstManagedVersion).coerceAtLeast(0)

        assertEquals(
            "Schema version bumped without a migration: append Migration(N, N+1) to " +
                "DiagnosticsDatabaseMigrations.ALL — do NOT re-enable destructive fallback.",
            requiredMigrations,
            DiagnosticsDatabaseMigrations.ALL.size,
        )

        // Count alone is not enough: a mis-ranged Migration(5, 7) would satisfy the size
        // check while leaving version 6 uncovered. Walk the sorted chain and assert each
        // migration steps exactly one version, contiguously from firstManagedVersion to
        // currentDbVersion, so any gap or overlap fails here.
        val sorted = DiagnosticsDatabaseMigrations.ALL.sortedBy { it.startVersion }
        var expected = firstManagedVersion
        for (migration in sorted) {
            assertEquals("Migration start must be contiguous", expected, migration.startVersion)
            assertEquals("Migration must step exactly one version", expected + 1, migration.endVersion)
            expected = migration.endVersion
        }
        assertEquals(
            "Migration chain must reach the current schema version",
            currentDbVersion,
            expected,
        )
    }

    /**
     * v0.1.0 shipped diagnostics schema 4. Upgrading such an install must migrate in place
     * through the production builder (no destructive fallback) and keep existing rows.
     */
    @Test
    fun `deployed version 4 database migrates to current schema and keeps telemetry rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "diagnostics-deployed-v4-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        schemaHelper.createDatabase(dbName, 4).use { legacyDb -> legacyDb.insertVersion4TelemetrySample() }

        val migrated = DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false)
        try {
            val sql = migrated.openHelper.writableDatabase
            assertEquals(DIAGNOSTICS_DB_VERSION, sql.version)
            sql
                .query(
                    "SELECT connectionState, txBytes, nativeHeapBytes, processRssBytes, " +
                        "relayProtocolKind, relayNativeEventsDropped " +
                        "FROM telemetry_samples WHERE id = 'v4-telemetry'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("connected", cursor.getString(0))
                    assertEquals(4096L, cursor.getLong(1))
                    assertTrue(cursor.isNull(2))
                    assertTrue(cursor.isNull(3))
                    assertTrue(cursor.isNull(4))
                    assertEquals(0L, cursor.getLong(5))
                }
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    /** The v4 -> v5 step must produce exactly the exported schema 5 (checked by Room). */
    @Test
    fun `migration 4 to 5 matches the exported version 5 schema`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "diagnostics-v4-v5-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        try {
            schemaHelper.createDatabase(dbName, 4).use { legacyDb -> legacyDb.insertVersion4TelemetrySample() }
            val migration = DiagnosticsDatabaseMigrations.ALL.single { it.startVersion == 4 }
            schemaHelper.runMigrationsAndValidate(dbName, 5, true, migration).use { db ->
                db.query("SELECT txBytes FROM telemetry_samples WHERE id = 'v4-telemetry'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(4096L, cursor.getLong(0))
                }
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * Pre-release schemas 1..3 (v0.0.8 and earlier) hold only disposable diagnostics data.
     * They must open on the current version through the scoped destructive path instead of
     * throwing "A migration from N to current was required but not found".
     */
    @Test
    fun `pre-release version 1 to 3 databases are recreated on the current schema`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (legacyVersion in 1..3) {
            val dbName = "diagnostics-deployed-v$legacyVersion-${System.nanoTime()}.db"
            context.deleteDatabase(dbName)
            schemaHelper.createDatabase(dbName, legacyVersion).use { legacyDb ->
                legacyDb.execSQL(
                    "INSERT INTO native_session_events (id, source, level, message, createdAt) " +
                        "VALUES ('legacy-v$legacyVersion', 'proxy', 'info', 'legacy', 1)",
                )
            }

            val reopened = DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false)
            try {
                val sql = reopened.openHelper.writableDatabase
                assertEquals(DIAGNOSTICS_DB_VERSION, sql.version)
                sql.query("SELECT COUNT(*) FROM native_session_events").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("v$legacyVersion rows are dropped", 0, cursor.getInt(0))
                }
                sql.query("SELECT COUNT(*) FROM home_diagnostics_runs").close()
            } finally {
                reopened.close()
                context.deleteDatabase(dbName)
            }
        }
    }

    @Test
    fun `migration 7 to 8 creates durable diagnostics state table without destructive fallback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "diagnostics-v7-v8-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)

        val seedDb =
            DiagnosticsDatabaseModule.buildDiagnosticsDatabase(
                context,
                dbName,
                allowDestructiveFallback = false,
            )
        try {
            seedDb.openHelper.writableDatabase
        } finally {
            seedDb.close()
        }

        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { legacyDb ->
            legacyDb.execSQL("DROP TABLE diagnostics_durable_state")
            legacyDb.execSQL("ALTER TABLE scan_sessions DROP COLUMN reportCompletionKind")
            legacyDb.execSQL("ALTER TABLE scan_sessions DROP COLUMN reportTerminationReason")
            legacyDb.dropStageTelemetryScope()
            legacyDb.dropRelayTraceColumns()
            legacyDb.execSQL("PRAGMA user_version = 7")
        }

        val migratedDb =
            DiagnosticsDatabaseModule.buildDiagnosticsDatabase(
                context,
                dbName,
                allowDestructiveFallback = false,
            )
        try {
            migratedDb.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM diagnostics_durable_state")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            migratedDb.openHelper.writableDatabase.query("SELECT COUNT(*) FROM scan_sessions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `migration 8 to 9 adds compact terminal report metadata without destructive fallback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "diagnostics-v8-v9-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)

        val seedDb =
            DiagnosticsDatabaseModule.buildDiagnosticsDatabase(
                context,
                dbName,
                allowDestructiveFallback = false,
            )
        try {
            seedDb.openHelper.writableDatabase
        } finally {
            seedDb.close()
        }

        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { legacyDb ->
            legacyDb.execSQL("ALTER TABLE scan_sessions DROP COLUMN reportCompletionKind")
            legacyDb.execSQL("ALTER TABLE scan_sessions DROP COLUMN reportTerminationReason")
            legacyDb.dropStageTelemetryScope()
            legacyDb.dropRelayTraceColumns()
            legacyDb.execSQL("PRAGMA user_version = 8")
        }

        val migratedDb =
            DiagnosticsDatabaseModule.buildDiagnosticsDatabase(
                context,
                dbName,
                allowDestructiveFallback = false,
            )
        try {
            migratedDb.openHelper.writableDatabase
                .query("PRAGMA table_info(scan_sessions)")
                .use { cursor ->
                    val columnNames =
                        buildSet {
                            val nameIndex = cursor.getColumnIndexOrThrow("name")
                            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                        }
                    assertTrue(columnNames.contains("reportCompletionKind"))
                    assertTrue(columnNames.contains("reportTerminationReason"))
                }
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `migration 9 to current preserves native event rows and initializes relay trace columns`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "diagnostics-v9-v10-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false).also { db ->
            db.openHelper.writableDatabase
            db.close()
        }

        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { legacyDb ->
            legacyDb.dropStageTelemetryScope()
            legacyDb.dropRelayTraceColumns()
            legacyDb.execSQL(
                "INSERT INTO native_session_events " +
                    "(id, sessionId, connectionSessionId, source, level, message, createdAt, runtimeId, mode, " +
                    "policySignature, fingerprintHash, subsystem) VALUES " +
                    "('legacy-event', NULL, 'connection-1', 'relay', 'warn', 'legacy', 123, '7', " +
                    "NULL, NULL, NULL, 'relay')",
            )
            legacyDb.execSQL("PRAGMA user_version = 9")
        }

        val migrated = DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false)
        try {
            migrated.openHelper.writableDatabase
                .query(
                    "SELECT id, attemptId, attemptSequence, stage, outcome, failureStage, carrierDisposition, " +
                        "healthAttemptId, relayProfileToken, relayTransport, relayTargetCategory, " +
                        "positiveEvidenceWatermark, relayHealthDecision, cooldownScope, cleanupReceipt " +
                        "FROM native_session_events WHERE id = 'legacy-event'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(
                        listOf(
                            "legacy-event",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                        ),
                        (0 until cursor.columnCount).map { index ->
                            if (cursor.isNull(index)) null else cursor.getString(index)
                        },
                    )
                }
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `deployed version 10 database migrates without destructive fallback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "diagnostics-deployed-v10-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false).also { db ->
            db.openHelper.writableDatabase
            db.close()
        }

        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { legacyDb ->
            legacyDb.dropRelayTraceColumns()
            legacyDb.execSQL(
                "INSERT INTO native_session_events " +
                    "(id, source, level, message, createdAt) VALUES " +
                    "('deployed-v10-event', 'relay', 'warn', 'legacy relay event', 42)",
            )
            legacyDb.execSQL(
                "UPDATE room_master_table SET identity_hash = " +
                    "'7d1612e550929370d50a06d5599258ff' WHERE id = 42",
            )
            legacyDb.execSQL("PRAGMA user_version = 10")
        }

        val migrated = DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false)
        try {
            migrated.openHelper.writableDatabase
                .query(
                    "SELECT id, attemptId, healthAttemptId, relayHealthDecision " +
                        "FROM native_session_events WHERE id = 'deployed-v10-event'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("deployed-v10-event", cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                    assertTrue(cursor.isNull(2))
                    assertTrue(cursor.isNull(3))
                }
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `deployed version 11 database migrates relay health fields without losing attempt trace`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "diagnostics-deployed-v11-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false).also { db ->
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO native_session_events " +
                    "(id, source, level, message, createdAt, attemptId, stage, outcome) VALUES " +
                    "('deployed-v11-event', 'relay', 'warn', 'legacy relay event', 42, 7, " +
                    "'reality_tls', 'failed')",
            )
            db.close()
        }

        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { legacyDb ->
            legacyDb.dropRelayHealthColumns()
            legacyDb.execSQL(
                "UPDATE room_master_table SET identity_hash = " +
                    "'6a1a2a8847bbeaa542e9148c1d09f4e5' WHERE id = 42",
            )
            legacyDb.execSQL("PRAGMA user_version = 11")
        }

        val migrated = DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false)
        try {
            migrated.openHelper.writableDatabase
                .query(
                    "SELECT attemptId, stage, outcome, healthAttemptId, relayHealthDecision " +
                        "FROM native_session_events WHERE id = 'deployed-v11-event'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(7L, cursor.getLong(0))
                    assertEquals("reality_tls", cursor.getString(1))
                    assertEquals("failed", cursor.getString(2))
                    assertTrue(cursor.isNull(3))
                    assertTrue(cursor.isNull(4))
                }
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `migration 12 to current creates home diagnostics run table outside scan sessions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "diagnostics-v12-v13-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false).also { db ->
            db.openHelper.writableDatabase
            db.close()
        }

        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { legacyDb ->
            legacyDb.execSQL("DROP INDEX IF EXISTS index_home_diagnostics_runs_updatedAt")
            legacyDb.execSQL("DROP TABLE IF EXISTS home_diagnostics_runs")
            legacyDb.execSQL("PRAGMA user_version = 12")
        }

        val migrated = DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false)
        try {
            migrated.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM home_diagnostics_runs")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            migrated.openHelper.writableDatabase
                .query("PRAGMA index_list(home_diagnostics_runs)")
                .use { cursor ->
                    val indexNames =
                        buildSet {
                            val nameIndex = cursor.getColumnIndexOrThrow("name")
                            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                        }
                    assertTrue(indexNames.contains("index_home_diagnostics_runs_updatedAt"))
                }
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `migration 12 to 13 indexes relay attempt hydration without losing events`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "diagnostics-v12-v13-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false).also { db ->
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO native_session_events " +
                    "(id, connectionSessionId, source, level, message, createdAt, runtimeId, subsystem, " +
                    "attemptId, attemptSequence, stage, outcome) VALUES " +
                    "('relay-attempt-v12', 'connection-1', 'relay', 'info', 'relay stage', 42, " +
                    "'runtime-1', 'relay', 7, 3, 'tcp_connect', 'succeeded')",
            )
            db.close()
        }

        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { legacyDb ->
            legacyDb.execSQL("DROP INDEX index_native_session_events_relay_attempt_sequence")
            legacyDb.execSQL("PRAGMA user_version = 12")
        }

        val migrated = DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false)
        try {
            migrated.openHelper.writableDatabase
                .query("SELECT attemptSequence FROM native_session_events WHERE id = 'relay-attempt-v12'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(3L, cursor.getLong(0))
                }
            migrated.openHelper.writableDatabase.query("PRAGMA index_list(native_session_events)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val indexes =
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                assertTrue(indexes.contains("index_native_session_events_relay_attempt_sequence"))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    /** Inserts one telemetry row using only the columns that exist in schema 4. */
    private fun SupportSQLiteDatabase.insertVersion4TelemetrySample() {
        execSQL(
            "INSERT INTO telemetry_samples " +
                "(id, connectionState, networkType, proxyRttBand, resolverRttBand, proxyRouteRetryCount, " +
                "tunnelRecoveryRetryCount, dnsFailuresTotal, resolverFallbackActive, proxyTelemetryState, " +
                "relayTelemetryState, warpTelemetryState, tunnelTelemetryState, txPackets, txBytes, " +
                "rxPackets, rxBytes, createdAt) VALUES " +
                "('v4-telemetry', 'connected', 'wifi', 'unknown', 'unknown', 0, 0, 0, 0, 'idle', 'idle', " +
                "'idle', 'idle', 8, 4096, 16, 8192, 1700000000000)",
        )
    }

    private fun android.database.sqlite.SQLiteDatabase.dropRelayTraceColumns() {
        dropRelayAttemptTraceColumns()
        dropRelayHealthColumns()
    }

    private fun android.database.sqlite.SQLiteDatabase.dropStageTelemetryScope() {
        execSQL("DROP INDEX index_telemetry_samples_diagnosticsRunId_diagnosticsStageKey_createdAt")
        execSQL("ALTER TABLE telemetry_samples DROP COLUMN diagnosticsRunId")
        execSQL("ALTER TABLE telemetry_samples DROP COLUMN diagnosticsStageKey")
    }

    private fun android.database.sqlite.SQLiteDatabase.dropRelayAttemptTraceColumns() {
        execSQL("DROP INDEX IF EXISTS index_native_session_events_relay_attempt_sequence")
        execSQL("ALTER TABLE telemetry_samples DROP COLUMN relayNativeEventsDropped")
        listOf(
            "attemptId",
            "attemptSequence",
            "stage",
            "outcome",
            "durationMs",
            "failureStage",
            "failureClass",
            "ioErrorKind",
            "osErrorCode",
            "peerClosePhase",
            "carrierDisposition",
        ).forEach { column -> execSQL("ALTER TABLE native_session_events DROP COLUMN $column") }
    }

    private fun android.database.sqlite.SQLiteDatabase.dropRelayHealthColumns() {
        listOf(
            "healthAttemptId",
            "relayProfileToken",
            "relayTransport",
            "relayTargetCategory",
            "positiveEvidenceWatermark",
            "relayHealthDecision",
            "cooldownScope",
            "cleanupReceipt",
        ).forEach { column -> execSQL("ALTER TABLE native_session_events DROP COLUMN $column") }
    }

    private companion object {
        /** Current schema version; migrationRegistryCoversVersionGap ties the chain end to @Database. */
        val DIAGNOSTICS_DB_VERSION: Int = DiagnosticsDatabaseMigrations.ALL.maxOf { it.endVersion }
    }
}
