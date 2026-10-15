package com.poyka.ripdpi.ui.localization

import androidx.compose.ui.geometry.Rect
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
