package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.LocalSocks5Client
import com.poyka.ripdpi.core.LocalSocksCredentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

private const val MaxBridgeClients = 64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagedClientSocksBridgeTest {
    private val credentials = LocalSocksCredentials("local-user", "local-pass")
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
    fun `missing or wrong credentials cannot open the fixed target bridge`() {
        ServerSocket(0).use { upstream ->
            upstream.soTimeout = 100
            val port = bridge(InetSocketAddress("127.0.0.1", upstream.localPort))
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 1_000
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                val input = DataInputStream(socket.getInputStream())
                assertEquals(5, input.readUnsignedByte())
                assertEquals(255, input.readUnsignedByte())
            }
            listOf(
                LocalSocksCredentials("wrong-user", credentials.password),
                LocalSocksCredentials(credentials.username, "wrong-pass"),
            ).forEach { wrong ->
                assertFalse(SocketSocksReadinessConnector().canConnect("127.0.0.1", port, wrong))
            }
            assertThrows(SocketTimeoutException::class.java) { upstream.accept() }
        }
    }

    @Test
    fun `readiness accepts only the current session credentials`() {
        val port = bridge()
        assertTrue(SocketSocksReadinessConnector().canConnect("127.0.0.1", port, credentials))
        assertFalse(SocketSocksReadinessConnector().canConnect("127.0.0.1", port, LocalSocksCredentials.generate()))
    }

    @Test
    fun `non-loopback listen host is refused before binding`() {
        val bridge =
            ManagedClientSocksBridge(
                credentials = credentials,
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
            credentials = credentials,
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

    /** Sends an authenticated greeting and [request], and returns the REP byte of the bridge reply. */
    private fun requestReply(
        port: Int,
        request: ByteArray,
    ): Int =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val output = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            LocalSocks5Client.authenticate(input, output, credentials)
            output.write(request)
            output.flush()
            assertEquals(0x05, input.readUnsignedByte())
            input.readUnsignedByte()
        }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
