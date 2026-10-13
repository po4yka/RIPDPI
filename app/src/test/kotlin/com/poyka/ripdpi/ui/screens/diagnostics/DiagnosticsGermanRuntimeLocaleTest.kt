package com.poyka.ripdpi.ui.screens.diagnostics

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.activities.DiagnosticsFieldUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsSparklineUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsGermanRuntimeLocaleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `retained Telegram card follows app locale and preserves custom errors`() {
        val context = mutableStateOf(languageContext("en"))
        val probe = probe("slow")
        composeRule.setContent {
            val current = context.value
            CompositionLocalProvider(
                LocalContext provides current,
                LocalConfiguration provides current.resources.configuration,
            ) {
                RipDpiTheme { TelegramResultCard(probe, onClick = {}) }
            }
        }
        composeRule.onNodeWithText("Slow").assertExists()
        composeRule.onAllNodesWithText("1.5 Mbps")[0].assertExists()
        composeRule.runOnIdle { context.value = languageContext("de") }
        composeRule.onNodeWithText("Langsam").assertExists()
        composeRule.onAllNodesWithText("1,5 Mbps")[0].assertExists()
        composeRule.onNodeWithText("Raw_ERROR: Host.test").assertExists()
        composeRule.runOnIdle { context.value = languageContext("en") }
        composeRule.onNodeWithText("Slow").assertExists()
        composeRule.onAllNodesWithText("1.5 Mbps")[0].assertExists()
        assertEquals("187500", probe.details.first { it.label == "downloadAvgBps" }.value)
    }

    @Test
    fun `custom Telegram verdict remains exact in German`() {
        val context = languageContext("de")
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
            ) {
                RipDpiTheme { TelegramResultCard(probe("Raw_Custom_Verdict"), onClick = {}) }
            }
        }
        composeRule.onNodeWithText("Raw_Custom_Verdict").assertExists()
    }

    @Test
    fun `retained sparkline visible numbers and accessibility follow selected locale`() {
        val context = mutableStateOf(languageContext("en"))
        composeRule.setContent {
            val current = context.value
            CompositionLocalProvider(
                LocalContext provides current,
                LocalConfiguration provides current.resources.configuration,
            ) {
                RipDpiTheme {
                    Column { TelemetrySparkline(DiagnosticsSparklineUiModel("TX", persistentListOf(1000f, 1500f))) }
                }
            }
        }
        composeRule.onAllNodesWithText("1.5 KB")[0].assertExists()
        composeRule.runOnIdle { context.value = languageContext("de") }
        composeRule.onAllNodesWithText("1,5 KB")[0].assertExists()
        val states =
            composeRule
                .onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher
                        .keyIsDefined(SemanticsProperties.StateDescription),
                ).fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.StateDescription] }
        assertTrue(states.any { it.contains("1,5 KB") })
        composeRule.runOnIdle { context.value = languageContext("en") }
        composeRule.onAllNodesWithText("1.5 KB")[0].assertExists()
    }

    private fun probe(verdict: String) =
        DiagnosticsProbeResultUiModel(
            id = "telegram",
            probeType = "telegram_availability",
            target = "Raw_Target",
            outcome = verdict,
            tone = DiagnosticsTone.Warning,
            details =
                persistentListOf(
                    DiagnosticsFieldUiModel("verdict", verdict),
                    DiagnosticsFieldUiModel("downloadStatus", "ok"),
                    DiagnosticsFieldUiModel("downloadAvgBps", "187500"),
                    DiagnosticsFieldUiModel("downloadPeakBps", "187500"),
                    DiagnosticsFieldUiModel("downloadBytes", "1500000"),
                    DiagnosticsFieldUiModel("downloadError", "Raw_ERROR: Host.test"),
                ),
        )

    private fun languageContext(language: String): Context {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return application.createConfigurationContext(configuration)
    }
}
