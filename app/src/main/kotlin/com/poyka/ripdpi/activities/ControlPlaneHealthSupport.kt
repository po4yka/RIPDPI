package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ControlPlaneCacheDegradationCode
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toImmutableList

internal fun StringResolver.buildControlPlaneHealthSummary(
    hostPackCatalog: HostPackCatalogUiState,
    strategyPackRuntimeState: StrategyPackRuntimeState,
): ControlPlaneHealthSummaryUiModel? {
    val items =
        listOfNotNull(
            buildHostPackHealthSummaryItem(hostPackCatalog),
            buildStrategyPackHealthSummaryItem(strategyPackRuntimeState),
        )
    if (items.isEmpty()) {
        return null
    }

    val severity =
        when {
            items.any { it.severity == ControlPlaneHealthSeverityUiModel.Error } -> {
                ControlPlaneHealthSeverityUiModel.Error
            }

            items.any { it.severity == ControlPlaneHealthSeverityUiModel.Warning } -> {
                ControlPlaneHealthSeverityUiModel.Warning
            }

            else -> {
                ControlPlaneHealthSeverityUiModel.Info
            }
        }

    return ControlPlaneHealthSummaryUiModel(
        title = getString(R.string.home_control_plane_health_title),
        summary = getString(R.string.home_control_plane_health_body),
        items = items.toImmutableList(),
        actionLabel = getString(R.string.home_control_plane_health_action),
        severity = severity,
    )
}

private fun StringResolver.buildHostPackHealthSummaryItem(
    hostPackCatalog: HostPackCatalogUiState,
): ControlPlaneHealthItemUiModel? {
    val summary =
        when {
            hostPackCatalog.cacheDegradationCode != null -> {
                getString(R.string.home_control_plane_host_pack_degraded)
            }

            hostPackCatalog.lastRefreshFailureCode == HostPackRefreshFailureCodeUiModel.VerificationFailed -> {
                getString(R.string.home_control_plane_host_pack_verification_failed)
            }

            hostPackCatalog.lastRefreshFailureCode == HostPackRefreshFailureCodeUiModel.InvalidSnapshot -> {
                getString(R.string.home_control_plane_host_pack_invalid_snapshot)
            }

            hostPackCatalog.lastRefreshFailureCode == HostPackRefreshFailureCodeUiModel.DownloadFailed -> {
                getString(R.string.home_control_plane_host_pack_download_failed)
            }

            else -> {
                null
            }
        } ?: return null

    val severity =
        when {
            hostPackCatalog.lastRefreshFailureCode == HostPackRefreshFailureCodeUiModel.VerificationFailed -> {
                ControlPlaneHealthSeverityUiModel.Error
            }

            else -> {
                ControlPlaneHealthSeverityUiModel.Warning
            }
        }

    return ControlPlaneHealthItemUiModel(
        label = getString(R.string.home_control_plane_host_packs_label),
        summary = summary,
        severity = severity,
    )
}

private fun StringResolver.buildStrategyPackHealthSummaryItem(
    runtimeState: StrategyPackRuntimeState,
): ControlPlaneHealthItemUiModel? {
    val uiState = StrategyPackCatalogUiState(runtimeState = runtimeState)
    val summary =
        when {
            uiState.cacheDegradationCode == ControlPlaneCacheDegradationCode.CachedSnapshotUnreadable -> {
                getString(R.string.home_control_plane_strategy_pack_degraded)
            }

            uiState.cacheDegradationCode == ControlPlaneCacheDegradationCode.CachedSnapshotIncompatible -> {
                getString(R.string.home_control_plane_strategy_pack_incompatible)
            }

            uiState.lastRefreshFailureCode == StrategyPackHealthFailureCodeUiModel.RollbackRejected -> {
                getString(
                    R.string.home_control_plane_strategy_pack_rollback_rejected,
                    uiState.lastRejectedSequence ?: 0L,
                    uiState.lastAcceptedSequence ?: 0L,
                )
            }

            uiState.lastRefreshFailureCode == StrategyPackHealthFailureCodeUiModel.StaleCatalog -> {
                getString(R.string.home_control_plane_strategy_pack_stale)
            }

            uiState.lastRefreshFailureCode == StrategyPackHealthFailureCodeUiModel.MissingSecurityMetadata ||
                uiState.lastRefreshFailureCode == StrategyPackHealthFailureCodeUiModel.InvalidIssuedAt
            -> {
                getString(R.string.home_control_plane_strategy_pack_security_rejected)
            }

            uiState.lastRefreshFailureCode == StrategyPackHealthFailureCodeUiModel.VerificationFailed -> {
                getString(R.string.home_control_plane_strategy_pack_verification_failed)
            }

            uiState.lastRefreshFailureCode == StrategyPackHealthFailureCodeUiModel.CompatibilityRejected -> {
                getString(R.string.home_control_plane_strategy_pack_incompatible)
            }

            uiState.lastRefreshFailureCode == StrategyPackHealthFailureCodeUiModel.DownloadFailed -> {
                getString(R.string.home_control_plane_strategy_pack_download_failed)
            }

            else -> {
                null
            }
        } ?: return null

    val severity =
        when (uiState.lastRefreshFailureCode) {
            StrategyPackHealthFailureCodeUiModel.RollbackRejected,
            StrategyPackHealthFailureCodeUiModel.StaleCatalog,
            StrategyPackHealthFailureCodeUiModel.CompatibilityRejected,
            -> {
                ControlPlaneHealthSeverityUiModel.Warning
            }

            StrategyPackHealthFailureCodeUiModel.MissingSecurityMetadata,
            StrategyPackHealthFailureCodeUiModel.InvalidIssuedAt,
            StrategyPackHealthFailureCodeUiModel.VerificationFailed,
            -> {
                ControlPlaneHealthSeverityUiModel.Error
            }

            StrategyPackHealthFailureCodeUiModel.DownloadFailed,
            null,
            -> {
                if (uiState.cacheDegradationCode != null) {
                    ControlPlaneHealthSeverityUiModel.Warning
                } else {
                    ControlPlaneHealthSeverityUiModel.Warning
                }
            }
        }

    return ControlPlaneHealthItemUiModel(
        label = getString(R.string.home_control_plane_strategy_packs_label),
        summary = summary,
        severity = severity,
    )
}
