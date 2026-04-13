package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.AnalysisStageStatus
import com.poyka.ripdpi.activities.AnalysisStageUiState
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private val SegmentHeight = 6.dp
private val SegmentGap = 4.dp
private const val ContainerBgAlpha = 0.06f
private const val PulseTargetAlpha = 0.45f
private const val PulseDurationMs = 1200
private const val ShimmerDurationMs = 1800
private const val ShimmerMinAlpha = 0.4f
private const val ShimmerMaxAlpha = 0.7f
private const val CompletionScalePeak = 1.06f
private const val ParallelStageMinCount = 3
private const val StrategyStageMinCount = 4
private const val ParallelColumnWeight = 3f
private const val OuterColumnWeight = 2f
private const val FillOriginY = 0.5f

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
    val containerShape = RipDpiThemeTokens.shapes.lg
    val (pulseAlpha, shimmerAlpha) = rememberPipelineAlphas(motion.allowsInfiniteMotion)
    val description = buildStageDescription(stages)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(color = colors.info.copy(alpha = ContainerBgAlpha), shape = containerShape)
                .padding(horizontal = spacing.sm, vertical = spacing.sm)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Polite
                },
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        PipelineRow(
            stages = stages,
            activeStageIndex = activeStageIndex,
            pulseAlpha = pulseAlpha,
            shimmerAlpha = shimmerAlpha,
        )
        val twoLineHeight =
            with(LocalDensity.current) {
                (typeScale.secondaryBody.lineHeight * 2).toDp()
            }
        AnimatedContent(
            targetState = stageLabel,
            transitionSpec = {
                (
                    fadeIn(tween(durationMillis = motion.duration(motion.stateDurationMillis))) togetherWith
                        fadeOut(tween(durationMillis = motion.duration(motion.quickDurationMillis)))
                ).using(SizeTransform(clip = false))
            },
            modifier = Modifier.defaultMinSize(minHeight = twoLineHeight),
            label = "stageLabelCrossfade",
        ) { label ->
            Text(
                text = label,
                style = typeScale.secondaryBody,
                color = colors.mutedForeground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun buildStageDescription(stages: List<AnalysisStageUiState>): String =
    buildString {
        val completed = stages.count { it.status == AnalysisStageStatus.COMPLETED }
        val failed = stages.count { it.status == AnalysisStageStatus.FAILED }
        val running = stages.count { it.status == AnalysisStageStatus.RUNNING }
        append("$completed completed")
        if (running > 0) append(", $running running")
        if (failed > 0) append(", $failed failed")
    }

@Composable
private fun rememberPipelineAlphas(allowsInfiniteMotion: Boolean): Pair<Float, Float> {
    val infiniteTransition = rememberInfiniteTransition(label = "analysisPulse")
    val pulseAlpha by if (allowsInfiniteMotion) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = PulseTargetAlpha,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = PulseDurationMs, easing = LinearEasing),
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
    val shimmerAlpha by if (allowsInfiniteMotion) {
        infiniteTransition.animateFloat(
            initialValue = ShimmerMinAlpha,
            targetValue = ShimmerMaxAlpha,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = ShimmerDurationMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "pendingShimmer",
        )
    } else {
        infiniteTransition.animateFloat(
            initialValue = ShimmerMaxAlpha,
            targetValue = ShimmerMaxAlpha,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "pendingStatic",
        )
    }
    return Pair(pulseAlpha, shimmerAlpha)
}

@Composable
private fun PipelineRow(
    stages: List<AnalysisStageUiState>,
    activeStageIndex: Int?,
    pulseAlpha: Float,
    shimmerAlpha: Float,
) {
    val colors = RipDpiThemeTokens.colors
    val typeScale = RipDpiThemeTokens.type
    // Pipeline topology: [audit] → [connectivity | dpi_full] → [strategy]
    // Stages 1 and 2 run in parallel and are stacked vertically.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SegmentGap),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        // Stage 0 — audit
        if (stages.isNotEmpty()) {
            PipelineSegment(
                stage = stages[0],
                index = 0,
                activeStageIndex = activeStageIndex,
                pulseAlpha = pulseAlpha,
                shimmerAlpha = shimmerAlpha,
                modifier = Modifier.weight(OuterColumnWeight),
            )
        }
        // Arrow connector
        Text(
            text = "\u203A",
            style = typeScale.monoSmall,
            color = colors.mutedForeground,
        )
        // Stages 1 & 2 — parallel (stacked)
        if (stages.size >= ParallelStageMinCount) {
            Column(
                modifier = Modifier.weight(ParallelColumnWeight),
                verticalArrangement = Arrangement.spacedBy(SegmentGap),
            ) {
                PipelineSegment(
                    stage = stages[1],
                    index = 1,
                    activeStageIndex = activeStageIndex,
                    pulseAlpha = pulseAlpha,
                    shimmerAlpha = shimmerAlpha,
                    modifier = Modifier.fillMaxWidth(),
                )
                PipelineSegment(
                    stage = stages[2],
                    index = 2,
                    activeStageIndex = activeStageIndex,
                    pulseAlpha = pulseAlpha,
                    shimmerAlpha = shimmerAlpha,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Arrow connector
        Text(
            text = "\u203A",
            style = typeScale.monoSmall,
            color = colors.mutedForeground,
        )
        // Stage 3 — strategy
        if (stages.size >= StrategyStageMinCount) {
            PipelineSegment(
                stage = stages[3],
                index = 3,
                activeStageIndex = activeStageIndex,
                pulseAlpha = pulseAlpha,
                shimmerAlpha = shimmerAlpha,
                modifier = Modifier.weight(OuterColumnWeight),
            )
        }
    }
}

@Composable
private fun PipelineSegment(
    stage: AnalysisStageUiState,
    index: Int,
    activeStageIndex: Int?,
    pulseAlpha: Float,
    shimmerAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val segmentShape = RipDpiThemeTokens.shapes.xs
    val targetColor =
        when (stage.status) {
            AnalysisStageStatus.COMPLETED -> colors.success
            AnalysisStageStatus.FAILED -> colors.destructive
            AnalysisStageStatus.RUNNING -> colors.info
            AnalysisStageStatus.PENDING -> colors.muted
        }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "segmentColor$index",
    )
    val isActive = index == activeStageIndex
    val isPending = stage.status == AnalysisStageStatus.PENDING
    val isCompleted = stage.status == AnalysisStageStatus.COMPLETED
    val completionScale = remember { Animatable(1f) }
    if (motion.allowsInfiniteMotion) {
        LaunchedEffect(isCompleted) {
            if (isCompleted) {
                completionScale.animateTo(
                    targetValue = CompletionScalePeak,
                    animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                )
                completionScale.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                )
            }
        }
    }
    val fillFraction by animateFloatAsState(
        targetValue =
            when {
                isCompleted || stage.status == AnalysisStageStatus.FAILED -> 1f
                isActive -> stage.progress.coerceIn(0f, 1f)
                else -> 0f
            },
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "segmentFill$index",
    )
    Box(
        modifier =
            modifier
                .height(SegmentHeight)
                .graphicsLayer {
                    scaleX = completionScale.value
                    scaleY = completionScale.value
                },
    ) {
        // Background track
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .then(if (isPending) Modifier.alpha(shimmerAlpha) else Modifier)
                    .background(colors.muted, segmentShape),
        )
        // Animated fill overlay
        if (fillFraction > 0f) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            scaleX = fillFraction
                            transformOrigin = TransformOrigin(0f, FillOriginY)
                        }.then(if (isActive) Modifier.alpha(pulseAlpha) else Modifier)
                        .background(animatedColor, segmentShape),
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
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED, progress = 1f),
                        AnalysisStageUiState(AnalysisStageStatus.RUNNING, progress = 0.6f),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                    ),
                activeStageIndex = 1,
                stageLabel = "Stage 2 of 4 \u00B7 Testing TCP candidate Parser-only",
            )
            AnalysisProgressIndicator(
                stages =
                    listOf(
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED, progress = 1f),
                        AnalysisStageUiState(AnalysisStageStatus.FAILED, progress = 1f),
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED, progress = 1f),
                        AnalysisStageUiState(AnalysisStageStatus.RUNNING, progress = 0.3f),
                    ),
                activeStageIndex = 3,
                stageLabel = "Stage 4 of 4 \u00B7 Testing UDP candidate",
            )
            AnalysisProgressIndicator(
                stages =
                    listOf(
                        AnalysisStageUiState(AnalysisStageStatus.RUNNING, progress = 0.15f),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                    ),
                activeStageIndex = 0,
                stageLabel = "Stage 1 of 3 \u00B7 Initializing scan",
            )
        }
    }
}
