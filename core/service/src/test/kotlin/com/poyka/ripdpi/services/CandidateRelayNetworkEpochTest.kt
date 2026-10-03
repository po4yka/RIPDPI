package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateRelayNetworkEpochTest {
    @Test
    fun `each callback changes epoch even when network returns to its previous fingerprint`() {
        var changed: () -> Unit = {}
        val source =
            CandidateRelayNetworkEpoch(
                register = { callback ->
                    changed = callback
                    true
                },
                available = { true },
                underlayGeneration = { 0L },
            )
        val before = source.capture()
        changed() // A -> B
        changed() // B -> A
        assertEquals(2L to 0L, source.capture())
        assertNotEquals(before, source.capture())
    }

    @Test
    fun `physical VPN underlay ABA changes scope even if default VPN network is unchanged`() {
        var underlay = 1L
        val source = CandidateRelayNetworkEpoch({ true }, { true }, { underlay })
        val before = source.capture()
        underlay = 3L
        assertNotEquals(before, source.capture())
    }

    @Test
    fun `failed registration or absent active network cannot supply a probe scope`() {
        assertNull(CandidateRelayNetworkEpoch({ false }, { true }, { 0L }).capture())
        assertNull(CandidateRelayNetworkEpoch({ true }, { false }, { 0L }).capture())
    }
}
