package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import com.poyka.ripdpi.data.rules.RipDpiDatabaseMigrations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates that [RipDpiDatabase] opens at its current schema version, that the
 * version-gap migration registry is complete, and that the v1 -> v2 migration
 * (which adds the `awg_profiles` standalone-AmneziaWG store) preserves the
 * existing `routing_rules` data.
 *
 * When the database version is bumped, append a `Migration(N-1, N)` to
 * [RipDpiDatabaseMigrations.ALL] -- the `migration registry covers the version gap`
 * guard below reds otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RipDpiDatabaseMigrationTest {
    private val currentDbVersion = 2

    @Test
    fun `schema version 2 opens successfully and both tables exist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db =
            Room
                .inMemoryDatabaseBuilder(context, RipDpiDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val supportDb = db.openHelper.writableDatabase
            assertTrue("routing_rules table must exist", supportDb.tableExists("routing_rules"))
            assertTrue("awg_profiles table must exist", supportDb.tableExists("awg_profiles"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration registry covers the version gap`() {
        // Each managed version above v1 must have exactly one Migration(N-1, N).
        assertEquals(
            "RipDpiDatabaseMigrations.ALL must hold one Migration per version bump above v1; " +
                "a version bump without a matching migration object will red this guard.",
            currentDbVersion - 1,
            RipDpiDatabaseMigrations.ALL.size,
        )
    }

    @Test
    fun `migration 1 to 2 adds awg_profiles and preserves routing_rules`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-1-to-2-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)

        // Build a real on-disk v1 database by hand: the v1 routing_rules table plus
        // Room's master table at version 1, then seed a rule.
        val helper = openHelper(context, dbName, version = 1)
        try {
            helper.writableDatabase.apply {
                execSQL(
                    "CREATE TABLE IF NOT EXISTS `routing_rules` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`userOrder` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `domains` TEXT NOT NULL, " +
                        "`ipCidrs` TEXT NOT NULL, `ports` TEXT NOT NULL, `sourcePorts` TEXT NOT NULL, " +
                        "`network` TEXT NOT NULL, `processName` TEXT NOT NULL, `packages` TEXT NOT NULL, " +
                        "`outboundTag` TEXT NOT NULL)",
                )
                execSQL(
                    "INSERT INTO routing_rules " +
                        "(id, name, userOrder, enabled, domains, ipCidrs, ports, sourcePorts, " +
                        "network, processName, packages, outboundTag) VALUES " +
                        "(100, 'pre-migration', 0, 1, '', '', '', '', 'BOTH', '', '', '0:0')",
                )
                // Run the REAL v1 -> v2 migration body against this database.
                RipDpiDatabaseMigrations.MIGRATION_1_2.migrate(this)

                // The pre-migration rule survives the additive migration.
                query("SELECT name FROM routing_rules WHERE id = 100").use { cursor ->
                    assertTrue("pre-migration rule must survive the migration", cursor.moveToFirst())
                    assertEquals("pre-migration", cursor.getString(0))
                }
                // The new awg_profiles table is queryable and empty.
                query("SELECT COUNT(*) FROM awg_profiles").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `fresh database contains seeded bypass-loopback and bypass-LAN rules`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db =
                Room
                    .inMemoryDatabaseBuilder(context, RipDpiDatabase::class.java)
                    .allowMainThreadQueries()
                    .addCallback(RipDpiDatabase.SeedCallback)
                    .build()
            try {
                val rules = db.ruleDao().allRules().first()
                val names = rules.map { it.name }
                assertTrue(
                    "Expected 'Bypass loopback' seed rule, found: $names",
                    names.any { it.contains("loopback", ignoreCase = true) },
                )
                assertTrue(
                    "Expected 'Bypass LAN' seed rule, found: $names",
                    names.any { it.contains("LAN", ignoreCase = true) },
                )
            } finally {
                db.close()
            }
        }

    private fun openHelper(
        context: Context,
        dbName: String,
        version: Int,
    ): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build(),
        )

    private fun SupportSQLiteDatabase.tableExists(name: String): Boolean =
        query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf<Any>(name),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0) == 1
        }
}
