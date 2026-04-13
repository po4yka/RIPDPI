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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val PassedLabel = "passed"
private const val FailedLabel = "failed"

@Composable
fun StageProgressIndicator(
    completedCount: Int,
    failedCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val pendingCount = (totalCount - completedCount - failedCount).coerceAtLeast(0)
    val resources = LocalContext.current.resources
    val description = buildStageProgressDescription(resources, completedCount, failedCount, pendingCount)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Polite
                },
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        StageProgressSegments(
            completedCount = completedCount,
            failedCount = failedCount,
            totalCount = totalCount,
        )
        StageProgressSummary(
            completedCount = completedCount,
            failedCount = failedCount,
        )
    }
}

private fun buildStageProgressDescription(
    resources: android.content.res.Resources,
    completedCount: Int,
    failedCount: Int,
    pendingCount: Int,
): String =
    buildString {
        append(resources.getQuantityString(R.plurals.stage_passed_count, completedCount, completedCount))
        if (failedCount > 0) {
            append(resources.getQuantityString(R.plurals.stage_failed_count, failedCount, failedCount))
        }
        if (pendingCount > 0) {
            append(resources.getQuantityString(R.plurals.stage_pending_count, pendingCount, pendingCount))
        }
    }

@Composable
private fun StageProgressSegments(
    completedCount: Int,
    failedCount: Int,
    totalCount: Int,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val segmentShape = RipDpiThemeTokens.shapes.xs
    val animSpec = tween<Color>(durationMillis = motion.duration(motion.stateDurationMillis))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(totalCount) { index ->
            val animatedColor by animateColorAsState(
                targetValue = stageSegmentColor(index, completedCount, failedCount),
                animationSpec = animSpec,
                label = "segmentColor$index",
            )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(animatedColor, segmentShape),
            )
        }
    }
}

@Composable
private fun StageProgressSummary(
    completedCount: Int,
    failedCount: Int,
) {
    val colors = RipDpiThemeTokens.colors
    val typeScale = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val summaryParts =
        buildList {
            if (completedCount > 0) add(StageSummaryPart(completedCount, PassedLabel, colors.success))
            if (failedCount > 0) add(StageSummaryPart(failedCount, FailedLabel, colors.destructive))
        }
    if (summaryParts.isEmpty()) {
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
        summaryParts.forEachIndexed { index, part ->
            if (index > 0) {
                Text(
                    text = "\u00B7",
                    style = typeScale.caption,
                    color = colors.mutedForeground,
                )
            }
            Text(
                text = "${part.count} ${part.label}",
                style = typeScale.caption,
                color = part.color,
            )
        }
    }
}

private fun stageSegmentColor(
    index: Int,
    completedCount: Int,
    failedCount: Int,
): Color {
    val colors = RipDpiThemeTokens.colors
    return when {
        index < completedCount -> colors.success
        index < completedCount + failedCount -> colors.destructive
        else -> colors.mutedForeground
    }
}

private data class StageSummaryPart(
    val count: Int,
    val label: String,
    val color: Color,
)

@Suppress("UnusedPrivateMember")
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
