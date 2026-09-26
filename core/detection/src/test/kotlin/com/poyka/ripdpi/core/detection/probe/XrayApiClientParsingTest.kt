package com.poyka.ripdpi.core.detection.probe

import com.google.protobuf.ByteString
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.xray.common.net.IPOrDomain
import com.xray.common.protocol.ServerEndpoint
import com.xray.common.protocol.User
import com.xray.common.serial.TypedMessage
import com.xray.transport.internet.StreamConfig
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.xray.app.proxyman.SenderConfig as ProxymanSenderConfig
import com.xray.proxy.vless.Account as VlessAccount
import com.xray.proxy.vless.outbound.Config as VlessOutboundConfig
import com.xray.transport.internet.reality.Config as RealityConfig

class XrayApiClientParsingTest {
    private val client =
        XrayApiClient(
            dispatchers =
                AppCoroutineDispatchers(
                    io = Dispatchers.IO,
                    default = Dispatchers.Default,
                    main = Dispatchers.Default,
                ),
        )

    @Test
    fun malformedRealityDoesNotHideValidTransportOrLaterSecurity() {
        val malformed =
            typedMessage(
                "xray.transport.internet.reality.Config",
                ByteString.copyFrom(byteArrayOf(-1)),
            )
        val validReality =
            RealityConfig
                .newBuilder()
                .setServerName("example.test")
                .setPublicKey(ByteString.copyFrom(byteArrayOf(1)))
                .build()
        val valid =
            typedMessage(
                "xray.transport.internet.reality.Config",
                validReality.toByteString(),
            )
        val stream =
            StreamConfig
                .newBuilder()
                .setProtocolName("tcp")
                .addSecuritySettings(malformed)
                .addSecuritySettings(valid)
                .build()
        val sender =
            ProxymanSenderConfig
                .newBuilder()
                .setStreamSettings(stream)
                .build()

        val parsed = client.parseSenderSettings("xray.app.proxyman.SenderConfig", sender.toByteString())
        assertEquals("tcp", parsed.protocolName)
        assertEquals("example.test", parsed.sni)
        assertTrue(parsed.publicKeyPresent)
    }

    @Test
    fun malformedAccountDoesNotHideVlessEndpoint() {
        val account =
            typedMessage(
                "xray.proxy.vless.Account",
                ByteString.copyFrom(byteArrayOf(-1)),
            )
        val endpoint =
            ServerEndpoint
                .newBuilder()
                .setAddress(IPOrDomain.newBuilder().setDomain("example.test"))
                .setPort(443)
                .setUser(User.newBuilder().setAccount(account))
                .build()
        val outbound = VlessOutboundConfig.newBuilder().setVnext(endpoint).build()

        val parsed = client.parseVlessProxySettings("xray.proxy.vless.outbound.Config", outbound.toByteString())
        assertEquals("example.test", parsed.address)
        assertEquals(443, parsed.port)
        assertFalse(parsed.uuidPresent)
    }

    @Test
    fun validAccountKeepsOnlyUuidPresence() {
        val uuid = "11111111-2222-3333-4444-555555555555"
        val validAccount =
            VlessAccount
                .newBuilder()
                .setId(uuid)
                .build()
        val account =
            typedMessage(
                "xray.proxy.vless.Account",
                validAccount.toByteString(),
            )
        val endpoint =
            ServerEndpoint
                .newBuilder()
                .setUser(User.newBuilder().setAccount(account))
                .build()
        val outbound = VlessOutboundConfig.newBuilder().setVnext(endpoint).build()

        val parsed = client.parseVlessProxySettings("xray.proxy.vless.outbound.Config", outbound.toByteString())
        assertTrue(parsed.uuidPresent)
        assertFalse(parsed.toString().contains(uuid))
    }

    private fun typedMessage(
        type: String,
        value: ByteString,
    ): TypedMessage =
        TypedMessage
            .newBuilder()
            .setType(type)
            .setValue(value)
            .build()
}
