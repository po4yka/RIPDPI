package com.poyka.ripdpi.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class NativeOwnedTlsHttpCancellationTest {
    @Test
    fun `cancellation closes the blocking transport before releasing its handle`() =
        runBlocking {
            ServerSocket(0).use { server ->
                server.soTimeout = 2000
                val bindings = SocketBindings(server.localPort)
                val fetcher = DefaultNativeOwnedTlsHttpFetcher(bindings)
                val pending = async(Dispatchers.IO) { fetcher.execute(request()) }
                server.accept().use { peer ->
                    peer.soTimeout = 2000
                    withTimeout(2000) { pending.cancelAndJoin() }
                    assertEquals(-1, peer.getInputStream().read())
                }
                assertTrue(pending.isCancelled)
                assertTrue(bindings.isEmpty)
                assertFalse(bindings.releasedWhileExecuting.get())

                val next = async(Dispatchers.IO) { fetcher.execute(request()) }
                server.accept().use { peer -> peer.getOutputStream().write(42) }
                assertEquals(200, withTimeout(2000) { next.await() }.statusCode)
                assertTrue(bindings.isEmpty)
            }
        }

    @Test
    fun `cancellation after reservation opens no transport and releases the handle`() =
        runBlocking {
            ServerSocket(0).use { server ->
                server.soTimeout = 100
                val bindings = SocketBindings(server.localPort)
                val fetcher = DefaultNativeOwnedTlsHttpFetcher(bindings)
                lateinit var pending: Deferred<NativeOwnedTlsHttpResult>
                pending = async(Dispatchers.IO, start = CoroutineStart.LAZY) { fetcher.execute(request()) }
                bindings.afterReserve = { pending.cancel() }
                pending.start()
                withTimeout(2000) { pending.join() }
                assertTrue(pending.isCancelled)
                assertTrue(bindings.isEmpty)
                val connected =
                    try {
                        server.accept().use { true }
                    } catch (_: SocketTimeoutException) {
                        false
                    }
                assertFalse(connected)
            }
        }

    @Test(expected = CancellationException::class)
    fun `native cancellation remains cancellation instead of an IO failure`() =
        runBlocking {
            val bindings =
                object : NativeOwnedTlsHttpFetcherBindings {
                    override fun createRequest(): Long = 1L

                    override fun execute(
                        requestHandle: Long,
                        requestJson: String,
                    ): String = """{"cancelled":true}"""

                    override fun cancelRequest(requestHandle: Long) = Unit

                    override fun releaseRequest(requestHandle: Long) = Unit
                }
            DefaultNativeOwnedTlsHttpFetcher(bindings).execute(request())
            Unit
        }

    private class SocketBindings(
        private val port: Int,
    ) : NativeOwnedTlsHttpFetcherBindings {
        private val sequence = AtomicLong()
        private val requests = ConcurrentHashMap<Long, RequestState>()
        val releasedWhileExecuting = AtomicBoolean()
        var afterReserve: () -> Unit = {}
        val isEmpty: Boolean get() = requests.isEmpty()

        override fun createRequest(): Long {
            val id = sequence.incrementAndGet()
            requests[id] = RequestState()
            afterReserve()
            return id
        }

        override fun execute(
            requestHandle: Long,
            requestJson: String,
        ): String {
            val state = checkNotNull(requests[requestHandle])
            if (state.cancelled.get()) return CancelledResponse
            state.executing.set(true)
            return try {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 2000
                    state.socket.set(socket)
                    if (state.cancelled.get()) socket.close()
                    socket.getInputStream().read()
                }
                """{"statusCode":200,"bodyBase64":""}"""
            } catch (error: SocketException) {
                if (!state.cancelled.get()) throw error
                CancelledResponse
            } finally {
                state.socket.set(null)
                state.executing.set(false)
            }
        }

        override fun cancelRequest(requestHandle: Long) {
            requests[requestHandle]?.let { state ->
                state.cancelled.set(true)
                state.socket.get()?.close()
            }
        }

        override fun releaseRequest(requestHandle: Long) {
            requests.remove(requestHandle)?.let { state ->
                if (state.executing.get()) releasedWhileExecuting.set(true)
            }
        }
    }

    private class RequestState {
        val cancelled = AtomicBoolean()
        val executing = AtomicBoolean()
        val socket = AtomicReference<Socket?>()
    }

    private fun request() = NativeOwnedTlsHttpRequest(url = "https://catalog.test/", tlsProfileId = "chrome_stable")

    private companion object {
        const val CancelledResponse = """{"cancelled":true,"error":"native TLS fetch cancelled"}"""
    }
}
