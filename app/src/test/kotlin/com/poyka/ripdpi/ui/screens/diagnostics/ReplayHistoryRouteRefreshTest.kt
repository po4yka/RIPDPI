package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeRequest
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeResult
import com.poyka.ripdpi.diagnostics.replay.ReplayResultStore
import com.poyka.ripdpi.diagnostics.replay.ReplayVerdict
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Route-level regression tests for the session-keyed history refresh:
 * reusing one [ReplayHistoryRoute] composition instance for a new
 * session id must re-read the [ReplayResultStore] snapshot instead of
 * keeping stale data (UIX-1786264762917972).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReplayHistoryRouteRefreshTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `changing session id triggers snapshot refresh`() {
        val store = ReplayResultStore()
        val viewModel = ReplayHistoryViewModel(store)
        var sessionId by mutableStateOf("session-1")

        composeRule.setContent {
            ReplayHistoryRoute(
                sessionId = sessionId,
                onRunScan = {},
                onBack = {},
                viewModel = viewModel,
            )
        }
        composeRule.waitForIdle()
        assertTrue(viewModel.uiState.value.isEmpty())

        store.record(
            ReplayProbeResult(
                request = ReplayProbeRequest("example.org", "default", 1_000L),
                events = persistentListOf(),
                verdict = ReplayVerdict.Success,
                terminalStep = null,
                recommendationKey = "",
            ),
        )

        composeRule.runOnUiThread { sessionId = "session-2" }
        composeRule.waitForIdle()

        assertEquals(1, viewModel.uiState.value.size)
    }
}
