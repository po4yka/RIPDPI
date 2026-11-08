package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RootHelperSocketDeadlineTest {
    @Test
    fun `deadline interrupts a real blocked read`() =
        withSocketPair { client, _ ->
            val started = System.nanoTime()
            assertThrows(SocketTimeoutException::class.java) {
                withRootHelperDeadline(deadline(), abort = client::close) { client.getInputStream().read() }
            }
            assertTrue(client.isClosed)
            assertBounded(started)
        }

    @Test
    fun `deadline interrupts a real blocked write`() =
        withSocketPair { client, peer ->
            client.sendBufferSize = 1_024
            peer.receiveBufferSize = 1_024
            val payload = ByteArray(64 * 1_024)
            val started = System.nanoTime()
            assertThrows(SocketTimeoutException::class.java) {
                withRootHelperDeadline(deadline(), abort = client::close) {
                    while (true) client.getOutputStream().write(payload)
                }
            }
            assertTrue(client.isClosed)
            assertBounded(started)
        }

    @Test
    fun `trickling a frame does not restart its total deadline`() =
        withSocketPair { client, peer ->
            val writer =
                thread(name = "root-helper-frame-test", isDaemon = true) {
                    try {
                        peer.getOutputStream().write(byteArrayOf(0, 0, 1, 0))
                        repeat(100) {
                            Thread.sleep(10)
                            peer.getOutputStream().write(1)
                        }
                    } catch (_: IOException) {
                        // The deadline closes the receiving socket before this frame is complete.
                    }
                }
            try {
                val started = System.nanoTime()
                assertThrows(SocketTimeoutException::class.java) {
                    withRootHelperDeadline(deadline(), abort = client::close) {
                        RootHelperIpc.readFrame(client.getInputStream())
                    }
                }
                assertBounded(started)
            } finally {
                peer.close()
                writer.join(2_000)
                assertTrue("frame writer did not stop", !writer.isAlive)
            }
        }

    @Test
    fun `a completed operation cancels its later socket abort`() =
        withSocketPair { client, peer ->
            val completedDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150)
            assertEquals(42, withRootHelperDeadline(completedDeadline, abort = client::close) { 42 })
            Thread.sleep(200)
            client.getOutputStream().write(42)
            peer.soTimeout = 1_000
            assertEquals(42, peer.getInputStream().read())
        }

    private fun withSocketPair(test: (Socket, Socket) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                server.accept().use { peer -> test(client, peer) }
            }
        }
    }

    private fun deadline(): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150)

    private fun assertBounded(started: Long) {
        assertTrue("socket deadline was not enforced", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
    }
}
