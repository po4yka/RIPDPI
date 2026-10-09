package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsTransferRunUiModel
import com.poyka.ripdpi.activities.DiagnosticsTransferSampleUiModel
import com.poyka.ripdpi.activities.DiagnosticsTransferUiModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DiagnosticsGuidedTransferSelectionComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun guidedThroughputResultOpensOriginalProbeWithCopyablePartialEvidence() {
        val probe = throughputProbe()
        var selected: DiagnosticsProbeResultUiModel? = null
        composeRule.setContent {
            RipDpiTheme {
                ScanSection(
                    scan = DiagnosticsScanUiModel(latestResults = persistentListOf(probe)),
                    expertMode = false,
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = {},
                    onOpenAdvancedSettings = {},
                    onOpenDnsSettings = {},
                    onRequestVpnPermission = {},
                    onSelectStrategyProbeCandidate = {},
                    onSelectProbe = { selected = it },
                    onOpenHistory = {},
                    onOpenModeEditor = {},
                    onOpenOwnedStackBrowser = {},
                )
            }
        }
        val resultTag = RipDpiTestTags.diagnosticsProbe(probe.id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(resultTag))
        composeRule.onNodeWithTag(resultTag).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertSame(probe, selected)
            val copy = formatDiagnosticsProbeEvidence(requireNotNull(selected))
            assertTrue(copy.contains("receivedBytes=128"))
            assertTrue(copy.contains("idle_timeout"))
            assertTrue(copy.contains("Sample: elapsedMs=20 bodyBytes=128"))
        }
    }

    private fun throughputProbe() =
        DiagnosticsProbeResultUiModel(
            id = "partial-throughput",
            probeType = "throughput",
            target = "example.org",
            outcome = "partial",
            tone = DiagnosticsTone.Warning,
            details = persistentListOf(),
            transferEvidence =
                DiagnosticsTransferUiModel(
                    target = "example.org",
                    runs =
                        persistentListOf(
                            DiagnosticsTransferRunUiModel(
                                runIndex = 1,
                                runCount = 1,
                                receivedBodyByteCount = 128,
                                expectedBodyByteCount = null,
                                elapsedMs = 30,
                                firstBodyByteMs = 10,
                                lastBodyProgressMs = 20,
                                terminationReason = "idle_timeout",
                                responseComplete = false,
                                windowComplete = false,
                                samples = persistentListOf(DiagnosticsTransferSampleUiModel(20, 128)),
                            ),
                        ),
                ),
        )
}
