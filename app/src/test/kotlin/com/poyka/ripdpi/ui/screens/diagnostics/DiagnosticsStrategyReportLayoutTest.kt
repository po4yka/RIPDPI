package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeFamilyUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningPathUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidence
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidenceLevel
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditCoverage
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU-w420dp-h900dp")
class DiagnosticsStrategyReportLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private val width = mutableStateOf(411)
    private val scale = mutableStateOf(2f)
    private val theme = mutableStateOf("dark")
    private val selected = mutableListOf<String>()
    private val candidates =
        listOf(
            candidate("tlsrec-split-host", "TLS record split", recommended = true),
            candidate("split-host", "Host split", recommended = true, skipped = true),
            candidate("quic-sni-split", "QUIC SNI split", skipped = true),
            candidate("quic-disabled", "QUIC disabled"),
        )

    @Test
    fun reportHeadersRetainWordsAndCandidateActions() {
        showReport()
        listOf("light", "dark").forEach { preference ->
            listOf(411 to 2f, 320 to 2f, 420 to 1f).forEach { (viewport, fontScale) ->
                composeRule.runOnIdle {
                    width.value = viewport
                    scale.value = fontScale
                    theme.value = preference
                }
                composeRule.waitForIdle()
                assertWordsFit("Достоверность аудита", RipDpiTestTags.DiagnosticsStrategyAuditAssessment)
                assertWordsFit("Средняя", RipDpiTestTags.DiagnosticsStrategyAuditAssessment)
                listOf(
                    RipDpiTestTags.DiagnosticsStrategyWinningTcpAction to "Победитель TCP",
                    RipDpiTestTags.DiagnosticsStrategyWinningQuicAction to "Победитель QUIC",
                ).forEach { (tag, title) ->
                    assertWordsFit(title, tag)
                    assertWordsFit("Соединение установлено", tag)
                    composeRule.onNodeWithTag(tag).performScrollTo().performClick()
                }
                candidates
                    .zip(listOf("Рекомендовано", "Резерв", "Пропущено", "Соединение установлено"))
                    .forEach { (candidate, status) ->
                        val tag = RipDpiTestTags.diagnosticsStrategyCandidate(candidate.id)
                        assertWordsFit(candidate.label, tag)
                        assertWordsFit(status, tag)
                        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
                    }
            }
        }
        assertEquals(
            List(6) { listOf(candidates[0].id, candidates[3].id) + candidates.map { it.id } }.flatten(),
            selected,
        )
    }

    @Test
    fun shortStatusRemainsInlineAtNormalFont() {
        width.value = 420
        scale.value = 1f
        showReport(shortOutcome = true)
        val tag = RipDpiTestTags.diagnosticsStrategyCandidate(candidates[3].id)
        val title = textNode(candidates[3].label, tag).performScrollTo().fetchSemanticsNode().boundsInRoot
        val status = textNode("OK", tag).fetchSemanticsNode().boundsInRoot
        assertEquals(title.center.y, status.center.y, 1f)
        assertTrue("Short status stays beside the full title", status.left > title.right)
    }

    private fun showReport(shortOutcome: Boolean = false) {
        val report = report(shortOutcome)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                RipDpiTheme(themePreference = theme.value) {
                    Column(
                        Modifier
                            .requiredWidth(width.value.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                    ) {
                        StrategyProbeReportCard(report = report, onSelectCandidate = { selected += it.id })
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag(RipDpiTestTags.DiagnosticsStrategyFullMatrixToggle)
            .performScrollTo()
            .performClick()
    }

    private fun textNode(
        text: String,
        tag: String,
    ) = composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    private fun assertWordsFit(
        text: String,
        tag: String,
    ) {
        val results = mutableListOf<TextLayoutResult>()
        textNode(text, tag)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val input = layout.layoutInput
        val wordWidth =
            TextMeasurer(
                input.fontFamilyResolver,
                LocaleList(
                    android.os.LocaleList
                        .getDefault()
                        .toLanguageTags(),
                ),
                input.density,
                input.layoutDirection,
            ).measure(AnnotatedString(text), style = input.style.copy(hyphens = Hyphens.None))
                .multiParagraph.minIntrinsicWidth
        assertTrue(
            "Whole word needs $wordWidth px but gets ${layout.size.width}: $text",
            layout.size.width + 1f >= wordWidth,
        )
        assertFalse("Text height is complete: $text", layout.didOverflowHeight)
        repeat(layout.lineCount) { line ->
            assertFalse("Line is complete: $text", layout.isLineEllipsized(line))
            assertTrue("Line starts inside its bounds: $text", layout.getLineLeft(line) >= -1f)
            assertTrue("Line ends inside its bounds: $text", layout.getLineRight(line) <= layout.size.width + 1f)
        }
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
    }

    private fun candidate(
        id: String,
        label: String,
        recommended: Boolean = false,
        skipped: Boolean = false,
    ) = DiagnosticsStrategyProbeCandidateUiModel(
        id = id,
        label = label,
        outcome = "Соединение установлено",
        rationale = "Проверка кандидата завершена",
        metrics = persistentListOf(),
        tone = DiagnosticsTone.Positive,
        skipped = skipped,
        recommended = recommended,
    )

    private fun detail(candidate: DiagnosticsStrategyProbeCandidateUiModel) =
        DiagnosticsStrategyProbeCandidateDetailUiModel(
            id = candidate.id,
            label = candidate.label,
            familyLabel = "TCP / QUIC",
            suiteLabel = "Automatic audit",
            outcome = candidate.outcome,
            rationale = candidate.rationale,
            tone = candidate.tone,
            recommended = candidate.recommended,
            notes = persistentListOf(),
            metrics = persistentListOf(),
            signature = persistentListOf(),
            resultGroups = persistentListOf(),
        )

    private fun winner(candidate: DiagnosticsStrategyProbeCandidateUiModel) =
        DiagnosticsStrategyProbeWinningCandidateUiModel(
            id = candidate.id,
            label = candidate.label,
            familyLabel = "TCP / QUIC",
            outcome = candidate.outcome,
            rationale = candidate.rationale,
            metrics = candidate.metrics,
            tone = candidate.tone,
            hiddenCandidateCount = 0,
        )

    private fun report(shortOutcome: Boolean): DiagnosticsStrategyProbeReportUiModel {
        val rows = candidates.map { if (shortOutcome) it.copy(outcome = "OK") else it }
        return DiagnosticsStrategyProbeReportUiModel(
            suiteId = "full_matrix_v1",
            suiteLabel = "Automatic audit",
            summaryMetrics = persistentListOf(),
            completionKind = StrategyProbeCompletionKind.NORMAL,
            auditAssessment =
                StrategyProbeAuditAssessment(
                    coverage = StrategyProbeAuditCoverage(4, 4, 0, 0, 2, 2, 0, 0, 3, 3, 1, 1, 100, 100),
                    confidence =
                        StrategyProbeAuditConfidence(StrategyProbeAuditConfidenceLevel.MEDIUM, 70, "Аудит завершён"),
                ),
            winningPath = DiagnosticsStrategyProbeWinningPathUiModel(winner(rows[0]), winner(rows[3])),
            families =
                persistentListOf(DiagnosticsStrategyProbeFamilyUiModel("Полная матрица", rows.toPersistentList())),
            candidateDetails = rows.associate { it.id to detail(it) }.toPersistentMap(),
        )
    }
}
