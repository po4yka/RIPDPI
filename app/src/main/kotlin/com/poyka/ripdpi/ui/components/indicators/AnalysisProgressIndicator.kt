package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.AnalysisStageStatus
import com.poyka.ripdpi.activities.AnalysisStageUiState
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private val SegmentHeight = 6.dp
private val SegmentGap = 4.dp
private const val ContainerBgAlpha = 0.06f
private const val PulseTargetAlpha = 0.45f
private const val PulseDurationMs = 1200
private const val ShimmerDurationMs = 1800
private const val ShimmerMinAlpha = 0.4f
private const val ShimmerMaxAlpha = 0.7f
private const val CompletionScalePeak = 1.06f
private const val FillOriginY = 0.5f

private data class PipelineAlphas(
    val pulse: State<Float>,
    val shimmer: State<Float>,
)

@Composable
fun AnalysisProgressIndicator(
    stages: ImmutableList<AnalysisStageUiState>,
    activeStageIndex: Int?,
    stageLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing
    val typeScale = RipDpiThemeTokens.type
    val containerShape = RipDpiThemeTokens.shapes.lg
    val pipelineAlphas = rememberPipelineAlphas(motion)
    val resources = LocalContext.current.resources
    val description = buildStageDescription(resources, stages)

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
            pipelineAlphas = pipelineAlphas,
        )
        val twoLineHeight =
            with(LocalDensity.current) {
                (typeScale.secondaryBody.lineHeight * 2).toDp()
            }
        AnimatedContent(
            targetState = stageLabel,
            transitionSpec = {
                (
                    fadeIn(motion.stateTween()) togetherWith
                        fadeOut(motion.quickTween())
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

private fun buildStageDescription(
    resources: android.content.res.Resources,
    stages: ImmutableList<AnalysisStageUiState>,
): String {
    val completed = stages.count { it.status == AnalysisStageStatus.COMPLETED }
    val failed = stages.count { it.status == AnalysisStageStatus.FAILED }
    val running = stages.count { it.status == AnalysisStageStatus.RUNNING }
    val fragments =
        buildList {
            add(resources.getQuantityString(R.plurals.analysis_progress_completed, completed, completed))
            if (running > 0) {
                add(resources.getQuantityString(R.plurals.analysis_progress_running, running, running))
            }
            if (failed > 0) {
                add(resources.getQuantityString(R.plurals.analysis_progress_failed, failed, failed))
            }
        }
    return fragments.joinToString(separator = ", ")
}

@Composable
private fun rememberPipelineAlphas(motion: com.poyka.ripdpi.ui.theme.RipDpiMotion): PipelineAlphas {
    if (LocalInspectionMode.current || !motion.allowsInfiniteMotion) {
        return PipelineAlphas(
            pulse = rememberUpdatedState(1f),
            shimmer = rememberUpdatedState(ShimmerMaxAlpha),
        )
    }
    val infiniteTransition = rememberInfiniteTransition(label = "analysisPulse")
    val pulseAlpha =
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = PulseTargetAlpha,
            animationSpec = motion.smoothPulseSpec(PulseDurationMs),
            label = "activeSegmentPulse",
        )
    val shimmerAlpha =
        infiniteTransition.animateFloat(
            initialValue = ShimmerMinAlpha,
            targetValue = ShimmerMaxAlpha,
            animationSpec = motion.smoothPulseSpec(ShimmerDurationMs),
            label = "pendingShimmer",
        )
    return PipelineAlphas(pulse = pulseAlpha, shimmer = shimmerAlpha)
}

/**
 * One equal-width segment per stage, which is both what the spec draws
 * (`docs/design/rds/preview/components-analysis-progress.html` lays the pipeline out as
 * `repeat(N, 1fr)`) and the only shape that can show the run it reports on.
 *
 * This used to hardcode `[audit] -> [connectivity | dpi_full] -> [strategy]`: segments at indices
 * 0, 1, 2 and 3, arrow glyphs between them, and 1 and 2 stacked as a parallel pair. That topology
 * matched neither the spec nor the runner. `HomeCompositeStageSpecs` is a flat list of nine
 * sequential stages -- `activeStageIndex` is a single Int, so nothing runs in parallel -- so the
 * hardcoded indices dropped the last four stages of every full run without a trace. A quick scan
 * emits three stages and happened to survive; a two-stage list would have lost its second stage
 * and drawn two arrows pointing at nothing.
 */
@Composable
private fun PipelineRow(
    stages: ImmutableList<AnalysisStageUiState>,
    activeStageIndex: Int?,
    pipelineAlphas: PipelineAlphas,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SegmentGap),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        stages.forEachIndexed { index, stage ->
            PipelineSegment(
                stage = stage,
                index = index,
                activeStageIndex = activeStageIndex,
                pipelineAlphas = pipelineAlphas,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PipelineSegment(
    stage: AnalysisStageUiState,
    index: Int,
    activeStageIndex: Int?,
    pipelineAlphas: PipelineAlphas,
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
            AnalysisStageStatus.PENDING -> colors.outlineVariant
        }
    val animatedColor =
        animateColorAsState(
            targetValue = targetColor,
            animationSpec = motion.stateTween(),
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
                    animationSpec = motion.quickTween(),
                )
                completionScale.animateTo(
                    targetValue = 1f,
                    animationSpec = motion.stateTween(),
                )
            }
        }
    }
    val fillFraction =
        animateFloatAsState(
            targetValue =
                when {
                    isCompleted || stage.status == AnalysisStageStatus.FAILED -> 1f
                    isActive -> stage.progress.coerceIn(0f, 1f)
                    else -> 0f
                },
            animationSpec = motion.stateTween(),
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
        // Empty track. outlineVariant, not muted: muted is #F5F5F5 against a #F1F4FD container,
        // which measures 1.02:1 and leaves every unstarted stage invisible, so a full nine-stage
        // run still read as four. outlineVariant is also the token the contrast ladder strengthens
        // for Medium/High, which muted is not, so the segments now respond to that setting at all.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = if (isPending) pipelineAlphas.shimmer.value else 1f
                    }.background(colors.outlineVariant, segmentShape),
        )
        // Animated fill overlay
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = fillFraction.value
                        alpha = if (isActive) pipelineAlphas.pulse.value else 1f
                        transformOrigin = TransformOrigin(0f, FillOriginY)
                    }.clip(segmentShape)
                    .drawBehind { drawRect(animatedColor.value) },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AnalysisProgressIndicatorPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.lg)) {
            AnalysisProgressIndicator(
                stages =
                    persistentListOf(
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
                    persistentListOf(
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
                    persistentListOf(
                        AnalysisStageUiState(AnalysisStageStatus.RUNNING, progress = 0.15f),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                    ),
                activeStageIndex = 0,
                stageLabel = "Stage 1 of 3 \u00B7 Initializing scan",
            )
            // The shape a full run actually produces: HomeCompositeStageSpecs has nine stages.
            AnalysisProgressIndicator(
                stages =
                    persistentListOf(
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED, progress = 1f),
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED, progress = 1f),
                        AnalysisStageUiState(AnalysisStageStatus.COMPLETED, progress = 1f),
                        AnalysisStageUiState(AnalysisStageStatus.FAILED, progress = 1f),
                        AnalysisStageUiState(AnalysisStageStatus.RUNNING, progress = 0.55f),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                        AnalysisStageUiState(AnalysisStageStatus.PENDING),
                    ),
                activeStageIndex = 4,
                stageLabel = "Stage 5 of 8 \u00B7 DPI detector full",
            )
        }
    }
}
