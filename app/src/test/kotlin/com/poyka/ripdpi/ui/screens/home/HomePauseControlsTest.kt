package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomePauseUiState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseFailure
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.homePauseDuration
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomePauseControlsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `unavailable connection has no misleading pause or resume action`() {
        render(HomePauseUiState())
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseOpen).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseResume).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseStop).assertDoesNotExist()
    }

    @Test
    fun `duration selection requests exact minutes once and closes sheet`() {
        val durations = mutableListOf<Long>()
        render(HomePauseUiState(available = true), onPause = durations::add)
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseOpen).performClick()
        listOf(5, 15, 30, 60).forEach { minutes ->
            composeRule.onNodeWithTag(RipDpiTestTags.homePauseDuration(minutes)).assertHasClickAction()
        }
        assertTrue(durations.isEmpty())
        composeRule.onNodeWithTag(RipDpiTestTags.homePauseDuration(15)).performClick()
        assertEquals(listOf(900_000L), durations)
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseDurationSheet).assertDoesNotExist()
    }

    @Test
    fun `cancelled chooser requests no pause`() {
        val durations = mutableListOf<Long>()
        render(HomePauseUiState(available = true), onPause = durations::add)
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseOpen).performClick()
        composeRule.onNodeWithText(text(android.R.string.cancel)).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseDurationSheet).assertDoesNotExist()
        assertTrue(durations.isEmpty())
    }

    @Test
    fun `loss of eligibility dismisses an open chooser without dispatch`() {
        var state by mutableStateOf(HomePauseUiState(available = true))
        val durations = mutableListOf<Long>()
        composeRule.setContent { RipDpiTheme { HomePauseControls(state, durations::add, {}, {}) } }
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseOpen).performClick()
        composeRule.runOnIdle { state = state.copy(available = false) }
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseDurationSheet).assertDoesNotExist()
        composeRule.runOnIdle { state = HomePauseUiState(available = true) }
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseDurationSheet).assertDoesNotExist()
        assertTrue(durations.isEmpty())
    }

    @Test
    fun `paused state shows chosen mode and deliberate resume and stop`() {
        var resumes = 0
        var stops = 0
        render(paused().copy(mode = Mode.Proxy), onResume = { resumes++ }, onStop = { stops++ })
        composeRule.onNodeWithText(text(R.string.home_mode_proxy)).assertExists()
        composeRule.onNodeWithText(text(R.string.pause_saved_settings)).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseOpen).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseResume).assertHasClickAction().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseStop).assertHasClickAction().performClick()
        assertEquals(1, resumes)
        assertEquals(1, stops)
    }

    @Test
    fun `unknown remaining time is not shown as measured zero`() {
        render(paused().copy(remainingMillis = null))
        composeRule.onNodeWithText(text(R.string.pause_remaining, "0")).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseResume).assertExists()
    }

    @Test
    fun `cleanup pending never claims paused or permits resume`() {
        render(paused().copy(phase = PausePhase.CleanupPending))
        composeRule.onNodeWithText(text(R.string.pause_cleanup_pending)).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseResume).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseStop).assertHasClickAction()
        composeRule.onNodeWithText(text(R.string.pause_remaining, "5")).assertDoesNotExist()
    }

    @Test
    fun `releasing and resuming cannot dispatch a competing resume`() {
        var state by mutableStateOf(paused().copy(phase = PausePhase.Releasing))
        composeRule.setContent { RipDpiTheme { HomePauseControls(state, {}, {}, {}) } }
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseResume).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.pause_releasing)).assertExists()
        composeRule.runOnIdle { state = state.copy(phase = PausePhase.Resuming) }
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseResume).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.pause_resuming)).assertExists()
    }

    @Test
    fun `deferred permission failure offers manual recovery without countdown`() {
        render(paused().copy(phase = PausePhase.Deferred, failure = PauseFailure.ConsentRequired))
        composeRule.onNodeWithText(text(R.string.pause_deferred)).assertExists()
        composeRule.onNodeWithText(text(R.string.pause_consent_required)).assertExists()
        composeRule.onNodeWithText(text(R.string.pause_remaining, "5")).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseResume).assertHasClickAction()
    }

    @Test
    fun `initial persistence error does not claim a scheduled pause`() {
        render(HomePauseUiState(available = true, requestFailed = true))
        composeRule.onNodeWithText(text(R.string.pause_persistence_failed)).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseResume).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h900dp-mdpi")
    fun `large font LTR exposes full text and both actions`() = assertLargeFont(LayoutDirection.Ltr)

    @Test
    @Config(qualifiers = "ar-rEG-w411dp-h900dp-mdpi")
    fun `large font Arabic RTL exposes full text and both actions`() = assertLargeFont(LayoutDirection.Rtl)

    private fun assertLargeFont(direction: LayoutDirection) {
        var resumes = 0
        var stops = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides direction) {
                RipDpiTheme {
                    Box(Modifier.requiredSize(411.dp, 900.dp)) {
                        HomePauseControls(paused(), {}, { resumes++ }, { stops++ })
                    }
                }
            }
        }
        val panel = composeRule.onNodeWithTag(RipDpiTestTags.HomePauseControls).fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "panel must fit the real viewport: $panel in $viewport",
            panel.left >= viewport.left && panel.right <= viewport.right,
        )
        assertTrue(
            "panel must fit the real viewport: $panel in $viewport",
            panel.top >= viewport.top && panel.bottom <= viewport.bottom,
        )
        listOf(R.string.pause_saved_settings, R.string.pause_resume_now, R.string.pause_stop).forEach { key ->
            val layouts = mutableListOf<TextLayoutResult>()
            val node =
                composeRule
                    .onNodeWithText(text(key), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    .fetchSemanticsNode()
            val layout = layouts.single()
            assertFalse("${text(key)} layout overflow", layout.hasVisualOverflow)
            assertTrue(node.boundsInRoot.height + 1f >= layout.size.height)
            assertTrue(node.boundsInRoot.top >= panel.top && node.boundsInRoot.bottom <= panel.bottom)
            assertTrue(node.boundsInRoot.left >= panel.left && node.boundsInRoot.right <= panel.right)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseResume).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.HomePauseStop).assertIsDisplayed().performClick()
        assertEquals(1, resumes)
        assertEquals(1, stops)
    }

    private fun paused() =
        HomePauseUiState(
            phase = PausePhase.Paused,
            mode = Mode.VPN,
            deadlineWallMillis = 1_791_224_100_000L,
            remainingMillis = 300_000L,
        )

    private fun render(
        state: HomePauseUiState,
        onPause: (Long) -> Unit = {},
        onResume: () -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        composeRule.setContent { RipDpiTheme { HomePauseControls(state, onPause, onResume, onStop) } }
    }

    private fun text(
        key: Int,
        vararg args: Any,
    ) = RuntimeEnvironment.getApplication().getString(key, *args)
}
