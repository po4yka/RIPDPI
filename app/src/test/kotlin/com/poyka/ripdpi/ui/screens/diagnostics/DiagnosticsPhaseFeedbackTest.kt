package com.poyka.ripdpi.ui.screens.diagnostics

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeLiveProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeProgressLaneUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.PhaseState
import com.poyka.ripdpi.activities.PhaseStepUiModel
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsPhaseFeedbackTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `active phase exposes its localized state`() {
        composeRule.setContent {
            RipDpiTheme {
                ScanProgressCard(
                    progress =
                        DiagnosticsProgressUiModel(
                            phase = "dns",
                            summary = "Checking network",
                            completedSteps = 1,
                            totalSteps = 4,
                            fraction = 0.25f,
                            scanKind = ScanKind.CONNECTIVITY,
                            isFullAudit = false,
                            scanStartedAtMs = 0L,
                            phaseSteps =
                                persistentListOf(
                                    PhaseStepUiModel("DNS", PhaseState.Active, DiagnosticsTone.Warning),
                                    PhaseStepUiModel("Reach", PhaseState.Pending, DiagnosticsTone.Neutral),
                                ),
                            currentProbeLabel = "Checking resolver",
                        ),
                    strategyProbeSelected = false,
                )
            }
        }

        composeRule.onNodeWithText("DNS").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In progress"),
        )
    }

    @Test
    @Config(qualifiers = "ru-w400dp-h800dp-mdpi")
    fun `each phase exposes its localized state without a spoken completion mark`() {
        showStrip(
            mutableStateOf(
                persistentListOf(
                    phase("DNS", PhaseState.Completed),
                    phase("TCP", PhaseState.Active),
                    phase("QUIC", PhaseState.Pending),
                ),
            ),
        )

        assertPhaseState("DNS", R.string.onboarding_test_row_state_done)
        assertPhaseState("TCP", R.string.onboarding_test_row_state_active)
        assertPhaseState("QUIC", R.string.onboarding_test_row_state_pending)
        composeRule
            .onAllNodes(
                SemanticsMatcher("spoken completion mark") {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains('✓') } == true
                },
            ).assertCountEquals(0)
    }

    @Test
    fun `phase change updates current semantics without moving phase bounds`() {
        val steps = mutableStateOf(persistentListOf(phase("DNS", PhaseState.Active), phase("TCP", PhaseState.Pending)))
        showStrip(steps)
        val dns = composeRule.onNodeWithText("DNS").fetchSemanticsNode()
        val tcp = composeRule.onNodeWithText("TCP").fetchSemanticsNode()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnUiThread {
            steps.value = persistentListOf(phase("DNS", PhaseState.Completed), phase("TCP", PhaseState.Active))
        }
        advance(32)

        assertPhaseState("DNS", R.string.onboarding_test_row_state_done)
        assertPhaseState("TCP", R.string.onboarding_test_row_state_active)
        assertEquals(dns.id, composeRule.onNodeWithText("DNS").fetchSemanticsNode().id)
        assertEquals(dns.boundsInRoot, composeRule.onNodeWithText("DNS").fetchSemanticsNode().boundsInRoot)
        assertEquals(tcp.id, composeRule.onNodeWithText("TCP").fetchSemanticsNode().id)
        assertEquals(tcp.boundsInRoot, composeRule.onNodeWithText("TCP").fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithContentDescription("DNS").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("TCP").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
    }

    @Test
    fun `normal phase feedback interpolates finitely and settles`() {
        val steps = mutableStateOf(persistentListOf(phase("DNS", PhaseState.Active)))
        showStrip(steps)
        val initial = chipBackground()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnUiThread { steps.value = persistentListOf(phase("DNS", PhaseState.Completed)) }
        advance(48)
        val moving = chipBackground()
        advance(160)
        val settled = chipBackground()

        assertNotEquals(initial, moving)
        assertNotEquals(settled, moving)
        advance(48)
        assertEquals(settled, chipBackground())
    }

    @Test
    fun `counter and probe ticks do not change the phase summary or phase bounds`() {
        val progress = mutableStateOf(progress())
        showCard(progress)
        val summary = composeRule.onNode(phaseSummary).fetchSemanticsNode()
        val phaseBounds = composeRule.onNodeWithText("DNS").fetchSemanticsNode().boundsInRoot
        val phaseColor = chipBackground()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnUiThread {
            progress.value =
                progress.value.copy(completedSteps = 2, fraction = 0.5f, currentProbeLabel = "Next resolver")
        }
        advance(48)

        val afterTick = composeRule.onNode(phaseSummary).fetchSemanticsNode()
        assertEquals(summary.id, afterTick.id)
        assertEquals(
            summary.config[SemanticsProperties.ContentDescription],
            afterTick.config[SemanticsProperties.ContentDescription],
        )
        assertEquals(
            summary.config[SemanticsProperties.StateDescription],
            afterTick.config[SemanticsProperties.StateDescription],
        )
        assertEquals(phaseBounds, composeRule.onNodeWithText("DNS").fetchSemanticsNode().boundsInRoot)
        assertEquals(phaseColor, chipBackground())
    }

    @Test
    fun `finished phase strip does not retain a stale active announcement`() {
        val steps = mutableStateOf(persistentListOf(phase("DNS", PhaseState.Active)))
        showStrip(steps)
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnUiThread { steps.value = persistentListOf(phase("DNS", PhaseState.Completed)) }
        advance(32)

        composeRule.onNodeWithContentDescription("DNS").assertDoesNotExist()
        composeRule.onAllNodes(phaseSummary).assertCountEquals(0)
        assertPhaseState("DNS", R.string.onboarding_test_row_state_done)
    }

    @Test
    fun `strategy lane does not duplicate the phase announcement`() {
        showCard(
            mutableStateOf(
                progress().copy(
                    scanKind = ScanKind.STRATEGY_PROBE,
                    phaseSteps = persistentListOf(phase("TCP", PhaseState.Active), phase("QUIC", PhaseState.Pending)),
                    strategyProbeProgress =
                        DiagnosticsStrategyProbeLiveProgressUiModel(
                            lane = DiagnosticsStrategyProbeProgressLaneUiModel.TCP,
                            candidateIndex = 1,
                            candidateTotal = 3,
                            candidateId = "tcp",
                            candidateLabel = "TCP candidate",
                        ),
                ),
            ),
        )

        composeRule
            .onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite) and
                    SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo) and
                    SemanticsMatcher("TCP announcement") {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { description ->
                            description.contains("TCP")
                        } == true
                    },
            ).assertCountEquals(1)
        composeRule
            .onNodeWithContentDescription(resource(R.string.status_indicator_description, "TCP"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `probe fade exposes only the current label without a live region`() {
        val label = mutableStateOf("Old resolver")
        showLabel(label)
        val originalNodeId = composeRule.onNodeWithText("Old resolver").fetchSemanticsNode().id
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnUiThread { label.value = "New resolver" }
        advance(32)

        composeRule.onNodeWithText("Old resolver").assertDoesNotExist()
        composeRule
            .onNodeWithText(
                "New resolver",
            ).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        assertEquals(originalNodeId, composeRule.onNodeWithText("New resolver").fetchSemanticsNode().id)
    }

    @Test
    fun `disabled phase motion shows final feedback immediately`() {
        assertStaticFeedback(DefaultRipDpiMotion.copy(animationsEnabled = false))
    }

    @Test
    fun `reduced phase motion shows final feedback immediately`() {
        assertStaticFeedback(DefaultRipDpiMotion.copy(reducedMotion = true))
    }

    @Test
    fun `inspection mode shows final feedback immediately`() {
        assertStaticFeedback(DefaultRipDpiMotion, inspection = true)
    }

    @Test
    fun `disabled motion replaces the probe label and its geometry immediately`() {
        assertStaticLabel(DefaultRipDpiMotion.copy(animationsEnabled = false))
    }

    @Test
    fun `reduced motion replaces the probe label and its geometry immediately`() {
        assertStaticLabel(DefaultRipDpiMotion.copy(reducedMotion = true))
    }

    @Test
    fun `inspection mode replaces the probe label and its geometry immediately`() {
        assertStaticLabel(DefaultRipDpiMotion, inspection = true)
    }

    @Test
    fun `disabling motion during a probe fade discards outgoing label geometry`() {
        val label = mutableStateOf("A long resolver verification label")
        val motion = mutableStateOf(DefaultRipDpiMotion)
        showLabel(label, motion)
        val originalNodeId = composeRule.onNodeWithText("A long resolver verification label").fetchSemanticsNode().id
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { label.value = "Next" }
        advance(32)
        val movingWidth =
            composeRule
                .onNodeWithText("Next")
                .fetchSemanticsNode()
                .boundsInRoot.width

        composeRule.runOnUiThread { motion.value = motion.value.copy(animationsEnabled = false) }
        advance(32)
        val directWidth =
            composeRule
                .onNodeWithText("Next")
                .fetchSemanticsNode()
                .boundsInRoot.width
        advance(200)

        assertNotEquals(movingWidth, directWidth)
        assertEquals(
            directWidth,
            composeRule
                .onNodeWithText("Next")
                .fetchSemanticsNode()
                .boundsInRoot.width,
        )
        assertEquals(originalNodeId, composeRule.onNodeWithText("Next").fetchSemanticsNode().id)
        composeRule.onNodeWithText("A long resolver verification label").assertDoesNotExist()
    }

    @Test
    fun `disabling motion during phase feedback shows the current state immediately`() {
        val steps = mutableStateOf(persistentListOf(phase("DNS", PhaseState.Active)))
        val motion = mutableStateOf(DefaultRipDpiMotion)
        showStrip(steps, motion)
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { steps.value = persistentListOf(phase("DNS", PhaseState.Completed)) }
        advance(32)
        val moving = chipBackground()

        composeRule.runOnUiThread { motion.value = motion.value.copy(animationsEnabled = false) }
        advance(32)
        val snapped = chipBackground()
        advance(200)

        assertNotEquals(moving, snapped)
        assertEquals(snapped, chipBackground())
        assertPhaseState("DNS", R.string.onboarding_test_row_state_done)
    }

    private fun assertStaticFeedback(
        motion: RipDpiMotion,
        inspection: Boolean = false,
    ) {
        val steps = mutableStateOf(persistentListOf(phase("DNS", PhaseState.Active)))
        showStrip(steps, mutableStateOf(motion), inspection)
        val initial = chipBackground()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { steps.value = persistentListOf(phase("DNS", PhaseState.Completed)) }
        advance(32)
        val immediate = chipBackground()
        advance(200)

        assertNotEquals(initial, immediate)
        assertEquals(immediate, chipBackground())
        assertPhaseState("DNS", R.string.onboarding_test_row_state_done)
    }

    private fun assertStaticLabel(
        motion: RipDpiMotion,
        inspection: Boolean = false,
    ) {
        val label = mutableStateOf("A long resolver verification label")
        showLabel(label, mutableStateOf(motion), inspection)
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { label.value = "Next" }
        advance(32)
        val immediate = composeRule.onNodeWithText("Next").fetchSemanticsNode().boundsInRoot
        advance(200)

        assertEquals(immediate, composeRule.onNodeWithText("Next").fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithText("A long resolver verification label").assertDoesNotExist()
    }

    // Snapshot notifications must reach the recomposer before the frozen test clock advances.
    private fun advance(millis: Long) {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    private fun chipBackground(): Color {
        val image = composeRule.onNodeWithText("DNS").captureToImage().toPixelMap()
        return image[image.width / 2, 2]
    }

    private fun assertPhaseState(
        label: String,
        stateRes: Int,
    ) {
        composeRule
            .onNodeWithText(label)
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, resource(stateRes)),
            ).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    private fun showStrip(
        steps: State<ImmutableList<PhaseStepUiModel>>,
        motion: MutableState<RipDpiMotion> = mutableStateOf(DefaultRipDpiMotion),
        inspection: Boolean = false,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(
                    LocalRipDpiMotion provides motion.value,
                    LocalInspectionMode provides inspection,
                ) {
                    DiagnosticsPhaseStrip(steps.value)
                }
            }
        }
    }

    private fun showCard(progress: MutableState<DiagnosticsProgressUiModel>) {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(LocalRipDpiMotion provides DefaultRipDpiMotion) {
                    ScanProgressCard(
                        progress.value,
                        strategyProbeSelected =
                            progress.value.scanKind == ScanKind.STRATEGY_PROBE,
                    )
                }
            }
        }
    }

    private fun showLabel(
        label: MutableState<String>,
        motion: MutableState<RipDpiMotion> = mutableStateOf(DefaultRipDpiMotion),
        inspection: Boolean = false,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(
                    LocalRipDpiMotion provides motion.value,
                    LocalInspectionMode provides inspection,
                ) {
                    DiagnosticsCurrentProbeLabel(label.value)
                }
            }
        }
    }

    private fun phase(
        label: String,
        state: PhaseState,
    ) = PhaseStepUiModel(
        label,
        state,
        when (state) {
            PhaseState.Completed -> DiagnosticsTone.Positive
            PhaseState.Active -> DiagnosticsTone.Warning
            PhaseState.Pending -> DiagnosticsTone.Neutral
        },
    )

    private fun progress() =
        DiagnosticsProgressUiModel(
            phase = "dns",
            summary = "Checking network",
            completedSteps = 1,
            totalSteps = 4,
            fraction = 0.25f,
            scanKind = ScanKind.CONNECTIVITY,
            isFullAudit = false,
            scanStartedAtMs = 0L,
            phaseSteps = persistentListOf(phase("DNS", PhaseState.Active), phase("Reach", PhaseState.Pending)),
            currentProbeLabel = "Checking resolver",
        )

    private fun resource(
        id: Int,
        vararg args: Any,
    ): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private val phaseSummary =
        SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription) and
            SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo)
}
