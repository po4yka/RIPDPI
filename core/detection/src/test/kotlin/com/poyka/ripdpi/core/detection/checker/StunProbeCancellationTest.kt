package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CallTransportPath
import com.poyka.ripdpi.core.detection.CallTransportStunScope
import com.poyka.ripdpi.core.detection.CallTransportStunServer
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.ProxyType
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StunProbeCancellationTest {
    private val dispatchers = AppCoroutineDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.IO)
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun callTransportCancellationClosesAWaitingDatagramSocket() =
        runTest {
            DatagramSocket(InetSocketAddress(loopback, 0)).use { server ->
                server.soTimeout = 3_000
                val sent = CompletableDeferred<Unit>()
                val peer =
                    async(Dispatchers.IO) {
                        receive(server)
                        sent.complete(Unit)
                    }
                val probe =
                    launch(Dispatchers.IO) {
                        CallTransportLeakChecker.defaultStunClient(dispatchers).reflexiveAddress(
                            CallTransportPath.APP_DEFAULT,
                            CallTransportStunServer("127.0.0.1", server.localPort, CallTransportStunScope.GLOBAL),
                        )
                    }
                sent.await()
                probe.cancelPromptly()
                peer.await()
            }
        }

    @Test
    fun webRtcCancellationStopsTheNextServerProbe() =
        runTest {
            DatagramSocket(InetSocketAddress(loopback, 0)).use { server ->
                server.soTimeout = 3_000
                val sent = CompletableDeferred<Unit>()
                val peer =
                    async(Dispatchers.IO) {
                        receive(server)
                        sent.complete(Unit)
                    }
                val probe =
                    launch(Dispatchers.IO) {
                        WebRtcLeakChecker.probeStunReachability { _, _ ->
                            WebRtcLeakChecker.sendStunBinding("127.0.0.1", server.localPort)
                        }
                    }
                sent.await()
                probe.cancelPromptly()
                peer.await()
                server.soTimeout = 100
                val furtherRequest = withContext(Dispatchers.IO) { runCatching { receive(server) }.getOrNull() }
                assertNull(furtherRequest)
            }
        }

    @Test
    fun socksStunCancellationClosesUdpAndItsControlConnection() =
        runTest {
            DatagramSocket(InetSocketAddress(loopback, 0)).use { relay ->
                ServerSocket(0, 1, loopback).use { proxy ->
                    relay.soTimeout = 3_000
                    proxy.soTimeout = 3_000
                    val sent = CompletableDeferred<Unit>()
                    val peer =
                        async(Dispatchers.IO) {
                            proxy.accept().use { control ->
                                control.soTimeout = 3_000
                                val input = DataInputStream(control.getInputStream())
                                input.readFully(ByteArray(3))
                                control.getOutputStream().write(byteArrayOf(5, 0))
                                input.readFully(ByteArray(10))
                                control.getOutputStream().write(
                                    byteArrayOf(5, 0, 0, 1) + loopback.address + port(relay.localPort),
                                )
                                receive(relay)
                                sent.complete(Unit)
                                input.read()
                            }
                        }
                    val probe =
                        launch(Dispatchers.IO) {
                            Socks5UdpAssociateStunClient(dispatchers).reflexiveAddress(
                                ProxyEndpoint("127.0.0.1", proxy.localPort, ProxyType.SOCKS5),
                            )
                        }
                    sent.await()
                    probe.cancelPromptly()
                    assertEquals(-1, peer.await())
                }
            }
        }

    @Test
    fun socksControlCancellationClosesAWaitingHandshake() =
        runTest {
            ServerSocket(0, 1, loopback).use { proxy ->
                proxy.soTimeout = 3_000
                val sent = CompletableDeferred<Unit>()
                val peer =
                    async(Dispatchers.IO) {
                        proxy.accept().use { control ->
                            control.soTimeout = 3_000
                            val input = DataInputStream(control.getInputStream())
                            input.readFully(ByteArray(3))
                            sent.complete(Unit)
                            input.read()
                        }
                    }
                val probe =
                    launch(Dispatchers.IO) {
                        Socks5UdpAssociateStunClient(dispatchers).reflexiveAddress(
                            ProxyEndpoint("127.0.0.1", proxy.localPort, ProxyType.SOCKS5),
                        )
                    }
                sent.await()
                probe.cancelPromptly()
                assertEquals(-1, peer.await())
            }
        }

    @Test
    fun cancelledDnsWaitDoesNotReleaseItsBlockingLookupAdmission() =
        runTest {
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val lookups =
                List(2) {
                    launch(Dispatchers.IO) {
                        resolveStunAddress("blocked.invalid") {
                            started.countDown()
                            check(release.await(3, TimeUnit.SECONDS))
                            loopback
                        }
                    }
                }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(1, TimeUnit.SECONDS) })
                lookups.forEach { it.cancelPromptly() }
                val third =
                    withContext(Dispatchers.Default) {
                        withTimeoutOrNull(100) {
                            resolveStunAddress(
                                "third.invalid",
                            ) { error("blocking lookup admission was released early") }
                        }
                    }
                assertNull(third)
            } finally {
                release.countDown()
                lookups.forEach { it.cancelAndJoin() }
            }
            val recovered =
                withContext(Dispatchers.Default) {
                    withTimeout(1_000) { resolveStunAddress("recovered.invalid") { loopback } }
                }
            assertEquals(loopback, recovered)
        }

    private suspend fun Job.cancelPromptly() =
        withContext(Dispatchers.Default) {
            withTimeout(1_000) { cancelAndJoin() }
        }

    private fun receive(socket: DatagramSocket): DatagramPacket =
        DatagramPacket(ByteArray(1_500), 1_500).also(socket::receive)

    private fun port(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())
}
