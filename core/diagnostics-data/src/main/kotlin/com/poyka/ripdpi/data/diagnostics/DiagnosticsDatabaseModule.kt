package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsDatabaseModule {
    @Provides
    @Singleton
    fun provideDiagnosticsDatabase(
        @ApplicationContext context: Context,
    ): DiagnosticsDatabase =
        Room
            .databaseBuilder(
                context,
                DiagnosticsDatabase::class.java,
                "diagnostics.db",
            ).fallbackToDestructiveMigration(true)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .addCallback(TtlCleanupCallback)
            .build()

    @Provides
    @Singleton
    fun provideDiagnosticsDao(database: DiagnosticsDatabase): DiagnosticsDao = database.diagnosticsDao()

    /**
     * Purges stale sensitive telemetry on every database open to limit the
     * plaintext exposure window.  Two retention tiers:
     *  - 7 days for high-frequency telemetry (samples, snapshots, events)
     *  - 30 days for session / policy data
     */
    private object TtlCleanupCallback : RoomDatabase.Callback() {
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            val now = System.currentTimeMillis()

            // High-frequency telemetry -- 7-day TTL
            db.execSQL(
                "DELETE FROM telemetry_samples WHERE createdAt < ?",
                arrayOf(now - SEVEN_DAYS_MS),
            )
            db.execSQL(
                "DELETE FROM network_snapshots WHERE capturedAt < ?",
                arrayOf(now - SEVEN_DAYS_MS),
            )
            db.execSQL(
                "DELETE FROM diagnostic_context_snapshots WHERE capturedAt < ?",
                arrayOf(now - SEVEN_DAYS_MS),
            )
            db.execSQL(
                "DELETE FROM native_session_events WHERE createdAt < ?",
                arrayOf(now - SEVEN_DAYS_MS),
            )
            db.execSQL(
                "DELETE FROM probe_results WHERE createdAt < ?",
                arrayOf(now - SEVEN_DAYS_MS),
            )
            db.execSQL(
                "DELETE FROM export_records WHERE createdAt < ?",
                arrayOf(now - SEVEN_DAYS_MS),
            )

            // Session / policy data -- 30-day TTL
            db.execSQL(
                "DELETE FROM scan_sessions WHERE finishedAt IS NOT NULL AND finishedAt < ?",
                arrayOf(now - THIRTY_DAYS_MS),
            )
            db.execSQL(
                "DELETE FROM bypass_usage_sessions WHERE finishedAt IS NOT NULL AND finishedAt < ?",
                arrayOf(now - THIRTY_DAYS_MS),
            )
            db.execSQL(
                "DELETE FROM remembered_network_policies WHERE updatedAt < ?",
                arrayOf(now - THIRTY_DAYS_MS),
            )
            db.execSQL(
                "DELETE FROM network_dns_path_preferences WHERE updatedAt < ?",
                arrayOf(now - THIRTY_DAYS_MS),
            )
            db.execSQL(
                "DELETE FROM network_dns_blocked_paths WHERE updatedAt < ?",
                arrayOf(now - THIRTY_DAYS_MS),
            )
            db.execSQL(
                "DELETE FROM network_edge_preferences WHERE updatedAt < ?",
                arrayOf(now - THIRTY_DAYS_MS),
            )
        }
    }
}
