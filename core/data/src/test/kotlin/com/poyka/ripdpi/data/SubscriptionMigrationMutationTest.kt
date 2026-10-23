package com.poyka.ripdpi.data

import app.cash.turbine.test
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSubscriptionProfile
import com.poyka.ripdpi.data.awg.toMigrationTarget
import com.poyka.ripdpi.data.subscription.MigrationMetadata
import com.poyka.ripdpi.data.subscription.receivedMigration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionMigrationMutationTest {
    @Test
    fun `renamed AWG and matching receipt recover together without replacing the source row`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
            val awg = AwgProfileRepository(fixture.awgProfiles, fixture.awgCredentials, mutations)
            groups.add(original)
            val original = groups.list().single()
            val source = awg.saveSubscriptionProfiles(listOf(sourceProfile)).single()
            val preparations = fixture.prepareAttempts
            var captured: ProxyGroup? = null
            groups.groups().test {
                assertEquals(listOf(original), awaitItem())
                fixture.failGroupWrite = true
                assertTrue(
                    runCatching {
                        awg.commitSubscriptionRefresh(original, listOf(targetProfile)) { prepared ->
                            val target = prepared.single()
                            assertNotEquals(source.id, target.id)
                            assertEquals("local-private-key", target.request.privateKey)
                            assertEquals("rotated-psk", target.request.presharedKey)
                            original
                                .copy(
                                    subscription =
                                        original.subscription!!.copy(
                                            migration =
                                                receivedMigration(
                                                    metadata,
                                                    digest,
                                                    3_000L,
                                                    emptyList(),
                                                    listOf(target.toMigrationTarget()),
                                                ),
                                        ),
                                ).also { captured = it }
                        }
                    }.isFailure,
                )
                assertNotNull(fixture.journal.pending())
                assertEquals(preparations + 1, fixture.prepareAttempts)
                expectNoEvents()

                fixture.failGroupWrite = false
                mutations.recover()

                val recovered = awaitItem().single()
                val preparedGroup = checkNotNull(captured)
                assertNotEquals(original.ownershipGeneration, recovered.ownershipGeneration)
                assertEquals(
                    preparedGroup.copy(ownershipGeneration = recovered.ownershipGeneration),
                    recovered,
                )
                val receipt = checkNotNull(recovered.subscription!!.migration)
                val savedTarget = checkNotNull(awg.load(receipt.targets.single().profileId))
                assertEquals(listOf(savedTarget.toMigrationTarget()), receipt.targets)
                assertEquals(metadata, receipt.metadata)
                assertEquals(digest, receipt.payloadSha256)
                assertEquals("local-private-key", savedTarget.request.privateKey)
                assertEquals("rotated-psk", savedTarget.request.presharedKey)
                assertEquals(source, awg.load(source.id))
                assertEquals(listOf(recovered), ProxyGroupCatalog(fixture.groupBlob).snapshot())
                assertEquals(preparations + 1, fixture.prepareAttempts)
                assertNull(fixture.journal.pending())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `lost source ownership cannot prepare receipt or mutate AWG keys`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val mutations = fixture.coordinator()
            val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
            val awg = AwgProfileRepository(fixture.awgProfiles, fixture.awgCredentials, mutations)
            groups.add(original)
            val original = groups.list().single()
            val source = awg.saveSubscriptionProfiles(listOf(sourceProfile)).single()
            val preparations = fixture.prepareAttempts
            val edited = original.copy(subscription = original.subscription!!.copy(link = "https://new.example/source"))
            groups.update(edited)
            var receiptsPrepared = 0

            assertFalse(
                awg.commitSubscriptionRefresh(original, listOf(targetProfile)) {
                    receiptsPrepared++
                    error("Stale group must never prepare migration authority")
                },
            )

            assertEquals(0, receiptsPrepared)
            assertEquals(preparations, fixture.prepareAttempts)
            assertEquals(listOf(source.id), fixture.awgProfiles.allProfiles().map { it.id })
            assertEquals(source, awg.load(source.id))
            val saved = groups.list().single()
            assertEquals(edited.copy(ownershipGeneration = saved.ownershipGeneration), saved)
            assertNotEquals(edited.ownershipGeneration, saved.ownershipGeneration)
            assertNull(
                groups
                    .list()
                    .single()
                    .subscription!!
                    .migration,
            )
            assertNull(fixture.journal.pending())
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
    private val sourceProfile =
        AwgSubscriptionProfile(
            original.id,
            "tag:blue",
            "blue",
            AwgActivationRequest(
                profileId = "",
                privateKey = "local-private-key",
                presharedKey = "source-psk",
                peerPublicKey = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
                endpointHost = "192.0.2.10",
                endpointPort = 443,
                interfaceAddressV4 = "10.8.0.2/32",
            ),
        )
    private val targetProfile =
        sourceProfile.copy(
            memberId = "tag:green",
            name = "green",
            previousMemberId = "tag:blue",
            request =
                sourceProfile.request.copy(
                    privateKey = "",
                    endpointHost = "192.0.2.20",
                    presharedKey = "rotated-psk",
                ),
        )
    private val metadata =
        MigrationMetadata(
            "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222",
            listOf("green"),
            "a".repeat(64),
            mapOf("green" to "blue"),
        )
    private val digest = "b".repeat(64)
}
