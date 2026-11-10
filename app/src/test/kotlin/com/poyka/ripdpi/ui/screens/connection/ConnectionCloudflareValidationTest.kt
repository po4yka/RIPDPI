package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionCloudflareValidationTest {
    private val defaultDraft = AppSettingsSerializer.defaultValue.toConfigDraft()

    @Test
    fun `relay validation requires a VLESS identity for Cloudflare publish`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindCloudflareTunnel,
                    relayCloudflareTunnelMode = RelayCloudflareTunnelModePublishLocalOrigin,
                    relayServer = "edge.example.com",
                    relayServerName = "edge.example.com",
                    relayVlessTransport = RelayVlessTransportXhttp,
                    relayCloudflarePublishLocalOriginUrl = "http://127.0.0.1:43128",
                    relayCloudflareTunnelToken = "fixture-tunnel-token",
                ),
            )

        assertEquals("required", errors[ConnectionFieldRelayCredentials])
    }

    @Test
    fun `relay validation preserves supported Cloudflare publish VLESS identities`() {
        listOf(
            "550e8400-e29b-41d4-a716-446655440000",
            "550e8400e29b41d4a716446655440000",
            "example",
        ).forEach { identity ->
            val errors =
                validateConfigDraft(
                    defaultDraft.copy(
                        relayEnabled = true,
                        relayKind = RelayKindCloudflareTunnel,
                        relayCloudflareTunnelMode = RelayCloudflareTunnelModePublishLocalOrigin,
                        relayServer = "edge.example.com",
                        relayServerName = "edge.example.com",
                        relayVlessTransport = RelayVlessTransportXhttp,
                        relayVlessUuid = identity,
                        relayCloudflarePublishLocalOriginUrl = "http://127.0.0.1:43128",
                        relayCloudflareTunnelToken = "fixture-tunnel-token",
                    ),
                )

            assertFalse(errors.containsKey(ConnectionFieldRelayCredentials))
        }
    }
}
