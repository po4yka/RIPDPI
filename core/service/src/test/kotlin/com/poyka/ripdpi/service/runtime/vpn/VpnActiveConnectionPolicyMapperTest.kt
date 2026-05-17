package com.poyka.ripdpi.service.runtime.vpn

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.sampleRememberedPolicyEntity
import com.poyka.ripdpi.services.sampleResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnActiveConnectionPolicyMapperTest {
    @Test
    fun `toVpnActiveConnectionPolicy maps resolved policy metadata`() {
        val matchedPolicy = sampleRememberedPolicyEntity(mode = Mode.VPN)
        val resolution =
            sampleResolution(
                mode = Mode.VPN,
                matchedPolicy = matchedPolicy,
                policySignature = "vpn-policy-signature",
            ).copy(
                rememberedPolicyAppliedByExactMatch = true,
                handoverClassification = "transport_switch",
            )

        val policy =
            resolution.toVpnActiveConnectionPolicy(
                restartReason = "network_handover",
                appliedAt = 2_000L,
            )

        assertEquals(Mode.VPN, policy?.mode)
        assertEquals(resolution.appliedPolicy, policy?.policy)
        assertEquals(matchedPolicy, policy?.matchedPolicy)
        assertEquals(true, policy?.usedRememberedPolicy)
        assertEquals(true, policy?.rememberedPolicyAppliedByExactMatch)
        assertEquals(resolution.fingerprintHash, policy?.fingerprintHash)
        assertEquals("vpn-policy-signature", policy?.policySignature)
        assertEquals(2_000L, policy?.appliedAt)
        assertEquals("network_handover", policy?.restartReason)
        assertEquals("transport_switch", policy?.handoverClassification)
    }

    @Test
    fun `toVpnActiveConnectionPolicy returns null when no policy was applied`() {
        val resolution = sampleResolution(mode = Mode.VPN, appliedPolicy = null)

        assertNull(
            resolution.toVpnActiveConnectionPolicy(
                restartReason = "initial_start",
                appliedAt = 1_000L,
            ),
        )
    }
}
