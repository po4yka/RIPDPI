package com.poyka.ripdpi.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.diagnostics.QuicVersionComparisonProbeType
import com.poyka.ripdpi.diagnostics.QuicVersionMatrixEvidence
import com.poyka.ripdpi.diagnostics.QuicVersionV1ProbeType
import com.poyka.ripdpi.diagnostics.QuicVersionV2ProbeType
import com.poyka.ripdpi.diagnostics.SelectiveMatrixComparison
import com.poyka.ripdpi.diagnostics.SelectiveMatrixLegSummary
import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairRow
import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairVerdict
import com.poyka.ripdpi.diagnostics.SelectiveMatrixTargetLegOutcome
import com.poyka.ripdpi.diagnostics.SelectiveMatrixTargetStage
import com.poyka.ripdpi.diagnostics.TlsHopAttempt
import com.poyka.ripdpi.diagnostics.TlsHopComparisonEvidence
import com.poyka.ripdpi.diagnostics.TlsHopStatus
import com.poyka.ripdpi.ui.screens.diagnostics.QuicVersionResultsCard
import com.poyka.ripdpi.ui.screens.diagnostics.TlsHopResultsCard
import com.poyka.ripdpi.ui.screens.diagnostics.state.QuicVersionRowUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.TlsHopResultUiModel
import com.poyka.ripdpi.ui.screens.history.SelectiveMatrixPairCandidateUiModel
import com.poyka.ripdpi.ui.screens.history.SelectiveMatrixPairCard
import com.poyka.ripdpi.ui.screens.history.SelectiveMatrixPairingUiModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DiagnosticsEvidenceCardsScreenshotTest {
    @Test
    fun tlsHopLight() = capture("tlsHop_light") { TlsHopCard() }

    @Test
    fun tlsHopDark() = capture("tlsHop_dark", darkMode = true) { TlsHopCard() }

    @Test
    @Config(qualifiers = "ar-rEG")
    fun tlsHopRtl() = capture("tlsHop_rtl", direction = LayoutDirection.Rtl) { TlsHopCard() }

    @Test
    fun tlsHopMaximumFont() = capture("tlsHop_max_font", fontScale = 2f) { TlsHopCard() }

    @Test
    fun quicVersionLight() = capture("quicVersion_light") { QuicVersionCard() }

    @Test
    fun quicVersionDark() = capture("quicVersion_dark", darkMode = true) { QuicVersionCard() }

    @Test
    @Config(qualifiers = "ar-rEG")
    fun quicVersionRtl() = capture("quicVersion_rtl", direction = LayoutDirection.Rtl) { QuicVersionCard() }

    @Test
    fun quicVersionMaximumFont() = capture("quicVersion_max_font", fontScale = 2f) { QuicVersionCard() }

    @Test
    fun matrixPairLight() = capture("matrixPair_light") { MatrixPairCard() }

    @Test
    fun matrixPairDark() = capture("matrixPair_dark", darkMode = true) { MatrixPairCard() }

    @Test
    @Config(qualifiers = "ar-rEG")
    fun matrixPairRtl() = capture("matrixPair_rtl", direction = LayoutDirection.Rtl) { MatrixPairCard() }

    @Test
    fun matrixPairMaximumFont() = capture("matrixPair_max_font", fontScale = 2f) { MatrixPairCard() }

    private fun capture(
        name: String,
        darkMode: Boolean = false,
        direction: LayoutDirection? = null,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) = withUtcScreenshotTime {
        captureSingle(
            name = name,
            widthDp = 420,
            heightDp = if (fontScale > 1f) 1000 else 600,
            darkMode = darkMode,
            fontScale = fontScale,
            layoutDirection = direction,
            testClassFqn = javaClass.name,
            content = content,
        )
    }
}

@Composable
private fun TlsHopCard() =
    TlsHopResultsCard(
        TlsHopResultUiModel(
            evidence =
                TlsHopComparisonEvidence(
                    status = TlsHopStatus.DIVERGENCE,
                    firstHop = 5,
                    lastHop = 5,
                    pathLength = 9,
                    stop = "COMPLETED",
                    controlAttempts = 18,
                    testAttempts = 18,
                ),
            controlSni = "control.example",
            testSni = "test.example",
            attempts =
                persistentListOf(
                    TlsHopAttempt(ttl = 5, role = "TEST", repetition = 0, outcome = "RST", elapsedMs = 41),
                ),
        ),
    )

@Composable
private fun QuicVersionCard() {
    val limits = listOf("initial_stage_only", "local_port_separation_only")
    QuicVersionResultsCard(
        listOf(
            QuicVersionRowUiModel(
                QuicVersionV1ProbeType,
                QuicVersionMatrixEvidence("TEST_NOT_ANSWERED", "V1", limits = limits),
            ),
            QuicVersionRowUiModel(
                QuicVersionV2ProbeType,
                QuicVersionMatrixEvidence("TEST_ANSWERED", "V2", limits = limits),
            ),
            QuicVersionRowUiModel(
                QuicVersionComparisonProbeType,
                QuicVersionMatrixEvidence("DIFFERENCE", limits = limits),
            ),
        ),
    )
}

@Composable
private fun MatrixPairCard() =
    SelectiveMatrixPairCard(
        SelectiveMatrixPairingUiModel(
            candidates = persistentListOf(SelectiveMatrixPairCandidateUiModel("cell", "cellular", "Jan 1")),
            targetLabels = persistentMapOf("site" to "example.org", "news" to "news.example"),
            pairedSessionId = "cell",
            comparison =
                SelectiveMatrixComparison.Compared(
                    first = SelectiveMatrixLegSummary("wifi", 0L, 1L, valid = true, controlsValid = true),
                    second =
                        SelectiveMatrixLegSummary(
                            "cellular",
                            60_000L,
                            61_000L,
                            valid = true,
                            controlsValid = true,
                        ),
                    rows =
                        listOf(
                            SelectiveMatrixPairRow(
                                "site",
                                SelectiveMatrixPairVerdict.PATH_SPECIFIC,
                                SelectiveMatrixTargetLegOutcome(SelectiveMatrixTargetStage.AVAILABLE, null, 2, 2),
                                SelectiveMatrixTargetLegOutcome(SelectiveMatrixTargetStage.FAILED, "tls", 2, 0),
                            ),
                            SelectiveMatrixPairRow(
                                "news",
                                SelectiveMatrixPairVerdict.AVAILABLE_ON_BOTH,
                                SelectiveMatrixTargetLegOutcome(SelectiveMatrixTargetStage.AVAILABLE, null, 2, 2),
                                SelectiveMatrixTargetLegOutcome(SelectiveMatrixTargetStage.AVAILABLE, null, 2, 2),
                            ),
                        ),
                ),
        ),
        onCompare = {},
    )
