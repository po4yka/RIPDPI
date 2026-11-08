package com.poyka.ripdpi.services

import android.net.LocalSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalSocketClientSessionTest {
    @Test
    fun `silent clients expire so the next request can use a handler`() {
        val server =
            VpnProtectSocketServer(
                socketPath = "/tmp/unused-protect-deadline.sock",
                protectFailureMonitor = InMemoryVpnProtectFailureMonitor(),
                fdProtector = { false },
            )
        try {
            ServerSocket(0, 3, InetAddress.getLoopbackAddress()).use { listener ->
                assertHandlerRecovery(server, listener)
            }
        } finally {
            server.stop()
        }
    }

    private fun assertHandlerRecovery(
        server: VpnProtectSocketServer,
        listener: ServerSocket,
    ) {
        TcpClientSession(listener).use { first ->
            TcpClientSession(listener).use { second ->
                assertSilentClientsExpire(server, listener, first, second)
            }
        }
    }

    private fun assertSilentClientsExpire(
        server: VpnProtectSocketServer,
        listener: ServerSocket,
        first: TcpClientSession,
        second: TcpClientSession,
    ) {
        assertTrue(server.dispatchClientSession(first.session))
        assertTrue(server.dispatchClientSession(second.session))
        assertEquals(-1, first.client.getInputStream().read())
        assertEquals(-1, second.client.getInputStream().read())
        TcpClientSession(listener).use { next ->
            next.client.getOutputStream().write(1)
            assertTrue(server.dispatchClientSession(next.session))
            assertEquals(1, next.client.getInputStream().read())
        }
    }

    @Test
    fun `queued session cannot start a handshake after its deadline`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            TcpClientSession(listener).use { peer ->
                peer.client.getOutputStream().write(1)
                Thread.sleep(1_100L)
                assertThrows(SocketTimeoutException::class.java) { peer.session.readHandshake() }
            }
        }
    }
}

/** Kernel TCP I/O exercises the session deadline without claiming Android UDS behavior. */
private class TcpClientSession(
    listener: ServerSocket,
) : AutoCloseable {
    val client = Socket(listener.inetAddress, listener.localPort).apply { soTimeout = 3_000 }
    private val accepted = listener.accept()
    val session = LocalSocketClientSession(TcpBackedLocalSocket(accepted))

    override fun close() {
        session.close()
        client.close()
    }
}

private class TcpBackedLocalSocket(
    private val socket: Socket,
) : LocalSocket() {
    override fun getInputStream() = socket.getInputStream()

    override fun getOutputStream() = socket.getOutputStream()

    override fun getAncillaryFileDescriptors(): Array<FileDescriptor>? = null

    override fun setSoTimeout(timeout: Int) {
        socket.soTimeout = timeout
    }

    override fun shutdownInput() = socket.shutdownInput()

    override fun close() = socket.close()
}
