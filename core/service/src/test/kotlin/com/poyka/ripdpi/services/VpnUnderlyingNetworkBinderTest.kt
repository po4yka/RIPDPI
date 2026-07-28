package com.poyka.ripdpi.services

import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.poyka.ripdpi.data.NetworkPathAssociationServiceBinder
import com.poyka.ripdpi.data.NetworkPathAssociationUnknown
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.io.FileDescriptor
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VpnUnderlyingNetworkBinderTest {
    @Test
    fun `diagnostic underlay observation is authoritative bounded and identifier free`() {
        val authority = DirectDnsUnderlayAuthority()
        val network = testNetwork(100)
        val epoch = authority.beginCallbackEpoch()
        val capabilities = eligibleCapabilities()
        val links =
            linkProperties("203.0.113.53").also {
                it.interfaceName = "wlan-secret"
                it.mtu = 1_420
                it.nat64Prefix = IpPrefix(InetAddress.getByName("64:ff9b::"), 96)
            }

        authority.onAvailable(epoch, network)
        authority.onCapabilitiesChanged(epoch, network, capabilities)
        authority.onLinkPropertiesChanged(epoch, network, links)

        val observation = authority.capture()
        val encoded = Json.encodeToString(observation)
        assertEquals(NetworkPathAssociationServiceBinder, observation.association)
        assertEquals("other", observation.transport)
        assertEquals(emptyList<String>(), observation.addressFamilies)
        assertEquals(listOf("ipv4"), observation.dnsServerFamilies)
        assertEquals("reduced", observation.mtuBand)
        assertFalse(requireNotNull(observation.congested))
        assertFalse(requireNotNull(observation.restricted))
        assertTrue(requireNotNull(observation.nat64Present))
        listOf("wlan-secret", "203.0.113.53", "64:ff9b").forEach { forbidden ->
            assertFalse("must not contain $forbidden", encoded.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun `diagnostic underlay generation is stable for duplicates and lost fails closed`() {
        val authority = DirectDnsUnderlayAuthority()
        val network = testNetwork(100)
        val epoch = authority.beginCallbackEpoch()
        val capabilities = eligibleCapabilities()
        val links = linkProperties("1.1.1.1").also { it.mtu = 1_500 }

        authority.onAvailable(epoch, network)
        authority.onCapabilitiesChanged(epoch, network, capabilities)
        authority.onLinkPropertiesChanged(epoch, network, links)
        val first = authority.capture()

        authority.onCapabilitiesChanged(epoch, network, capabilities)
        authority.onLinkPropertiesChanged(epoch, network, links)
        assertEquals(first.generation, authority.capture().generation)

        authority.onLinkPropertiesChanged(epoch, network, linkProperties("1.1.1.1").also { it.mtu = 1_420 })
        assertTrue(requireNotNull(authority.capture().generation) > requireNotNull(first.generation))

        authority.onLost(epoch, network)
        assertEquals(NetworkPathAssociationUnknown, authority.capture().association)
        assertNull(authority.capture().generation)
    }

    @Test
    fun `diagnostic underlay stays unknown until complete then preserves failure evidence`() {
        val authority = DirectDnsUnderlayAuthority()
        val network = testNetwork(100)
        val epoch = authority.beginCallbackEpoch()

        authority.onAvailable(epoch, network)
        assertEquals(NetworkPathAssociationUnknown, authority.capture().association)

        authority.onCapabilitiesChanged(epoch, network, eligibleCapabilities())
        assertEquals(NetworkPathAssociationUnknown, authority.capture().association)

        authority.onLinkPropertiesChanged(epoch, network, LinkProperties())
        val dnsMissing = authority.capture()
        assertEquals(NetworkPathAssociationServiceBinder, dnsMissing.association)
        assertEquals(0, dnsMissing.dnsServerCount)
        assertEquals(emptyList<String>(), dnsMissing.dnsServerFamilies)

        authority.onLinkPropertiesChanged(epoch, network, linkProperties("1.1.1.1"))
        assertEquals(NetworkPathAssociationServiceBinder, authority.capture().association)

        val ineligibleNetwork = testNetwork(200)
        val failingCapabilities =
            NetworkCapabilities().also { capabilities ->
                listOf(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET,
                    NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
                    NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL,
                ).forEach { capability ->
                    NetworkCapabilities::class
                        .java
                        .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                        .invoke(capabilities, capability)
                }
            }
        authority.onAvailable(epoch, ineligibleNetwork)
        authority.onCapabilitiesChanged(epoch, ineligibleNetwork, failingCapabilities)
        authority.onLinkPropertiesChanged(epoch, ineligibleNetwork, linkProperties("1.1.1.1"))
        val ineligible = authority.capture()
        assertEquals(NetworkPathAssociationServiceBinder, ineligible.association)
        assertFalse(requireNotNull(ineligible.validated))
        assertTrue(requireNotNull(ineligible.captivePortal))
    }

    @Test
    fun `complete vpn callback is never captured as authoritative underlay`() {
        val authority = DirectDnsUnderlayAuthority()
        val network = testNetwork(100)
        val epoch = authority.beginCallbackEpoch()
        val vpnCapabilities =
            NetworkCapabilities().also { capabilities ->
                listOf(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET,
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                ).forEach { capability ->
                    NetworkCapabilities::class
                        .java
                        .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                        .invoke(capabilities, capability)
                }
                NetworkCapabilities::class
                    .java
                    .getDeclaredMethod("addTransportType", Int::class.javaPrimitiveType)
                    .invoke(capabilities, NetworkCapabilities.TRANSPORT_VPN)
                NetworkCapabilities::class
                    .java
                    .getDeclaredMethod("removeCapability", Int::class.javaPrimitiveType)
                    .invoke(capabilities, NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }

        authority.onAvailable(epoch, network)
        authority.onCapabilitiesChanged(epoch, network, vpnCapabilities)
        authority.onLinkPropertiesChanged(epoch, network, linkProperties("1.1.1.1"))

        val observation = authority.capture()
        assertEquals(NetworkPathAssociationUnknown, observation.association)
        assertNull(observation.generation)
    }

    @Test
    fun `authority changes are monotonic and stale eligibility waiters terminate`() =
        runTest {
            val authority = DirectDnsUnderlayAuthority()
            val network = testNetwork(100)
            val epoch = authority.beginCallbackEpoch()
            val initialChange = authority.changes.value
            val staleWait =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { authority.awaitEligible(epoch) }
                }

            authority.onAvailable(epoch, network)
            assertTrue(authority.changes.value > initialChange)
            authority.endCallbackEpoch(epoch)

            assertTrue(staleWait.await().isFailure)
        }

    @Test
    fun `authority readiness waits for a validated non vpn network with dns`() =
        runTest {
            val authority = DirectDnsUnderlayAuthority()
            val network = testNetwork(100)
            val epoch = authority.beginCallbackEpoch()
            val ready = async(start = CoroutineStart.UNDISPATCHED) { authority.awaitEligible(epoch) }

            authority.onAvailable(epoch, network)
            authority.onLinkPropertiesChanged(epoch, network, linkProperties("1.1.1.1"))
            assertFalse(ready.isCompleted)

            authority.onCapabilitiesChanged(epoch, network, eligibleCapabilities())
            ready.await()
        }

    @Test
    fun `authority token is exact monotonic and ignores stale callback interleavings`() {
        val authority = DirectDnsUnderlayAuthority()
        val networkA = testNetwork(100)
        val networkB = testNetwork(200)
        val capabilities = eligibleCapabilities()
        val links = linkProperties("1.1.1.1")
        val epoch = authority.beginCallbackEpoch()

        authority.onAvailable(epoch, networkA)
        authority.onLinkPropertiesChanged(epoch, networkA, links)
        assertNull("capabilities are required", authority.snapshot(epoch))
        authority.onCapabilitiesChanged(epoch, networkA, capabilities)
        val tokenA = checkNotNull(authority.generationFor(networkA, capabilities, links))
        assertEquals(tokenA, authority.generationFor(networkA, capabilities, links))

        authority.onCapabilitiesChanged(epoch, networkA, capabilities)
        authority.onLinkPropertiesChanged(epoch, networkA, links)
        assertEquals("duplicate callbacks retain generation", tokenA, authority.snapshot(epoch)?.generation)

        authority.onAvailable(epoch, networkB)
        authority.onCapabilitiesChanged(epoch, networkA, capabilities)
        authority.onLinkPropertiesChanged(epoch, networkA, links)
        assertNull("late A callbacks cannot revive A", authority.generationFor(networkA, capabilities, links))
        authority.onCapabilitiesChanged(epoch, networkB, capabilities)
        authority.onLinkPropertiesChanged(epoch, networkB, links)
        val tokenB = checkNotNull(authority.generationFor(networkB, capabilities, links))
        assertTrue(tokenB > tokenA)

        authority.onLost(epoch, networkB)
        authority.onAvailable(epoch, networkB)
        authority.onLinkPropertiesChanged(epoch, networkB, links)
        authority.onCapabilitiesChanged(epoch, networkB, capabilities)
        val tokenB2 = checkNotNull(authority.generationFor(networkB, capabilities, links))
        assertTrue("ABA gets a fresh token", tokenB2 > tokenB)

        authority.endCallbackEpoch(epoch)
        authority.onAvailable(epoch, networkA)
        assertNull("old callback epoch cannot resurrect state", authority.snapshot(epoch))
    }

    @Test
    fun `numeric parser rejects hostname and noncanonical IPv4 without resolving`() {
        assertNull(parseDirectDnsNumericAddress("resolver.example"))
        assertNull(parseDirectDnsNumericAddress("001.1.1.1"))
        assertEquals("1.1.1.1", parseDirectDnsNumericAddress("1.1.1.1")?.hostAddress)
    }

    @Test
    @Config(sdk = [27])
    fun `numeric parser accepts IPv6 on min sdk without hostname fallback`() {
        assertTrue(parseDirectDnsNumericAddress("2001:4860:4860::8888") is Inet6Address)
        assertNull(parseDirectDnsNumericAddress("2001:db8::\u0661"))
        assertNull(parseDirectDnsNumericAddress(" 2001:db8::1"))
        assertNull(parseDirectDnsNumericAddress("fe80::1%wlan0"))
    }

    @Test
    fun `underlay eligibility is fail closed for every missing capability`() {
        assertTrue(
            isDirectDnsUnderlayEligible(hasInternet = true, validated = true, notVpn = true, captivePortal = false),
        )
        assertFalse(
            isDirectDnsUnderlayEligible(hasInternet = false, validated = true, notVpn = true, captivePortal = false),
        )
        assertFalse(
            isDirectDnsUnderlayEligible(hasInternet = true, validated = false, notVpn = true, captivePortal = false),
        )
        assertFalse(
            isDirectDnsUnderlayEligible(hasInternet = true, validated = true, notVpn = false, captivePortal = false),
        )
        assertFalse(
            isDirectDnsUnderlayEligible(hasInternet = true, validated = true, notVpn = true, captivePortal = true),
        )
    }

    @Test
    fun `lease requires exact DNS set and exact policy network identity`() {
        val resolverA = InetAddress.getByName("1.1.1.1")
        val resolverB = InetAddress.getByName("8.8.8.8")
        val state = DirectDnsUnderlayLeaseState<String>()

        state.capture("wifi", snapshotGeneration = 10L, eligible = true, dnsServers = setOf(resolverA, resolverB))
        val partialToken = state.preparePolicy(setOf(resolverA), complete = true, networkGeneration = 10L)
        assertNull(state.preparedLease(partialToken))

        val wifiToken = state.preparePolicy(setOf(resolverA, resolverB), complete = true, networkGeneration = 10L)
        val wifiLease = checkNotNull(state.preparedLease(wifiToken))
        assertTrue(state.commitPrepared(wifiToken))
        assertTrue(state.isCommittedCurrent(wifiLease.leaseGeneration))

        assertFalse(
            state.capture("wifi", snapshotGeneration = 10L, eligible = true, dnsServers = setOf(resolverA, resolverB)),
        )
        assertTrue(state.isCommittedCurrent(wifiLease.leaseGeneration))

        state.capture("cell", snapshotGeneration = 20L, eligible = true, dnsServers = setOf(resolverA, resolverB))
        assertNull("same DNS set must not authorize a different underlay", state.committedLease())
        val cellToken = state.preparePolicy(setOf(resolverA, resolverB), complete = true, networkGeneration = 20L)
        val cellLease = checkNotNull(state.preparedLease(cellToken))
        assertTrue(state.commitPrepared(cellToken))
        assertTrue(cellLease.leaseGeneration > wifiLease.leaseGeneration)
        assertFalse(state.isCommittedCurrent(wifiLease.leaseGeneration))

        assertTrue(state.invalidate("cell"))
        assertNull(state.committedLease())
        assertFalse(state.isCommittedCurrent(cellLease.leaseGeneration))
    }

    @Test
    fun `ineligible network and incomplete numeric policy never create lease`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()

        state.capture("vpn", snapshotGeneration = 1L, eligible = false, dnsServers = setOf(resolver))
        val vpnToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 1L)
        assertNull(state.preparedLease(vpnToken))

        state.capture("wifi", snapshotGeneration = 2L, eligible = true, dnsServers = setOf(resolver))
        val incompleteToken = state.preparePolicy(setOf(resolver), complete = false, networkGeneration = 2L)
        assertNull(state.preparedLease(incompleteToken))
    }

    @Test
    fun `cold start relay hostname resolution uses eligible authority without direct DNS policy`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val leaseState = DirectDnsUnderlayLeaseState<String>()
        leaseState.capture("wifi", snapshotGeneration = 7L, eligible = true, dnsServers = setOf(resolver))
        assertNull("relay bootstrap must not depend on a split-DNS policy lease", leaseState.committedLease())

        var current = ResolverUnderlaySnapshot("wifi", generation = 7L)
        val stable = resolveOnCurrentUnderlay(snapshot = { current }, resolve = { arrayOf("192.0.2.10") })
        assertEquals(listOf("192.0.2.10"), stable?.toList())

        fun staleLookup(network: String): Array<String> {
            assertEquals("wifi", network)
            current = ResolverUnderlaySnapshot("cell", generation = 8L)
            return arrayOf("192.0.2.11")
        }
        val stale = resolveOnCurrentUnderlay(snapshot = { current }, resolve = ::staleLookup)
        assertNull("network change during relay bootstrap must reject the result", stale)
    }

    @Test
    fun `callback completion publishes only after VPN establishment`() {
        assertFalse(shouldPublishDirectDnsLease(snapshotChanged = false, livePublicationEnabled = false))
        assertFalse(shouldPublishDirectDnsLease(snapshotChanged = true, livePublicationEnabled = false))
        assertFalse(shouldPublishDirectDnsLease(snapshotChanged = false, livePublicationEnabled = true))
        assertTrue(shouldPublishDirectDnsLease(snapshotChanged = true, livePublicationEnabled = true))
    }

    @Test
    fun `no direct policy keeps default underlay and JNI lease unavailable`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()
        state.capture("wifi", snapshotGeneration = 1L, eligible = true, dnsServers = setOf(resolver))
        val token = state.preparePolicy(emptySet(), complete = true, networkGeneration = 1L)

        assertNull(vpnUnderlay(state.preparedLease(token), state.preparedRequiresDirectUnderlay(token)))
        assertEquals(0L, leaseGenerationOrZero(state.preparedLease(token)))

        var published: List<String>? = listOf("unexpected")
        assertFalse(
            applyPreparedDirectDnsLease(
                current = state.preparedLease(token),
                directUnderlayRequired = state.preparedRequiresDirectUnderlay(token),
                publish = { networks ->
                    published = networks
                    true
                },
                clearIfSame = { snapshot -> if (state.preparedLease(token) === snapshot) state.abortPrepared(token) },
            ),
        )
        assertNull("null retains Android's system-default encrypted-only underlay", published)
    }

    @Test
    fun `pre establish callback preserves exact builder lease until live publication`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()
        val token = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 7L)
        state.capture("wifi", snapshotGeneration = 7L, eligible = true, dnsServers = setOf(resolver))
        val prepared = checkNotNull(state.preparedLease(token))
        assertNull("prepared replacement must not become JNI-visible before native start", state.committedLease())

        assertFalse(shouldPublishDirectDnsLease(snapshotChanged = true, livePublicationEnabled = false))
        assertEquals(listOf("wifi"), vpnUnderlay(prepared, state.preparedRequiresDirectUnderlay(token)))
        assertEquals(prepared.leaseGeneration, leaseGenerationOrZero(prepared))

        val publications = mutableListOf<List<String>?>()
        assertTrue(
            applyPreparedDirectDnsLease(
                current = prepared,
                publish = { networks ->
                    publications += networks
                    true
                },
                clearIfSame = { snapshot -> if (state.preparedLease(token) === snapshot) state.abortPrepared(token) },
            ),
        )
        assertEquals(listOf(listOf("wifi")), publications)
    }

    @Test
    fun `preparing replacement B keeps committed A visible until exact commit or abort`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()
        state.capture("wifi", snapshotGeneration = 7L, eligible = true, dnsServers = setOf(resolver))
        val tokenA = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 7L)
        val preparedA = checkNotNull(state.preparedLease(tokenA))
        assertTrue(state.commitPrepared(tokenA))
        assertEquals(preparedA, state.committedLease())

        val tokenB = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 7L)
        val preparedB = checkNotNull(state.preparedLease(tokenB))

        assertTrue(preparedB.leaseGeneration > preparedA.leaseGeneration)
        assertEquals("old runtime must keep committed A while B is staged", preparedA, state.committedLease())
        assertTrue(state.isCommittedCurrent(preparedA.leaseGeneration))
        assertFalse(state.isCommittedCurrent(preparedB.leaseGeneration))
        assertTrue(state.abortPrepared(tokenB))
        assertEquals("failed establish must preserve committed A", preparedA, state.committedLease())
        assertNull(state.preparedLease(tokenB))
    }

    @Test
    fun `failed rebuild keeps active tunnel publication enabled for later handover`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()
        var activeTunnelPublicationEnabled = true

        state.capture("wifi", snapshotGeneration = 7L, eligible = true, dnsServers = setOf(resolver))
        val liveToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 7L)
        checkNotNull(state.preparedLease(liveToken))
        assertTrue(state.commitPrepared(liveToken))

        val failedToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 8L)
        assertTrue("policy preparation must not disable the established tunnel", activeTunnelPublicationEnabled)
        assertNull(state.preparedLease(failedToken))
        assertEquals(
            emptyList<String>(),
            vpnUnderlay(state.preparedLease(failedToken), state.preparedRequiresDirectUnderlay(failedToken)),
        )

        val failedRebuildPublications = mutableListOf<List<String>?>()
        assertFalse(
            applyPreparedDirectDnsLease(
                current = state.preparedLease(failedToken),
                directUnderlayRequired = state.preparedRequiresDirectUnderlay(failedToken),
                publish = { networks ->
                    failedRebuildPublications += networks
                    true
                },
                clearIfSame = { snapshot ->
                    if (state.preparedLease(failedToken) === snapshot) state.abortPrepared(failedToken)
                },
            ),
        )
        assertEquals(listOf(emptyList<String>()), failedRebuildPublications)

        state.capture("cell", snapshotGeneration = 8L, eligible = true, dnsServers = setOf(resolver))
        assertTrue(shouldPublishDirectDnsLease(snapshotChanged = true, activeTunnelPublicationEnabled))
        activeTunnelPublicationEnabled = false
        assertFalse(shouldPublishDirectDnsLease(snapshotChanged = true, activeTunnelPublicationEnabled))
    }

    @Test
    fun `callback registration publishes epoch before synchronous and concurrent delivery`() {
        val lock = Any()
        var published: String? = null
        val callbackAttempted = CountDownLatch(1)
        val callbackCompleted = CountDownLatch(1)
        lateinit var callbackThread: Thread

        assertTrue(
            registerPublishedCallback(
                lock = lock,
                prepare = {
                    published = "epoch-1"
                    "callback"
                },
                register = { callback ->
                    assertEquals("callback", callback)
                    assertEquals("epoch-1", published)
                    callbackThread =
                        thread(start = true) {
                            callbackAttempted.countDown()
                            synchronized(lock) {
                                assertEquals("epoch-1", published)
                                callbackCompleted.countDown()
                            }
                        }
                    assertTrue(callbackAttempted.await(1, TimeUnit.SECONDS))
                    assertFalse(callbackCompleted.await(50, TimeUnit.MILLISECONDS))
                },
                rollback = { error("registration must not roll back") },
            ),
        )
        assertTrue(callbackCompleted.await(1, TimeUnit.SECONDS))
        callbackThread.join()
    }

    @Test
    fun `callback registration runtime failure rolls back published epoch`() {
        var published: String? = null
        var rolledBack: String? = null

        val failure =
            runCatching {
                registerPublishedCallback(
                    lock = Any(),
                    prepare = {
                        published = "epoch-1"
                        "callback"
                    },
                    register = { error("callback limit") },
                    rollback = { callback ->
                        rolledBack = callback
                        published = null
                    },
                )
            }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("callback", rolledBack)
        assertNull(published)
    }

    @Test
    fun `publish false or throw clears the exact prepared lease and publishes empty on loss`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()
        state.capture("wifi", snapshotGeneration = 1L, eligible = true, dnsServers = setOf(resolver))
        val firstToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 1L)
        val first = checkNotNull(state.preparedLease(firstToken))

        assertFalse(
            applyPreparedDirectDnsLease(
                current = first,
                publish = { false },
                clearIfSame = { snapshot ->
                    if (state.preparedLease(firstToken) === snapshot) state.abortPrepared(firstToken)
                },
            ),
        )
        assertNull(state.preparedLease(firstToken))

        val secondToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 1L)
        val second = checkNotNull(state.preparedLease(secondToken))
        assertFalse(
            applyPreparedDirectDnsLease(
                current = second,
                publish = { error("publisher failed") },
                clearIfSame = { snapshot ->
                    if (state.preparedLease(secondToken) === snapshot) state.abortPrepared(secondToken)
                },
            ),
        )
        assertNull(state.preparedLease(secondToken))

        var published: List<String>? = null
        assertFalse(
            applyPreparedDirectDnsLease(
                current = null,
                directUnderlayRequired = true,
                publish = { networks ->
                    published = networks
                    true
                },
                clearIfSame = {},
            ),
        )
        assertEquals(emptyList<String>(), published)

        val staleToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 1L)
        val stale = checkNotNull(state.preparedLease(staleToken))
        val publications = mutableListOf<List<String>?>()
        assertFalse(
            applyPreparedDirectDnsLease(
                current = stale,
                publish = { networks ->
                    publications += networks
                    true
                },
                clearIfSame = { snapshot ->
                    if (state.preparedLease(staleToken) === snapshot) state.abortPrepared(staleToken)
                },
                stillCurrent = { false },
            ),
        )
        assertEquals(listOf(listOf("wifi"), emptyList()), publications)
        assertNull(state.preparedLease(staleToken))
        assertEquals(0L, leaseGenerationOrZero(state.preparedLease(staleToken)))
    }

    @Test
    fun `socket bind follows protect duplicate bind close and generation recheck order`() {
        val events = mutableListOf<String>()
        val descriptor = FileDescriptor()
        val lease = DirectDnsUnderlayLease("wifi", 1, 1, 7)
        val ops = recordingOps(events, descriptor)

        assertTrue(
            bindDirectDnsSocketLease(
                fd = 42,
                generation = 7,
                lease = {
                    events += "snapshot"
                    lease
                },
                isCurrent = {
                    events += "recheck"
                    true
                },
                ops = ops,
            ),
        )
        assertEquals(listOf("snapshot", "protect:42", "duplicate:42", "bind:wifi", "close", "recheck"), events)
    }

    @Test
    fun `socket bind fails closed before and after every fallible boundary`() {
        val lease = DirectDnsUnderlayLease("wifi", 1, 1, 7)

        assertFalse(
            bindDirectDnsSocketLease(42, 8, { lease }, { true }, recordingOps(mutableListOf(), FileDescriptor())),
        )

        val protectEvents = mutableListOf<String>()
        assertFalse(
            bindDirectDnsSocketLease(
                42,
                7,
                { lease },
                { true },
                recordingOps(protectEvents, FileDescriptor(), protectResult = false),
            ),
        )
        assertEquals(listOf("protect:42"), protectEvents)

        val protectThrowEvents = mutableListOf<String>()
        assertFalse(
            bindDirectDnsSocketLease(
                42,
                7,
                { lease },
                { true },
                recordingOps(protectThrowEvents, FileDescriptor(), protectFailure = true),
            ),
        )
        assertEquals(listOf("protect:42"), protectThrowEvents)

        val duplicateEvents = mutableListOf<String>()
        assertFalse(
            bindDirectDnsSocketLease(
                42,
                7,
                { lease },
                { true },
                recordingOps(duplicateEvents, FileDescriptor(), duplicateFailure = true),
            ),
        )
        assertEquals(listOf("protect:42", "duplicate:42"), duplicateEvents)

        val bindEvents = mutableListOf<String>()
        assertFalse(
            bindDirectDnsSocketLease(
                42,
                7,
                { lease },
                { true },
                recordingOps(bindEvents, FileDescriptor(), bindFailure = true),
            ),
        )
        assertEquals(listOf("protect:42", "duplicate:42", "bind:wifi", "close"), bindEvents)

        val staleEvents = mutableListOf<String>()
        assertFalse(
            bindDirectDnsSocketLease(
                42,
                7,
                { lease },
                {
                    staleEvents += "recheck"
                    false
                },
                recordingOps(staleEvents, FileDescriptor()),
            ),
        )
        assertEquals(listOf("protect:42", "duplicate:42", "bind:wifi", "close", "recheck"), staleEvents)
    }

    private fun recordingOps(
        events: MutableList<String>,
        descriptor: FileDescriptor,
        protectResult: Boolean = true,
        protectFailure: Boolean = false,
        duplicateFailure: Boolean = false,
        bindFailure: Boolean = false,
    ): DirectDnsSocketBindingOps<String> =
        object : DirectDnsSocketBindingOps<String> {
            override fun protect(fd: Int): Boolean {
                events += "protect:$fd"
                if (protectFailure) error("protect failed")
                return protectResult
            }

            override fun duplicate(fd: Int): DirectDnsFileDescriptor {
                events += "duplicate:$fd"
                if (duplicateFailure) error("duplicate failed")
                return object : DirectDnsFileDescriptor {
                    override val fileDescriptor: FileDescriptor = descriptor

                    override fun close() {
                        events += "close"
                    }
                }
            }

            override fun bind(
                network: String,
                fileDescriptor: FileDescriptor,
            ) {
                assertEquals(descriptor, fileDescriptor)
                events += "bind:$network"
                if (bindFailure) error("bind failed")
            }
        }

    private fun testNetwork(id: Int): Network = ShadowNetwork.newInstance(id)

    private fun eligibleCapabilities(): NetworkCapabilities =
        NetworkCapabilities().also { capabilities ->
            listOf(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
                NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED,
                NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
            ).forEach { capability ->
                NetworkCapabilities::class
                    .java
                    .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                    .invoke(capabilities, capability)
            }
        }

    private fun linkProperties(dns: String): LinkProperties =
        LinkProperties().also { links -> links.setDnsServers(listOf(InetAddress.getByName(dns))) }
}
