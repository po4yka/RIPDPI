package com.poyka.ripdpi.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LocalSocks5ClientTest {
    @Test
    fun `CONNECT rejects malformed success headers`() {
        for (header in listOf(byteArrayOf(4, 0, 0, 1), byteArrayOf(5, 0, 127, 1))) {
            withReply(header + byteArrayOf(127, 0, 0, 1, 0, 53, 66)) { socket ->
                assertThrows(ProtocolException::class.java) { connect(socket) }
            }
        }
    }

    @Test
    fun `CONNECT consumes each bound address and preserves payload`() {
        val addresses =
            listOf(
                byteArrayOf(1, 127, 0, 0, 1),
                byteArrayOf(4) + ByteArray(15) + byteArrayOf(1),
                byteArrayOf(3, 1, 'a'.code.toByte()),
            )
        for (address in addresses) {
            withReply(byteArrayOf(5, 0, 0) + address + byteArrayOf(0, 53, 66)) { socket ->
                connect(socket)
                assertEquals(66, socket.getInputStream().read())
            }
        }
    }

    @Test
    fun `CONNECT retains nonzero reply codes`() {
        for (reply in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 255)) {
            withReply(byteArrayOf(5, reply.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)) { socket ->
                val error = assertThrows(LocalSocksConnectRejectedException::class.java) { connect(socket) }
                assertEquals(reply, error.reply)
            }
        }
    }

    private fun connect(socket: Socket) {
        LocalSocks5Client.connect(socket, InetSocketAddress("127.0.0.1", 53), null)
    }

    private fun withReply(
        reply: ByteArray,
        check: (Socket) -> Unit,
    ) {
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { listener ->
            listener.soTimeout = 2_000
            val completed = CompletableFuture<Unit>()
            val server =
                thread(name = "local-socks-reply", isDaemon = true) {
                    try {
                        listener.accept().use { peer ->
                            peer.soTimeout = 2_000
                            val input = DataInputStream(peer.getInputStream())
                            val output = peer.getOutputStream()
                            val greeting = ByteArray(3).also(input::readFully)
                            assertArrayEquals(byteArrayOf(5, 1, 0), greeting)
                            output.write(byteArrayOf(5, 0))
                            val request = ByteArray(10).also(input::readFully)
                            assertArrayEquals(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0, 53), request)
                            output.write(reply)
                        }
                        completed.complete(Unit)
                    } catch (failure: Throwable) {
                        completed.completeExceptionally(failure)
                    }
                }
            try {
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    socket.soTimeout = 2_000
                    check(socket)
                }
            } finally {
                completed.get(3, TimeUnit.SECONDS)
                server.join(3_000)
            }
        }
    }
}
