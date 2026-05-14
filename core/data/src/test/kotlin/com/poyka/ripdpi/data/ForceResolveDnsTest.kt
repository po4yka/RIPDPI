package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.ForceResolveDns
import com.poyka.ripdpi.data.subscription.HostResolution
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ForceResolveDns]: pre-resolves subscription profile
 * hostnames to IPs with bounded-concurrency (max 5 parallel lookups). Replaces
 * NekoBox's `Executors.newFixedThreadPool(5)` with a coroutine `Semaphore(5)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForceResolveDnsTest {
    private fun vless(
        id: String,
        server: String,
    ) = ProxyProfile.Vless(
        id = id,
        displayName = id,
        groupId = "g",
        server = server,
        serverPort = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
    )

    @Test
    fun `resolves each profile server address and rewrites it to the resolved ip`() =
        runTest {
            val profiles =
                listOf(
                    vless("a", "a.example.com"),
                    vless("b", "b.example.com"),
                )
            val resolved =
                ForceResolveDns.resolveAll(profiles) { host ->
                    HostResolution.Resolved(addresses = listOf("203.0.113.${host.first().code}"))
                }

            assertEquals("203.0.113.97", (resolved[0] as ProxyProfile.Vless).server)
            assertEquals("203.0.113.98", (resolved[1] as ProxyProfile.Vless).server)
        }

    @Test
    fun `a profile whose host fails to resolve is left unchanged`() =
        runTest {
            val profiles = listOf(vless("a", "unresolvable.example.com"))

            val resolved =
                ForceResolveDns.resolveAll(profiles) { HostResolution.Failed("NXDOMAIN") }

            assertEquals("unresolvable.example.com", (resolved.single() as ProxyProfile.Vless).server)
        }

    @Test
    fun `a profile whose server is already an ip literal is left unchanged and not looked up`() =
        runTest {
            val profiles = listOf(vless("a", "198.51.100.7"))
            val lookups = AtomicInteger(0)

            val resolved =
                ForceResolveDns.resolveAll(profiles) {
                    lookups.incrementAndGet()
                    HostResolution.Resolved(addresses = listOf("203.0.113.1"))
                }

            assertEquals("198.51.100.7", (resolved.single() as ProxyProfile.Vless).server)
            assertEquals(0, lookups.get())
        }

    @Test
    fun `an ipv6 literal server is left unchanged and not looked up`() =
        runTest {
            val profiles = listOf(vless("a", "2001:db8::1"))
            val lookups = AtomicInteger(0)

            val resolved =
                ForceResolveDns.resolveAll(profiles) {
                    lookups.incrementAndGet()
                    HostResolution.Resolved(addresses = listOf("203.0.113.1"))
                }

            assertEquals("2001:db8::1", (resolved.single() as ProxyProfile.Vless).server)
            assertEquals(0, lookups.get())
        }

    @Test
    fun `dual-stack resolution prefers the first returned address`() =
        runTest {
            val profiles = listOf(vless("a", "dual.example.com"))

            val resolved =
                ForceResolveDns.resolveAll(profiles) {
                    HostResolution.Resolved(addresses = listOf("2001:db8::99", "203.0.113.99"))
                }

            assertEquals("2001:db8::99", (resolved.single() as ProxyProfile.Vless).server)
        }

    @Test
    fun `lookups never exceed the bounded concurrency limit of 5`() =
        runTest {
            val profiles = (1..20).map { vless("p$it", "host$it.example.com") }
            val inFlight = AtomicInteger(0)
            val peak = AtomicInteger(0)
            // A semaphore the test uses purely to observe concurrency; the
            // resolver under test has its own internal Semaphore(5).
            val observer = Semaphore(Int.MAX_VALUE)

            val resolved =
                ForceResolveDns.resolveAll(profiles) {
                    observer.withPermit {
                        val current = inFlight.incrementAndGet()
                        peak.updateAndGet { maxOf(it, current) }
                        delay(1)
                        inFlight.decrementAndGet()
                        HostResolution.Resolved(addresses = listOf("203.0.113.1"))
                    }
                }

            assertEquals(20, resolved.size)
            assertTrue("peak concurrency was ${peak.get()}, expected <= 5", peak.get() <= 5)
        }

    @Test
    fun `non-addressed profiles like raw config pass through untouched`() =
        runTest {
            val raw =
                ProxyProfile.RawConfig(
                    id = "raw",
                    displayName = "raw",
                    groupId = "g",
                    config = "{}",
                )

            val resolved =
                ForceResolveDns.resolveAll(listOf(raw)) {
                    HostResolution.Resolved(addresses = listOf("203.0.113.1"))
                }

            assertEquals(raw, resolved.single())
        }

    @Test
    fun `resolveAll on an empty profile list is a no-op`() =
        runTest {
            val resolved =
                ForceResolveDns.resolveAll(emptyList()) {
                    HostResolution.Resolved(addresses = listOf("203.0.113.1"))
                }

            assertTrue(resolved.isEmpty())
        }

    @Test
    fun `an empty resolved address list is treated as a failure leaving the host unchanged`() =
        runTest {
            val profiles = listOf(vless("a", "empty.example.com"))

            val resolved =
                ForceResolveDns.resolveAll(profiles) { HostResolution.Resolved(addresses = emptyList()) }

            assertEquals("empty.example.com", (resolved.single() as ProxyProfile.Vless).server)
            assertNull((resolved.single() as ProxyProfile.Vless).server.toIntOrNull())
        }
}
