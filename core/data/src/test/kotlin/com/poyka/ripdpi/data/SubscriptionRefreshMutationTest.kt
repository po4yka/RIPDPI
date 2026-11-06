package com.poyka.ripdpi.data

import app.cash.turbine.test
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubscriptionRefreshMutationTest : RoomProfileTest() {
    @Test
    fun `identical delete and recreate rejects captured refresh before preparation`() =
        runTest {
            val fixture = fixture()
            val groups = fixture.groups
            groups.add(original)
            val captured = groups.list().single()
            groups.delete(fixture.coordinator().captureMutation(ProfileMutationOrigin.ExplicitDeletion), captured.id)
            groups.add(captured)
            val recreated = groups.list().single()
            val before = fixture.snapshot()
            assertFalse(fixture.coordinator().commitSubscriptionRefresh(captured) { error("Stale refresh prepared") })
            assertEquals(recreated, groups.list().single())
            assertEquals(before, fixture.snapshot())
        }

    @Test
    fun `stale ownership rejects before preparing any AWG write`() =
        runTest {
            val fixture = fixture()
            fixture.groups.add(original)
            val captured = fixture.groups.list().single()
            fixture.groups.update(captured.copy(name = "Edited"))
            val before = fixture.snapshot()
            assertFalse(fixture.coordinator().commitSubscriptionRefresh(captured) { error("Stale refresh prepared") })
            assertEquals(before, fixture.snapshot())
            assertEquals(
                "Edited",
                fixture.groups
                    .list()
                    .single()
                    .name,
            )
            assertNull(fixture.awgCredentials.load(rotation.profile.id))
        }

    @Test
    fun `group edits wait until AWG and catalogue commit together`() =
        runTest {
            val fixture = fixture()
            fixture.groups.add(original)
            val captured = fixture.groups.list().single()
            val barrier = ProfileWriteBarrier("proxy_groups")
            fixture.room.beforeSeal = barrier::beforeSeal
            val refresh =
                async {
                    fixture.coordinator().commitSubscriptionRefresh(captured) {
                        PreparedSubscriptionRefresh(replacement, listOf(rotation))
                    }
                }
            try {
                barrier.awaitEntry()
                val edit =
                    launch { fixture.groups.updateGroup(captured.id) { it.copy(name = "Edited after rotation") } }
                runCurrent()
                assertFalse(edit.isCompleted)
                barrier.release()
                assertTrue(refresh.await())
                edit.join()
            } finally {
                barrier.release()
            }
            fixture.clearFaults()
            assertEquals(
                rotation.profile,
                withContext(Dispatchers.IO) { fixture.awgProfiles.getProfile(rotation.profile.id) },
            )
            assertEquals(rotation.secrets, fixture.awgCredentials.load(rotation.profile.id))
            val edited = fixture.groups.list().single()
            assertEquals(
                replacement.copy(name = "Edited after rotation", ownershipGeneration = edited.ownershipGeneration),
                edited,
            )
            assertNotEquals(captured.ownershipGeneration, edited.ownershipGeneration)
        }

    @Test
    fun `failed group write rolls back AWG and observer changes only after a successful retry`() =
        runTest {
            val fixture = fixture()
            fixture.groups.add(original)
            val captured = fixture.groups.list().single()
            val before = fixture.snapshot()
            fixture.groups.groups().test {
                assertEquals(listOf(captured), awaitItem())
                fixture.failWrite("proxy_groups")
                assertTrue(
                    runCatching {
                        fixture.coordinator().commitSubscriptionRefresh(captured) {
                            PreparedSubscriptionRefresh(replacement, listOf(rotation))
                        }
                    }.isFailure,
                )
                assertEquals(before, fixture.snapshot())
                assertNull(fixture.awgCredentials.load(rotation.profile.id))
                expectNoEvents()
                fixture.clearFaults()
                fixture.coordinator().recover()
                expectNoEvents()
                assertTrue(
                    fixture.coordinator().commitSubscriptionRefresh(captured) {
                        PreparedSubscriptionRefresh(replacement, listOf(rotation))
                    },
                )
                val accepted = awaitItem().single()
                assertEquals(replacement.copy(ownershipGeneration = accepted.ownershipGeneration), accepted)
                assertNotEquals(captured.ownershipGeneration, accepted.ownershipGeneration)
                assertEquals(rotation.secrets, fixture.awgCredentials.load(rotation.profile.id))
                assertEquals(
                    rotation.profile,
                    withContext(Dispatchers.IO) { fixture.awgProfiles.getProfile(rotation.profile.id) },
                )
            }
        }

    @Test
    fun `cancellation after AWG writes rolls back catalogue and credentials without replay`() =
        runTest {
            val fixture = fixture()
            fixture.groups.add(original)
            val captured = fixture.groups.list().single()
            val before = fixture.snapshot()
            val barrier = ProfileWriteBarrier("proxy_groups")
            fixture.room.beforeSeal = barrier::beforeSeal
            fixture.groups.groups().test {
                assertEquals(listOf(captured), awaitItem())
                val refresh =
                    launch {
                        fixture.coordinator().commitSubscriptionRefresh(captured) {
                            PreparedSubscriptionRefresh(replacement, listOf(rotation))
                        }
                    }
                try {
                    barrier.awaitEntry()
                    refresh.cancel()
                } finally {
                    barrier.release()
                }
                refresh.join()
                fixture.clearFaults()
                assertEquals(before, fixture.snapshot())
                expectNoEvents()
            }
            fixture.reopen()
            assertEquals(before, fixture.snapshot())
            assertEquals(captured, fixture.groups.list().single())
        }

    @Test
    fun `edit and revert reject stale refresh while no-op preserves ownership`() =
        runTest {
            val fixture = fixture()
            fixture.groups.add(original)
            val captured = fixture.groups.list().single()
            assertEquals(captured, fixture.groups.updateGroup(captured.id) { it })
            fixture.groups.update(captured.copy(name = "Temporary"))
            fixture.groups.update(captured)
            val reverted = fixture.groups.list().single()
            assertEquals(captured.copy(ownershipGeneration = reverted.ownershipGeneration), reverted)
            assertNotEquals(captured.ownershipGeneration, reverted.ownershipGeneration)
            assertFalse(fixture.coordinator().commitSubscriptionRefresh(captured) { error("Stale refresh prepared") })
        }

    @Test
    fun `restore and compensation invalidate previously captured ownership`() =
        runTest {
            for (compensation in listOf(false, true)) {
                val fixture = fixture()
                fixture.groups.add(original)
                val captured = fixture.groups.list().single()
                if (compensation) {
                    fixture.groups.compensateReplacement(listOf(captured))
                } else {
                    fixture.coordinator().runReset { fixture.groups.replaceAll(it, listOf(captured)) }
                }
                val restored = fixture.groups.list().single()
                assertEquals(captured.copy(ownershipGeneration = restored.ownershipGeneration), restored)
                assertNotEquals(captured.ownershipGeneration, restored.ownershipGeneration)
                assertFalse(
                    fixture.coordinator().commitSubscriptionRefresh(captured) { error("Stale refresh prepared") },
                )
                assertTrue(withContext(Dispatchers.IO) { fixture.awgProfiles.allProfiles().isEmpty() })
            }
        }

    @Test
    fun `unreadable catalogue blocks refresh without writing AWG or erasing stored bytes`() =
        runTest {
            val fixture = fixture()
            fixture.groups.add(original)
            val captured = fixture.groups.list().single()
            val before = fixture.snapshot()
            fixture.room.beforeOpen =
                { namespace, _ -> if (namespace == "proxy_groups") error("Catalogue unavailable") }
            repeat(2) {
                assertTrue(
                    runCatching {
                        fixture.coordinator().commitSubscriptionRefresh(captured) {
                            error("Unreadable catalogue must not prepare refresh")
                        }
                    }.isFailure,
                )
                assertEquals(before, fixture.snapshot())
            }
            fixture.clearFaults()
            assertEquals(captured, fixture.groups.list().single())
            assertNull(fixture.awgCredentials.load(rotation.profile.id))
        }

    private val original =
        ProxyGroup(
            "subscription",
            "Subscription",
            ProxyGroupType.SUBSCRIPTION,
            0,
            false,
            subscription = Subscription(link = "https://subscription.example/original"),
        )
    private val replacement = original.copy(subscription = original.subscription!!.copy(lastUpdated = 3_000L))
    private val rotation =
        SubscriptionAwgWrite(
            AwgProfileEntity(
                "awg-fixture",
                "Peer",
                RipDpiEncodeDefaultsJson.encodeToString(
                    AwgActivationRequest.serializer(),
                    AwgActivationRequest(
                        profileId = "",
                        privateKey = "",
                        peerPublicKey = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
                        endpointHost = "192.0.2.20",
                        endpointPort = 443,
                        interfaceAddressV4 = "10.8.0.2/32",
                    ),
                ),
                3_000L,
            ),
            AwgSecrets("local-private-key", "rotated-preshared-key"),
        )
}
