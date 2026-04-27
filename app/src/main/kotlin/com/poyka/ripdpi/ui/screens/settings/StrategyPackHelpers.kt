package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.StrategyPackCatalogUiState
import com.poyka.ripdpi.activities.StrategyPackHealthFailureCodeUiModel
import com.poyka.ripdpi.data.ControlPlaneCacheDegradationCode
import com.poyka.ripdpi.data.StrategyPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.StrategyPackRefreshPolicyAutomatic
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun strategyPackRefreshEnabled(strategyPackCatalog: StrategyPackCatalogUiState): Boolean =
    !strategyPackCatalog.isRefreshing

internal data class StrategyPackCatalogStatusSpec(
    val labelResId: Int,
    val bodyResId: Int,
    val bodyArgs: List<Long> = emptyList(),
    val tone: StatusIndicatorTone,
)

@Suppress("CyclomaticComplexMethod")
internal fun strategyPackCatalogStatusSpec(
    strategyPackCatalog: StrategyPackCatalogUiState,
): StrategyPackCatalogStatusSpec =
    strategyPackRefreshingOrDegradedSpec(strategyPackCatalog)
        ?: strategyPackFailureCodeSpec(strategyPackCatalog)
        ?: strategyPackSourceSpec(strategyPackCatalog)

private fun strategyPackRefreshingOrDegradedSpec(catalog: StrategyPackCatalogUiState): StrategyPackCatalogStatusSpec? =
    when {
        catalog.isRefreshing -> {
            StrategyPackCatalogStatusSpec(
                labelResId = R.string.strategy_pack_refresh_status_title,
                bodyResId = R.string.strategy_pack_refresh_status_body,
                tone = StatusIndicatorTone.Active,
            )
        }

        catalog.cacheDegradationCode == ControlPlaneCacheDegradationCode.CachedSnapshotUnreadable -> {
            StrategyPackCatalogStatusSpec(
                labelResId = R.string.strategy_pack_degraded_status_title,
                bodyResId = R.string.strategy_pack_degraded_status_body_unreadable,
                tone = StatusIndicatorTone.Warning,
            )
        }

        catalog.cacheDegradationCode == ControlPlaneCacheDegradationCode.CachedSnapshotIncompatible -> {
            StrategyPackCatalogStatusSpec(
                labelResId = R.string.strategy_pack_degraded_status_title,
                bodyResId = R.string.strategy_pack_degraded_status_body_incompatible,
                tone = StatusIndicatorTone.Warning,
            )
        }

        else -> {
            null
        }
    }

private fun strategyPackFailureCodeSpec(catalog: StrategyPackCatalogUiState): StrategyPackCatalogStatusSpec? =
    when (catalog.lastRefreshFailureCode) {
        StrategyPackHealthFailureCodeUiModel.RollbackRejected -> {
            StrategyPackCatalogStatusSpec(
                labelResId = R.string.strategy_pack_rollback_rejected_status_title,
                bodyResId = R.string.strategy_pack_rollback_rejected_status_body,
                bodyArgs =
                    listOf(
                        catalog.lastRejectedSequence ?: 0L,
                        catalog.lastAcceptedSequence ?: 0L,
                    ),
                tone = StatusIndicatorTone.Warning,
            )
        }

        StrategyPackHealthFailureCodeUiModel.StaleCatalog -> {
            StrategyPackCatalogStatusSpec(
                labelResId = R.string.strategy_pack_stale_status_title,
                bodyResId = R.string.strategy_pack_stale_status_body,
                tone = StatusIndicatorTone.Warning,
            )
        }

        StrategyPackHealthFailureCodeUiModel.MissingSecurityMetadata -> {
            StrategyPackCatalogStatusSpec(
                labelResId = R.string.strategy_pack_security_rejected_status_title,
                bodyResId = R.string.strategy_pack_security_rejected_status_body_missing_metadata,
                tone = StatusIndicatorTone.Error,
            )
        }

        StrategyPackHealthFailureCodeUiModel.InvalidIssuedAt -> {
            StrategyPackCatalogStatusSpec(
                labelResId = R.string.strategy_pack_security_rejected_status_title,
                bodyResId = R.string.strategy_pack_security_rejected_status_body_invalid_issued_at,
                tone = StatusIndicatorTone.Error,
            )
        }

        StrategyPackHealthFailureCodeUiModel.VerificationFailed -> {
            StrategyPackCatalogStatusSpec(
                labelResId = R.string.strategy_pack_verification_failed_status_title,
                bodyResId = R.string.strategy_pack_verification_failed_status_body,
                tone = StatusIndicatorTone.Error,
            )
        }

        StrategyPackHealthFailureCodeUiModel.CompatibilityRejected -> {
            StrategyPackCatalogStatusSpec(
                labelResId = R.string.strategy_pack_compatibility_failed_status_title,
                bodyResId = R.string.strategy_pack_compatibility_failed_status_body,
                tone = StatusIndicatorTone.Warning,
            )
        }

        StrategyPackHealthFailureCodeUiModel.DownloadFailed -> {
            StrategyPackCatalogStatusSpec(
                labelResId = R.string.strategy_pack_download_failed_status_title,
                bodyResId = R.string.strategy_pack_download_failed_status_body,
                tone = StatusIndicatorTone.Warning,
            )
        }

        null -> {
            null
        }
    }

private fun strategyPackSourceSpec(catalog: StrategyPackCatalogUiState): StrategyPackCatalogStatusSpec =
    if (catalog.source == StrategyPackCatalogSourceDownloaded) {
        StrategyPackCatalogStatusSpec(
            labelResId = R.string.strategy_pack_downloaded_status_title,
            bodyResId = R.string.strategy_pack_downloaded_status_body,
            tone = StatusIndicatorTone.Active,
        )
    } else {
        StrategyPackCatalogStatusSpec(
            labelResId = R.string.strategy_pack_bundled_status_title,
            bodyResId = R.string.strategy_pack_bundled_status_body,
            tone = StatusIndicatorTone.Idle,
        )
    }

internal fun strategyPackRefreshPolicyLabelResId(policy: String): Int =
    if (policy == StrategyPackRefreshPolicyAutomatic) {
        R.string.strategy_pack_badge_policy_automatic
    } else {
        R.string.strategy_pack_badge_policy_manual
    }

private val strategyPackTimestampFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)

internal fun formatStrategyPackFetchedAt(timestampMillis: Long): String =
    strategyPackTimestampFormatter.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))
