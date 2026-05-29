package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.rules.DomainBypassList
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DomainBypassRepositoryTest {
    private lateinit var db: RipDpiDatabase
    private lateinit var dao: RuleDao
    private lateinit var repository: RuleRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, RipDpiDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.ruleDao()
        repository = RuleRepository(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `saveDomainBypassList inserts a single managed rule and observes it`() =
        runTest {
            repository.saveDomainBypassList("bank.ru\n.gov.ru")

            val bypass = repository.domainBypassRule().first()
            requireNotNull(bypass)
            assertEquals(DomainBypassList.ManagedBypassRuleName, bypass.name)
            assertEquals(OutboundTag.Bypass, bypass.outboundTag)
            assertEquals("bank.ru\ndomain_suffix:.gov.ru", bypass.domains)
        }

    @Test
    fun `saving twice updates in place rather than duplicating`() =
        runTest {
            repository.saveDomainBypassList("a.com")
            repository.saveDomainBypassList("a.com\nb.com")

            val matches = dao.rulesByName(DomainBypassList.ManagedBypassRuleName).first()
            assertEquals(1, matches.size)
            assertEquals("a.com\nb.com", matches[0].domains)
        }

    @Test
    fun `empty list deletes the managed rule`() =
        runTest {
            repository.saveDomainBypassList("a.com")
            repository.saveDomainBypassList("   \n\n")

            assertNull(repository.domainBypassRule().first())
        }

    @Test
    fun `managed rule is excluded from userRules but other rules appear`() =
        runTest {
            dao.insert(RuleEntity(name = "user-rule", userOrder = 0, outboundTag = OutboundTag.Proxy))
            repository.saveDomainBypassList("a.com")

            val userRules = repository.userRules().first()
            assertEquals(listOf("user-rule"), userRules.map { it.name })
        }

    @Test
    fun `saving the bypass list does not reorder user rules`() =
        runTest {
            dao.insert(RuleEntity(name = "first", userOrder = 0, outboundTag = OutboundTag.Proxy))
            dao.insert(RuleEntity(name = "second", userOrder = 1, outboundTag = OutboundTag.Proxy))

            repository.saveDomainBypassList("a.com")
            repository.saveDomainBypassList("a.com\nb.com")

            val userRules = repository.userRules().first()
            assertEquals(listOf("first", "second"), userRules.map { it.name })
            assertEquals(listOf(0, 1), userRules.map { it.userOrder })
        }

    @Test
    fun `moveDomainBypassListToEditor renames and appends to end of user order`() =
        runTest {
            dao.insert(RuleEntity(name = "existing", userOrder = 0, outboundTag = OutboundTag.Proxy))
            repository.saveDomainBypassList("a.com")

            val moved = repository.moveDomainBypassListToEditor("My bypass rule")
            assertTrue(moved)

            assertNull(repository.domainBypassRule().first())
            val userRules = repository.userRules().first()
            assertEquals(listOf("existing", "My bypass rule"), userRules.map { it.name })
            assertEquals(1, userRules.first { it.name == "My bypass rule" }.userOrder)
        }

    @Test
    fun `moveDomainBypassListToEditor returns false when no bypass rule exists`() =
        runTest {
            assertFalse(repository.moveDomainBypassListToEditor("anything"))
        }
}
