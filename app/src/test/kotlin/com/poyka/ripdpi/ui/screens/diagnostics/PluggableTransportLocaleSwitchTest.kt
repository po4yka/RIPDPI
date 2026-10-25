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
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportState
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.toUiModel
import com.poyka.ripdpi.diagnostics.dpich.PluggableTransportResult
import com.poyka.ripdpi.diagnostics.dpich.PtVerdict
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
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
    fun `retained English PT rows follow Hindi then English without a new probe`() {
        val verdicts =
            listOf(
                PtVerdict.PtOk("Exact_Host.test:443"),
                PtVerdict.PtBridgeBlocked("Raw_Bridge_Reason"),
                PtVerdict.PtBrokerBlocked("Raw_Broker_Reason"),
                PtVerdict.PtFrontBlocked("Raw_Front_Reason"),
                PtVerdict.PtStunBlocked("Raw_STUN_Reason"),
                PtVerdict.PtError("Raw_Error_Reason"),
            )
        val rows = verdicts.map { mapped(it).rows.first() }.toPersistentList()
        val retainedTool = mapped(verdicts.first()).copy(rows = rows)
        val language = mutableStateOf("en")
        present(language, listOf(retainedTool))
        val english =
            listOf("Reachable", "Bridge blocked", "Broker blocked", "Front blocked", "STUN blocked", "Error")
        val hindi =
            listOf("पहुँच उपलब्ध", "ब्रिज अवरुद्ध", "ब्रोकर अवरुद्ध", "फ़्रंट होस्ट अवरुद्ध", "STUN अवरुद्ध", "त्रुटि")
        listOf("en" to english, "hi" to hindi, "en" to english).forEach { (tag, labels) ->
            composeRule.runOnIdle { language.value = tag }
            labels.forEach { label -> composeRule.onNodeWithText("obfs4: $label").assertIsDisplayed() }
            rows.forEach { row -> composeRule.onNodeWithText(row.detail).assertIsDisplayed() }
            val summary =
                if (tag == "hi") {
                    "obfs4, Snowflake, और meek एंडपॉइंट पहुँच जाँची गई."
                } else {
                    "Checked obfs4, Snowflake, and meek endpoint reachability."
                }
            composeRule.onNodeWithText(summary).assertIsDisplayed()
            if (tag == "hi") capture("hindi-pt-retained-verdicts.png")
        }
        assertTrue(retainedTool.summary.startsWith("Checked"))
        assertTrue(retainedTool.rows.first().verdict == "Reachable")
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
        val retainedTool = mapped(PtVerdict.PtError(" \t"))
        val language = mutableStateOf("en")
        present(language, listOf(retainedTool))
        listOf("en" to "no detail", "hi" to "कोई विवरण नहीं", "en" to "no detail").forEach { (tag, detail) ->
            composeRule.runOnIdle { language.value = tag }
            composeRule.onAllNodesWithText(detail).assertCountEquals(3)
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
                            verdict = "Exact_Custom_Verdict",
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

    private fun mapped(verdict: PtVerdict): DiagnosticsPluggableTransportToolUiModel =
        PluggableTransportResult(verdict, verdict, verdict, emptyList())
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
