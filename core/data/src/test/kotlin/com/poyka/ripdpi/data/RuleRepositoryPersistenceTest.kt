package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.data.rules.RuleRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuleRepositoryPersistenceTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val databaseName = "routing-persistence.db"

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `edited rules and order survive reopen without restoring deleted seeds`() =
        runTest {
            val first =
                RuleEntity(
                    id = 10L,
                    name = "Full rule",
                    domains = "example.org\nexample.net",
                    ipCidrs = "192.0.2.0/24\n2001:db8::/32",
                    ports = "443\n8443",
                    sourcePorts = "1024-65535",
                    network = RuleNetwork.TCP,
                    processName = "example.process",
                    packages = setOf("org.example.first", "org.example.second"),
                    outboundTag = OutboundTag.Profile(7L),
                )
            val second = RuleEntity(id = 11L, name = "Second", outboundTag = OutboundTag.Group(9L))
            val edited = first.copy(enabled = false, outboundTag = OutboundTag.Block, userOrder = 1)
            withDatabase { db ->
                val dao = db.ruleDao()
                assertEquals(2, dao.allRules().first().size)
                dao.deleteAll()
                val repository = RuleRepository(dao)
                repository.insert(first)
                repository.insert(second)
                repository.update(edited)
                repository.reorder(listOf(second.id, first.id))
            }

            withDatabase {
                val repository = RuleRepository(it.ruleDao())
                assertEquals(listOf(second, edited), repository.allRules().first())
                assertEquals(listOf(second), repository.enabledRules().first())
                repository.delete(second)
            }
            withDatabase { assertEquals(listOf(edited), it.ruleDao().allRules().first()) }
        }

    @Test
    fun `profile reset survives reopen and preserves group with same id`() =
        runTest {
            val profile = RuleEntity(id = 10L, name = "Profile", outboundTag = OutboundTag.Profile(7L))
            val group = RuleEntity(id = 11L, name = "Group", userOrder = 1, outboundTag = OutboundTag.Group(7L))
            withDatabase {
                it.ruleDao().replaceAll(listOf(profile, group))
                RuleRepository(it.ruleDao()).resetOutboundTagForProfile(7L, listOf(profile, group))
            }

            withDatabase {
                assertEquals(
                    listOf(profile.copy(outboundTag = OutboundTag.Proxy), group),
                    it.ruleDao().allRules().first(),
                )
            }
        }

    @Test
    fun `failed bulk replacement rolls back deleted and inserted rows on disk`() =
        runTest {
            val original = RuleEntity(id = 10L, name = "Original", domains = "example.org")
            withDatabase { db ->
                val dao = db.ruleDao()
                dao.replaceAll(listOf(original))
                db.openHelper.writableDatabase.execSQL(
                    """
                    CREATE TRIGGER reject_rule BEFORE INSERT ON routing_rules
                    WHEN NEW.name = 'Rejected'
                    BEGIN SELECT RAISE(ABORT, 'Rejected rule'); END
                    """.trimIndent(),
                )
                val failure =
                    runCatching {
                        dao.replaceAll(
                            listOf(
                                RuleEntity(id = 11L, name = "Inserted before failure"),
                                RuleEntity(id = 12L, name = "Rejected"),
                            ),
                        )
                    }.exceptionOrNull()
                assertTrue(failure is android.database.sqlite.SQLiteException)
                assertEquals(listOf(original), dao.allRules().first())
            }

            withDatabase { assertEquals(listOf(original), it.ruleDao().allRules().first()) }
        }

    @Test
    fun `invalid bypass draft preserves stored list and explicit empty save deletes it`() =
        runTest {
            withDatabase {
                val repository = RuleRepository(it.ruleDao())
                repository.saveDomainBypassList("example.org\n192.0.2.0/24")
            }
            withDatabase {
                val repository = RuleRepository(it.ruleDao())
                val before = repository.domainBypassRule().first()
                assertTrue(before != null)
                val invalid = repository.saveDomainBypassList("not a domain or address!")
                assertFalse(invalid.errors.isEmpty())
                assertEquals(before, repository.domainBypassRule().first())
            }
            withDatabase {
                val repository = RuleRepository(it.ruleDao())
                assertTrue(repository.domainBypassRule().first() != null)
                repository.saveDomainBypassList("")
            }
            withDatabase { assertEquals(null, RuleRepository(it.ruleDao()).domainBypassRule().first()) }
        }

    private suspend fun withDatabase(block: suspend (RipDpiDatabase) -> Unit) {
        val database =
            Room
                .databaseBuilder(context, RipDpiDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .addCallback(RipDpiDatabase.SeedCallback)
                .build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}
