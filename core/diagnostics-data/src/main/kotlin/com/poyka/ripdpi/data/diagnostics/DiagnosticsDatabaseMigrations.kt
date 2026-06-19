package com.poyka.ripdpi.data.diagnostics

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DiagnosticsDatabaseMigrations {
    /** All migrations, in order. Append new Migration(N, N+1) objects here when the schema version bumps. */
    val ALL: Array<Migration> =
        arrayOf(
            migration5To6,
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
