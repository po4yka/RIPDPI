package com.poyka.ripdpi.activities

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRoute
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.util.MainDispatcherRule
import org.junit.Assert.assertEquals
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
class DiagnosticsRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `route initializes the view model once`() {
        val diagnosticsBootstrapper = StubDiagnosticsBootstrapper()
        val viewModel =
            createDiagnosticsViewModel(
                appContext = RuntimeEnvironment.getApplication(),
                diagnosticsBootstrapper = diagnosticsBootstrapper,
                diagnosticsTimelineSource = StubDiagnosticsTimelineSource(),
                appSettingsRepository = FakeAppSettingsRepository(),
                initialize = false,
            )
        val recomposeTrigger = mutableIntStateOf(0)

        composeRule.setContent {
            recomposeTrigger.intValue
            RipDpiTheme {
                DiagnosticsRoute(
                    onShareArchive = { _, _ -> },
                    onSaveArchive = { _, _ -> },
                    onShareSummary = { _, _ -> },
                    onSaveLogs = {},
                    onOpenHistory = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { diagnosticsBootstrapper.initializeCalls == 1 }

        composeRule.runOnUiThread {
            recomposeTrigger.intValue += 1
        }
        composeRule.waitForIdle()

        assertEquals(1, diagnosticsBootstrapper.initializeCalls)
    }
}
