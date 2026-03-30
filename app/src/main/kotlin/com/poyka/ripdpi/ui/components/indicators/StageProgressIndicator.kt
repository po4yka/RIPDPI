package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun StageProgressIndicator(
    completedCount: Int,
    failedCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing
    val typeScale = RipDpiThemeTokens.type
    val pendingCount = (totalCount - completedCount - failedCount).coerceAtLeast(0)

    val segmentShape = RoundedCornerShape(2.dp)
    val animSpec =
        tween<androidx.compose.ui.graphics.Color>(
            durationMillis = motion.duration(motion.stateDurationMillis),
        )

    val description =
        buildString {
            append("$completedCount passed")
            if (failedCount > 0) append(", $failedCount failed")
            if (pendingCount > 0) append(", $pendingCount pending")
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                },
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(totalCount) { index ->
                val targetColor =
                    when {
                        index < completedCount -> colors.success
                        index < completedCount + failedCount -> colors.destructive
                        else -> colors.mutedForeground
                    }
                val animatedColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = animSpec,
                    label = "segmentColor$index",
                )
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(animatedColor, segmentShape),
                )
            }
        }

        val summaryParts =
            buildList {
                if (completedCount > 0) add(completedCount to colors.success)
                if (failedCount > 0) add(failedCount to colors.destructive)
            }

        if (summaryParts.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                summaryParts.forEachIndexed { i, (count, color) ->
                    if (i > 0) {
                        Text(
                            text = "\u00B7",
                            style = typeScale.caption,
                            color = colors.mutedForeground,
                        )
                    }
                    val label = if (color == colors.success) "passed" else "failed"
                    Text(
                        text = "$count $label",
                        style = typeScale.caption,
                        color = color,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StageProgressIndicatorPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.lg)) {
            StageProgressIndicator(
                completedCount = 3,
                failedCount = 1,
                totalCount = 5,
            )
            StageProgressIndicator(
                completedCount = 4,
                failedCount = 0,
                totalCount = 4,
            )
            StageProgressIndicator(
                completedCount = 0,
                failedCount = 0,
                totalCount = 6,
            )
        }
    }
}
