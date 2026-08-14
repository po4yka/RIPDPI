package com.poyka.ripdpi.ui.screens.detection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.core.detection.community.CommunityStats
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinnerSize
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val historyEntryLimit = 5
private const val highStealthScoreThreshold = 70
private const val mediumStealthScoreThreshold = 40
private const val detectedPercentageAlertThreshold = 50
private const val percentScale = 100.0

@Composable
internal fun HistoryCard(entries: List<DetectionHistoryEntry>) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val limited = entries.take(historyEntryLimit)
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(
            text = stringResource(R.string.detection_history_title).uppercase(),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        for ((index, entry) in limited.withIndex()) {
            val scoreColor =
                when {
                    entry.stealthScore >= highStealthScoreThreshold -> colors.success
                    entry.stealthScore >= mediumStealthScoreThreshold -> colors.warning
                    else -> colors.destructive
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.networkSummary, style = type.body, maxLines = 1)
                    Text(formatTimestamp(entry.timestamp), style = type.caption, color = colors.mutedForeground)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (index > 0) {
                        val diff = entry.stealthScore - limited[index - 1].stealthScore
                        val (icon, desc, tint) =
                            when {
                                diff > 0 -> {
                                    Triple(
                                        RipDpiIcons.KeyboardArrowUp,
                                        stringResource(R.string.detection_score_improved),
                                        colors.success,
                                    )
                                }

                                diff < 0 -> {
                                    Triple(
                                        RipDpiIcons.KeyboardArrowDown,
                                        stringResource(R.string.detection_score_degraded),
                                        colors.destructive,
                                    )
                                }

                                else -> {
                                    Triple(
                                        RipDpiIcons.Remove,
                                        stringResource(R.string.detection_score_unchanged),
                                        colors.mutedForeground,
                                    )
                                }
                            }
                        Icon(icon, contentDescription = desc, modifier = Modifier.size(16.dp), tint = tint)
                    }
                    val stealthScoreDescription =
                        stringResource(R.string.detection_stealth_score_a11y, entry.stealthScore)
                    Text(
                        "${entry.stealthScore}",
                        style = type.bodyEmphasis,
                        color = scoreColor,
                        modifier = Modifier.semantics { contentDescription = stealthScoreDescription },
                    )
                }
            }
        }
    }
}

@Composable
internal fun CommunityStatsCard(stats: CommunityStats) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val title =
        if (stats.isLocalOnly) {
            stringResource(R.string.detection_community_local_title)
        } else {
            stringResource(R.string.detection_community_title)
        }
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(text = title.uppercase(), style = type.sectionTitle, color = colors.mutedForeground)
        Text(
            text = stringResource(R.string.detection_community_reports, stats.totalReports),
            style = type.body,
        )
        if (stats.averageStealthScore > 0) {
            Text(
                text = stringResource(R.string.detection_community_avg_score, stats.averageStealthScore.toInt()),
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
        val detected = stats.verdictDistribution["DETECTED"] ?: 0
        if (stats.totalReports > 0) {
            val pct = (detected * percentScale / stats.totalReports).toInt()
            Text(
                text = stringResource(R.string.detection_community_detected_pct, pct),
                style = type.bodyEmphasis,
                color = if (pct > detectedPercentageAlertThreshold) colors.destructive else colors.success,
            )
        }
    }
}

@Composable
internal fun CommunityStatsLoadingCard() {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RipDpiSpinner(size = RipDpiSpinnerSize.Standard)
            Text(
                text = stringResource(R.string.detection_community_loading),
                style = type.body,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
internal fun CommunityStatsErrorCard(
    message: String,
    onRetry: () -> Unit,
) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(
            text = stringResource(R.string.detection_community_error),
            style = type.bodyEmphasis,
            color = colors.destructive,
        )
        Text(
            text = message,
            style = type.caption,
            color = colors.mutedForeground,
        )
        RipDpiButton(
            text = stringResource(R.string.detection_community_retry),
            onClick = onRetry,
            variant = RipDpiButtonVariant.Outline,
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    val formatter =
        DateTimeFormatter
            .ofPattern("dd MMM HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(millis))
}
