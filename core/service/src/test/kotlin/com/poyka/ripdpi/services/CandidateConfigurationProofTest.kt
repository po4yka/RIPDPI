package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateConfigurationProofTest {
    @Test fun `only listener, session credential and protection ownership differences are normalized`() {
        val measured = sampleResolvedRelayConfig()
        val proof = CandidateConfigurationProofs.relay(measured)
        val actual =
            measured.copy(
                localSocksHost = "::1",
                localSocksPort = 18181,
                localSocksUsername = "session-user",
                localSocksPassword = "pass-1",
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

    @Test fun `xray proof ignores the inbound address and session credentials but binds the outbound`() {
        fun rendered(
            port: Int,
            password: String,
            server: String = "192.0.2.1",
        ) = """{"inbounds":[{"tag":"socks-in","listen":"127.0.0.1","port":$port,"protocol":"socks",""" +
            """"settings":{"auth":"password","accounts":[{"user":"u-$password","pass":"$password"}],"udp":true}}],""" +
            """"outbounds":[{"tag":"proxy","settings":{"vnext":[{"address":"$server"}]}}]}"""
        val measured = CandidateConfigurationProofs.xray(rendered(41_000, "candidate"))

        assertTrue(measured.matches(CandidateConfigurationProofs.xray(rendered(10_808, "session"))))
        assertFalse(measured.matches(CandidateConfigurationProofs.xray(rendered(10_808, "session", "192.0.2.9"))))
    }

    @Test fun `actual consumed B is rejected even after live input returns to A`() {
        val original = sampleResolvedRelayConfig()
        val measured = CandidateConfigurationProofs.relay(original)
        val consumed = ConsumedUpstreamConfiguration.relay(original.copy(quicBindLowPort = true))
        assertFalse(measured.matches(checkNotNull(consumed.measuredInputProof)))
        assertTrue(measured.matches(CandidateConfigurationProofs.relay(original)))
    }
}
