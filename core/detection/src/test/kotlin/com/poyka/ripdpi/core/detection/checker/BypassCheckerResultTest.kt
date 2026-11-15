package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.BypassPortRange
import com.poyka.ripdpi.core.detection.BypassScanOptions
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.probe.LocalScanStatus
import com.poyka.ripdpi.core.detection.probe.XrayApiEndpoint
import com.poyka.ripdpi.core.detection.probe.XrayApiScanResult
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassCheckerResultTest {
    private val dispatchers =
        AppCoroutineDispatchers(
            io = Dispatchers.IO,
            default = Dispatchers.Default,
            main = Dispatchers.Default,
        )

    @Test
    fun xrayApiOnlyIsLocalReachabilityEvidence() =
        runBlocking {
            val api = XrayApiScanResult(XrayApiEndpoint("127.0.0.1", 10085), emptyList())

            val result =
                BypassChecker.check(
                    dispatchers = dispatchers,
                    options = BypassScanOptions(proxyScanEnabled = false),
                    xrayApiScanProvider = { api },
                )

            assertEquals(api, result.xrayApiScanResult)
            assertEquals(LocalScanStatus.SKIPPED, result.proxyScanStatus)
            assertEquals(LocalScanStatus.MATCHED, result.xrayScanStatus)
            assertEquals(CheckExecutionStatus.COMPLETED, result.executionStatus)
            assertFalse(result.detected)
            assertTrue(result.needsReview)
            val evidence = result.evidence.single()
            assertEquals(EvidenceSource.XRAY_API, evidence.source)
            assertTrue(evidence.detected)
            assertNull(evidence.family)
        }

    @Test
    fun disabledOrInvalidScansAreSkipped() =
        runBlocking {
            val result =
                BypassChecker.check(
                    dispatchers = dispatchers,
                    options = BypassScanOptions(proxyScanEnabled = false, portRange = BypassPortRange.Custom(2, 1)),
                )

            assertEquals(LocalScanStatus.SKIPPED, result.proxyScanStatus)
            assertEquals(LocalScanStatus.SKIPPED, result.xrayScanStatus)
            assertEquals(CheckExecutionStatus.SKIPPED, result.executionStatus)
            assertTrue(result.evidence.isEmpty())
        }

    @Test
    fun completedScanWithoutMatchIsNotSkipped() =
        runBlocking {
            val result =
                BypassChecker.check(
                    dispatchers = dispatchers,
                    options = BypassScanOptions(xrayApiScanEnabled = false),
                    proxyEndpointProvider = { null },
                )

            assertEquals(LocalScanStatus.NO_MATCH, result.proxyScanStatus)
            assertEquals(LocalScanStatus.SKIPPED, result.xrayScanStatus)
            assertEquals(CheckExecutionStatus.COMPLETED, result.executionStatus)
            assertTrue(result.evidence.isEmpty())
        }
}
