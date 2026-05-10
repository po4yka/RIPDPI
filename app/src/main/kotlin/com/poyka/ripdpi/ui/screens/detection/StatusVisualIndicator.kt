package com.poyka.ripdpi.ui.screens.detection

import android.graphics.Color.parseColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.core.detection.ui.DetectionVisualState
import com.poyka.ripdpi.core.detection.ui.StatusShapeVariant
import com.poyka.ripdpi.core.detection.ui.StatusVisualResolver
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun StatusVisualIndicator(
    state: DetectionVisualState,
    mode: DetectionColorVisionMode,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    contentDescription: String = state.displayName,
) {
    val visual = StatusVisualResolver.resolve(state = state, mode = mode)
    val color = Color(parseColor(visual.colorHex))

    Canvas(
        modifier =
            modifier
                .size(size)
                .semantics { this.contentDescription = contentDescription },
    ) {
        val side = min(this.size.width, this.size.height)
        val left = (this.size.width - side) / 2f
        val top = (this.size.height - side) / 2f
        val centerX = left + side / 2f
        val centerY = top + side / 2f
        when (visual.shape) {
            StatusShapeVariant.CIRCLE -> {
                drawCircle(
                    color = color,
                    radius = side / 2f,
                    center = Offset(centerX, centerY),
                )
            }

            StatusShapeVariant.TRIANGLE -> {
                drawPath(
                    path =
                        Path().apply {
                            moveTo(centerX, top)
                            lineTo(left + side, top + side)
                            lineTo(left, top + side)
                            close()
                        },
                    color = color,
                )
            }

            StatusShapeVariant.DIAMOND -> {
                drawPath(
                    path =
                        Path().apply {
                            moveTo(centerX, top)
                            lineTo(left + side, centerY)
                            lineTo(centerX, top + side)
                            lineTo(left, centerY)
                            close()
                        },
                    color = color,
                )
            }

            StatusShapeVariant.SQUARE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(side / 8f, side / 8f),
                )
            }

            StatusShapeVariant.LINE -> {
                val lineHeight = max(2f, side / 3f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, centerY - lineHeight / 2f),
                    size = Size(side, lineHeight),
                    cornerRadius = CornerRadius(lineHeight / 2f, lineHeight / 2f),
                )
            }
        }
    }
}

@Composable
internal fun StatusVisualPreviewRow(
    mode: DetectionColorVisionMode,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetectionVisualState.entries.forEach { state ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusVisualIndicator(state = state, mode = mode)
                Text(
                    text = state.displayName,
                    style = RipDpiThemeTokens.type.caption,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
    }
}

internal fun Verdict.toDetectionVisualState(): DetectionVisualState =
    when (this) {
        Verdict.NOT_DETECTED -> DetectionVisualState.CLEAN
        Verdict.NEEDS_REVIEW -> DetectionVisualState.REVIEW
        Verdict.DETECTED -> DetectionVisualState.DETECTED
    }

internal fun detectionCategoryVisualState(
    detected: Boolean,
    needsReview: Boolean,
): DetectionVisualState =
    when {
        detected -> DetectionVisualState.DETECTED
        needsReview -> DetectionVisualState.REVIEW
        else -> DetectionVisualState.CLEAN
    }
