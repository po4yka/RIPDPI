package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WarpCandidatePoolLookupTest {
    @Test
    fun `explicit endpoint families remain probeable without an IPv6 DNS answer`() =
        runTest {
            val endpoint = dualFamilyEndpoint()
            val dnsAnswers = listOf(emptyList(), listOf(InetAddress.getByName("127.0.0.1")))

            dnsAnswers.forEach { answer ->
                val pool =
                    buildPreferredWarpCandidatePool(
                        endpointStore = FakeWarpEndpointStore(),
                        profileId = DefaultWarpProfileId,
                        provisioned = endpoint,
                        lookupHost = { answer },
                    )

                assertEquals(
                    listOf("127.0.0.1" to "::1", "127.0.0.1" to null, null to "::1"),
                    pool.map { it.ipv4 to it.ipv6 },
                )
                assertTrue(pool.all { it.port == 2408 && it.profileId == DefaultWarpProfileId })
            }
        }

    @Test
    fun `runtime scanner selects explicit IPv6 when IPv4 is unavailable`() =
        runTest {
            val settings =
                TestAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setWarpScannerEnabled(true)
                        .build(),
                )
            val scanner =
                DefaultWarpEndpointScanner(
                    settings,
                    FakeWarpEndpointStore(),
                    object : WarpEndpointProbe {
                        override suspend fun probe(
                            candidate: WarpEndpointCacheEntry,
                            timeoutMillis: Int,
                        ): WarpEndpointCacheEntry? =
                            candidate.takeIf { it.ipv4 == null && it.ipv6 == "::1" }?.copy(rttMs = 9L)
                    },
                )

            val selected =
                scanner.resolveEndpoint(
                    profileId = DefaultWarpProfileId,
                    networkScopeKey = "fixture-network",
                    provisioned = dualFamilyEndpoint(),
                    origin = WarpEndpointResolutionOrigin.RuntimeProvisioning,
                )

            assertNotNull(selected)
            assertNull(selected?.ipv4)
            assertEquals("::1", selected?.ipv6)
            assertEquals(2408, selected?.port)
            assertEquals("fixture-network", selected?.networkScopeKey)
        }

    @Test
    fun `remembered endpoints resolve their shared host once and a held lookup is abandoned`() =
        runTest {
            val store =
                FakeWarpEndpointStore().apply {
                    listOf("wifi-a" to "162.159.192.1", "wifi-b" to "162.159.192.2", "cell" to "162.159.192.3")
                        .forEach { (scope, ipv4) ->
                            save(
                                WarpEndpointCacheEntry(
                                    profileId = "warp",
                                    networkScopeKey = scope,
                                    host = "engage.example",
                                    ipv4 = ipv4,
                                    port = 2408,
                                ),
                            )
                        }
                }
            val lookups = AtomicInteger()
            // A resolver on a network that drops DNS: the call blocks and ignores cancellation.
            val resolverHeld = CountDownLatch(1)
            // The lookup runs detached on a real thread while runTest skips the timeout in virtual time.
            val lookupStarted = CountDownLatch(1)
            try {
                val pool =
                    buildPreferredWarpCandidatePool(
                        endpointStore = store,
                        profileId = "warp",
                        provisioned = null,
                        lookupHost = {
                            lookups.incrementAndGet()
                            lookupStarted.countDown()
                            resolverHeld.await(30, TimeUnit.SECONDS)
                            emptyList<InetAddress>()
                        },
                    )

                assertTrue(lookupStarted.await(10, TimeUnit.SECONDS))
                assertEquals(1, lookups.get())
                assertEquals(
                    listOf("162.159.192.1", "162.159.192.2", "162.159.192.3"),
                    pool.map { it.ipv4 },
                )
            } finally {
                resolverHeld.countDown()
            }
        }

    private fun dualFamilyEndpoint(): WarpEndpointCacheEntry =
        WarpEndpointCacheEntry(
            profileId = DefaultWarpProfileId,
            networkScopeKey = "fixture-saved",
            host = "127.0.0.1",
            ipv4 = "127.0.0.1",
            ipv6 = "::1",
            port = 2408,
            source = "fixture",
        )
}
