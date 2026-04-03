package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.AnalysisStageStatus
import com.poyka.ripdpi.activities.AnalysisStageUiState
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private val SegmentHeight = 6.dp
private val SegmentCornerRadius = 3.dp
private val SegmentGap = 4.dp
private val ContainerCornerRadius = 12.dp
private const val CONTAINER_BG_ALPHA = 0.06f
private const val PULSE_TARGET_ALPHA = 0.45f
private const val PULSE_DURATION_MS = 1200
private const val SHIMMER_DURATION_MS = 1800
private const val SHIMMER_MIN_ALPHA = 0.4f
private const val SHIMMER_MAX_ALPHA = 0.7f
private const val COMPLETION_SCALE_PEAK = 1.06f

@Composable
fun AnalysisProgressIndicator(
    stages: List<AnalysisStageUiState>,
    activeStageIndex: Int?,
    stageLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing
    val typeScale = RipDpiThemeTokens.type

    val segmentShape = RoundedCornerShape(SegmentCornerRadius)
    val containerShape = RoundedCornerShape(ContainerCornerRadius)
    val colorAnimSpec =
        tween<androidx.compose.ui.graphics.Color>(
            durationMillis = motion.duration(motion.stateDurationMillis),
        )

    val infiniteTransition = rememberInfiniteTransition(label = "analysisPulse")

    // Active segment pulse
    val pulseAlpha by if (motion.allowsInfiniteMotion) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = PULSE_TARGET_ALPHA,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = PULSE_DURATION_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "activeSegmentPulse",
        )
    } else {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "activeSegmentStatic",
        )
    }

    // Pending segments shimmer
    val shimmerAlpha by if (motion.allowsInfiniteMotion) {
        infiniteTransition.animateFloat(
            initialValue = SHIMMER_MIN_ALPHA,
            targetValue = SHIMMER_MAX_ALPHA,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = SHIMMER_DURATION_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "pendingShimmer",
        )
    } else {
        infiniteTransition.animateFloat(
            initialValue = SHIMMER_MAX_ALPHA,
            targetValue = SHIMMER_MAX_ALPHA,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "pendingStatic",
        )
    }

    val description =
        buildString {
            val completed = stages.count { it.status == AnalysisStageStatus.COMPLETED }
            val failed = stages.count { it.status == AnalysisStageStatus.FAILED }
            val running = stages.count { it.status == AnalysisStageStatus.RUNNING }
            append("$completed completed")
            if (running > 0) append(", $running running")
            if (failed > 0) append(", $failed failed")
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    color = colors.info.copy(alpha = CONTAINER_BG_ALPHA),
                    shape = containerShape,
                ).padding(horizontal = spacing.sm, vertical = spacing.sm)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                },
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            stages.forEachIndexed { index, stage ->
                val targetColor =
                    when (stage.status) {
                        AnalysisStageStatus.COMPLETED -> colors.success
                        AnalysisStageStatus.FAILED -> colors.destructive
                        AnalysisStageStatus.RUNNING -> colors.info
                        AnalysisStageStatus.PENDING -> colors.muted
                    }
                val animatedColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = colorAnimSpec,
                    label = "segmentColor$index",
                )

                val isActive = index == activeStageIndex
                val isPending = stage.status == AnalysisStageStatus.PENDING
                val isCompleted = stage.status == AnalysisStageStatus.COMPLETED

                // Completion sweep scale animation
                val completionScale = remember { Animatable(1f) }
                if (motion.allowsInfiniteMotion) {
                    LaunchedEffect(isCompleted) {
                        if (isCompleted) {
                            completionScale.animateTo(
                                targetValue = COMPLETION_SCALE_PEAK,
                                animationSpec =
                                    tween(
                                        durationMillis = motion.duration(motion.quickDurationMillis),
                                    ),
                            )
                            completionScale.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    tween(
                                        durationMillis = motion.duration(motion.stateDurationMillis),
                                    ),
                            )
                        }
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(SegmentHeight)
                            .graphicsLayer {
                                scaleX = completionScale.value
                                scaleY = completionScale.value
                            }.then(
                                when {
                                    isActive -> Modifier.alpha(pulseAlpha)
                                    isPending -> Modifier.alpha(shimmerAlpha)
                                    else -> Modifier
                                },
                            ).background(animatedColor, segmentShape),
                )
            }
        }

        AnimatedContent(
            targetState = stageLabel,
            transitionSpec = {
                fadeIn(
                    tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                ) togetherWith
                    fadeOut(
                        tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                    )
            },
            label = "stageLabelCrossfade",
        ) { label ->
            Text(
                text = label,
                style = typeScale.secondaryBody,
                color = colors.mutedForeground,
            )
        }
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun AnalysisProgressIndicatorPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.lg)) {
            AnalysisProgressIndicator(
                stages =
                    listOf(
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED),
                        AnalysisStageUiState(AnalysisStageStatus.RUNNING),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                    ),
                activeStageIndex = 1,
                stageLabel = "Stage 2 of 4 \u00B7 Testing TCP candidate Parser-only",
            )
            AnalysisProgressIndicator(
                stages =
                    listOf(
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED),
                        AnalysisStageUiState(AnalysisStageStatus.FAILED),
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED),
                        AnalysisStageUiState(AnalysisStageStatus.RUNNING),
                    ),
                activeStageIndex = 3,
                stageLabel = "Stage 4 of 4 \u00B7 Testing UDP candidate",
            )
            AnalysisProgressIndicator(
                stages =
                    listOf(
                        AnalysisStageUiState(AnalysisStageStatus.RUNNING),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                    ),
                activeStageIndex = 0,
                stageLabel = "Stage 1 of 3 \u00B7 Initializing scan",
            )
        }
    }
}
