package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates that [RipDpiDatabase] at schema version 1 can be opened, that the
 * routing_rules table exists, and that the two seeded default rules are present.
 *
 * When the database version is bumped, add a new test method that verifies
 * the incremental migration using [androidx.room.testing.MigrationTestHelper].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RipDpiDatabaseMigrationTest {
    @Test
    fun `schema version 1 opens successfully and routing_rules table exists`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db =
            Room
                .inMemoryDatabaseBuilder(context, RipDpiDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            // Force open the database to trigger schema creation
            val supportDb = db.openHelper.writableDatabase
            val cursor =
                supportDb.query(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='routing_rules'",
                )
            val tableExists =
                cursor.use { c ->
                    c.moveToFirst()
                    c.getInt(0) == 1
                }
            assertTrue("routing_rules table must exist at schema version 1", tableExists)
        } finally {
            db.close()
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
}
