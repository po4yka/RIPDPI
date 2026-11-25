package com.poyka.ripdpi.data.diagnostics

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DiagnosticsDatabaseMigrations {
    val DESTRUCTIVE_FROM_VERSIONS: IntArray = intArrayOf(1, 2, 3)

    val ALL: Array<Migration> =
        arrayOf(
            migration4To5,
            migration5To6,
            migration6To7,
            migration7To8,
            migration8To9,
            migration9To10,
            migration10To11,
            migration11To12,
            migration12To13,
            migration13To14,
            migration14To15,
            migration15To16,
        )
}

private val migration4To5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE telemetry_samples ADD COLUMN nativeHeapBytes INTEGER")
            db.execSQL("ALTER TABLE telemetry_samples ADD COLUMN processRssBytes INTEGER")
        }
    }

private val migration5To6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE telemetry_samples ADD COLUMN relayProtocolKind TEXT",
            )
        }
    }

private val migration6To7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE remembered_network_policies ADD COLUMN connectionConcurrencyPolicyJson TEXT")
        }
    }

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

private val migration8To9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE scan_sessions ADD COLUMN reportCompletionKind TEXT")
            db.execSQL("ALTER TABLE scan_sessions ADD COLUMN reportTerminationReason TEXT")
        }
    }

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

private val migration12To13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS home_diagnostics_runs (
                    runId TEXT NOT NULL,
                    origin TEXT NOT NULL,
                    status TEXT NOT NULL,
                    payloadVersion INTEGER NOT NULL,
                    payloadJson TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(runId)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_home_diagnostics_runs_updatedAt
                ON home_diagnostics_runs(updatedAt)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_native_session_events_relay_attempt_sequence
                ON native_session_events(
                    connectionSessionId,
                    runtimeId,
                    attemptId,
                    attemptSequence,
                    createdAt
                )
                """.trimIndent(),
            )
        }
    }

private val migration13To14 =
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            var afterId: String? = null
            while (true) {
                val rows =
                    db
                        .query(
                            "SELECT id, payloadJson FROM network_snapshots " +
                                "WHERE (? IS NULL OR id > ?) ORDER BY id LIMIT 128",
                            arrayOf(afterId, afterId),
                        ).use { cursor ->
                            buildList {
                                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                            }
                        }
                if (rows.isEmpty()) break
                rows.forEach { (id, payloadJson) ->
                    val redacted = redactLegacyNetworkSnapshot(payloadJson)
                    if (redacted == null) {
                        db.execSQL("DELETE FROM network_snapshots WHERE id = ?", arrayOf(id))
                    } else {
                        db.execSQL("UPDATE network_snapshots SET payloadJson = ? WHERE id = ?", arrayOf(redacted, id))
                    }
                }
                afterId = rows.last().first
            }
        }
    }

private val migration14To15 =
    object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE scan_sessions ADD COLUMN networkScopeKey TEXT")
        }
    }

private val migration15To16 =
    object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf("networkTransport", "matrixTargetSetRevision", "matrixProbeRevision", "matrixConfigRevision")
                .forEach { column -> db.execSQL("ALTER TABLE scan_sessions ADD COLUMN $column TEXT") }
        }
    }
