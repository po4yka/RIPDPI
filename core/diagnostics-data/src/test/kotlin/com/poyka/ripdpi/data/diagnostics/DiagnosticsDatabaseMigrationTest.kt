package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsDatabaseMigrationTest {
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
     * schema version above 5 (the first version under migration management). When a developer
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

        val firstManagedVersion = 5
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
    fun `migration 9 to 10 preserves native event rows and initializes relay trace columns`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "diagnostics-v9-v10-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false).also { db ->
            db.openHelper.writableDatabase
            db.close()
        }

        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { legacyDb ->
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

    private fun android.database.sqlite.SQLiteDatabase.dropRelayTraceColumns() {
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
}
