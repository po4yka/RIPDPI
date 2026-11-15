package com.poyka.ripdpi.ui.screens.diagnostics

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpich.PluggableTransportResult
import com.poyka.ripdpi.diagnostics.dpich.PtProbeStatus
import com.poyka.ripdpi.diagnostics.dpich.PtProbeTrace
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsPluggableTransportRowUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsPluggableTransportState
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsPluggableTransportToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTone
import com.poyka.ripdpi.ui.screens.diagnostics.state.toUiModel
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w411dp-h1600dp-mdpi", application = Application::class)
class PluggableTransportLocaleSwitchTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `retained PT operations localize while HTTP status and target stay exact`() {
        val broker = PtProbeTrace("broker.test", PtProbeStatus.HTTP_RESPONSE, 1, 404)
        val trace = PtProbeTrace("", PtProbeStatus.NOT_CONFIGURED, 0)
        val english = context("en")
        val tool =
            PluggableTransportResult(trace, broker, trace, trace)
                .toUiModel(privacyModeEnabled = false, stringResolver = AndroidStringResolver(english))
        val language = mutableStateOf("en")
        present(language, listOf(tool))

        listOf("en", "hi", "en").forEach { tag ->
            composeRule.runOnIdle { language.value = tag }
            val current = context(tag)
            val brokerLabel = current.getString(R.string.diagnostics_pt_label_snowflake)
            val bridgeLabel = current.getString(R.string.diagnostics_pt_label_obfs4)
            val notConfigured = current.getString(R.string.diagnostics_pt_status_not_configured)
            composeRule.onNodeWithText("$brokerLabel: HTTP 404").assertIsDisplayed()
            composeRule.onNodeWithText("$bridgeLabel: $notConfigured").assertIsDisplayed()
            composeRule.onNodeWithText("broker.test").assertIsDisplayed()
        }
    }

    @Test
    fun `all retained known PT summaries use current resources`() {
        val states =
            listOf(
                DiagnosticsPluggableTransportState.Idle to R.string.diagnostics_pt_idle,
                DiagnosticsPluggableTransportState.Running to R.string.diagnostics_pt_running,
                DiagnosticsPluggableTransportState.Complete to R.string.diagnostics_pt_complete,
                DiagnosticsPluggableTransportState.Disabled to R.string.diagnostics_pt_privacy_disabled,
                DiagnosticsPluggableTransportState.Failed to R.string.diagnostics_pt_failed,
            )
        val englishContext = context("en")
        val tools =
            states.map { (state, resId) ->
                DiagnosticsPluggableTransportToolUiModel(
                    state = state,
                    summary = englishContext.getString(resId),
                    summaryResId = resId,
                    privacyModeEnabled = state == DiagnosticsPluggableTransportState.Disabled,
                )
            }
        val language = mutableStateOf("en")
        present(language, tools)
        listOf("en", "hi", "en").forEach { tag ->
            composeRule.runOnIdle { language.value = tag }
            val currentContext = context(tag)
            states.forEach { (_, resId) ->
                composeRule.onNodeWithText(currentContext.getString(resId)).assertIsDisplayed()
            }
            if (tag == "hi") {
                composeRule.onNodeWithText("Checking PT endpoint reachability...").assertDoesNotExist()
                capture("hindi-pt-retained-states.png")
            }
        }
    }

    @Test
    fun `blank PT details follow current locale rather than cached no detail text`() {
        val retainedTool = mapped(PtProbeTrace(" \t", PtProbeStatus.NETWORK_ERROR, 0))
        val language = mutableStateOf("en")
        present(language, listOf(retainedTool))
        listOf("en" to "no detail", "hi" to "कोई विवरण नहीं", "en" to "no detail").forEach { (tag, detail) ->
            composeRule.runOnIdle { language.value = tag }
            composeRule.onAllNodesWithText(detail).assertCountEquals(4)
            if (tag == "hi") {
                composeRule.onAllNodesWithText("no detail").assertCountEquals(0)
                capture("hindi-pt-retained-empty-detail.png")
            }
        }
    }

    @Test
    fun `legacy custom model strings and raw errors remain exact across locale changes`() {
        val retainedTool =
            DiagnosticsPluggableTransportToolUiModel(
                state = DiagnosticsPluggableTransportState.Complete,
                summary = "Exact_Custom_Summary",
                rows =
                    persistentListOf(
                        DiagnosticsPluggableTransportRowUiModel(
                            label = "Exact_Custom_Protocol",
                            status = "Exact_Custom_Verdict",
                            detail = "Exact_Custom_Detail",
                            tone = DiagnosticsTone.Warning,
                        ),
                    ),
            )
        val language = mutableStateOf("en")
        val currentTool = mutableStateOf(retainedTool)
        present(language, currentTool)
        listOf("en", "hi", "en").forEach { tag ->
            composeRule.runOnIdle { language.value = tag }
            composeRule.onNodeWithText("Exact_Custom_Summary").assertIsDisplayed()
            composeRule.onNodeWithText("Exact_Custom_Protocol: Exact_Custom_Verdict").assertIsDisplayed()
            composeRule.onNodeWithText("Exact_Custom_Detail").assertIsDisplayed()
        }
        composeRule.runOnIdle {
            language.value = "hi"
            currentTool.value =
                retainedTool.copy(errorMessage = "Exact_Raw_Error", summaryResId = R.string.diagnostics_pt_failed)
        }
        composeRule.onNodeWithText("Exact_Raw_Error").assertIsDisplayed()
        composeRule.onNodeWithText("Exact_Custom_Summary").assertDoesNotExist()
        composeRule.onNodeWithText("PT पहुँच जाँच विफल रही.").assertDoesNotExist()
        capture("hindi-pt-retained-custom-error.png")
    }

    private fun mapped(trace: PtProbeTrace): DiagnosticsPluggableTransportToolUiModel =
        PluggableTransportResult(trace, trace, trace, trace)
            .toUiModel(privacyModeEnabled = false, stringResolver = AndroidStringResolver(context("en")))

    private fun present(
        language: MutableState<String>,
        tools: List<DiagnosticsPluggableTransportToolUiModel>,
    ) {
        composeRule.setContent {
            val currentContext = remember(language.value) { context(language.value) }
            CompositionLocalProvider(
                LocalContext provides currentContext,
                LocalConfiguration provides currentContext.resources.configuration,
            ) {
                RipDpiTheme {
                    Column { tools.forEach { PluggableTransportProbeCard(tool = it, onRun = {}) } }
                }
            }
        }
    }

    private fun present(
        language: MutableState<String>,
        tool: MutableState<DiagnosticsPluggableTransportToolUiModel>,
    ) {
        composeRule.setContent {
            val currentContext = remember(language.value) { context(language.value) }
            CompositionLocalProvider(
                LocalContext provides currentContext,
                LocalConfiguration provides currentContext.resources.configuration,
            ) {
                RipDpiTheme { PluggableTransportProbeCard(tool = tool.value, onRun = {}) }
            }
        }
    }

    private fun context(tag: String): Context {
        val application: Application = RuntimeEnvironment.getApplication()
        return application.createConfigurationContext(
            Configuration(application.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
        )
    }

    private fun capture(name: String) {
        val output = File("build/compose-previews/renders/$name")
        output.parentFile?.mkdirs()
        output.outputStream().use { stream ->
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
