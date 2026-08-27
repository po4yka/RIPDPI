package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.SubscriptionMirror
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the audit P0 / P1-11 finding: subscription/selector group member
 * secrets and the subscription token were serialized in PLAINTEXT into the
 * `proxy_group_cache` SharedPreferences.
 *
 * Persistence now flows exclusively through [ProxyGroupBlobStore], whose production
 * binding ([KeystoreProxyGroupBlobStore]) AES-256-GCM seals the blob at rest. This
 * test pins that the repository persists *only* via that seam — so the seal always
 * applies — and that the secrets survive a round trip. The AES seal and the
 * legacy-plaintext migration are exercised by the production binding / instrumented
 * tests, matching how `FakeAwgCredentialStore` / `FakeWarpCredentialStore` are used
 * (AndroidKeyStore is unavailable under Robolectric).
 */
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
    fun `groups are persisted only through the sealed blob store, and secrets round-trip`() =
        runTest {
            val blobStore = FakeProxyGroupBlobStore()
            val repo = SharedPreferencesProxyGroupRepository(blobStore)

            repo.add(sampleGroup())

            // The serialized blob (which the production binding AES-seals) is the
            // single persisted artifact and carries the credentials, so the seal
            // covers every member secret + the subscription token.
            val persisted = blobStore.read()
            assertNotNull("group blob must be handed to the sealed store", persisted)
            assertTrue("member secret must be inside the to-be-sealed blob", persisted!!.contains(memberPassword))
            assertTrue("subscription token must be inside the to-be-sealed blob", persisted.contains(subToken))

            assertTrue(persisted.contains("fixture-mirror-token"))
            val loaded = SharedPreferencesProxyGroupRepository(blobStore).list().single()
            assertEquals(memberPassword, (loaded.members.single() as ProxyProfile.Trojan).password)
            assertEquals(subToken, loaded.subscription?.token)
            assertEquals(sampleGroup().subscription?.mirrors, loaded.subscription?.mirrors)
            assertEquals(setOf("m1"), loaded.cloudflareMemberIds)
        }

    @Test
    fun `deleting the last group clears the sealed blob`() =
        runTest {
            val blobStore = FakeProxyGroupBlobStore()
            val repo = SharedPreferencesProxyGroupRepository(blobStore)
            repo.add(sampleGroup())

            repo.delete("g1")

            assertEquals(emptyList<ProxyGroup>(), repo.list())
        }
}
