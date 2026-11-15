package com.poyka.ripdpi.core.detection.probe

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.xray.app.proxyman.command.HandlerServiceGrpc
import com.xray.app.proxyman.command.ListOutboundsRequest
import com.xray.app.proxyman.command.ListOutboundsResponse
import io.grpc.Context
import io.grpc.InsecureServerCredentials
import io.grpc.Status
import io.grpc.okhttp.OkHttpServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class XrayApiScannerTest {
    private val dispatchers =
        AppCoroutineDispatchers(
            io = Dispatchers.IO,
            default = Dispatchers.Default,
            main = Dispatchers.Default,
        )

    @Test
    fun `authentication failure is not retried`() =
        runBlocking {
            val calls = AtomicInteger()
            withServer({
                calls.incrementAndGet()
                it.onError(Status.UNAUTHENTICATED.asRuntimeException())
            }) { port ->
                assertNull(scanner(listOf(port)).findXrayApi {})
                assertEquals(1, calls.get())
            }
        }

    @Test
    fun `first match cancels an active sibling RPC`() =
        runBlocking {
            val stalledStarted = CountDownLatch(1)
            val stalledCancelled = CompletableDeferred<Unit>()
            withServer({
                Context.current().addListener({ stalledCancelled.complete(Unit) }, Runnable::run)
                stalledStarted.countDown()
            }) { stalledPort ->
                withServer({
                    if (stalledStarted.await(3, TimeUnit.SECONDS)) {
                        it.succeed()
                    } else {
                        it.onError(Status.INTERNAL.asRuntimeException())
                    }
                }) { responsivePort ->
                    withTimeout(4_000) {
                        val match = scanner(listOf(stalledPort, responsivePort)).findXrayApi {}
                        assertEquals(responsivePort, match?.endpoint?.port)
                        stalledCancelled.await()
                    }
                }
            }
        }

    @Test
    fun `scan deadline cancels an active RPC`() =
        runBlocking {
            val calls = AtomicInteger()
            val cancelled = CompletableDeferred<Unit>()
            withServer({
                calls.incrementAndGet()
                Context.current().addListener({ cancelled.complete(Unit) }, Runnable::run)
            }) { port ->
                withTimeout(6_000) {
                    assertNull(scanner(listOf(port), scanDeadlineMs = 2_000).findXrayApi {})
                    cancelled.await()
                    assertEquals(1, calls.get())
                }
            }
        }

    @Test
    fun `loopback RPC ignores HTTP and SOCKS proxy selection`() =
        runBlocking {
            val previous = ProxySelector.getDefault()
            val selections = AtomicInteger()
            withServer({ it.succeed() }) { port ->
                ProxySelector.setDefault(
                    object : ProxySelector() {
                        override fun select(uri: URI): List<Proxy> {
                            selections.incrementAndGet()
                            val type = if (uri.scheme == "socket") Proxy.Type.SOCKS else Proxy.Type.HTTP
                            return listOf(Proxy(type, InetSocketAddress("127.0.0.1", 1)))
                        }

                        override fun connectFailed(
                            uri: URI,
                            address: SocketAddress,
                            error: IOException,
                        ) = Unit
                    },
                )
                try {
                    assertEquals(port, scanner(listOf(port)).findXrayApi {}?.endpoint?.port)
                    assertEquals(0, selections.get())
                } finally {
                    ProxySelector.setDefault(previous)
                }
            }
        }

    @Test
    fun `loopback RPC rejects hostnames and non-loopback literals`() {
        listOf("localhost", "192.0.2.1").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { XrayApiClient(host, dispatchers) }
        }
    }

    @Test
    fun `early match reports only completed probes`() =
        runBlocking {
            withServer({ it.succeed() }) { port ->
                val progress = mutableListOf<XrayScanProgress>()
                val scan =
                    XrayApiScanner(
                        dispatchers = dispatchers,
                        loopbackHosts = listOf("127.0.0.1"),
                        scanPorts = listOf(port, 1, 2, 3),
                        maxConcurrency = 1,
                        progressUpdateEvery = 1,
                    )
                assertEquals(port, scan.findXrayApi { progress += it }?.endpoint?.port)
                assertEquals(listOf(0, 1), progress.map(XrayScanProgress::scanned))
                assertEquals(listOf(4, 4), progress.map(XrayScanProgress::total))
                assertEquals(port, progress.last().currentPort)
            }
        }

    private fun scanner(
        ports: List<Int>,
        scanDeadlineMs: Long = 10_000,
    ) = XrayApiScanner(
        dispatchers = dispatchers,
        loopbackHosts = listOf("127.0.0.1"),
        scanPorts = ports,
        grpcDeadlineMs = 8_000,
        maxConcurrency = ports.size,
        scanDeadlineMs = scanDeadlineMs,
    )

    private suspend fun withServer(
        respond: (StreamObserver<ListOutboundsResponse>) -> Unit,
        block: suspend (Int) -> Unit,
    ) {
        val service =
            object : HandlerServiceGrpc.HandlerServiceImplBase() {
                override fun listOutbounds(
                    request: ListOutboundsRequest,
                    responseObserver: StreamObserver<ListOutboundsResponse>,
                ) = respond(responseObserver)
            }
        val server =
            OkHttpServerBuilder
                .forPort(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), InsecureServerCredentials.create())
                .addService(service)
                .build()
                .start()
        try {
            block(server.port)
        } finally {
            server.shutdownNow()
            server.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    private fun StreamObserver<ListOutboundsResponse>.succeed() {
        onNext(ListOutboundsResponse.getDefaultInstance())
        onCompleted()
    }
}
