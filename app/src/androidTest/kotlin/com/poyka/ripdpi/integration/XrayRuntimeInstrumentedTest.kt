package com.poyka.ripdpi.integration

import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.core.RipDpiXrayRuntime
import com.poyka.ripdpi.core.StopCause
import com.poyka.ripdpi.core.XrayNativeBridgeLibXrayImpl
import com.poyka.ripdpi.core.XrayRuntimeOwner
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual gomobile runtime and VLESS transport, exclusively on emulator loopback. No external endpoints. */
class XrayRuntimeInstrumentedTest {
    @Test
    fun realVlessRuntimeStopsRestartsAndDeniesUnprotectedConnections() =
        runBlocking(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val bridge = XrayNativeBridgeLibXrayImpl(context.cacheDir.absolutePath)
            val owner = XrayRuntimeOwner(bridge)
            val previousCalls = AtomicInteger()
            repeat(2) { generation ->
                EchoPeer().use { echo ->
                    val (socksPort, vlessPort) = reservePorts()
                    val allowed = AtomicBoolean(true)
                    val denied = AtomicInteger()
                    val calls = if (generation == 0) previousCalls else AtomicInteger()
                    val previousCount = previousCalls.get()
                    val runtime = RipDpiXrayRuntime(owner, XrayProviderConfig(localInboundPort = socksPort))
                    try {
                        runtime.start(config(socksPort, vlessPort, echo.port, PeerId)) { fd ->
                            assertTrue(fd >= 0)
                            calls.incrementAndGet()
                            allowed.get().also { if (!it) denied.incrementAndGet() }
                        }
                        runtime.awaitReady()
                        assertTrue(runtime.pollTelemetry().ptRuntimeVersion?.startsWith("Xray ") == true)
                        val payload = "xray-owned-generation-$generation".toByteArray()
                        assertArrayEquals(payload, exchange(socksPort, echo.port, payload))
                        assertEquals(1, echo.exchanges.get())
                        assertTrue(calls.get() > 0)
                        allowed.set(false)
                        assertTrue(runCatching { exchange(socksPort, echo.port, payload) }.isFailure)
                        assertTrue("Native socket denial was observed", denied.get() > 0)
                        assertEquals("Denied socket must not reach echo", 1, echo.exchanges.get())
                        if (generation > 0) assertEquals(previousCount, previousCalls.get())
                    } finally {
                        assertEquals(StopCause.Clean, runtime.stop())
                    }
                    assertFalse(owner.isOccupied)
                    assertPortReleased(socksPort)
                    assertPortReleased(vlessPort)
                }
            }
        }

    @Test
    fun wrongVlessIdentityCannotFallBackToDirectEgress() =
        runBlocking(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val owner = XrayRuntimeOwner(XrayNativeBridgeLibXrayImpl(context.cacheDir.absolutePath))
            EchoPeer().use { echo ->
                val (socksPort, vlessPort) = reservePorts()
                val runtime = RipDpiXrayRuntime(owner, XrayProviderConfig(localInboundPort = socksPort))
                try {
                    runtime.start(config(socksPort, vlessPort, echo.port, WrongPeerId)) { true }
                    runtime.awaitReady()
                    assertTrue(runCatching { exchange(socksPort, echo.port, byteArrayOf(42)) }.isFailure)
                    assertEquals(0, echo.exchanges.get())
                } finally {
                    assertEquals(StopCause.Clean, runtime.stop())
                }
                assertFalse(owner.isOccupied)
            }
        }

    private fun exchange(
        socksPort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray =
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), SocketTimeoutMs)
            socket.soTimeout = SocketTimeoutMs
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            output.write(byteArrayOf(5, 1, 0))
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
            output.write(
                byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (destinationPort shr 8).toByte(), destinationPort.toByte()),
            )
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
            check(input.readUnsignedByte() == 0)
            val addressLength =
                when (input.readUnsignedByte()) {
                    1 -> 4
                    4 -> 16
                    3 -> input.readUnsignedByte()
                    else -> error("Invalid SOCKS address")
                }
            input.readFully(ByteArray(addressLength + 2))
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
            ByteArray(payload.size).also(input::readFully)
        }

    private fun config(
        socks: Int,
        vless: Int,
        echo: Int,
        clientId: String,
    ): String =
        """
        {
          "log":{"loglevel":"none"},
          "dns":{"servers":["127.0.0.1"]},
          "inbounds":[
            {"tag":"client-socks","listen":"127.0.0.1","port":$socks,"protocol":"socks",
             "settings":{"auth":"noauth","udp":false}},
            {"tag":"local-vless","listen":"127.0.0.1","port":$vless,"protocol":"vless",
             "settings":{"clients":[{"id":"$PeerId"}],"decryption":"none"},
             "streamSettings":{"network":"tcp","security":"none"}}
          ],
          "outbounds":[
            {"tag":"deny","protocol":"blackhole"},
            {"tag":"vless-loopback","protocol":"vless",
             "settings":{"vnext":[{"address":"127.0.0.1","port":$vless,
                "users":[{"id":"$clientId","encryption":"none"}]}]},
             "streamSettings":{"network":"tcp","security":"none"},"mux":{"enabled":false}},
            {"tag":"echo-direct","protocol":"freedom"}
          ],
          "routing":{"domainStrategy":"AsIs","rules":[
            {"type":"field","inboundTag":["client-socks"],"outboundTag":"vless-loopback"},
            {"type":"field","inboundTag":["local-vless"],"ip":["127.0.0.1/32"],
             "port":"$echo","outboundTag":"echo-direct"}
          ]}
        }
        """.trimIndent()

    private fun reservePorts(): Pair<Int, Int> =
        ServerSocket(0, 1, Loopback).use { first ->
            ServerSocket(0, 1, Loopback).use { second -> first.localPort to second.localPort }
        }

    private fun assertPortReleased(port: Int) {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(Loopback, port))
        }
    }

    private class EchoPeer : AutoCloseable {
        private val server = ServerSocket(0, 4, Loopback)
        val port: Int = server.localPort
        val exchanges = AtomicInteger()
        private val worker =
            Thread {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { socket ->
                            socket.soTimeout = SocketTimeoutMs
                            val input = DataInputStream(socket.getInputStream())
                            val size = input.readInt()
                            check(size in 1..512)
                            val bytes = ByteArray(size).also(input::readFully)
                            exchanges.incrementAndGet()
                            socket.getOutputStream().write(bytes)
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

        override fun close() {
            server.close()
            worker.join(SocketTimeoutMs.toLong() + 1000)
            check(!worker.isAlive) { "Echo peer did not stop" }
        }
    }

    private companion object {
        const val PeerId = "00000000-0000-4000-8000-000000000001"
        const val WrongPeerId = "00000000-0000-4000-8000-000000000002"
        const val SocketTimeoutMs = 3000
        val Loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
