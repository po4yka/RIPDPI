package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySocketProtectionPolicyTest {
    @Test
    fun `vpn mode rejects every relay path without complete protected dialing`() {
        val unsupported =
            listOf(
                sampleResolvedRelayConfig(kind = RelayKindGoogleAppsScript),
                sampleResolvedRelayConfig(kind = RelayKindTor),
                sampleResolvedRelayConfig(kind = RelayKindMasque).copy(
                    masqueAuthMode = RelayMasqueAuthModePrivacyPass,
                ),
                sampleResolvedRelayConfig(kind = RelayKindSnowflake),
                sampleResolvedRelayConfig(kind = RelayKindObfs4),
                sampleResolvedRelayConfig(kind = RelayKindCloudflareTunnel).copy(
                    cloudflareTunnelMode = RelayCloudflareTunnelModePublishLocalOrigin,
                ),
            )

        unsupported.forEach { config ->
            val error =
                runCatching {
                    validateRelaySocketProtectionPolicy(
                        config.copy(socketProtection = RelaySocketProtection.VpnRequired),
                    )
                }.exceptionOrNull()

            assertTrue("${config.kind} must return a typed startup rejection", error is ServiceStartupRejectedException)
            assertTrue(
                "${config.kind} must classify as relay config rejection",
                (error as ServiceStartupRejectedException).reason is FailureReason.RelayConfigRejected,
            )
        }
    }

    @Test
    fun `proxy mode preserves relay paths that VPN mode rejects`() {
        listOf(
            sampleResolvedRelayConfig(kind = RelayKindGoogleAppsScript),
            sampleResolvedRelayConfig(kind = RelayKindTor),
            sampleResolvedRelayConfig(kind = RelayKindMasque).copy(
                masqueAuthMode = RelayMasqueAuthModePrivacyPass,
            ),
            sampleResolvedRelayConfig(kind = RelayKindSnowflake),
            sampleResolvedRelayConfig(kind = RelayKindObfs4),
            sampleResolvedRelayConfig(kind = RelayKindCloudflareTunnel).copy(
                cloudflareTunnelMode = RelayCloudflareTunnelModePublishLocalOrigin,
            ),
        ).forEach { config ->
            validateRelaySocketProtectionPolicy(config.copy(socketProtection = RelaySocketProtection.Inactive))
        }
    }

    @Test
    fun `vpn mode preserves paths with complete protected dialing`() {
        listOf(
            sampleResolvedRelayConfig(kind = RelayKindSsh),
            sampleResolvedRelayConfig(kind = RelayKindWebTunnel),
            sampleResolvedRelayConfig(kind = RelayKindNaiveProxy),
            sampleResolvedRelayConfig(kind = RelayKindCloudflareTunnel),
            sampleResolvedRelayConfig(kind = RelayKindMasque).copy(masqueAuthMode = RelayMasqueAuthModeBearer),
        ).forEach { config ->
            validateRelaySocketProtectionPolicy(config.copy(socketProtection = RelaySocketProtection.VpnRequired))
        }
    }
}
