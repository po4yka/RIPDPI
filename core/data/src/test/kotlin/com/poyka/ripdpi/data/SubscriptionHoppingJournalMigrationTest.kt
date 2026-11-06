package com.poyka.ripdpi.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionHoppingJournalMigrationTest : RoomProfileTest() {
    @Test
    fun `pending hopping decoding preserves raw fields without rewriting encrypted source`() =
        runTest {
            val fixture = LegacyJournalDecoderFixture()
            val pending = fixture.seed(LegacyHoppingPayload)
            val accepted = checkNotNull(fixture.reader.read())
            val group = (accepted.intent as SubscriptionRefreshIntent).groups.single()
            assertEquals(
                Hysteria2PortHopping("20000-21000", 45_000),
                (group.members.single() as ProxyProfile.Hysteria2).portHopping,
            )
            assertEquals("captured-owner", group.ownershipGeneration)
            assertEquals(pending.copy(payload = accepted.pending.payload), accepted.pending)
            assertTrue(accepted.pending.payload.contains("futurePayload"))
            assertFalse(accepted.pending.payload.contains("portHopPorts"))
            assertEquals(accepted, fixture.reader.read())
            assertEquals(pending, fixture.journal.pending())
        }

    @Test
    fun `failed Room import preserves hopping source and retry commits converted policy`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                legacy.seedPayload(ProfileMutationFamily.Subscription, LegacyHoppingPayload)
                val source = legacy.legacyBytes()
                val before = legacy.fixture.snapshot()
                legacy.fixture.failWrite("system")
                assertTrue(runCatching { legacy.fixture.initialize() }.isFailure)
                assertEquals(source, legacy.legacyBytes())
                assertEquals(before, legacy.fixture.snapshot())
                legacy.fixture.clearFaults()
                legacy.fixture.initialize()
                val group =
                    legacy.fixture.groupCatalog
                        .snapshot()
                        .single()
                assertEquals(
                    Hysteria2PortHopping("20000-21000", 45_000),
                    (group.members.single() as ProxyProfile.Hysteria2).portHopping,
                )
                val committed = legacy.fixture.snapshot()
                legacy.fixture.reopen()
                assertEquals(committed, legacy.fixture.snapshot())
            }
        }

    @Test
    fun `invalid pending hopping cannot import a scalar profile or discard source`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                legacy.seedPayload(ProfileMutationFamily.Subscription, LegacyHoppingPayload.replace("45s", "4s"))
                val source = legacy.legacyBytes()
                val before = legacy.fixture.snapshot()
                assertTrue(runCatching { legacy.fixture.initialize() }.isFailure)
                assertEquals(source, legacy.legacyBytes())
                assertEquals(before, legacy.fixture.snapshot())
            }
        }
}

private val LegacyHoppingPayload =
    """
    {"type":"subscription_refresh","ownershipVersion":1,"awgProfiles":[],"futurePayload":true,
    "groups":[{"id":"group","name":"Group","type":"SUBSCRIPTION","order":0,"isSelector":true,
    "ownershipGeneration":"captured-owner","subscription":{"link":"https://subscription.example/config"},
    "members":[{"type":"hysteria2","id":"hy","displayName":"Hysteria","groupId":"group",
    "server":"hopping.example","serverPort":443,"password":"fixture",
    "portHopPorts":"20000:21000","portHopInterval":"45s"}]}]}
    """.trimIndent()
