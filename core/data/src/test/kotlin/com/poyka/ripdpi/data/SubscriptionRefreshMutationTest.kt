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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionRefreshMutationTest {
    @Test
    fun `stale ownership rejects before preparing or journaling any AWG write`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
            groups.add(original)
            groups.update(original.copy(name = "Edited"))
            var preparations = 0

            assertFalse(
                mutations.commitSubscriptionRefresh(original, replacement) {
                    preparations++
                    listOf(rotation)
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
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.awgProfiles.beforeUpsert = {
                entered.complete(Unit)
                release.await()
            }
            val refresh = async { mutations.commitSubscriptionRefresh(original, replacement) { listOf(rotation) } }
            entered.await()
            val edit = launch { groups.update(accepted.copy(name = "Edited after rotation")) }
            runCurrent()
            assertFalse(edit.isCompleted)
            assertFalse(checkNotNull(fixture.pauseAuthority.states.value).profileUtility.catalogReady)

            release.complete(Unit)
            assertTrue(refresh.await())
            edit.join()

            assertEquals(rotation.profile, fixture.awgProfiles.getProfile(rotation.profile.id))
            assertEquals(rotation.secrets, fixture.awgCredentials.load(rotation.profile.id))
            assertEquals(accepted.copy(name = "Edited after rotation"), groups.list().single())
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `failed group write recovers AWG and publishes to an existing observer without reload`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
            groups.add(original)
            groups.groups().test {
                assertEquals(listOf(original), awaitItem())
                fixture.failGroupWrite = true

                assertTrue(
                    runCatching {
                        mutations.commitSubscriptionRefresh(original, replacement) { listOf(rotation) }
                    }.isFailure,
                )
                assertEquals(rotation.secrets, fixture.awgCredentials.load(rotation.profile.id))
                assertNotNull(fixture.journal.pending())
                assertFalse(checkNotNull(fixture.pauseAuthority.states.value).profileUtility.catalogReady)
                expectNoEvents()

                fixture.failGroupWrite = false
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
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.awgProfiles.beforeUpsert = {
                entered.complete(Unit)
                release.await()
            }
            groups.groups().test {
                assertEquals(listOf(original), awaitItem())
                val refresh = launch { mutations.commitSubscriptionRefresh(original, replacement) { listOf(rotation) } }
                entered.await()
                refresh.cancelAndJoin()

                assertEquals(rotation.secrets, fixture.awgCredentials.load(rotation.profile.id))
                assertNull(fixture.awgProfiles.getProfile(rotation.profile.id))
                assertNotNull(fixture.journal.pending())
                expectNoEvents()

                fixture.awgProfiles.beforeUpsert = null
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
    fun `invalid or exhausted revision rejects before preparing any credential mutation`() =
        runTest {
            for (revision in listOf(-1L, Long.MAX_VALUE)) {
                val fixture = ProfileMutationTestFixture()
                val mutations = fixture.coordinator()
                val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
                val current = original.copy(subscription = original.subscription!!.copy(refreshRevision = revision))
                groups.add(current)
                var preparations = 0
                assertTrue(
                    runCatching {
                        mutations.commitSubscriptionRefresh(current, replacement) {
                            preparations++
                            listOf(rotation)
                        }
                    }.exceptionOrNull() is IllegalArgumentException,
                )
                assertEquals(0, preparations)
                assertEquals(0, fixture.prepareAttempts)
                assertNull(fixture.journal.pending())
                assertTrue(fixture.awgProfiles.allProfiles().isEmpty())
                assertNull(fixture.awgCredentials.load(rotation.profile.id))
                assertEquals(current, groups.list().single())
            }
        }

    @Test
    fun `cold recovery keeps a valid journal and refuses AWG writes when the catalogue is unreadable`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations).add(original)
            val encoded = fixture.groupBlob.read()
            fixture.afterPrepare = { error("Interrupted after journal prepare") }
            assertTrue(
                runCatching {
                    mutations.commitSubscriptionRefresh(original, replacement) { listOf(rotation) }
                }.isFailure,
            )
            val pending = checkNotNull(fixture.journal.pending())
            val writes = fixture.groupWriteAttempts
            fixture.failGroupRead = true
            val coldCatalog = ProxyGroupCatalog(fixture.groupBlob)
            val restarted = fixture.coordinatorWithCatalog(coldCatalog)
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

    private fun ProfileMutationTestFixture.coordinatorWithCatalog(catalog: ProxyGroupCatalog) =
        ProfileMutationRecoveryCoordinator(
            ProfileMutationStores(
                stores.settings,
                stores.relayProfiles,
                stores.relayCredentials,
                stores.warpProfiles,
                stores.warpCredentials,
                stores.warpEndpoints,
                stores.xrayMetadata,
                stores.xraySecrets,
                stores.xraySelection,
                stores.bootSession,
                catalog,
                stores.selectorChoice,
            ),
            awgProfiles,
            awgCredentials,
            journal,
            mutationGeneration,
            pauseAuthority,
        )

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
    private val accepted = replacement.copy(subscription = replacement.subscription!!.copy(refreshRevision = 1))
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
