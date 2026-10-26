package com.poyka.ripdpi.services

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val MaxBridgeClients = 64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagedClientSocksBridgeTest {
    private val bridges = mutableListOf<ManagedClientSocksBridge>()

    @After
    fun closeBridges() {
        bridges.forEach(ManagedClientSocksBridge::close)
    }

    @Test
    fun `unsupported command gets REP 07`() {
        val reply = requestReply(bridge(), byteArrayOf(0x05, 0x03, 0x00, 0x01, 127, 0, 0, 1, 0, 53))

        assertEquals(0x07, reply)
    }

    @Test
    fun `unsupported address type gets REP 08`() {
        val reply = requestReply(bridge(), byteArrayOf(0x05, 0x01, 0x00, 0x05, 0, 0))

        assertEquals(0x08, reply)
    }

    @Test
    fun `unreachable pluggable transport listener gets REP 05`() {
        val closedPort = freePort()

        val reply =
            requestReply(
                bridge(upstream = InetSocketAddress("127.0.0.1", closedPort)),
                byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, 0, 80),
            )

        assertEquals(0x05, reply)
    }

    @Test
    fun `non-loopback listen host is refused before binding`() {
        val bridge =
            ManagedClientSocksBridge(
                listenHost = "0.0.0.0",
                listenPort = freePort(),
                upstreamListener = InetSocketAddress("127.0.0.1", freePort()),
                bridgeSpec = ManagedClientSocksBridgeSpec("obfs4", "192.0.2.1", 443, ""),
            )

        assertThrows(IllegalArgumentException::class.java) { bridge.start() }
    }

    @Test
    fun `clients beyond the concurrency cap are closed instead of served`() {
        val port = bridge()
        val held = List(MaxBridgeClients) { Socket("127.0.0.1", port) }
        try {
            Socket("127.0.0.1", port).use { extra ->
                extra.soTimeout = 2_000
                assertEquals(-1, extra.getInputStream().read())
            }
        } finally {
            held.forEach(Socket::close)
        }
    }

    private fun bridge(upstream: InetSocketAddress = InetSocketAddress("127.0.0.1", freePort())): Int {
        val port = freePort()
        ManagedClientSocksBridge(
            listenHost = "127.0.0.1",
            listenPort = port,
            upstreamListener = upstream,
            bridgeSpec =
                ManagedClientSocksBridgeSpec(
                    methodName = "obfs4",
                    targetHost = "192.0.2.1",
                    targetPort = 443,
                    ptArguments = "",
                ),
        ).also { bridge ->
            bridges += bridge
            bridge.start()
        }
        return port
    }

    /** Sends a NO-AUTH greeting and [request], and returns the REP byte of the bridge reply. */
    private fun requestReply(
        port: Int,
        request: ByteArray,
    ): Int =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val output = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            assertEquals(0x05, input.readUnsignedByte())
            assertEquals(0x00, input.readUnsignedByte())
            output.write(request)
            output.flush()
            assertEquals(0x05, input.readUnsignedByte())
            input.readUnsignedByte()
        }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
