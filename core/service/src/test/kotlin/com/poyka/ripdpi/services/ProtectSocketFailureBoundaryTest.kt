package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class ProtectSocketFailureBoundaryTest {
    @Test
    fun `an unexpected extraction failure closes the control channel without escaping`() {
        val server =
            VpnProtectSocketServer(
                socketPath = "/tmp/unused-protect-failure.sock",
                protectFailureMonitor = InMemoryVpnProtectFailureMonitor(),
                fdProtector = { false },
                fileDescriptorIntExtractor = { throw IllegalStateException("Unexpected descriptor failure") },
            )
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                assertFailureClosesPeer(server, listener)
            }
        } finally {
            server.stop()
        }
    }

    private fun assertFailureClosesPeer(
        server: VpnProtectSocketServer,
        listener: ServerSocket,
    ) {
        Socket(listener.inetAddress, listener.localPort).use { client ->
            client.soTimeout = 3_000
            listener.accept().use { accepted ->
                accepted.soTimeout = 3_000
                val session =
                    object : ProtectSocketClientSession {
                        override val ancillaryFileDescriptors = arrayOf(FileDescriptor())

                        override fun readHandshake(): Int = accepted.getInputStream().read(ByteArray(1))

                        override fun writeAck(success: Boolean) {
                            accepted.getOutputStream().write(if (success) 0 else 1)
                        }

                        override fun close() = accepted.close()
                    }
                client.getOutputStream().write(1)
                server.handleClientSession(session)
                assertEquals(-1, client.getInputStream().read())
            }
        }
    }
}
