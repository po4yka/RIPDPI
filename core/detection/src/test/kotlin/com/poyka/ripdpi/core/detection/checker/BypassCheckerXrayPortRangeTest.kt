package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.BypassPortRange
import com.poyka.ripdpi.core.detection.BypassScanOptions
import com.poyka.ripdpi.core.detection.probe.XrayApiScanner
import com.poyka.ripdpi.core.detection.vpn.VpnAppCatalog
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

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
            assertEquals(54320, firstXrayPort(BypassPortRange.Custom(54321, 54320)))
            assertEquals(
                (VpnAppCatalog.localhostProxyPorts + listOf(1081, 7890, 7891, 10085)).min(),
                firstXrayPort(BypassPortRange.Popular),
            )
        }

    @Test
    fun xrayScannerAttemptsExplicitPorts() =
        runBlocking {
            val visited = mutableListOf<Int>()
            XrayApiScanner(
                dispatchers = dispatchers,
                loopbackHosts = listOf("127.0.0.1"),
                scanRange = 5000..5000,
                scanPorts = listOf(-1),
                maxConcurrency = 1,
                progressUpdateEvery = 1,
            ).findXrayApi { progress ->
                if (progress.scanned == 1) visited += progress.currentPort
            }
            assertEquals(listOf(-1, -1), visited)
        }

    private suspend fun firstXrayPort(range: BypassPortRange): Int {
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
        return requireNotNull(firstPort)
    }

    private class StopScan : RuntimeException()
}
