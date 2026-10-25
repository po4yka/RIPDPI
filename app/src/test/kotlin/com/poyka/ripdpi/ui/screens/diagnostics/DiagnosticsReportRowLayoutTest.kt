package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
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
@Config(sdk = [35], qualifiers = "ru-rRU-w420dp-h2000dp")
class DiagnosticsReportRowLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private val width = mutableStateOf(411)
    private val scale = mutableStateOf(2f)
    private val theme = mutableStateOf("light")

    @Test
    fun sessionHeaderRetainsWordsCompletionAndClickAction() {
        val title = "Automation diagnostics report ready"
        val subtitle = "Отчёт диагностики готов"
        val completion = "Сканирование завершено"
        val session =
            DiagnosticsSessionRowUiModel(
                "session",
                "profile",
                title,
                subtitle,
                "raw",
                "proxy",
                "completed",
                "12:34",
                "",
                persistentListOf(),
                DiagnosticsTone.Positive,
                completionLabel = completion,
            )
        var clicks = 0
        composeRule.setContent { Container { SessionRow(session, { clicks++ }) } }
        configurations {
            assertWordsFit(title)
            assertWordsFit(subtitle)
            assertWordsFit(completion)
            composeRule.onNodeWithText(title).assertHasClickAction().performClick()
        }
        assertEquals(6, clicks)
    }

    @Test
    fun probeHeaderRetainsTargetOutcomeAndClickAction() {
        val target = "TLS connection to example.org"
        val probeType = "Проверка доступности"
        val outcome = "Соединение установлено"
        val probe =
            DiagnosticsProbeResultUiModel(
                "probe",
                probeType,
                target,
                outcome,
                tone = DiagnosticsTone.Positive,
                details = persistentListOf(),
            )
        var clicks = 0
        composeRule.setContent { Container { ProbeResultRow(probe, { clicks++ }) } }
        configurations {
            assertWordsFit(target)
            assertWordsFit(probeType)
            assertWordsFit(outcome)
            composeRule.onNodeWithText(target).assertHasClickAction().performClick()
        }
        assertEquals(6, clicks)
    }

    @Test
    fun eventHeaderRetainsMessageTimestampBadgesAndClickAction() {
        val message = "Соединение не установлено"
        val timestamp = "13.10.2026 12:34:56"
        val source = "Диагностика"
        val severity = "Предупреждение"
        val event = DiagnosticsEventUiModel("event", source, severity, message, timestamp, DiagnosticsTone.Warning)
        var clicks = 0
        composeRule.setContent { Container { EventRow(event, { clicks++ }) } }
        configurations {
            listOf(message, timestamp, source, severity).forEach(::assertWordsFit)
            composeRule.onNodeWithText(message).assertHasClickAction().performClick()
        }
        assertEquals(6, clicks)
    }

    @Test
    fun shortSessionMetadataStaysInlineAtNormalFont() {
        width.value = 420
        scale.value = 1f
        val session =
            DiagnosticsSessionRowUiModel(
                "session",
                "profile",
                "Report",
                "Ready",
                "raw",
                "proxy",
                "Done",
                "12:34",
                "",
                persistentListOf(),
                DiagnosticsTone.Positive,
            )
        composeRule.setContent { Container { SessionRow(session, null) } }
        val title = composeRule.onNodeWithText("Report", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val status = composeRule.onNodeWithText("Done", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(title.top, status.top, 1f)
        assertTrue("Normal metadata remains beside the title", status.left > title.right)
    }

    @Composable
    private fun Container(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
            RipDpiTheme(themePreference = theme.value) {
                Box(Modifier.requiredWidth(width.value.dp).padding(horizontal = 20.dp)) { content() }
            }
        }
    }

    private fun configurations(assertion: () -> Unit) {
        listOf("light", "dark").forEach { preference ->
            listOf(411 to 2f, 320 to 2f, 420 to 1f).forEach { (viewport, fontScale) ->
                composeRule.runOnIdle {
                    width.value = viewport
                    scale.value = fontScale
                    theme.value = preference
                }
                composeRule.waitForIdle()
                assertion()
            }
        }
    }

    private fun assertWordsFit(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
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
            "Word needs $wordWidth px but receives ${layout.size.width}: $text",
            layout.size.width + 1f >= wordWidth,
        )
        // A cached paragraph can reserve more width than its occupied lines.
        // Check visible line bounds and truncation instead of that unused space.
        assertFalse("Text height is complete: $text", layout.didOverflowHeight)
        repeat(layout.lineCount) { line ->
            assertFalse("Line is complete: $text", layout.isLineEllipsized(line))
            assertTrue("Line starts inside its bounds: $text", layout.getLineLeft(line) >= -1f)
            assertTrue(
                "Line ends inside its bounds: $text",
                layout.getLineRight(line) <= layout.size.width + 1f,
            )
        }
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
    }
}
