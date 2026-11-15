package com.poyka.ripdpi.data.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CancellableDetectionHttpTest {
    @Test
    fun `cancellation closes the connection while its body is incomplete`() =
        runBlocking<Unit> {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                val headersSent = CompletableDeferred<Unit>()
                try {
                    val closed =
                        executor.submit<Int> {
                            server.accept().use { socket ->
                                socket.soTimeout = 3_000
                                val input = socket.getInputStream().bufferedReader()
                                while (!input.readLine().isNullOrEmpty()) { /* Consume the request headers. */ }
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray())
                                    flush()
                                }
                                headersSent.complete(Unit)
                                socket.getInputStream().read()
                            }
                        }
                    val result =
                        async(Dispatchers.IO) {
                            DetectionResolverNetworkStack().executeCancellable(request(server.localPort))
                        }
                    withTimeout(2_000) { headersSent.await() }

                    withTimeout(1_000) { result.cancelAndJoin() }

                    assertEquals(-1, closed.get(1, TimeUnit.SECONDS))
                } finally {
                    executor.shutdownNow()
                }
            }
        }

    @Test
    fun `decoded response bytes cannot exceed the configured limit`() =
        runBlocking<Unit> {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val served =
                        executor.submit {
                            server.accept().use { socket ->
                                socket.soTimeout = 3_000
                                val input = socket.getInputStream().bufferedReader()
                                while (!input.readLine().isNullOrEmpty()) { /* Consume the request headers. */ }
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nABCDE".toByteArray())
                                    flush()
                                }
                            }
                        }
                    val error =
                        try {
                            DetectionResolverNetworkStack().executeCancellable(
                                request(server.localPort),
                                maxResponseBytes = 4,
                            )
                            error("Oversized response was accepted")
                        } catch (expected: IOException) {
                            expected
                        }

                    assertTrue(error.message.orEmpty().contains("exceeds 4 bytes"))
                    served.get(2, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                }
            }
        }

    private fun request(port: Int): Request = Request.Builder().url("http://127.0.0.1:$port/ip").build()
}
