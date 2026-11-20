package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.subscription.DnsMorphBridge
import com.poyka.ripdpi.data.subscription.DnsMorphFetcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class SubscriptionRefreshDnsMorphTest : SubscriptionRefreshTestSupport() {
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val token = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
    private val declared = DnsMorphBridge("192.0.2.53", 53, "morph.example.test", publicKey, token)
    private val bundleWithBridge =
        """{"outbounds":[{"type":"trojan","tls":{"enabled":true},"tag":"first","server":"relay.example",
        "server_port":443,"password":"fixture-password"}],"ripdpi":{"schema_version":3,"dns_morph":
        {"address":"192.0.2.53","port":53,"zone":"Morph.Example.Test","public_key":"$publicKey",
        "token":"$token"}}}"""
    private val bundleFromBridge =
        """{"outbounds":[{"type":"trojan","tls":{"enabled":true},"tag":"from-bridge","server":"relay.example",
        "server_port":443,"password":"fixture-password"}],"ripdpi":{"schema_version":3}}"""

    @Test
    fun `bundle-declared bridge delivers the refresh when HTTPS fails`() =
        runTest {
            val requested = mutableListOf<DnsMorphBridge>()
            val fixture =
                fixture(
                    now = 3_000L,
                    dnsMorphFetcher =
                        DnsMorphFetcher { bridge ->
                            requested += bridge
                            bundleFromBridge.toByteArray()
                        },
                )
            server.enqueue(response(200, bundleWithBridge))
            assertTrue(fixture.coordinator.refresh("subscription-group") is SubscriptionRefreshResult.Updated)
            assertEquals(
                declared,
                fixture.repository
                    .list()
                    .single()
                    .subscription
                    ?.dnsMorph,
            )
            assertTrue("HTTPS succeeded, so the bridge stays unused", requested.isEmpty())

            server.enqueue(response(503))
            val result = fixture.coordinator.refresh("subscription-group")

            assertTrue("result=$result", result is SubscriptionRefreshResult.Updated)
            assertEquals(listOf(declared), requested)
            val group = fixture.repository.list().single()
            assertEquals(listOf("from-bridge"), group.members.map { it.displayName })
            assertNull("the bridge bundle declares no channel, so it is now off", group.subscription?.dnsMorph)
        }

    @Test
    fun `failed bridge keeps the HTTPS failure and the last members`() =
        runTest {
            val fixture =
                fixture(now = 3_000L, dnsMorphFetcher = DnsMorphFetcher { throw IOException("bad signature") })
            server.enqueue(response(200, bundleWithBridge))
            fixture.coordinator.refresh("subscription-group")
            server.enqueue(response(503))

            val result = fixture.coordinator.refresh("subscription-group")

            assertEquals(
                SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.SERVER_ERROR, retryable = true),
                result,
            )
            assertEquals(
                listOf("first"),
                fixture.repository
                    .list()
                    .single()
                    .members
                    .map { it.displayName },
            )
        }

    @Test
    fun `undeclared bridge is never contacted`() =
        runTest {
            var calls = 0
            val fixture =
                fixture(
                    now = 3_000L,
                    dnsMorphFetcher =
                        DnsMorphFetcher {
                            calls++
                            ByteArray(0)
                        },
                )
            server.enqueue(response(503))

            fixture.coordinator.refresh("subscription-group")

            assertEquals(0, calls)
        }
}
