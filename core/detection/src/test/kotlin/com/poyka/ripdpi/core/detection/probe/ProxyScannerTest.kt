package com.poyka.ripdpi.core.detection.probe

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

class ProxyScannerTest {
    private val loopbackHost = "127.0.0.1"
    private val loopback = InetAddress.getByName(loopbackHost)
    private val dispatchers =
        AppCoroutineDispatchers(
            io = Dispatchers.IO,
            default = Dispatchers.Default,
            main = Dispatchers.Default,
        )

    @Test
    fun refusesNonliteralLoopbackHostsAndOutOfRangePorts() {
        assertThrows(IllegalArgumentException::class.java) {
            ProxyScanner(dispatchers, loopbackHosts = listOf("localhost"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyScanner(dispatchers, loopbackHosts = listOf("192.0.2.1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyScanner(dispatchers, popularPorts = listOf(65536))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyScanner(dispatchers, scanRange = 0..1000)
        }
    }

    @Test
    fun popularPortOutsideSelectedRangeIsNotOpened() {
        ServerSocket(0, 1, loopback).use { popular ->
            ServerSocket(0, 1, loopback).use { selected ->
                val result =
                    runBlocking {
                        ProxyScanner(
                            dispatchers = dispatchers,
                            loopbackHosts = listOf(loopbackHost),
                            popularPorts = listOf(popular.localPort),
                            scanRange = selected.localPort..selected.localPort,
                            readTimeoutMs = 30,
                            maxConcurrency = 1,
                        ).scan {}
                    }

                assertNull(result.match)
                popular.soTimeout = 100
                assertThrows(SocketTimeoutException::class.java) { popular.accept().close() }
            }
        }
    }

    @Test(timeout = 5_000)
    fun scanBudgetCancelsBeforeADeferredPortAttempt() {
        ServerSocket(0, 1, loopback).use { listener ->
            val result =
                runBlocking {
                    ProxyScanner(
                        dispatchers = dispatchers,
                        loopbackHosts = listOf(loopbackHost),
                        popularPorts = emptyList(),
                        scanRange = listener.localPort..listener.localPort,
                        scanDeadlineMs = 100,
                    ).scan {
                        delay(1_000)
                    }
                }

            assertEquals(LocalScanResult(null, LocalScanStatus.TIMED_OUT), result)
            listener.soTimeout = 100
            assertThrows(SocketTimeoutException::class.java) { listener.accept().close() }
        }
    }

    @Test(timeout = 5_000)
    fun fullRangeReportsCompletedAttemptsWhenItFindsAnEarlyMatch() =
        runBlocking {
            ServerSocket(0, 1, loopback).use { listener ->
                val port = listener.localPort
                val peer =
                    async(Dispatchers.IO) {
                        listener.soTimeout = 2_000
                        listener.accept().use { socket ->
                            socket.soTimeout = 2_000
                            assertArrayEquals(byteArrayOf(5, 1, 0), socket.getInputStream().readNBytes(3))
                            socket.getOutputStream().write(byteArrayOf(5, 0))
                        }
                    }
                val progress = mutableListOf<ScanProgress>()
                val result =
                    ProxyScanner(
                        dispatchers = dispatchers,
                        loopbackHosts = listOf(loopbackHost),
                        popularPorts = emptyList(),
                        scanRange = port..65535,
                        readTimeoutMs = 500,
                        maxConcurrency = 1,
                    ).scan { progress += it }
                peer.await()

                assertEquals(
                    LocalScanResult(
                        ProxyEndpoint(loopbackHost, port, ProxyType.SOCKS5),
                        LocalScanStatus.MATCHED,
                    ),
                    result,
                )
                assertEquals(
                    listOf(
                        ScanProgress(ScanPhase.FULL_RANGE, 0, 65535 - port + 1, port),
                        ScanProgress(ScanPhase.FULL_RANGE, 1, 65535 - port + 1, port),
                    ),
                    progress,
                )
            }
        }
}
