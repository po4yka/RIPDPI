package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.checker.IcmpSpoofingChecker
import com.poyka.ripdpi.core.detection.checker.PingProbeResult
import com.poyka.ripdpi.core.detection.checker.PingProber
import com.poyka.ripdpi.core.detection.checker.RttTriangulationChecker
import com.poyka.ripdpi.core.detection.checker.TimingAnalysisChecker
import com.poyka.ripdpi.core.detection.debug.DetectionDebugFormatter
import com.poyka.ripdpi.core.detection.export.DetectionExportMetadata
import com.poyka.ripdpi.core.detection.export.DetectionJsonExportFormatter
import com.poyka.ripdpi.core.detection.export.DetectionMarkdownExportFormatter
import com.poyka.ripdpi.core.detection.export.detectionExportFixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionMeasurementStatusTest {
    @Test
    fun `missing ICMP observations and OS failures retain different execution states`() =
        runTest {
            for ((failure, status) in listOf(
                PingProbeFailure.NO_REPLY to CheckExecutionStatus.INCONCLUSIVE,
                PingProbeFailure.OS_ERROR to CheckExecutionStatus.ERROR,
            )) {
                val prober =
                    object : PingProber {
                        override suspend fun ping(host: String) =
                            PingProbeResult.unreachable(host, null, failure = failure)
                    }
                val result = IcmpSpoofingChecker.check(prober, false)
                assertEquals(IcmpSpoofingState.INCONCLUSIVE, result.state)
                assertEquals(status, result.category.executionStatus)
                assertFalse(result.category.detected)
                assertTrue(result.category.evidence.isEmpty())
            }
        }

    @Test
    fun `RTT absence and intentional skip do not become completed results`() =
        runTest {
            val prober =
                object : PingProber {
                    override suspend fun ping(host: String) = PingProbeResult.unreachable(host, null)
                }
            val missing = RttTriangulationChecker.check(prober, true, "RU")
            assertEquals(RttTriangulationState.INCONCLUSIVE, missing.state)
            assertEquals(CheckExecutionStatus.INCONCLUSIVE, missing.category.executionStatus)
            val skipped = RttTriangulationChecker.check(prober, false, "RU")
            assertEquals(CheckExecutionStatus.SKIPPED, skipped.category.executionStatus)
        }

    @Test
    fun `TCP no samples and insufficient samples are inconclusive`() {
        for (snapshots in listOf(
            emptyList(),
            listOf(TimingAnalysisChecker.TimingSnapshot("control", listOf(1, 2, 3))),
        )) {
            val result = TimingAnalysisChecker.analyze(snapshots)
            assertEquals(CheckExecutionStatus.INCONCLUSIVE, result.executionStatus)
            assertFalse(result.detected)
        }
    }

    @Test
    fun `plain JSON and Markdown reports preserve incomplete and skipped states`() {
        val fixture = detectionExportFixture()
        for (state in listOf(
            CheckExecutionStatus.INCONCLUSIVE,
            CheckExecutionStatus.ERROR,
            CheckExecutionStatus.SKIPPED,
        )) {
            val category = CategoryResult("ICMP", false, emptyList(), executionStatus = state)
            val result =
                fixture.copy(
                    timingAnalysis = category,
                    icmpSpoofing = IcmpSpoofingResult(IcmpSpoofingState.INCONCLUSIVE, category),
                    rttTriangulation = RttTriangulationResult(RttTriangulationState.INCONCLUSIVE, category),
                    webRtcLeak = category,
                    callTransport = CallTransportResult(category),
                )
            val report = DetectionReportFormatter.format(result)
            for (section in listOf("ICMP Spoofing", "WebRTC Leak", "Call Transport")) {
                assertTrue(report.contains("--- $section ---\nStatus: $state"))
            }
            val json =
                DetectionJsonExportFormatter.format(
                    result,
                    DetectionExportMetadata("2026-11-15T00:00:00Z", "test", "debug", false),
                )
            val categories =
                Json
                    .parseToJsonElement(json)
                    .jsonObject
                    .getValue("results")
                    .jsonObject
            for (name in listOf("timingAnalysis", "icmpSpoofing", "rttTriangulation", "webRtcLeak", "callTransport")) {
                assertEquals(
                    state.name,
                    categories
                        .getValue(name)
                        .jsonObject
                        .getValue("executionStatus")
                        .jsonPrimitive.content,
                )
            }
            val markdown =
                DetectionMarkdownExportFormatter.format(
                    result,
                    DetectionExportMetadata("2026-11-15T00:00:00Z", "test", "debug", false),
                )
            for (section in listOf("TimingAnalysis", "IcmpSpoofing", "RttTriangulation", "CallTransport")) {
                assertTrue(markdown.contains("## $section"))
                assertTrue(markdown.contains("| $section | [$state] |"))
            }
            val debug = DetectionDebugFormatter.format(result)
            for (section in listOf("timingAnalysis", "icmpSpoofing", "rttTriangulation", "callTransport")) {
                assertTrue(debug.contains("[$section]"))
            }
            assertTrue(debug.contains("executionStatus=$state"))
        }
    }

    @Test
    fun `incomplete observations never produce a clean recommendation`() {
        val empty = CategoryResult("empty", false, emptyList())
        val base =
            DetectionCheckResult(
                empty,
                empty,
                empty,
                empty,
                BypassResult(null, null, null, null, findings = emptyList(), detected = false),
                verdict = Verdict.NOT_DETECTED,
            )
        assertTrue(DetectionRecommendations.generate(base).any { it.title == "No issues detected" })
        val missing = empty.copy(executionStatus = CheckExecutionStatus.INCONCLUSIVE)
        for (result in listOf(
            base.copy(timingAnalysis = missing),
            base.copy(icmpSpoofing = IcmpSpoofingResult(IcmpSpoofingState.INCONCLUSIVE, missing)),
            base.copy(rttTriangulation = RttTriangulationResult(RttTriangulationState.INCONCLUSIVE, missing)),
            base.copy(webRtcLeak = missing),
            base.copy(callTransport = CallTransportResult(missing)),
        )) {
            assertFalse(DetectionRecommendations.generate(result).any { it.title == "No issues detected" })
            assertFalse(DetectionReportFormatter.format(result).contains("No issues detected"))
        }
    }
}
