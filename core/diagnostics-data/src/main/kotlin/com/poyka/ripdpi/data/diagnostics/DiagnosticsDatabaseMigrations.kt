package com.poyka.ripdpi.data.diagnostics

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DiagnosticsDatabaseMigrations {
    /** All migrations, in order. Append new Migration(N, N+1) objects here when the schema version bumps. */
    val ALL: Array<Migration> =
        arrayOf(
            migration5To6,
            migration6To7,
            migration7To8,
            migration8To9,
            migration9To10,
            migration10To11,
            migration11To12,
        )
}

/**
 * v5 → v6: add `relayProtocolKind` column to `telemetry_samples`.
 * The column is nullable with no default — existing rows will have NULL,
 * which the Kotlin entity decodes as `null` (the field default).
 */
private val migration5To6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE telemetry_samples ADD COLUMN relayProtocolKind TEXT",
            )
        }
    }

/** v6 → v7: persist the confirmed per-network TLS profile/concurrency policy. */
private val migration6To7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE remembered_network_policies ADD COLUMN connectionConcurrencyPolicyJson TEXT")
        }
    }

/** v7 → v8: add repository-owned durable diagnostics state for process-death ledgers. */
private val migration7To8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS diagnostics_durable_state (
                    `key` TEXT NOT NULL,
                    value TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent(),
            )
        }
    }

/** v8 → v9: retain terminal report state when a large report JSON cannot be read inline. */
private val migration8To9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE scan_sessions ADD COLUMN reportCompletionKind TEXT")
            db.execSQL("ALTER TABLE scan_sessions ADD COLUMN reportTerminationReason TEXT")
        }
    }

/** v9 → v10: correlate runtime telemetry samples with a home diagnostics run and stage. */
private val migration9To10 =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE telemetry_samples ADD COLUMN diagnosticsRunId TEXT")
            db.execSQL("ALTER TABLE telemetry_samples ADD COLUMN diagnosticsStageKey TEXT")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_telemetry_samples_diagnosticsRunId_diagnosticsStageKey_createdAt
                ON telemetry_samples(diagnosticsRunId, diagnosticsStageKey, createdAt)
                """.trimIndent(),
            )
        }
    }

/** v10 → v11: persist privacy-safe VLESS/Reality relay attempt stage evidence. */
private val migration10To11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE telemetry_samples ADD COLUMN relayNativeEventsDropped INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN attemptId INTEGER")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN attemptSequence INTEGER")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN stage TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN outcome TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN durationMs INTEGER")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN failureStage TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN failureClass TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN ioErrorKind TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN osErrorCode INTEGER")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN peerClosePhase TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN carrierDisposition TEXT")
        }
    }

/** v11 → v12: persist privacy-safe relay-health decisions and cleanup provenance. */
private val migration11To12 =
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN healthAttemptId TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN relayProfileToken TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN relayTransport TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN relayTargetCategory TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN positiveEvidenceWatermark INTEGER")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN relayHealthDecision TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN cooldownScope TEXT")
            db.execSQL("ALTER TABLE native_session_events ADD COLUMN cleanupReceipt TEXT")
        }
    }
