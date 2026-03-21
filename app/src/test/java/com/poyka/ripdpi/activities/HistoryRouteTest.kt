package com.poyka.ripdpi.activities

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.poyka.ripdpi.ui.screens.history.HistoryRoute
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.util.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HistoryRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `route initializes the view model once`() {
        val initializer = RecordingHistoryInitializer()
        val viewModel = createHistoryViewModel(initializer = initializer)
        val recomposeTrigger = mutableIntStateOf(0)

        composeRule.setContent {
            recomposeTrigger.intValue
            RipDpiTheme {
                HistoryRoute(
                    onBack = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { initializer.calls == 1 }

        composeRule.runOnUiThread {
            recomposeTrigger.intValue += 1
        }
        composeRule.waitForIdle()

        assertEquals(1, initializer.calls)
    }

    private fun createHistoryViewModel(
        initializer: HistoryInitializer = RecordingHistoryInitializer(),
    ): HistoryViewModel {
        val coreSupport = DiagnosticsUiCoreSupport()
        return HistoryViewModel(
            historyTimelineDataSource = FakeHistoryTimelineDataSource(),
            historyDetailLoader = FakeHistoryDetailLoader(),
            historyInitializer = initializer,
            historyUiStateFactory =
                HistoryUiStateFactory(
                    coreSupport = coreSupport,
                    connectionDetailUiFactory = HistoryConnectionDetailUiFactory(coreSupport),
                ),
        )
    }
}
