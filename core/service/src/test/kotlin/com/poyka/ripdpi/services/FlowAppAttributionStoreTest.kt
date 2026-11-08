package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FlowAppAttributionStoreTest {
    @Test
    fun `admission-only request resolves uid without destination attribution`() {
        val store = RecordingResolutionStore()

        val uid = resolveFlowRequest(store, 17, "10.0.0.2", 53000, "198.18.0.10", 53, AdmissionOnlyFlowRequest)

        assertEquals(10123, uid)
        assertEquals(0, store.attributionCalls)
        assertEquals(1, store.uidOnlyCalls)
    }

    @Test
    fun `retired resolver cannot record into replacement qualification epoch`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store =
            object : FlowAppAttributionStore by RecordingResolutionStore() {
                override fun resolveSessionFlowUid(
                    session: Long,
                    protocol: Int,
                    localIp: String,
                    localPort: Int,
                    remoteIp: String,
                    remotePort: Int,
                    requestKind: Int,
                ): Int = resolveFlowUidOnly(protocol, localIp, localPort, remoteIp, remotePort)

                override fun resolveFlowUidOnly(
                    protocol: Int,
                    localIp: String,
                    localPort: Int,
                    remoteIp: String,
                    remotePort: Int,
                ): Int {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return 10123
                }
            }
        val bridge =
            FlowAttributionBridge(
                store,
                null,
                SoBindToDeviceUidPolicyEligibility.forTest(
                    sdkInt = android.os.Build.VERSION_CODES.S,
                    kernelRelease = "6.1.99-android",
                    probe = { BindToDeviceProbeOutcome.Supported },
                ),
            )
        bridge.activateUidPolicy(NativeUidPolicy("allowlist", listOf(10123)))
        val resolver =
            Thread {
                bridge.noteFlow(17, "10.0.0.2", 53000, "203.0.113.7", 443, AdmissionOnlyFlowRequest)
            }
        resolver.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            bridge.deactivateUidPolicy()
            bridge.activateUidPolicy(NativeUidPolicy("denylist", listOf(10123)))
        } finally {
            release.countDown()
            resolver.join(5000)
        }
        assertFalse(resolver.isAlive)
        assertEquals(0L, bridge.snapshot().uidResolvedCount)
    }

    @Test
    fun `ended session rejects late writes and stale teardown preserves its replacement`() {
        val cache = FlowAttributionCache(2)
        val first = cache.beginSession()
        val firstRevision = requireNotNull(cache.resolutionRevision(first))
        val appA = FlowAttribution.Attributed("com.example.a", 1)
        assertTrue(cache.commitResolution(first, firstRevision, "destination" to appA))
        cache.endSession(first)
        assertEquals(null, cache.get("destination"))

        val replacement = cache.beginSession()
        val replacementRevision = requireNotNull(cache.resolutionRevision(replacement))
        val appB = FlowAttribution.Attributed("com.example.b", 2)
        assertTrue(cache.commitResolution(replacement, replacementRevision, "destination" to appB))
        assertFalse(cache.commitResolution(first, firstRevision, "destination" to appA))
        cache.endSession(first)
        assertEquals(appB, cache.get("destination"))
    }

    @Test
    fun `package invalidation fences a resolver which has not committed yet`() {
        val cache = FlowAttributionCache(2)
        val session = cache.beginSession()
        val beforeUpdate = requireNotNull(cache.resolutionRevision(session))
        cache.removeIf { false }
        val oldApp = FlowAttribution.Attributed("com.example.a", 1)

        assertFalse(cache.commitResolution(session, beforeUpdate, "destination" to oldApp))
        assertEquals(null, cache.get("destination"))
        val afterUpdate = requireNotNull(cache.resolutionRevision(session))
        val updatedApp = FlowAttribution.Attributed("com.example.a", 2)
        assertTrue(cache.commitResolution(session, afterUpdate, "destination" to updatedApp))
        assertEquals(updatedApp, cache.get("destination"))
    }

    @Test
    fun `callback invoked after replacement cannot resolve for the new epoch`() {
        val bridge =
            FlowAttributionBridge(
                RecordingResolutionStore(),
                null,
                SoBindToDeviceUidPolicyEligibility.forTest(
                    sdkInt = android.os.Build.VERSION_CODES.S,
                    kernelRelease = "6.1.99-android",
                    probe = { BindToDeviceProbeOutcome.Supported },
                ),
            )
        val retired = bridge.activateUidPolicy(NativeUidPolicy("allowlist", listOf(10123)))
        bridge.activateUidPolicy(NativeUidPolicy("denylist", listOf(10123)))

        assertEquals(InvalidUid, retired.noteFlow(17, "10.0.0.2", 53000, "203.0.113.7", 443, AdmissionOnlyFlowRequest))
        assertEquals(0L, bridge.snapshot().uidResolvedCount)
    }

    @Test
    fun `attribution cache evicts the least recently used destination at capacity`() {
        val cache = FlowAttributionCache(capacity = 2)
        val app = FlowAttribution.Attributed("com.example.app", 1L)
        cache.put("a", app)
        cache.put("b", app)
        assertEquals(app, cache.get("a"))

        cache.put("c", app)

        assertEquals(2, cache.size)
        assertEquals(app, cache.get("a"))
        assertEquals(null, cache.get("b"))
        assertEquals(app, cache.get("c"))
    }

    // ── Digest parity ─────────────────────────────────────────────────────────
    // These vectors are the lowercase hex of the first 8 bytes of SHA-256(ipString),
    // identical to the Rust `direct_path_ip_set_digest` for a single destination IP.
    // They MUST match the proxy `.so`'s digest so the Kotlin join correlates.

    @Test
    fun `digest matches the rust single-ip vectors`() {
        assertEquals("f1412386aa8db257", flowAttributionDigest("1.1.1.1"))
        assertEquals("e14759884124ebda", flowAttributionDigest("93.184.216.34"))
        assertEquals("dbb719a71fd81182", flowAttributionDigest("2606:4700:4700::1111"))
    }

    @Test
    fun `digest is sixteen lowercase hex chars`() {
        val digest = flowAttributionDigest("203.0.113.7")
        assertEquals(16, digest.length)
        assertEquals(digest.lowercase(), digest)
        assertEquals(digest, flowAttributionDigest("203.0.113.7"))
    }

    // ── decideAttribution ─────────────────────────────────────────────────────

    @Test
    fun `invalid uid is unattributed`() {
        assertEquals(
            FlowAttribution.Unattributed,
            decideAttribution(uid = -1, packagesForUid = arrayOf("com.example.app")) { 7L },
        )
    }

    @Test
    fun `null or empty packages are unattributed`() {
        assertEquals(FlowAttribution.Unattributed, decideAttribution(uid = 10123, packagesForUid = null) { 7L })
        assertEquals(FlowAttribution.Unattributed, decideAttribution(uid = 10123, packagesForUid = emptyArray()) { 7L })
    }

    @Test
    fun `shared uid with multiple packages is unattributed`() {
        assertEquals(
            FlowAttribution.Unattributed,
            decideAttribution(uid = 10123, packagesForUid = arrayOf("com.a", "com.b")) { 7L },
        )
    }

    @Test
    fun `single package with a missing version is unattributed`() {
        assertEquals(
            FlowAttribution.Unattributed,
            decideAttribution(uid = 10123, packagesForUid = arrayOf("com.example.app")) { null },
        )
    }

    @Test
    fun `single package with a version is attributed`() {
        assertEquals(
            FlowAttribution.Attributed("com.example.app", 42L),
            decideAttribution(uid = 10123, packagesForUid = arrayOf("com.example.app")) { 42L },
        )
    }

    @Test
    fun `native uid policy is disarmed when SO_BINDTODEVICE is ineligible`() {
        val policy =
            nativeUidPolicyFor(
                plan = VpnAppRoutingPlan.AllowOnly(setOf("com.example.allowed")),
                eligible = false,
                ownPackage = "com.example.vpn",
                uidForPackage = { 10123 },
            )

        assertEquals(NativeUidPolicy.Disarmed, policy)
    }

    @Test
    fun `native uid allowlist mirrors resolved builder packages`() {
        val uids = mapOf("com.example.one" to 10123, "com.example.two" to 10124, "com.example.shared" to 10123)
        val policy =
            nativeUidPolicyFor(
                plan = VpnAppRoutingPlan.AllowOnly(uids.keys + "com.example.missing"),
                eligible = true,
                ownPackage = "com.example.vpn",
                uidForPackage = uids::get,
            )

        assertEquals(NativeUidPolicy("allowlist", listOf(10123, 10124)), policy)
    }

    @Test
    fun `native uid allowlist fails closed when no package remains installed`() {
        val policy =
            nativeUidPolicyFor(
                plan = VpnAppRoutingPlan.AllowOnly(setOf("com.example.missing")),
                eligible = true,
                ownPackage = "com.example.vpn",
                uidForPackage = { null },
            )

        assertEquals(NativeUidPolicy("allowlist", emptyList()), policy)
    }

    @Test
    fun `native uid denylist mirrors resolved builder packages`() {
        val policy =
            nativeUidPolicyFor(
                plan = VpnAppRoutingPlan.Disallow(setOf("com.example.denied")),
                eligible = true,
                ownPackage = "com.example.vpn",
                uidForPackage = { packageName -> if (packageName == "com.example.denied") 10420 else null },
            )

        assertEquals(NativeUidPolicy("denylist", listOf(10420)), policy)
    }

    @Test
    fun `native uid policy is disarmed for full tunnel own package exclusion`() {
        val policy =
            nativeUidPolicyFor(
                plan = VpnAppRoutingPlan.Disallow(setOf("com.example.vpn")),
                eligible = true,
                ownPackage = "com.example.vpn",
                uidForPackage = { 10420 },
            )

        assertEquals(NativeUidPolicy.Disarmed, policy)
    }

    @Test
    fun `native uid denylist remains armed when a third party is excluded`() {
        val uids = mapOf("com.example.vpn" to 10420, "com.example.denied" to 10421)
        val policy =
            nativeUidPolicyFor(
                plan = VpnAppRoutingPlan.Disallow(uids.keys),
                eligible = true,
                ownPackage = "com.example.vpn",
                uidForPackage = uids::get,
            )

        assertEquals(NativeUidPolicy("denylist", listOf(10420, 10421)), policy)
    }

    @Test
    fun `qualification epoch counts categorical attribution outcomes and protocol denials`() {
        val epoch = UidPolicyQualificationEpoch(NativeUidPolicy("allowlist", listOf(10123)))

        epoch.record(protocol = 6, uid = 10123, requestKind = AdmissionOnlyFlowRequest)
        epoch.record(protocol = 6, uid = 10420, requestKind = AdmissionOnlyFlowRequest)
        epoch.record(protocol = 17, uid = InvalidUid, requestKind = AdmissionOnlyFlowRequest)
        epoch.record(protocol = 132, uid = 10421, requestKind = AdmissionOnlyFlowRequest)

        assertEquals(3L, epoch.uidResolvedCount.get())
        assertEquals(1L, epoch.uidUnresolvedCount.get())
        assertEquals(1L, epoch.policyDecisionDeniedTcpCount.get())
        assertEquals(1L, epoch.policyDecisionDeniedUdpCount.get())
        assertEquals(1L, epoch.policyDecisionDeniedOtherCount.get())
    }

    @Test
    fun `non-admission attribution does not fabricate a policy denial`() {
        val epoch = UidPolicyQualificationEpoch(NativeUidPolicy("denylist", listOf(10123)))

        epoch.record(protocol = 6, uid = 10123, requestKind = 0)

        assertEquals(1L, epoch.uidResolvedCount.get())
        assertEquals(0L, epoch.policyDecisionDeniedTcpCount.get())
    }

    @Test
    fun `bridge qualification is armed only for an active native policy epoch`() {
        val bridge =
            FlowAttributionBridge(
                RecordingResolutionStore(),
                null,
                SoBindToDeviceUidPolicyEligibility.forTest(
                    sdkInt = android.os.Build.VERSION_CODES.S,
                    kernelRelease = "6.1.99-android",
                    probe = { BindToDeviceProbeOutcome.Supported },
                ),
            )

        bridge.activateUidPolicy(NativeUidPolicy("allowlist", listOf(10420)))
        bridge.noteFlow(6, "10.0.0.2", 53000, "198.18.0.10", 443, AdmissionOnlyFlowRequest)

        assertTrue(bridge.snapshot().uidPolicyArmed)
        assertEquals(1L, bridge.snapshot().uidResolvedCount)
        assertEquals(1L, bridge.snapshot().policyDecisionDeniedTcpCount)

        bridge.deactivateUidPolicy()

        assertFalse(bridge.snapshot().uidPolicyArmed)
        assertEquals(0L, bridge.snapshot().uidResolvedCount)
        assertEquals(0L, bridge.snapshot().policyDecisionDeniedTcpCount)
    }
}

private class RecordingResolutionStore : FlowAppAttributionStore {
    var attributionCalls = 0
    var uidOnlyCalls = 0

    override fun noteFlow(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ) = Unit

    override fun resolveFlowUid(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ): Int {
        attributionCalls += 1
        return 10123
    }

    override fun resolveFlowUidOnly(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ): Int {
        uidOnlyCalls += 1
        return 10123
    }

    override fun lookup(ipSetDigest: String): FlowAttribution.Attributed? = null

    override fun invalidateOnAppUpdate(
        packageName: String,
        newVersionCode: Long,
    ) = Unit

    override fun clear() = Unit
}
