package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class DeveloperBaselineDeltaTest {
    @Test
    fun `baseline delta uses a dated distinct-network cohort distribution`() {
        val comparison =
            requireNotNull(
                buildDeveloperBaselineDelta(
                    context = currentOutcomeContext(),
                    baselineSamples = baselineSamples(),
                ),
            ).comparisons.single()

        assertEquals("stage_success_rate", comparison.metric)
        assertEquals("0.50", comparison.userValue)
        assertEquals(
            DeveloperBaselineDistribution(
                cohort = "device_local_distinct_networks:8_stages",
                sampleCount = 5,
                p50 = 0.75,
                p95 = 1.0,
                asOfDate = "2026-04-05",
                source = "device_local_probe_result_cache",
            ),
            comparison.baseline,
        )
        assertEquals("below_baseline", comparison.verdict)
    }

    @Test
    fun `baseline delta with too few cohort samples exports evidence without a comparative verdict`() {
        val comparison =
            requireNotNull(
                buildDeveloperBaselineDelta(
                    context = currentOutcomeContext(),
                    baselineSamples = baselineSamples().filterNot { it.fingerprintHash == "current" }.take(4),
                ),
            ).comparisons.single()

        assertEquals(
            DeveloperBaselineDistribution(
                cohort = "device_local_distinct_networks:8_stages",
                sampleCount = 4,
                p50 = 0.625,
                p95 = 0.875,
                asOfDate = "2026-04-04",
                source = "device_local_probe_result_cache",
            ),
            comparison.baseline,
        )
        assertEquals("insufficient_sample", comparison.verdict)
    }

    @Test
    fun `baseline delta excludes legacy samples without an exact stage denominator`() {
        val legacySample =
            cachedOutcome("legacy", completed = 6, cachedAt = "2026-04-06T00:00:00Z")
                .copy(failedStageCount = 2, totalStageCount = null)
        val comparison =
            requireNotNull(
                buildDeveloperBaselineDelta(
                    context = currentOutcomeContext(),
                    baselineSamples = baselineSamples().drop(1).take(4) + legacySample,
                ),
            ).comparisons.single()

        assertEquals(4, comparison.baseline.sampleCount)
        assertEquals("2026-04-04", comparison.baseline.asOfDate)
        assertEquals("insufficient_sample", comparison.verdict)
    }

    private fun currentOutcomeContext() =
        DeveloperAnalyticsContext(
            archiveCreatedAtMs = timestamp("2026-04-10T00:00:00Z"),
            archiveFileName = "ripdpi-analysis.zip",
            homeCompositeOutcome =
                DiagnosticsHomeCompositeOutcome(
                    runId = "current-run",
                    fingerprintHash = "current",
                    actionable = false,
                    headline = "Analysis complete",
                    summary = "Four of eight stages completed.",
                    stageSummaries =
                        List(8) { index ->
                            DiagnosticsHomeCompositeStageSummary(
                                stageKey = "stage_$index",
                                stageLabel = "Stage $index",
                                profileId = "profile_$index",
                                pathMode = ScanPathMode.RAW_PATH,
                                status =
                                    if (index < 4) {
                                        DiagnosticsHomeCompositeStageStatus.COMPLETED
                                    } else {
                                        DiagnosticsHomeCompositeStageStatus.FAILED
                                    },
                                headline = "Stage $index",
                                summary = "Stage $index result",
                            )
                        },
                    completedStageCount = 4,
                    failedStageCount = 4,
                ),
        )

    private fun baselineSamples(): List<CachedProbeOutcome> =
        listOf(
            cachedOutcome("current", completed = 8, cachedAt = "2026-04-09T00:00:00Z"),
            cachedOutcome("network-1", completed = 4, cachedAt = "2026-04-01T00:00:00Z"),
            cachedOutcome("network-2", completed = 5, cachedAt = "2026-04-02T00:00:00Z"),
            cachedOutcome("network-3", completed = 6, cachedAt = "2026-04-03T00:00:00Z"),
            cachedOutcome("network-4", completed = 7, cachedAt = "2026-04-04T00:00:00Z"),
            cachedOutcome("network-5", completed = 8, cachedAt = "2026-04-05T00:00:00Z"),
        )

    private fun cachedOutcome(
        fingerprintHash: String,
        completed: Int,
        cachedAt: String,
    ) = CachedProbeOutcome(
        fingerprintHash = fingerprintHash,
        headline = "Cached outcome",
        summary = "Cached outcome for $fingerprintHash",
        appliedSettings = emptyList(),
        completedStageCount = completed,
        failedStageCount = 8 - completed,
        totalStageCount = 8,
        cachedAtMs = timestamp(cachedAt),
    )

    private fun timestamp(value: String): Long = Instant.parse(value).toEpochMilli()
}
