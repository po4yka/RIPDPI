package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.StrategyPackCatalogUiState
import com.poyka.ripdpi.data.StrategyPackCatalogSourceDownloaded
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal fun LazyListScope.strategyPackSection(
    strategyPackCatalog: StrategyPackCatalogUiState,
    onRefreshStrategyPackCatalog: () -> Unit,
) {
    item(key = "advanced_strategy_packs") {
        AdvancedSettingsSection(
            title = stringResource(R.string.strategy_pack_section_title),
            testTag = RipDpiTestTags.advancedSection("strategy_packs"),
        ) {
            StrategyPackCatalogStatusCard(
                strategyPackCatalog = strategyPackCatalog,
                onRefreshCatalog = onRefreshStrategyPackCatalog,
            )
        }
    }
}

private data class StrategyPackCatalogStatusContent(
    val label: String,
    val body: String,
    val tone: com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone,
)

@Composable
internal fun StrategyPackCatalogStatusCard(
    strategyPackCatalog: StrategyPackCatalogUiState,
    onRefreshCatalog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val status = rememberStrategyPackCatalogStatus(strategyPackCatalog)
    val lastFetchedAt =
        remember(strategyPackCatalog.lastFetchedAtEpochMillis) {
            strategyPackCatalog.lastFetchedAtEpochMillis?.let(::formatStrategyPackFetchedAt)
        }
    val lastRefreshAttempt =
        remember(strategyPackCatalog.lastRefreshAttemptAtEpochMillis) {
            strategyPackCatalog.lastRefreshAttemptAtEpochMillis?.let(::formatStrategyPackFetchedAt)
        }
    val downloadedBadge = stringResource(R.string.strategy_pack_badge_downloaded)
    val bundledBadge = stringResource(R.string.strategy_pack_badge_bundled)
    val selectedPackBadge =
        strategyPackCatalog.selectedPackId?.let { packId ->
            strategyPackCatalog.selectedPackVersion?.let { version ->
                stringResource(R.string.strategy_pack_badge_selected_pack, packId, version)
            } ?: stringResource(R.string.strategy_pack_badge_selected_pack_id_only, packId)
        }
    val policyBadge = stringResource(strategyPackRefreshPolicyLabelResId(strategyPackCatalog.refreshPolicy))
    val badges =
        remember(
            strategyPackCatalog.source,
            strategyPackCatalog.refreshPolicy,
            selectedPackBadge,
            downloadedBadge,
            bundledBadge,
            policyBadge,
        ) {
            buildList {
                add(
                    if (strategyPackCatalog.source == StrategyPackCatalogSourceDownloaded) {
                        downloadedBadge to SummaryCapsuleTone.Active
                    } else {
                        bundledBadge to SummaryCapsuleTone.Info
                    },
                )
                selectedPackBadge?.let { add(it to SummaryCapsuleTone.Neutral) }
                add(policyBadge to SummaryCapsuleTone.Neutral)
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Tonal,
    ) {
        StatusIndicator(label = status.label, tone = status.tone)
        Text(
            text = status.body,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(items = badges)
        StrategyPackMetadataLines(
            strategyPackCatalog = strategyPackCatalog,
            lastFetchedAt = lastFetchedAt,
            lastRefreshAttempt = lastRefreshAttempt,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            RipDpiButton(
                text =
                    if (strategyPackCatalog.isRefreshing) {
                        stringResource(R.string.strategy_pack_refresh_in_progress)
                    } else {
                        stringResource(R.string.strategy_pack_refresh_action)
                    },
                onClick = onRefreshCatalog,
                enabled = strategyPackRefreshEnabled(strategyPackCatalog),
                loading = strategyPackCatalog.isRefreshing,
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}

@Composable
private fun StrategyPackMetadataLines(
    strategyPackCatalog: StrategyPackCatalogUiState,
    lastFetchedAt: String?,
    lastRefreshAttempt: String?,
) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    strategyPackCatalog.selectedPackId?.let { packId ->
        ProfileSummaryLine(
            label = stringResource(R.string.strategy_pack_selected_pack_label),
            value =
                strategyPackCatalog.selectedPackVersion?.let { version ->
                    "$packId · $version"
                } ?: packId,
        )
    }
    ProfileSummaryLine(
        label = stringResource(R.string.strategy_pack_last_fetch_label),
        value = lastFetchedAt ?: stringResource(R.string.strategy_pack_last_fetch_never),
    )
    ProfileSummaryLine(
        label = stringResource(R.string.strategy_pack_last_refresh_attempt_label),
        value = lastRefreshAttempt ?: stringResource(R.string.strategy_pack_last_refresh_attempt_never),
    )
    strategyPackCatalog.lastAcceptedSequence?.let { acceptedSequence ->
        ProfileSummaryLine(
            label = stringResource(R.string.strategy_pack_last_accepted_sequence_label),
            value = acceptedSequence.toString(),
        )
    }
    strategyPackCatalog.lastRejectedSequence?.let { rejectedSequence ->
        ProfileSummaryLine(
            label = stringResource(R.string.strategy_pack_last_rejected_sequence_label),
            value = rejectedSequence.toString(),
        )
    }
    strategyPackCatalog.lastRefreshError
        ?.takeIf { strategyPackCatalog.lastRefreshFailureCode != null }
        ?.let { detail ->
            Text(
                text = detail,
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
    Text(
        text = stringResource(R.string.strategy_pack_refresh_source_hint),
        style = type.caption,
        color = colors.mutedForeground,
    )
}

@Composable
private fun rememberStrategyPackCatalogStatus(
    strategyPackCatalog: StrategyPackCatalogUiState,
): StrategyPackCatalogStatusContent {
    val spec = strategyPackCatalogStatusSpec(strategyPackCatalog)
    return StrategyPackCatalogStatusContent(
        label = stringResource(spec.labelResId),
        body = spec.body,
        tone = spec.tone,
    )
}
