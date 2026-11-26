package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.data.awg.toWgQuickConf
import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import com.poyka.ripdpi.data.wireguard.AwgHeaderProtectionKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class AmneziaWgConfShareRoundTripTest {
    private val plain =
        AwgActivationRequest(
            profileId = "awg-1",
            privateKey = AwgPrivateTestKey,
            peerPublicKey = AwgPeerTestKey,
            presharedKey = Base64.getEncoder().encodeToString(ByteArray(32) { 4 }),
            endpointHost = "vpn.example.com",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            interfaceAddressV6 = "fd00::2/128",
            dnsServers = listOf("1.1.1.1", "2606:4700:4700::1111"),
            allowedIps = listOf("0.0.0.0/0", "::/0"),
            mtu = 1300,
            persistentKeepalive = 25,
        )

    private val awg1 =
        plain.copy(
            endpointHost = "2001:db8::9",
            presharedKey = "",
            obfuscation =
                AwgActivationObfuscation(
                    jc = 4,
                    jmin = 40,
                    jmax = 70,
                    s1 = 15,
                    s2 = 68,
                    h1 = "1234567891",
                    h2 = "1234567892",
                    h3 = "1234567893",
                    h4 = "1234567894",
                ),
        )

    private val awg3 =
        plain.copy(
            persistentKeepaliveMax = 35,
            obfuscation =
                AwgActivationObfuscation(
                    jc = 5,
                    jmin = 10,
                    jmax = 50,
                    s1 = 12,
                    s2 = 13,
                    s3 = 14,
                    s4 = 15,
                    h1 = "100-199",
                    h2 = "200-299",
                    h3 = "300-399",
                    h4 = "400-499",
                    i1 = "<r 2><b 0x8580><rd 2><rc 2><t>",
                    i2 = "<b 0xf2>",
                    v3 =
                        AmneziaWgV3Parameters(
                            headerProtectionKey =
                                AwgHeaderProtectionKey(Base64.getEncoder().encodeToString(ByteArray(32) { 3 })),
                            contentPaddingAddition = "10-100",
                            rekeyAfterTime = "100-120",
                            randomTrailers = true,
                        ),
                ),
        )

    @Test
    fun `importing a shared conf gives the same server for WireGuard, AmneziaWG 1 and AmneziaWG 3`() {
        for (server in listOf(plain, awg1, awg3)) {
            server.requireRuntimeReady()
            val imported =
                (
                    AmneziaWgEditorState.initial().importConf(
                        server.toWgQuickConf(),
                        AwgTestCatalog,
                    ) as AwgConfImport.Imported
                ).editor

            assertEquals(server, imported.toActivationRequest(server.profileId))
        }
    }
}
