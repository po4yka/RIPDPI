package com.poyka.ripdpi.data

import app.cash.turbine.test
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionRefreshMutationTest {
    @Test
    fun `identical delete and recreate rejects the captured refresh before preparation`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
            groups.add(original)
            val original = groups.list().single()
            val captured = groups.list().single()
            groups.delete(mutations.captureMutation(ProfileMutationOrigin.ExplicitDeletion), captured.id)
            groups.add(captured)
            val recreated = groups.list().single()
            var preparations = 0

            assertFalse(
                mutations.commitSubscriptionRefresh(captured) {
                    preparations++
                    PreparedSubscriptionRefresh(replacement, listOf(rotation))
                },
            )

            assertEquals(0, preparations)
            assertEquals(recreated, groups.list().single())
            assertEquals(0, fixture.prepareAttempts)
            assertTrue(fixture.awgProfiles.allProfiles().isEmpty())
            assertNull(fixture.awgCredentials.load(rotation.profile.id))
        }

    @Test
    fun `stale ownership rejects before preparing or journaling any AWG write`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
            groups.add(original)
            val original = groups.list().single()
            groups.update(original.copy(name = "Edited"))
            var preparations = 0

            assertFalse(
                mutations.commitSubscriptionRefresh(original) {
                    preparations++
                    PreparedSubscriptionRefresh(replacement, listOf(rotation))
                },
            )

            assertEquals(0, preparations)
            assertEquals(0, fixture.prepareAttempts)
            assertNull(fixture.journal.pending())
            assertTrue(fixture.awgProfiles.allProfiles().isEmpty())
            assertNull(fixture.awgCredentials.load(rotation.profile.id))
            assertEquals("Edited", groups.list().single().name)
        }

    @Test
    fun `group edits wait through AWG commit and accepted group publication`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
            groups.add(original)
            val original = groups.list().single()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.awgProfiles.beforeUpsert = {
                entered.complete(Unit)
                release.await()
            }
            val refresh =
                async {
                    mutations.commitSubscriptionRefresh(
                        original,
                    ) { PreparedSubscriptionRefresh(replacement, listOf(rotation)) }
                }
            entered.await()
            val edit = launch { groups.updateGroup(original.id) { it.copy(name = "Edited after rotation") } }
            runCurrent()
            assertFalse(edit.isCompleted)
            assertFalse(checkNotNull(fixture.pauseAuthority.states.value).profileUtility.catalogReady)

            release.complete(Unit)
            assertTrue(refresh.await())
            edit.join()

            assertEquals(rotation.profile, fixture.awgProfiles.getProfile(rotation.profile.id))
            assertEquals(rotation.secrets, fixture.awgCredentials.load(rotation.profile.id))
            val edited = groups.list().single()
            assertEquals(
                replacement.copy(name = "Edited after rotation", ownershipGeneration = edited.ownershipGeneration),
                edited,
            )
            assertNotEquals(original.ownershipGeneration, edited.ownershipGeneration)
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `failed group write recovers AWG and publishes to an existing observer without reload`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
            groups.add(original)
            val original = groups.list().single()
            groups.groups().test {
                assertEquals(listOf(original), awaitItem())
                fixture.failGroupWrite = true

                assertTrue(
                    runCatching {
                        mutations.commitSubscriptionRefresh(
                            original,
                        ) { PreparedSubscriptionRefresh(replacement, listOf(rotation)) }
                    }.isFailure,
                )
                assertEquals(rotation.secrets, fixture.awgCredentials.load(rotation.profile.id))
                assertNotNull(fixture.journal.pending())
                assertFalse(checkNotNull(fixture.pauseAuthority.states.value).profileUtility.catalogReady)
                expectNoEvents()

                fixture.failGroupWrite = false
                val accepted = pendingGroup(fixture)
                assertNotEquals(original.ownershipGeneration, accepted.ownershipGeneration)
                mutations.recover()

                assertEquals(listOf(accepted), awaitItem())
                assertEquals(listOf(accepted), ProxyGroupCatalog(fixture.groupBlob).snapshot())
                assertEquals(rotation.profile, fixture.awgProfiles.getProfile(rotation.profile.id))
                assertTrue(checkNotNull(fixture.pauseAuthority.states.value).profileUtility.catalogReady)
                assertNull(fixture.journal.pending())
                assertEquals(1, fixture.prepareAttempts)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cancellation after credentials preserves journal and direct recovery updates the observer`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
            groups.add(original)
            val original = groups.list().single()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.awgProfiles.beforeUpsert = {
                entered.complete(Unit)
                release.await()
            }
            groups.groups().test {
                assertEquals(listOf(original), awaitItem())
                val refresh =
                    launch {
                        mutations.commitSubscriptionRefresh(
                            original,
                        ) { PreparedSubscriptionRefresh(replacement, listOf(rotation)) }
                    }
                entered.await()
                refresh.cancelAndJoin()

                assertEquals(rotation.secrets, fixture.awgCredentials.load(rotation.profile.id))
                assertNull(fixture.awgProfiles.getProfile(rotation.profile.id))
                assertNotNull(fixture.journal.pending())
                expectNoEvents()

                fixture.awgProfiles.beforeUpsert = null
                val accepted = pendingGroup(fixture)
                assertNotEquals(original.ownershipGeneration, accepted.ownershipGeneration)
                mutations.recover()

                assertEquals(listOf(accepted), awaitItem())
                assertEquals(listOf(accepted), ProxyGroupCatalog(fixture.groupBlob).snapshot())
                assertEquals(rotation.profile, fixture.awgProfiles.getProfile(rotation.profile.id))
                assertEquals(rotation.secrets, fixture.awgCredentials.load(rotation.profile.id))
                assertNull(fixture.journal.pending())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `edit and revert rejects stale refresh but a no-op preserves ownership`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
            groups.add(original)
            val captured = groups.list().single()
            assertEquals(captured, groups.updateGroup(captured.id) { it })
            groups.update(captured.copy(name = "Temporary edit"))
            groups.update(captured)
            val reverted = groups.list().single()
            assertEquals(captured.copy(ownershipGeneration = reverted.ownershipGeneration), reverted)
            assertNotEquals(captured.ownershipGeneration, reverted.ownershipGeneration)
            assertFalse(mutations.commitSubscriptionRefresh(captured) { error("Stale preparation") })
            assertEquals(0, fixture.prepareAttempts)
            assertTrue(fixture.awgProfiles.allProfiles().isEmpty())
        }

    @Test
    fun `identical restore and compensation invalidate previously captured ownership`() =
        runTest {
            for (compensation in listOf(false, true)) {
                val fixture = ProfileMutationTestFixture()
                val mutations = fixture.coordinator()
                val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
                groups.add(original)
                val captured = groups.list().single()
                if (compensation) {
                    groups.compensateReplacement(listOf(captured))
                } else {
                    mutations.runReset { receipt -> groups.replaceAll(receipt, listOf(captured)) }
                }
                val restored = groups.list().single()
                assertEquals(captured.copy(ownershipGeneration = restored.ownershipGeneration), restored)
                assertNotEquals(captured.ownershipGeneration, restored.ownershipGeneration)
                assertFalse(mutations.commitSubscriptionRefresh(captured) { error("Stale preparation") })
                assertTrue(fixture.awgProfiles.allProfiles().isEmpty())
                assertEquals(restored, ProxyGroupCatalog(fixture.groupBlob).snapshot().single())
            }
        }

    @Test
    fun `cold recovery keeps a valid journal and refuses AWG writes when the catalogue is unreadable`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations).add(original)
            val original = fixture.groupCatalog.snapshot().single()
            val encoded = fixture.groupBlob.read()
            fixture.afterPrepare = { error("Interrupted after journal prepare") }
            assertTrue(
                runCatching {
                    mutations.commitSubscriptionRefresh(
                        original,
                    ) { PreparedSubscriptionRefresh(replacement, listOf(rotation)) }
                }.isFailure,
            )
            val pending = checkNotNull(fixture.journal.pending())
            val writes = fixture.groupWriteAttempts
            fixture.failGroupRead = true
            val coldCatalog = ProxyGroupCatalog(fixture.groupBlob)
            val restarted = fixture.coordinator(catalog = coldCatalog)
            val groups = SharedPreferencesProxyGroupRepository(coldCatalog, restarted)
            groups.groups().test {
                expectNoEvents()
                assertTrue(runCatching { restarted.recover() }.exceptionOrNull() is IllegalStateException)

                assertEquals(pending, fixture.journal.pending())
                assertEquals(0, fixture.awgCredentials.saveAttempts)
                assertEquals(0, fixture.awgProfiles.upsertAttempts)
                assertEquals(writes, fixture.groupWriteAttempts)
                assertEquals(0, fixture.groupClearAttempts)
                expectNoEvents()
                fixture.failGroupRead = false
                assertEquals(encoded, fixture.groupBlob.read())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun pendingGroup(fixture: ProfileMutationTestFixture): ProxyGroup =
        (
            com.poyka.ripdpi.serialization.RipDpiContractJson.decodeFromString(
                ProfileMutationIntent.serializer(),
                checkNotNull(fixture.journal.pending()).payload,
            ) as SubscriptionRefreshIntent
        ).groups.single()

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
