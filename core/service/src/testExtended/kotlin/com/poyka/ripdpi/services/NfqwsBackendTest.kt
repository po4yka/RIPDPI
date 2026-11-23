package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiFakePacketConfig
import com.poyka.ripdpi.core.RipDpiHostAutolearnConfig
import com.poyka.ripdpi.core.RipDpiListenConfig
import com.poyka.ripdpi.core.RipDpiParserEvasionConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.withoutPacketStrategies
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

class NfqwsBackendTest {
    @Test
    fun `nfqws owns packet changes while proxy keeps transport settings`() {
        val source =
            RipDpiProxyUIPreferences(
                listen = RipDpiListenConfig(port = 1088),
                parserEvasions = RipDpiParserEvasionConfig(hostMixedCase = true, httpMethodEol = true),
                fakePackets = RipDpiFakePacketConfig(dropSack = true, windowClamp = 1234),
                hostAutolearn = RipDpiHostAutolearnConfig(enabled = true),
                rootMode = true,
                rootHelperSocketPath = "/private/root_helper.sock",
            )
        assertTrue(source.chains.tcpSteps.isNotEmpty())
        val plain = source.withoutPacketStrategies()
        assertEquals(source.listen, plain.listen)
        assertEquals(source.destinationRouting, plain.destinationRouting)
        assertEquals(source.relay, plain.relay)
        assertEquals(source.wsTunnel, plain.wsTunnel)
        assertEquals(source.rootHelperSocketPath, plain.rootHelperSocketPath)
        assertTrue(plain.chains.tcpSteps.isEmpty())
        assertTrue(plain.chains.udpSteps.isEmpty())
        assertEquals(RipDpiParserEvasionConfig(), plain.parserEvasions)
        assertEquals(RipDpiFakePacketConfig(), plain.fakePackets)
        assertFalse(plain.adaptiveFallback.enabled)
        assertFalse(plain.hostAutolearn.enabled)
        assertFalse(plain.protocols.desyncHttp || plain.protocols.desyncHttps || plain.protocols.desyncUdp)
    }

    @Test
    fun `upstream command keeps quoted Lua args and requires explicit root activation`() {
        val settings =
            AppSettings
                .newBuilder()
                .setEnableCmdSettings(true)
                .setRootModeEnabled(true)
                .setCmdArgs("nfqws2 --filter-tcp=443 --lua-init='function custom(d) return VERDICT_PASS end'")
                .build()
        assertEquals(
            listOf("--filter-tcp=443", "--lua-init=function custom(d) return VERDICT_PASS end"),
            nfqwsArguments(settings),
        )
        assertThrows(IllegalArgumentException::class.java) {
            nfqwsArguments(settings.toBuilder().setRootModeEnabled(false).build())
        }
        assertThrows(IllegalArgumentException::class.java) {
            nfqwsArguments(settings.toBuilder().setStrategyChainYaml("steps: []").build())
        }
        assertEquals(null, nfqwsArguments(settings.toBuilder().setEnableCmdSettings(false).build()))
        assertEquals(null, nfqwsArguments(settings.toBuilder().setCmdArgs("ripdpi --port 1080").build()))
    }

    @Test
    fun `helper framing preserves big endian length and full payload`() {
        val payload = ByteArray(300) { (it % 127).toByte() }
        val output = ByteArrayOutputStream()
        RootHelperIpc.writeFrame(output, payload)
        assertEquals(listOf<Byte>(0, 0, 1, 44), output.toByteArray().take(4))
        assertEquals(payload.toList(), RootHelperIpc.readFrame(ByteArrayInputStream(output.toByteArray())).toList())
    }

    @Test
    fun `helper framing rejects unbounded and truncated input before use`() {
        for (length in listOf(-1, 0, 8193)) {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).writeInt(length)
            assertThrows(
                IOException::class.java,
            ) { RootHelperIpc.readFrame(ByteArrayInputStream(output.toByteArray())) }
        }
        assertThrows(IOException::class.java) { RootHelperIpc.writeFrame(ByteArrayOutputStream(), ByteArray(8193)) }
        assertThrows(EOFException::class.java) {
            RootHelperIpc.readFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, 1)))
        }
    }
}
