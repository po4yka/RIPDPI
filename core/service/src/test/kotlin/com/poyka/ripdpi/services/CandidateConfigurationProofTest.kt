package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateConfigurationProofTest {
    @Test fun `only listener and protection ownership differences are normalized`() {
        val measured = sampleResolvedRelayConfig()
        val proof = CandidateConfigurationProofs.relay(measured)
        val actual =
            measured.copy(
                localSocksHost = "::1",
                localSocksPort = 18181,
                socketProtection = RelaySocketProtection.VpnRequired,
            )
        assertTrue(proof.matches(CandidateConfigurationProofs.relay(actual)))
        assertFalse(proof.toString().contains(measured.server))
    }

    @Test fun `crypto policy credentials and every transient helper input remain bound`() {
        val measured = sampleResolvedRelayConfig()
        val proof = CandidateConfigurationProofs.relay(measured)
        val changed =
            listOf(
                measured.copy(tlsFingerprintProfile = "firefox_stable"),
                measured.copy(quicBindLowPort = true),
                measured.copy(quicMigrateAfterHandshake = true),
                measured.copy(server = "other.fixture"),
                measured.copy(vlessUuid = "changed-secret"),
                measured.copy(ptBridgeLine = "bridge-input"),
                measured.copy(ptWebTunnelUrl = "https://webtunnel.fixture"),
                measured.copy(ptSnowflakeBrokerUrl = "https://broker.fixture"),
                measured.copy(ptSnowflakeFrontDomain = "front.fixture"),
            )
        changed.forEach { assertFalse(proof.matches(CandidateConfigurationProofs.relay(it))) }
        assertTrue(proof.matches(CandidateConfigurationProofs.relay(measured)))
    }

    @Test fun `actual consumed B is rejected even after live input returns to A`() {
        val original = sampleResolvedRelayConfig()
        val measured = CandidateConfigurationProofs.relay(original)
        val consumed = ConsumedUpstreamConfiguration.relay(original.copy(quicBindLowPort = true))
        assertFalse(measured.matches(checkNotNull(consumed.measuredInputProof)))
        assertTrue(measured.matches(CandidateConfigurationProofs.relay(original)))
    }
}
