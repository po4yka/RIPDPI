package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    }
}
