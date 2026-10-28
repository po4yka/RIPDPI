package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WarpCandidatePoolLookupTest {
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
}
