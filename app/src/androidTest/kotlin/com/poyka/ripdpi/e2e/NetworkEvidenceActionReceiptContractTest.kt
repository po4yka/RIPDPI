package com.poyka.ripdpi.e2e

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkEvidenceActionReceiptContractTest {
    @Test
    fun querySetDigestUsesTheGateSpecificSemanticFactName() {
        val querySha256 = "a".repeat(64)

        assertEquals(
            "f661b77c58f58668879d2b906aa494d871a6835c67d27907a5f37b4c2a6a9a0b",
            networkEvidenceQuerySetSha256(NetworkEvidenceStartupGateId, querySha256),
        )
        assertEquals(
            "f09ccc7aa9e916685cfd02c54237fb3bda4b9363bf75d5372bcca17cdd928d53",
            networkEvidenceQuerySetSha256("dns-virtual-vpn-resolver", querySha256),
        )
        assertEquals(
            "2b115af61ea5625e84235432f7363e9e2510aa1a64acff1abcd2841098b44763",
            networkEvidenceQuerySetSha256("dns-proxied-through-tunnelled-resolver", querySha256),
        )
        assertEquals(
            "81d86fd778b9d249a2cbec18d2a0e07884d04046e34384f4b4f9b56ba1c55e50",
            networkEvidenceQuerySetSha256("dns-no-isp-fallback-on-encrypted-resolver-outage", querySha256),
        )
    }
}
