package com.poyka.ripdpi.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionHoppingJournalMigrationTest {
    @Test
    fun `pending hopping after image is durably converted once before replay`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val pending = seed(fixture)
            var writes = 0
            fixture.journal.beforeReplace = { writes++ }
            val reader = ProfileMutationJournalRecoveryReader(fixture.journal, ProxyGroupCatalog(fixture.groupBlob))
            val accepted = checkNotNull(reader.read())
            val group = (accepted.intent as SubscriptionRefreshIntent).groups.single()
            assertEquals(
                Hysteria2PortHopping("20000-21000", 45_000),
                (group.members.single() as ProxyProfile.Hysteria2).portHopping,
            )
            assertEquals("captured-owner", group.ownershipGeneration)
            assertEquals(pending.copy(payload = accepted.pending.payload), accepted.pending)
            assertTrue(accepted.pending.payload.contains("futurePayload"))
            assertFalse(accepted.pending.payload.contains("portHopPorts"))
            assertEquals(accepted, reader.read())
            assertEquals(1, writes)
            assertNoReplay(fixture)
        }

    @Test
    fun `failed journal conversion preserves old bytes and prevents every replay store`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val pending = seed(fixture)
            fixture.journal.beforeReplace = { error("Journal commit failed") }
            assertTrue(runCatching { fixture.coordinator().recover() }.isFailure)
            assertEquals(pending, fixture.journal.pending())
            assertNoReplay(fixture)
            fixture.journal.beforeReplace = null
            val catalog = ProxyGroupCatalog(fixture.groupBlob)
            fixture
                .coordinator(
                    catalog = catalog,
                    authority = testPauseAuthority(fixture.persistence, initialize = false),
                ).recover()
            assertNull(fixture.journal.pending())
            assertEquals(
                Hysteria2PortHopping("20000-21000", 45_000),
                (
                    catalog
                        .snapshot()
                        .single()
                        .members
                        .single() as ProxyProfile.Hysteria2
                ).portHopping,
            )
        }

    @Test
    fun `invalid pending hopping does not replay a scalar profile`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val pending = seed(fixture, LegacyHoppingPayload.replace("45s", "4s"))
            assertTrue(runCatching { fixture.coordinator().recover() }.isFailure)
            assertEquals(pending, fixture.journal.pending())
            assertNoReplay(fixture)
        }

    private suspend fun seed(
        fixture: ProfileMutationTestFixture,
        payload: String = LegacyHoppingPayload,
    ): PendingProfileMutation {
        fixture.pauseAuthority.initializeAfterMigration()
        val pending =
            PendingProfileMutation(
                mutationId = "hopping-refresh",
                family = ProfileMutationFamily.Subscription,
                payload = payload,
                origin = ProfileMutationOrigin.SubscriptionRefresh,
                expectedPauseAuthority = fixture.pauseAuthority.reference(),
            )
        fixture.journal.prepare(pending)
        return pending
    }

    private fun assertNoReplay(fixture: ProfileMutationTestFixture) {
        assertEquals(0, fixture.storeWriteAttempts)
        assertEquals(0, fixture.groupWriteAttempts)
        assertEquals(0, fixture.awgCredentials.saveAttempts)
        assertEquals(0, fixture.awgProfiles.upsertAttempts)
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
