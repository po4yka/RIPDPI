package com.poyka.ripdpi.data.rules

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * All [RipDpiDatabase] migrations, in order. Append a new `Migration(N, N+1)`
 * object here on every schema version bump -- the version-gap guard test
 * (`RipDpiDatabaseMigrationTest`) asserts `ALL.size == currentVersion - 1`, so a
 * bump without a matching migration reds CI.
 */
internal object RipDpiDatabaseMigrations {
    /**
     * v1 -> v2: adds the standalone-AmneziaWG profile store (`awg_profiles`).
     *
     * Additive: the existing `routing_rules` table is untouched, so v1 databases
     * keep every user rule. The CREATE statement mirrors the Room-generated DDL
     * for [com.poyka.ripdpi.data.awg.AwgProfileEntity] exactly (column order,
     * affinities, NOT NULL, primary key) so the post-migration identity hash
     * matches the freshly-created schema.
     */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `awg_profiles` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`requestJson` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

    /** All migrations, in order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
