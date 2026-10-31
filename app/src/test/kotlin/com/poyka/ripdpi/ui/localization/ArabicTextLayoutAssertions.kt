package com.poyka.ripdpi.ui.localization

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal fun assertArabicTextLayout(
    layout: TextLayoutResult,
    bounds: Rect,
    viewport: Rect,
    diagnostic: String,
) {
    assertTrue(diagnostic, bounds.left >= viewport.left - 1f && bounds.right <= viewport.right + 1f)
    assertTrue(diagnostic, bounds.top >= viewport.top - 1f && bounds.bottom <= viewport.bottom + 1f)
    assertTrue(diagnostic, layout.size.width <= bounds.width + 1f)
    assertTrue(diagnostic, layout.size.height <= bounds.height + 1f)
    assertFalse(diagnostic, layout.didOverflowHeight)
    assertFalse(diagnostic, layout.multiParagraph.didExceedMaxLines)

    val alignment = layout.layoutInput.style.textAlign
    val rightAligned =
        alignment == TextAlign.Start || alignment == TextAlign.Unspecified || alignment == TextAlign.Right
    // ParagraphLayoutCache semantics rebuild the paragraph at the parent's maximum width,
    // but drawing uses the intrinsic node width. Move only single-line RTL line
    // coordinates to that drawing origin; wide text must still fail the checks.
    val offset =
        if (isSingleLineRtl(layout) && rightAligned && layout.multiParagraph.width > layout.size.width) {
            (layout.multiParagraph.width - layout.size.width).coerceAtLeast(0f)
        } else {
            0f
        }
    if (layout.multiParagraph.width <= layout.size.width + 1f) assertFalse(diagnostic, layout.hasVisualOverflow)
    repeat(layout.lineCount) { line ->
        val left = layout.getLineLeft(line) - offset
        val right = layout.getLineRight(line) - offset
        val lineDiagnostic = "$diagnostic; drawingOffset=$offset; line=$line; drawnLeft=$left; drawnRight=$right"
        assertFalse(lineDiagnostic, layout.isLineEllipsized(line))
        assertTrue(lineDiagnostic, left >= -1f)
        assertTrue(lineDiagnostic, right <= layout.size.width + 1f)
        assertTrue(lineDiagnostic, right - left <= bounds.width + 1f)
        assertTrue(lineDiagnostic, layout.getLineTop(line) >= -1f)
        assertTrue(lineDiagnostic, layout.getLineBottom(line) <= layout.size.height + 1f)
    }
}

private fun isSingleLineRtl(layout: TextLayoutResult): Boolean =
    layout.lineCount == 1 &&
        layout.layoutInput.layoutDirection == LayoutDirection.Rtl &&
        layout.getParagraphDirection(0) == ResolvedTextDirection.Rtl

/** Fails when any text on the screen is ellipsized or overflows its box, so a screen cannot clip at large font. */
internal fun ComposeContentTestRule.assertNoClippedText(screen: String) {
    val nodes =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
    assertTrue("$screen renders no text", nodes.isNotEmpty())
    nodes.forEach { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        layouts.forEach { layout ->
            val text = layout.layoutInput.text.text
            // The semantics layout can come from another width pass, so compare heights only when it matches the node.
            if (layout.size.width == node.size.width) {
                assertTrue(
                    "$screen: '$text' is clipped (box ${node.size}, text ${layout.multiParagraph.height})",
                    node.size.height + 1f >= layout.multiParagraph.height,
                )
            }
            assertFalse("$screen: '$text' exceeds its line cap", layout.multiParagraph.didExceedMaxLines)
            // Same guard as assertArabicTextLayout: a semantics paragraph can be wider than the drawn node in RTL.
            if (layout.multiParagraph.width <= layout.size.width + 1f) {
                assertFalse("$screen: '$text' overflows", layout.hasVisualOverflow)
            }
            repeat(layout.lineCount) { line ->
                assertFalse("$screen: '$text' is ellipsized", layout.isLineEllipsized(line))
                val lineWidth = layout.getLineRight(line) - layout.getLineLeft(line)
                assertTrue("$screen: '$text' line $line is wider than its box", lineWidth <= layout.size.width + 1f)
            }
        }
    }
}
