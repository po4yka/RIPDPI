package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.data.rules.RuleRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuleEntityRoomTest {
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
    fun `insert and get round-trips all entity fields`() =
        runTest {
            val entity =
                RuleEntity(
                    id = 1L,
                    name = "test-rule",
                    userOrder = 10,
                    enabled = true,
                    domains = "example.com\ngoogle.com",
                    ipCidrs = "192.168.0.0/16\n10.0.0.0/8",
                    ports = "80\n443",
                    sourcePorts = "1024-65535",
                    network = RuleNetwork.TCP,
                    processName = "com.example.app",
                    packages = setOf("com.example.app", "com.other.app"),
                    outboundTag = OutboundTag.Proxy,
                )
            dao.insert(entity)

            val retrieved = dao.allRules().first()
            assertEquals(1, retrieved.size)
            val r = retrieved[0]
            assertEquals(1L, r.id)
            assertEquals("test-rule", r.name)
            assertEquals(10, r.userOrder)
            assertTrue(r.enabled)
            assertEquals("example.com\ngoogle.com", r.domains)
            assertEquals("192.168.0.0/16\n10.0.0.0/8", r.ipCidrs)
            assertEquals("80\n443", r.ports)
            assertEquals("1024-65535", r.sourcePorts)
            assertEquals(RuleNetwork.TCP, r.network)
            assertEquals("com.example.app", r.processName)
            assertEquals(setOf("com.example.app", "com.other.app"), r.packages)
            assertEquals(OutboundTag.Proxy, r.outboundTag)
        }

    @Test
    fun `packages Set converter round-trips empty and multi-value sets`() =
        runTest {
            val emptyPackages =
                RuleEntity(
                    id = 2L,
                    name = "empty-pkg",
                    userOrder = 0,
                    enabled = false,
                    packages = emptySet(),
                    outboundTag = OutboundTag.Bypass,
                )
            val multiPackages =
                RuleEntity(
                    id = 3L,
                    name = "multi-pkg",
                    userOrder = 1,
                    enabled = true,
                    packages = setOf("a.b.c", "d.e.f", "g.h.i"),
                    outboundTag = OutboundTag.Block,
                )
            dao.insert(emptyPackages)
            dao.insert(multiPackages)

            val all = dao.allRules().first().sortedBy { it.id }
            assertEquals(emptySet<String>(), all[0].packages)
            assertEquals(setOf("a.b.c", "d.e.f", "g.h.i"), all[1].packages)
        }

    @Test
    fun `outboundTag sentinel encoding round-trips all variants`() =
        runTest {
            val rules =
                listOf(
                    RuleEntity(
                        id = 10L,
                        name = "proxy",
                        userOrder = 0,
                        enabled = true,
                        outboundTag = OutboundTag.Proxy,
                    ),
                    RuleEntity(
                        id = 11L,
                        name = "bypass",
                        userOrder = 1,
                        enabled = true,
                        outboundTag = OutboundTag.Bypass,
                    ),
                    RuleEntity(
                        id = 12L,
                        name = "block",
                        userOrder = 2,
                        enabled = true,
                        outboundTag = OutboundTag.Block,
                    ),
                    RuleEntity(
                        id = 13L,
                        name = "profile",
                        userOrder = 3,
                        enabled = true,
                        outboundTag = OutboundTag.Profile(42L),
                    ),
                    RuleEntity(
                        id = 14L,
                        name = "group",
                        userOrder = 4,
                        enabled = true,
                        outboundTag = OutboundTag.Group(99L),
                    ),
                )
            rules.forEach { dao.insert(it) }

            val all = dao.allRules().first().sortedBy { it.id }
            assertEquals(OutboundTag.Proxy, all[0].outboundTag)
            assertEquals(OutboundTag.Bypass, all[1].outboundTag)
            assertEquals(OutboundTag.Block, all[2].outboundTag)
            assertEquals(OutboundTag.Profile(42L), all[3].outboundTag)
            assertEquals(OutboundTag.Group(99L), all[4].outboundTag)
        }

    @Test
    fun `allRules flow emits on insert, update, and delete`() =
        runTest {
            repository.allRules().test {
                assertEquals(emptyList<RuleEntity>(), awaitItem())

                repository.insert(
                    RuleEntity(id = 1L, name = "r1", userOrder = 0, enabled = true, outboundTag = OutboundTag.Proxy),
                )
                assertEquals(listOf("r1"), awaitItem().map { it.name })

                repository.update(
                    RuleEntity(
                        id = 1L,
                        name = "r1-updated",
                        userOrder = 0,
                        enabled = true,
                        outboundTag = OutboundTag.Bypass,
                    ),
                )
                assertEquals(listOf("r1-updated"), awaitItem().map { it.name })

                repository.delete(
                    RuleEntity(
                        id = 1L,
                        name = "r1-updated",
                        userOrder = 0,
                        enabled = true,
                        outboundTag = OutboundTag.Bypass,
                    ),
                )
                assertEquals(emptyList<RuleEntity>(), awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledRules returns only enabled rules`() =
        runTest {
            dao.insert(
                RuleEntity(id = 1L, name = "enabled", userOrder = 0, enabled = true, outboundTag = OutboundTag.Proxy),
            )
            dao.insert(
                RuleEntity(id = 2L, name = "disabled", userOrder = 1, enabled = false, outboundTag = OutboundTag.Proxy),
            )

            val enabled = dao.enabledRules().first()
            assertEquals(1, enabled.size)
            assertEquals("enabled", enabled[0].name)
        }

    @Test
    fun `reorder updates userOrder for each id`() =
        runTest {
            dao.insert(RuleEntity(id = 1L, name = "a", userOrder = 0, enabled = true, outboundTag = OutboundTag.Proxy))
            dao.insert(RuleEntity(id = 2L, name = "b", userOrder = 1, enabled = true, outboundTag = OutboundTag.Proxy))
            dao.insert(RuleEntity(id = 3L, name = "c", userOrder = 2, enabled = true, outboundTag = OutboundTag.Proxy))

            // reverse order: c=0, b=1, a=2
            repository.reorder(listOf(3L, 2L, 1L))

            val rules = dao.allRules().first()
            val byId = rules.associateBy { it.id }
            assertEquals(0, byId[3L]!!.userOrder)
            assertEquals(1, byId[2L]!!.userOrder)
            assertEquals(2, byId[1L]!!.userOrder)
        }

    @Test
    fun `resetOutboundTagForProfile bulk resets only matching scoped rules`() =
        runTest {
            val scopedProfile =
                RuleEntity(id = 1L, name = "profile-42", userOrder = 0, outboundTag = OutboundTag.Profile(42L))
            val otherProfile =
                RuleEntity(id = 2L, name = "profile-7", userOrder = 1, outboundTag = OutboundTag.Profile(7L))
            val alreadyProxy =
                RuleEntity(id = 3L, name = "proxy", userOrder = 2, outboundTag = OutboundTag.Proxy)
            val outsideScope =
                RuleEntity(id = 4L, name = "outside-scope", userOrder = 3, outboundTag = OutboundTag.Profile(42L))
            listOf(scopedProfile, otherProfile, alreadyProxy, outsideScope).forEach { dao.insert(it) }

            repository.resetOutboundTagForProfile(
                profileId = 42L,
                rules = listOf(scopedProfile, otherProfile, alreadyProxy),
            )

            val byId = dao.allRules().first().associateBy { it.id }
            assertEquals(OutboundTag.Proxy, byId[1L]!!.outboundTag)
            assertEquals(OutboundTag.Profile(7L), byId[2L]!!.outboundTag)
            assertEquals(OutboundTag.Proxy, byId[3L]!!.outboundTag)
            assertEquals(OutboundTag.Profile(42L), byId[4L]!!.outboundTag)
        }

    @Test
    fun `resetOutboundTagForGroup bulk resets only matching scoped rules`() =
        runTest {
            val scopedGroup = RuleEntity(id = 1L, name = "group-9", userOrder = 0, outboundTag = OutboundTag.Group(9L))
            val otherGroup = RuleEntity(id = 2L, name = "group-8", userOrder = 1, outboundTag = OutboundTag.Group(8L))
            val profileSameId =
                RuleEntity(id = 3L, name = "profile-9", userOrder = 2, outboundTag = OutboundTag.Profile(9L))
            val outsideScope =
                RuleEntity(id = 4L, name = "outside-scope", userOrder = 3, outboundTag = OutboundTag.Group(9L))
            listOf(scopedGroup, otherGroup, profileSameId, outsideScope).forEach { dao.insert(it) }

            repository.resetOutboundTagForGroup(
                groupId = 9L,
                rules = listOf(scopedGroup, otherGroup, profileSameId),
            )

            val byId = dao.allRules().first().associateBy { it.id }
            assertEquals(OutboundTag.Proxy, byId[1L]!!.outboundTag)
            assertEquals(OutboundTag.Group(8L), byId[2L]!!.outboundTag)
            assertEquals(OutboundTag.Profile(9L), byId[3L]!!.outboundTag)
            assertEquals(OutboundTag.Group(9L), byId[4L]!!.outboundTag)
        }

    @Test
    fun `fresh database seeds bypass-LAN and bypass-loopback rules`() =
        runTest {
            // Seeded rules are inserted in onCreate callback; in-memory DB runs callback on first open
            val context = ApplicationProvider.getApplicationContext<Context>()
            val seededDb =
                Room
                    .inMemoryDatabaseBuilder(context, RipDpiDatabase::class.java)
                    .allowMainThreadQueries()
                    .addCallback(RipDpiDatabase.SeedCallback)
                    .build()
            try {
                val rules =
                    seededDb
                        .ruleDao()
                        .allRules()
                        .first()
                        .associateBy { it.name }
                assertEquals(
                    listOf("127.0.0.0/8", "::1/128"),
                    rules
                        .getValue("Bypass loopback")
                        .ipCidrs
                        .lineSequence()
                        .toList(),
                )
                assertEquals(
                    listOf("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "169.254.0.0/16", "fc00::/7"),
                    rules
                        .getValue("Bypass LAN")
                        .ipCidrs
                        .lineSequence()
                        .toList(),
                )
            } finally {
                seededDb.close()
            }
        }

    @Test
    fun `opening database repairs only exact malformed legacy seed CIDRs`() =
        runTest {
            dao.insert(
                RuleEntity(
                    id = 1L,
                    name = "Bypass loopback",
                    ipCidrs = "127.0.0.0/8\\n::1/128",
                    outboundTag = OutboundTag.Bypass,
                ),
            )
            dao.insert(
                RuleEntity(
                    id = 2L,
                    name = "Bypass LAN",
                    userOrder = 1,
                    ipCidrs = "192.168.0.0/16\\n10.0.0.0/8\\n172.16.0.0/12\\n169.254.0.0/16\\nfc00::/7",
                    outboundTag = OutboundTag.Bypass,
                ),
            )
            dao.insert(
                RuleEntity(
                    id = 3L,
                    name = "User rule",
                    userOrder = 2,
                    ipCidrs = "10.0.0.0/8\\n192.168.0.0/16",
                    outboundTag = OutboundTag.Bypass,
                ),
            )

            RipDpiDatabase.SeedCallback.onOpen(db.openHelper.writableDatabase)

            val rules = dao.allRules().first().associateBy { it.id }
            assertEquals("127.0.0.0/8\n::1/128", rules.getValue(1L).ipCidrs)
            assertEquals(
                "192.168.0.0/16\n10.0.0.0/8\n172.16.0.0/12\n169.254.0.0/16\nfc00::/7",
                rules.getValue(2L).ipCidrs,
            )
            assertEquals("10.0.0.0/8\\n192.168.0.0/16", rules.getValue(3L).ipCidrs)
        }

    @Test
    fun `network enum round-trips all values`() =
        runTest {
            val entities =
                listOf(
                    RuleEntity(
                        id = 20L,
                        name = "tcp",
                        userOrder = 0,
                        enabled = true,
                        network = RuleNetwork.TCP,
                        outboundTag = OutboundTag.Proxy,
                    ),
                    RuleEntity(
                        id = 21L,
                        name = "udp",
                        userOrder = 1,
                        enabled = true,
                        network = RuleNetwork.UDP,
                        outboundTag = OutboundTag.Proxy,
                    ),
                    RuleEntity(
                        id = 22L,
                        name = "both",
                        userOrder = 2,
                        enabled = true,
                        network = RuleNetwork.BOTH,
                        outboundTag = OutboundTag.Proxy,
                    ),
                )
            entities.forEach { dao.insert(it) }

            val all = dao.allRules().first().sortedBy { it.id }
            assertEquals(RuleNetwork.TCP, all[0].network)
            assertEquals(RuleNetwork.UDP, all[1].network)
            assertEquals(RuleNetwork.BOTH, all[2].network)
        }
}
