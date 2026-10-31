@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.animate
import androidx.compose.ui.graphics.Color
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationTone
import com.poyka.ripdpi.ui.components.indicators.LogRowTone
import com.poyka.ripdpi.ui.components.indicators.RipDpiStaleTier

private val DegradationAccentStyles =
    RipDpiDegradationTone.entries.associateWith { tone ->
        CommonStyle {
            val colors = LocalRipDpiExtendedColors.currentValue
            val container =
                when (tone) {
                    // The resting state keeps a quiet accent so it does not outweigh real problems.
                    RipDpiDegradationTone.Nominal -> colors.border

                    RipDpiDegradationTone.Warning -> colors.warning

                    RipDpiDegradationTone.Critical -> colors.destructive
                }
            rdsBackground(container)
        }
    }

private val LogBadgeStyles =
    LogRowTone.entries.associateWith { tone ->
        CommonStyle {
            val colors = LocalRipDpiExtendedColors.currentValue
            val container =
                when (tone) {
                    LogRowTone.Dns -> colors.infoContainer
                    LogRowTone.Connection -> colors.inputBackground
                    LogRowTone.Warning -> colors.warningContainer
                    LogRowTone.Error -> colors.destructiveContainer
                }
            rdsBackground(container)
        }
    }

private val StaleBadgeStyles =
    RipDpiStaleTier.entries.associateWith { tier ->
        CommonStyle {
            val colors = LocalRipDpiExtendedColors.currentValue
            val container =
                when (tier) {
                    RipDpiStaleTier.Fresh -> colors.success
                    RipDpiStaleTier.Recent -> colors.card
                    RipDpiStaleTier.Aging -> colors.muted
                    RipDpiStaleTier.Stale -> colors.warningContainer
                    RipDpiStaleTier.Expired -> colors.destructiveContainer
                }
            val border =
                if (tier == RipDpiStaleTier.Recent || tier == RipDpiStaleTier.Aging) {
                    colors.border
                } else {
                    Color.Transparent
                }
            rdsBackground(container)
            rdsBorder(border, RipDpiStroke.Thin)
        }
    }

private val SheetHandleStyle = CommonStyle { rdsBackground(LocalRipDpiExtendedColors.currentValue.border) }
private val MetadataChipStyle = CommonStyle { rdsBackground(LocalRipDpiExtendedColors.currentValue.inputBackground) }
private val AnalysisContainerStyle =
    CommonStyle {
        rdsBackground(LocalRipDpiExtendedColors.currentValue.info.copy(alpha = 0.06f))
    }
private val PageIndicatorStyle =
    CommonStyle {
        val colors = LocalRipDpiExtendedColors.currentValue
        val motion = LocalRipDpiMotion.currentValue
        animate(
            motion.stateTween<Float>(),
        ) { rdsBackground(if (state.isSelected) colors.foreground else colors.mutedForeground) }
    }

fun RipDpiComponentStyles.degradationAccent(tone: RipDpiDegradationTone): CommonStyle =
    DegradationAccentStyles.getValue(tone)

fun RipDpiComponentStyles.logBadge(tone: LogRowTone): CommonStyle = LogBadgeStyles.getValue(tone)

fun RipDpiComponentStyles.staleBadge(tier: RipDpiStaleTier): CommonStyle = StaleBadgeStyles.getValue(tier)

val RipDpiComponentStyles.sheetHandle: CommonStyle get() = SheetHandleStyle
val RipDpiComponentStyles.metadataChip: CommonStyle get() = MetadataChipStyle
val RipDpiComponentStyles.analysisContainer: CommonStyle get() = AnalysisContainerStyle
val RipDpiComponentStyles.pageIndicator: CommonStyle get() = PageIndicatorStyle
