package com.poyka.ripdpi.ui.screens.home

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.activities.homeOutcome
import com.poyka.ripdpi.activities.projectHome
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.ui.localization.assertArabicTextLayout
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fa-rIR-w420dp-h1100dp-mdpi")
class HomePersianPresentationRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `generated Home result is readable in Persian`() = render("normal", 1f)

    @Test
    @Config(qualifiers = "fa-rIR-w360dp-h1100dp-mdpi")
    fun `generated Home result is readable at narrow width and large font`() = render("narrow-large", 1.5f)

    private fun render(
        name: String,
        scale: Float,
    ) {
        RuntimeEnvironment.setFontScale(scale)
        val raw =
            homeOutcome().copy(
                headline = "Analysis complete and settings applied",
                summary =
                    "Completed 10 of 12 diagnostics stages. 2 stages finished with failures or were unavailable. " +
                        "DNS issues were detected during the audit. " +
                        "Network changed during analysis — results may not reflect current network.",
                confidenceSummary = "Confidence high (87)",
                coverageSummary = "Matrix 76% · winners 100%",
                recommendationSummary =
                    "Detected TCP RST injection on 2 TCP probe(s) with 1 blocked service(s). " +
                        "Recommend switching to fake packets with adaptive TTL.",
                appliedSettings = listOf(DiagnosticsAppliedSetting("TCP/TLS lane", "TLS record split")),
                stageSummaries =
                    listOf(
                        DiagnosticsHomeCompositeStageSummary(
                            stageKey = "automatic_audit",
                            stageLabel = "Automatic audit",
                            profileId = "automatic-audit",
                            pathMode = ScanPathMode.RAW_PATH,
                            status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                            headline = "unused",
                            summary = "Scan completed with partial results · Scan time limit reached",
                        ),
                    ),
            )
        val state = projectHome(raw, "fa")
        val sheet = requireNotNull(state.analysisSheet)
        assertFalse(sheet.summary.contains("DNS issues"))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, scale),
            ) {
                RipDpiTheme(themePreference = "light") {
                    HomeDiagnosticsBottomSheetHost(
                        homeDiagnostics = state,
                        onOpenDiagnostics = {},
                        onOpenHistory = {},
                        onOpenAdvancedSettings = {},
                        onOpenModeEditor = {},
                        onOpenOwnedStackBrowser = {},
                        onShareAnalysis = {},
                        onDismissAnalysisSheet = {},
                        onDismissVerificationSheet = {},
                    )
                }
            }
        }
        readable(sheet.headline, scale)
        readable(sheet.summary, scale)
        capture("$name-outcome")
        readable(requireNotNull(sheet.confidenceSummary), scale)
        readable(requireNotNull(sheet.coverageSummary), scale)
        capture("$name-confidence")
        readable(requireNotNull(sheet.recommendationSummary), scale)
        capture("$name-recommendation")
        sheet.appliedSettings.forEach {
            readable(it.label, scale)
            readable(it.value, scale)
        }
        sheet.stageSummaries.forEach {
            readable(it.label, scale)
            readable(it.summary, scale)
        }
        capture("$name-stages")
        assertEquals("Confidence high (87)", raw.confidenceSummary)
    }

    private fun readable(
        text: String,
        scale: Float,
    ) {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true)
        node.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing layout for $text", layouts.isNotEmpty())
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport =
            composeRule
                .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsAnalysisSheet, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        layouts.forEach { layout ->
            assertEquals("Actual Persian modal font scale", scale, layout.layoutInput.density.fontScale, 0.001f)
            assertArabicTextLayout(layout, bounds, viewport, "Persian Home: $text")
        }
    }

    private fun capture(name: String) {
        val target = File("build/reports/localization-fa/home-$name.png").apply { requireNotNull(parentFile).mkdirs() }
        target.outputStream().use {
            assertTrue(
                composeRule
                    .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsAnalysisSheet, useUnmergedTree = true)
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it),
            )
        }
    }
}
