package com.poyka.ripdpi.ui.screens.diagnostics

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsUiFactorySupport
import com.poyka.ripdpi.ui.diagnostics.toApproachDetailUiModel
import com.poyka.ripdpi.ui.diagnostics.toStrategyProbeReportUiModel
import com.poyka.ripdpi.ui.localization.assertArabicTextLayout
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.After
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], qualifiers = "fa-rIR-w420dp-h900dp-mdpi", application = Application::class)
class DiagnosticsPersianStrategyRenderTest {
    @get:Rule val composeRule = createComposeRule()
    private var expectedScale = 1f

    @After
    fun resetFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun normalStrategyReport() = renderReport("normal", 1f)

    @Test
    @Config(qualifiers = "fa-rIR-w360dp-h900dp-mdpi")
    fun narrowStrategyReportAtLargeFont() = renderReport("narrow-large-font", 1.5f)

    @Test
    fun normalApproachAndCandidateSheets() = renderSheets("normal", 1f)

    @Test
    @Config(qualifiers = "fa-rIR-w360dp-h900dp-mdpi")
    fun narrowApproachAndCandidateSheetsAtLargeFont() = renderSheets("narrow-large-font", 1.5f)

    private fun renderReport(
        name: String,
        fontScale: Float,
    ) {
        configureFont(fontScale)
        val factory = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())
        val report = factory.toStrategyProbeReportUiModel(persianRenderStrategyReport(), emptyList(), "VPN")
        val selectedIds = mutableListOf<String>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                RipDpiTheme(themePreference = "light") {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .ripDpiTestTag(ViewportTag)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                    ) {
                        StrategyProbeReportCard(report, onSelectCandidate = { selectedIds += it.id })
                    }
                }
            }
        }
        assertText(requireNotNull(report.presentation).matrixTitle, ViewportTag)
        assertText(requireNotNull(report.recommendation).headline, ViewportTag)
        capture("report-$name-header", ViewportTag)
        assertText(report.suiteLabel, ViewportTag)
        requireNotNull(report.recommendation).fields.forEach { field -> assertText(field.label, ViewportTag) }
        assertText(factory.context.getString(R.string.dns_mode_plain), ViewportTag)
        capture("report-$name-recommendation", ViewportTag)
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyFullMatrixToggle).performScrollTo().performClick()
        report.families.forEach { family -> assertText(family.title, ViewportTag) }
        val candidateTag = RipDpiTestTags.diagnosticsStrategyCandidate("tcp-0012")
        assertText("User TCP 0012", candidateTag, ViewportTag)
        composeRule.onNodeWithTag(candidateTag).performScrollTo().performClick()
        assertEquals(listOf("tcp-0012"), selectedIds)
        capture("report-$name-matrix", ViewportTag)
    }

    private fun renderSheets(
        name: String,
        fontScale: Float,
    ) {
        configureFont(fontScale)
        val factory = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())
        val approach = factory.toApproachDetailUiModel(persianRenderApproach())
        val report = factory.toStrategyProbeReportUiModel(persianRenderStrategyReport(), emptyList(), "VPN")
        val candidate = requireNotNull(report.candidateDetails["tcp-0012"])
        val showApproach = mutableStateOf(true)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                RipDpiTheme(themePreference = "light") {
                    DiagnosticsBottomSheetHost(
                        selectedSessionDetail = null,
                        selectedApproachDetail = approach.takeIf { showApproach.value },
                        selectedEvent = null,
                        probePresentation = DiagnosticsProbeSheetPresentation(selectedProbe = null),
                        selectedStrategyProbeCandidate = candidate.takeUnless { showApproach.value },
                        onDismissSessionDetail = {},
                        onToggleSensitiveSessionDetails = {},
                        onSelectStrategyProbeCandidate = {},
                        onDismissStrategyProbeCandidate = {},
                        onSelectEvent = {},
                        onDismissEventDetail = {},
                        onSelectProbe = {},
                        onDismissProbeDetail = {},
                        onDismissApproachDetail = {},
                    )
                }
            }
        }
        val approachTag = RipDpiTestTags.DiagnosticsApproachDetailSheet
        assertText("User strategy 0012", approachTag)
        assertMetrics(approach.runtimeSummary, approachTag, "approach-$name")
        assertEquals(listOf("2", "11", "13"), approach.runtimeSummary.filterIndexed { i, _ -> i != 1 }.map { it.value })
        composeRule.runOnIdle { showApproach.value = false }
        val candidateTag = RipDpiTestTags.DiagnosticsStrategyCandidateDetailSheet
        assertText(candidate.label, candidateTag)
        // The sheet formats this subtitle with stringResource, not with the factory resolver.
        val subtitle =
            RuntimeEnvironment.getApplication().getString(
                R.string.diagnostics_candidate_subtitle_format,
                candidate.familyLabel,
                candidate.suiteLabel,
            )
        assertText(subtitle, candidateTag)
        assertText(candidate.rationale, candidateTag)
        assertMetrics(candidate.metrics, candidateTag, "candidate-$name")
        candidate.notes.forEach { assertText(it, candidateTag) }
        capture("candidate-$name-notes", candidateTag)
        assertEquals("tcp-0012", candidate.id)
        assertEquals("User note 0012", candidate.notes.first())
        assertEquals(listOf("1/2", "10/20", "7", "۱۷ ms"), candidate.metrics.take(4).map { it.value })
    }

    private fun configureFont(fontScale: Float) {
        expectedScale = fontScale
        // Modal windows read Android configuration outside the caller's CompositionLocal.
        RuntimeEnvironment.setFontScale(fontScale)
    }

    private fun assertMetrics(
        metrics: List<DiagnosticsMetricUiModel>,
        tag: String,
        imageName: String,
    ) {
        val row =
            composeRule.onNode(
                hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(tag)),
                useUnmergedTree = true,
            )
        metrics.forEachIndexed { index, metric ->
            row.performScrollToIndex(index)
            assertText(metric.label.uppercase(), tag)
            assertText(metric.value, tag)
            capture("$imageName-metric-$index", tag)
        }
    }

    private fun assertText(
        text: String,
        scopeTag: String,
        viewportTag: String = scopeTag,
    ) {
        val node =
            composeRule.onAllNodes(
                hasText(text) and hasAnyAncestor(hasTestTag(scopeTag)),
                useUnmergedTree = true,
            )[0]
        // performScrollTo targets only the nearest scroll container. Bring nested LazyRows
        // into the vertical viewport before scrolling their individual text horizontally.
        val scrollAncestors =
            generateSequence(node.fetchSemanticsNode().parent) { it.parent }
                .filter { SemanticsActions.ScrollBy in it.config }
                .toList()
        scrollAncestors.dropLast(1).asReversed().forEach { ancestor ->
            composeRule
                .onNode(
                    SemanticsMatcher("scroll ancestor ${ancestor.id}") { it.id == ancestor.id },
                    useUnmergedTree = true,
                ).performScrollTo()
        }
        node.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout: $text", layouts.isNotEmpty())
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag(viewportTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        layouts.forEach { layout ->
            assertEquals("Actual text font scale: $text", expectedScale, layout.layoutInput.density.fontScale)
            assertEquals("Actual RTL text layout: $text", LayoutDirection.Rtl, layout.layoutInput.layoutDirection)
            assertArabicTextLayout(
                finiteSemanticLayout(layout),
                bounds,
                viewport,
                "Persian Strategy/Approach text: $text",
            )
        }
    }

    private fun finiteSemanticLayout(layout: TextLayoutResult): TextLayoutResult {
        val input = layout.layoutInput
        if (input.constraints.hasBoundedWidth) return layout
        // ParagraphLayoutCache recreates its semantics paragraph at LazyRow's infinite max width,
        // but draws at layout.size.width. At Int.MAX_VALUE, Float line positions round to 128 px.
        // Recreate the same paragraph at its actual drawing width to retain precise glyph bounds.
        val finite =
            TextMeasurer(
                input.fontFamilyResolver,
                LocaleList(
                    android.os.LocaleList
                        .getDefault()
                        .toLanguageTags(),
                ),
                input.density,
                input.layoutDirection,
            ).measure(
                text = input.text,
                style = input.style,
                overflow = input.overflow,
                softWrap = input.softWrap,
                maxLines = input.maxLines,
                placeholders = input.placeholders,
                constraints = input.constraints.copy(minWidth = 0, maxWidth = layout.size.width),
            )
        assertEquals("Finite semantics preserves line count", layout.lineCount, finite.lineCount)
        assertEquals("Finite semantics preserves drawn height", layout.size.height, finite.size.height)
        return finite
    }

    private fun capture(
        name: String,
        tag: String,
    ) {
        val output =
            File(
                "build/reports/localization-fa/strategy-$name.png",
            ).apply { requireNotNull(parentFile).mkdirs() }
        output.outputStream().use {
            composeRule
                .onNodeWithTag(tag, useUnmergedTree = true)
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

private const val ViewportTag = "fa-strategy-report-viewport"
