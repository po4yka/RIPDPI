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
