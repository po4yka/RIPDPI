package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.BypassPortRange
import com.poyka.ripdpi.core.detection.BypassScanOptions
import com.poyka.ripdpi.core.detection.vpn.VpnAppCatalog
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

class BypassCheckerXrayPortRangeTest {
    private val dispatchers =
        AppCoroutineDispatchers(
            io = Dispatchers.IO,
            default = Dispatchers.Default,
            main = Dispatchers.Default,
        )

    @Test
    fun xrayScanUsesSelectedPorts() =
        runBlocking {
            assertEquals(1, firstXrayPort(BypassPortRange.Full))
            assertEquals(54321, firstXrayPort(BypassPortRange.Custom(54321, 54321)))
            assertEquals(65535, firstXrayPort(BypassPortRange.Custom(65535, 65535)))
            assertNull(firstXrayPort(BypassPortRange.Custom(54321, 54320)))
            assertNull(firstXrayPort(BypassPortRange.Custom(0, 65536)))
            assertNull(firstXrayPort(BypassPortRange.Custom(5555, 5555)))
            assertEquals(
                (VpnAppCatalog.localhostProxyPorts + listOf(1081, 7890, 7891, 10085)).min(),
                firstXrayPort(BypassPortRange.Popular),
            )
        }

    @Test
    fun excludedPortIsNotOpenedByEitherScanner() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val port = listener.localPort
            val result =
                runBlocking {
                    BypassChecker.check(
                        dispatchers = dispatchers,
                        excludePorts = setOf(port),
                        options = BypassScanOptions(portRange = BypassPortRange.Custom(port, port)),
                    )
                }

            assertNull(result.proxyEndpoint)
            assertNull(result.xrayApiScanResult)
            listener.soTimeout = 100
            assertThrows(SocketTimeoutException::class.java) { listener.accept().close() }
        }
    }

    private suspend fun firstXrayPort(range: BypassPortRange): Int? {
        var firstPort: Int? = null
        try {
            BypassChecker.check(
                dispatchers = dispatchers,
                options =
                    BypassScanOptions(
                        proxyScanEnabled = false,
                        callTransportProbeEnabled = false,
                        portRange = range,
                    ),
                onProgress = { progress ->
                    if (progress.phase == "Xray API" && progress.detail.contains(" (")) {
                        firstPort =
                            progress.detail
                                .substringBefore(' ')
                                .substringAfterLast(':')
                                .toInt()
                        throw StopScan()
                    }
                },
            )
        } catch (_: StopScan) {
            // Stop before opening sockets; only the scan plan is under test.
        }
        return firstPort
    }

    private class StopScan : RuntimeException()
}
