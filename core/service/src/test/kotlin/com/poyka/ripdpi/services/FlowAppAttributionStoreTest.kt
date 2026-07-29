package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowAppAttributionStoreTest {
    @Test
    fun `admission-only request resolves uid without destination attribution`() {
        val store = RecordingResolutionStore()

        val uid = resolveFlowRequest(store, 17, "10.0.0.2", 53000, "198.18.0.10", 53, AdmissionOnlyFlowRequest)

        assertEquals(10123, uid)
        assertEquals(0, store.attributionCalls)
        assertEquals(1, store.uidOnlyCalls)
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
        assertEquals(1L, epoch.uidPolicyDeniedTcpCount.get())
        assertEquals(1L, epoch.uidPolicyDeniedUdpCount.get())
        assertEquals(1L, epoch.uidPolicyDeniedOtherCount.get())
    }

    @Test
    fun `non-admission attribution does not fabricate a policy denial`() {
        val epoch = UidPolicyQualificationEpoch(NativeUidPolicy("denylist", listOf(10123)))

        epoch.record(protocol = 6, uid = 10123, requestKind = 0)

        assertEquals(1L, epoch.uidResolvedCount.get())
        assertEquals(0L, epoch.uidPolicyDeniedTcpCount.get())
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
        assertEquals(1L, bridge.snapshot().uidPolicyDeniedTcpCount)

        bridge.deactivateUidPolicy()

        assertFalse(bridge.snapshot().uidPolicyArmed)
        assertEquals(0L, bridge.snapshot().uidResolvedCount)
        assertEquals(0L, bridge.snapshot().uidPolicyDeniedTcpCount)
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
