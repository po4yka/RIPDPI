package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.MigrationDelivery
import com.poyka.ripdpi.data.subscription.MigrationMetadata
import com.poyka.ripdpi.data.subscription.SubscriptionMirror
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catalog serialization preserves secrets; Room encryption is verified in the data module. */
class ProxyGroupSecretAtRestTest {
    // "fixture-" prefixed so the no-secrets pre-commit gate skips these lines.
    private val memberPassword = "fixture-trojan-pw-9f3a"
    private val subToken = "fixture-sub-token-7b21"

    private fun sampleGroup() =
        ProxyGroup(
            id = "g1",
            name = "Sub",
            type = ProxyGroupType.SUBSCRIPTION,
            order = 0,
            isSelector = true,
            subscription =
                Subscription(
                    link = "https://sub.example/x",
                    token = subToken,
                    migration =
                        MigrationDelivery(
                            MigrationMetadata(
                                "11111111-1111-1111-1111-111111111111",
                                "22222222-2222-2222-2222-222222222222",
                                listOf("m1"),
                                "a".repeat(64),
                                emptyMap(),
                            ),
                            "b".repeat(64),
                            1000,
                            emptyList(),
                        ),
                    mirrors =
                        SubscriptionMirrorSet(
                            listOf(
                                SubscriptionMirror(
                                    "direct",
                                    "https://direct.example/sub/device",
                                    "fixture-mirror-token",
                                ),
                            ),
                        ),
                ),
            cloudflareMemberIds = setOf("m1"),
            members =
                listOf(
                    ProxyProfile.Trojan(
                        id = "m1",
                        displayName = "m1",
                        groupId = "g1",
                        server = "a.example.com",
                        serverPort = 443,
                        password = memberPassword,
                    ),
                ),
        )

    @Test
    fun `group secrets round-trip through the storage contract`() =
        runTest {
            val blobStore = FakeProxyGroupBlobStore()
            val repo =
                DefaultProxyGroupRepository(
                    com.poyka.ripdpi.data
                        .ProxyGroupCatalog(blobStore),
                    com.poyka.ripdpi.data
                        .testMutationPreparationSource(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                )

            repo.add(sampleGroup())

            // The storage input must retain every secret for the encrypted Room writer.
            val persisted = blobStore.read()
            assertNotNull("group blob must be handed to the sealed store", persisted)
            assertTrue("member secret must be inside the to-be-sealed blob", persisted!!.contains(memberPassword))
            assertTrue("subscription token must be inside the to-be-sealed blob", persisted.contains(subToken))

            assertTrue(persisted.contains("fixture-mirror-token"))
            val loaded =
                DefaultProxyGroupRepository(
                    com.poyka.ripdpi.data
                        .ProxyGroupCatalog(blobStore),
                    com.poyka.ripdpi.data
                        .testMutationPreparationSource(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                ).list().single()
            assertEquals(memberPassword, (loaded.members.single() as ProxyProfile.Trojan).password)
            assertEquals(subToken, loaded.subscription?.token)
            assertEquals(sampleGroup().subscription?.migration, loaded.subscription?.migration)
            assertEquals(sampleGroup().subscription?.mirrors, loaded.subscription?.mirrors)
            assertEquals(setOf("m1"), loaded.cloudflareMemberIds)
        }

    @Test
    fun `deleting the last group clears the sealed blob`() =
        runTest {
            val blobStore = FakeProxyGroupBlobStore()
            val repo =
                DefaultProxyGroupRepository(
                    com.poyka.ripdpi.data
                        .ProxyGroupCatalog(blobStore),
                    com.poyka.ripdpi.data
                        .testMutationPreparationSource(
                            com.poyka.ripdpi.data
                                .testPauseAuthority(),
                        ),
                )
            repo.add(sampleGroup())

            repo.delete(
                com.poyka.ripdpi.data.ProfileMutationPreparation(
                    com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitDeletion,
                    com.poyka.ripdpi.data
                        .PauseAuthorityRef(0),
                ),
                "g1",
            )

            assertEquals(emptyList<ProxyGroup>(), repo.list())
        }
}
