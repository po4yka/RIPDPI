package com.poyka.ripdpi.ui.screens.diagnostics

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.DiagnosticsPerformanceUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w420dp-h1100dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsFrenchDebugLocaleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `expanded debug timings and prose follow English French English with retained performance`() {
        val context = mutableStateOf(languageContext("en"))
        val performance =
            DiagnosticsPerformanceUiModel(
                buildSequence = 7,
                totalDurationMillis = 12.5,
                eventMappingDurationMillis = 0.1,
                resolveDurationMillis = 8.5,
                overviewDurationMillis = 1.5,
                scanDurationMillis = 1.0,
                liveDurationMillis = 0.5,
                sessionsDurationMillis = 0.4,
                approachesDurationMillis = 0.3,
                eventsDurationMillis = 0.2,
                shareDurationMillis = 0.1,
                telemetryCount = 2,
                nativeEventCount = 3,
                sessionCount = 4,
            )
        composeRule.setContent {
            val current = context.value
            CompositionLocalProvider(
                LocalContext provides current,
                LocalConfiguration provides current.resources.configuration,
            ) {
                RipDpiTheme { DiagnosticsPerformanceCard(performance, DiagnosticsSection.Tools) }
            }
        }
        composeRule.onNodeWithText("Debug #7 · tools · 12.5 ms").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Slowest stage: resolve 8.5 ms", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle { context.value = languageContext("fr") }
        composeRule.onNodeWithText("Débogage #7 · tools · 12,5 ms", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Étape la plus lente : resolve 8,5 ms", useUnmergedTree = true).assertIsDisplayed()
        composeRule
            .onNodeWithText("Entrée : télémétrie 2 · événements 3 · sessions 4", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "resolve 8,5 ms  overview 1,5 ms  scan 1,0 ms  live 0,5 ms",
                useUnmergedTree = true,
            ).assertIsDisplayed()
        composeRule.runOnIdle { context.value = languageContext("en") }
        composeRule.onNodeWithText("Debug #7 · tools · 12.5 ms", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Slowest stage: resolve 8.5 ms", useUnmergedTree = true).assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "resolve 8.5 ms  overview 1.5 ms  scan 1.0 ms  live 0.5 ms",
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    private fun languageContext(language: String): Context {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return application.createConfigurationContext(configuration)
    }
}
