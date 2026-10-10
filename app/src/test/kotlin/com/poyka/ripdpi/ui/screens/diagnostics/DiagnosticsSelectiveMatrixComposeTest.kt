package com.poyka.ripdpi.ui.screens.diagnostics

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsFieldUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.SelectiveMatrixTargetUiModel
import com.poyka.ripdpi.activities.parseSelectiveMatrixHosts
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h839dp-mdpi")
class DiagnosticsSelectiveMatrixComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun optionalTargetsValidateEditsAndBecomeReadOnlyDuringScan() {
        val input = mutableStateOf("")
        val enabled = mutableStateOf(true)
        composeRule.setContent {
            MatrixViewport {
                SelectiveMatrixInputCard(
                    input = input.value,
                    targets = listOf(previewTarget()),
                    valid = parseSelectiveMatrixHosts(input.value) != null,
                    enabled = enabled.value,
                    onInputChanged = { input.value = it },
                )
            }
        }
        val field = composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsSelectiveMatrixHosts)
        field.performScrollTo().performTextReplacement("example.org example.net")
        composeRule.runOnIdle {
            assertEquals(listOf("example.org", "example.net"), parseSelectiveMatrixHosts(input.value))
        }
        field.performTextReplacement("127.0.0.1")
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_matrix_hosts_error)))
        capture("selective-matrix-input-error")
        composeRule.runOnIdle { enabled.value = false }
        field.assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.diagnostics_matrix_not_verified)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.diagnostics_matrix_catalog_show, 1)).performScrollTo().performClick()
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_matrix_not_verified)))
    }

    @Test
    fun resultShowsRepeatCoverageAndUnmeasuredStagesWithoutClaimingPolicy() {
        showResults()
        assertResults("selective-matrix-results")
    }

    @Test
    @Config(qualifiers = "ar-rEG-w411dp-h839dp-mdpi")
    fun resultsKeepCompleteLabelsReachableWithLargeRtlText() {
        showResults(fontScale = 1.5f, direction = LayoutDirection.Rtl)
        assertResults("selective-matrix-results-rtl-large")
    }

    private fun showResults(
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            MatrixViewport(fontScale, direction) {
                SelectiveMatrixResultsCard(listOf(attempt(1, true), attempt(2, false), summary()))
            }
        }
    }

    private fun assertResults(name: String) {
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_matrix_inconclusive)))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_matrix_scope_unverified)))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_matrix_attempt_count, "2", "2")))
        capture("$name-summary")
        composeRule.onAllNodesWithText("example.org").assertCountEquals(1)
        composeRule.onAllNodesWithText(text(R.string.diagnostics_matrix_not_verified)).assertCountEquals(0)
        composeRule.onNodeWithText(text(R.string.diagnostics_matrix_attempts_show, 2)).performScrollTo().performClick()
        composeRule.onAllNodesWithText("example.org").assertCountEquals(3)
        composeRule.onAllNodesWithText(text(R.string.diagnostics_matrix_not_verified)).assertCountEquals(2)
        assertComplete(composeRule.onNodeWithText("TLS: ${text(R.string.diagnostics_matrix_stage_failed)}"))
        assertComplete(composeRule.onNodeWithText("HTTP: ${text(R.string.diagnostics_matrix_stage_not_run)}"))
        assertComplete(composeRule.onNodeWithText(text(R.string.diagnostics_matrix_body_partial, "0")))
        capture("$name-failed-attempt")
        composeRule.onNodeWithText(text(R.string.diagnostics_matrix_selective)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.diagnostics_matrix_verified, "unknown")).assertDoesNotExist()
    }

    @Composable
    private fun MatrixViewport(
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(1f, fontScale),
            LocalLayoutDirection provides direction,
        ) {
            RipDpiTheme(themePreference = "light") {
                Box(
                    Modifier
                        .requiredSize(360.dp, 760.dp)
                        .background(RipDpiThemeTokens.colors.background)
                        .ripDpiTestTag("selective-matrix-viewport"),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                }
            }
        }
    }

    private fun assertComplete(node: SemanticsNodeInteraction) {
        node.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout", layouts.isNotEmpty())
        layouts.forEach { assertFalse("Clipped text: ${it.layoutInput.text}", it.hasVisualOverflow) }
    }

    private fun capture(name: String) {
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onNodeWithTag("selective-matrix-viewport")
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun previewTarget() =
        SelectiveMatrixTargetUiModel(
            label = "Example",
            url = "https://example.org/",
            cohort = "global",
            infrastructure = "example",
            source = "https://example.org/catalog",
            sourceDate = "2026-10-09",
            verifiedAt = null,
        )

    private fun attempt(
        number: Int,
        success: Boolean,
    ) = result(
        type = "selective_availability",
        outcome = if (success) "matrix_target_available" else "matrix_target_transport_failed",
        "cohort" to "user",
        "attempt" to number.toString(),
        "dnsStatus" to "admitted_addresses",
        "tcpStatus" to "ok",
        "tlsStatus" to if (success) "ok" else "failed",
        "httpStatus" to if (success) "ok" else "not_run",
        "bodyStatus" to if (success) "ok" else "not_run",
        "bodyComplete" to success.toString(),
        "bodyByteCount" to if (success) "1024" else "0",
        "lastVerifiedAt" to "unknown",
        "failureStage" to if (success) "none" else "tls",
        "elapsedMs" to "5000",
        "infrastructureGroup" to "user-example",
    )

    private fun summary() =
        result(
            type = "selective_availability_summary",
            outcome = "matrix_inconclusive",
            "reason" to "network_scope_unverified",
            "completedAttempts" to "2",
            "expectedAttempts" to "2",
        )

    private fun result(
        type: String,
        outcome: String,
        vararg details: Pair<String, String>,
    ) = DiagnosticsProbeResultUiModel(
        id = "$type-${details.toList()}",
        probeType = type,
        target = if (type == "selective_availability") "example.org" else "matrix",
        outcome = outcome,
        tone = DiagnosticsTone.Neutral,
        details = details.map { DiagnosticsFieldUiModel(it.first, it.second) }.toImmutableList(),
    )
}
