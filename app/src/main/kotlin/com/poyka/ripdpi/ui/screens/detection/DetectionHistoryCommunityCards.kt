package com.poyka.ripdpi.ui.screens.detection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.networkTypeLabel
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.core.detection.community.CommunityStats
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.platform.localizedTimestamp
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinnerSize
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val historyEntryLimit = 5
private const val highStealthScoreThreshold = 70
private const val mediumStealthScoreThreshold = 40
private const val detectedPercentageAlertThreshold = 50
private const val percentScale = 100.0

@Composable
internal fun HistoryCard(entries: List<DetectionHistoryEntry>) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val limited = entries.take(historyEntryLimit)
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        RipDpiPanelHeader(title = stringResource(R.string.detection_history_title))
        for (entry in limited) {
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
                    val networkSummary =
                        if (entry.hasGeneratedUnknownNetwork()) {
                            stringResource(R.string.remembered_networks_transport_unknown)
                        } else {
                            val strings = AndroidStringResolver(LocalContext.current)
                            // Only a recognized transport is relabeled; anything else keeps the stored text.
                            entry
                                .generatedTransport()
                                ?.let(strings::networkTypeLabel)
                                ?.takeIf { it != entry.generatedTransport() }
                                ?: entry.networkSummary
                        }
                    Text(networkSummary, style = type.body, maxLines = 1)
                    Text(localizedTimestamp(entry.timestamp), style = type.caption, color = colors.mutedForeground)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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

// Both history producers reserve this fingerprint when network capture is unavailable.
// Match their exact sentinels so captured network names and custom summaries stay intact.
private fun DetectionHistoryEntry.hasGeneratedUnknownNetwork(): Boolean =
    networkFingerprint == "unknown" && (networkSummary == "Unknown" || networkSummary == "Unknown network")

// The producers store "<transport>/<identityKind>", for example "wifi/wifi"; show only the transport.
private fun DetectionHistoryEntry.generatedTransport(): String? =
    GeneratedNetworkSummary.matchEntire(networkSummary)?.groupValues?.get(1)

private val GeneratedNetworkSummary = Regex("([a-z_]+)/[a-z_]+")

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
        RipDpiPanelHeader(title = title)
        Text(
            text =
                pluralStringResource(R.plurals.detection_community_reports, stats.totalReports, stats.totalReports),
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
