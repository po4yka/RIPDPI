package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowAppAttributionStoreTest {
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
}
