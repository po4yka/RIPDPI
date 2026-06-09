package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.core.detection.BlockLayer
import com.poyka.ripdpi.core.detection.BlockLayerDiagnosis
import com.poyka.ripdpi.core.detection.BypassStrategyClass
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeFailureKind
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.ui.screens.blockcheck.BlockcheckRunState
import com.poyka.ripdpi.ui.screens.blockcheck.BlockcheckScreen
import com.poyka.ripdpi.ui.screens.blockcheck.BlockcheckSiteDiagnosis
import com.poyka.ripdpi.ui.screens.blockcheck.BlockcheckUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BlockcheckScreenScreenshotTest {
    @Test
    fun blockcheckIdleState() {
        captureBlockcheckScreen(BlockcheckUiState())
    }

    @Test
    fun blockcheckRunningState() {
        captureBlockcheckScreen(
            BlockcheckUiState(
                runState = BlockcheckRunState.Running,
                results =
                    listOf(
                        probeResult("tlsrec_split_host", "TLS record split", success = true, latencyMs = 118),
                        probeResult(
                            "quic_disabled",
                            "Disable QUIC",
                            success = false,
                            latencyMs = 0,
                            failureKind = StrategyProbeFailureKind.DnsTampered,
                            dnsTampered = true,
                        ),
                    ),
                rankedStrategies =
                    listOf(
                        rankedResult(
                            "tlsrec_split_host",
                            "TLS record split",
                            successes = 1,
                            total = 1,
                            latencyMs = 118,
                        ),
                        rankedResult("quic_disabled", "Disable QUIC", successes = 0, total = 1, latencyMs = 0),
                    ),
                diagnoses =
                    listOf(
                        diagnosis(
                            domain = "youtube.com",
                            layer = BlockLayer.DNS_POISONING,
                            bypassClass = BypassStrategyClass.ENCRYPTED_DNS,
                        ),
                    ),
                totalExpectedResults = 12,
                message = "Testing youtube.com",
            ),
        )
    }

    @Test
    fun blockcheckCompleteState() {
        captureBlockcheckScreen(
            BlockcheckUiState(
                runState = BlockcheckRunState.Complete,
                results =
                    listOf(
                        probeResult("tlsrec_split_host", "TLS record split", success = true, latencyMs = 118),
                        probeResult("tlsrec_split_host", "TLS record split", success = true, latencyMs = 132),
                        probeResult("fake_split", "Fake split", success = true, latencyMs = 170),
                        probeResult("plain", "Plain", success = false, latencyMs = 0),
                    ),
                rankedStrategies =
                    listOf(
                        rankedResult(
                            "tlsrec_split_host",
                            "TLS record split",
                            successes = 2,
                            total = 2,
                            latencyMs = 125,
                        ),
                        rankedResult("fake_split", "Fake split", successes = 1, total = 1, latencyMs = 170),
                        rankedResult("plain", "Plain", successes = 0, total = 1, latencyMs = 0),
                    ),
                diagnoses =
                    listOf(
                        diagnosis(
                            domain = "youtube.com",
                            layer = BlockLayer.SNI_BASED_RESET,
                            bypassClass = BypassStrategyClass.TLS_RECORD_SPLIT,
                        ),
                    ),
                recommendedStrategyId = "tlsrec_split_host",
                recommendedStrategyLabel = "TLS record split",
                totalExpectedResults = 4,
                message = "Probe complete",
            ),
        )
    }
}

private fun captureBlockcheckScreen(state: BlockcheckUiState) {
    captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
        RipDpiTheme(themePreference = "light") {
            BlockcheckScreen(
                state = state,
                onBack = {},
                onDomainsChanged = {},
                onRun = {},
                onCancel = {},
                onApplyBest = {},
                onApplyRecommended = {},
                onExport = {},
            )
        }
    }
}

private fun probeResult(
    strategyId: String,
    strategyLabel: String,
    success: Boolean,
    latencyMs: Long,
    failureKind: StrategyProbeFailureKind? = null,
    dnsTampered: Boolean = false,
): StrategyProbeResult =
    StrategyProbeResult(
        strategyId = strategyId,
        strategyLabel = strategyLabel,
        domain = "youtube.com",
        success = success,
        latencyMs = latencyMs,
        failureKind = failureKind,
        dnsTampered = dnsTampered,
    )

private fun rankedResult(
    strategyId: String,
    strategyLabel: String,
    successes: Int,
    total: Int,
    latencyMs: Long,
): RankedStrategyProbeResult =
    RankedStrategyProbeResult(
        strategyId = strategyId,
        strategyLabel = strategyLabel,
        total = total,
        successes = successes,
        successRate = successes.toDouble() / total.toDouble(),
        averageLatencyMs = latencyMs,
        dnsTamperedCount = 0,
    )

private fun diagnosis(
    domain: String,
    layer: BlockLayer,
    bypassClass: BypassStrategyClass,
): BlockcheckSiteDiagnosis =
    BlockcheckSiteDiagnosis(
        domain = domain,
        diagnosis =
            BlockLayerDiagnosis(
                layer = layer,
                bypassClass = bypassClass,
                confidence = EvidenceConfidence.HIGH,
                reasonCode = "screenshot",
            ),
    )
