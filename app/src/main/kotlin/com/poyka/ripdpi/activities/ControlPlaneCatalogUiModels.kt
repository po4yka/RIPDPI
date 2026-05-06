package com.poyka.ripdpi.activities

import androidx.compose.runtime.Stable
import com.poyka.ripdpi.data.ControlPlaneCacheDegradationCode
import com.poyka.ripdpi.data.HostPackCatalogSnapshot
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.StrategyPackRefreshFailureCode
import com.poyka.ripdpi.data.StrategyPackRuntimeState

@Stable
enum class HostPackRefreshFailureCodeUiModel {
    VerificationFailed,
    InvalidSnapshot,
    DownloadFailed,
}

@Stable
data class HostPackCatalogUiState(
    val snapshot: HostPackCatalogSnapshot = HostPackCatalogSnapshot(),
    val isRefreshing: Boolean = false,
    val cacheDegradationCode: ControlPlaneCacheDegradationCode? = null,
    val cacheDegradationDetail: String? = null,
    val lastRefreshAttemptAtEpochMillis: Long? = null,
    val lastRefreshFailureCode: HostPackRefreshFailureCodeUiModel? = null,
    val lastRefreshFailureMessage: String? = null,
) {
    val presets: List<HostPackPreset>
        get() = snapshot.packs
}

enum class StrategyPackHealthFailureCodeUiModel {
    RollbackRejected,
    StaleCatalog,
    MissingSecurityMetadata,
    InvalidIssuedAt,
    VerificationFailed,
    CompatibilityRejected,
    DownloadFailed,
}

@Stable
data class StrategyPackCatalogUiState(
    val runtimeState: StrategyPackRuntimeState = StrategyPackRuntimeState(),
    val isRefreshing: Boolean = false,
) {
    val snapshot
        get() = runtimeState.snapshot

    val packs
        get() = runtimeState.snapshot.packs

    val source: String
        get() = runtimeState.snapshot.source

    val selectedPackId: String?
        get() = runtimeState.selectedPackId

    val selectedPackVersion: String?
        get() = runtimeState.selectedPackVersion

    val lastFetchedAtEpochMillis: Long?
        get() = runtimeState.snapshot.lastFetchedAtEpochMillis

    val lastRefreshAttemptAtEpochMillis: Long?
        get() = runtimeState.lastRefreshAttemptAtEpochMillis

    val lastRefreshError: String?
        get() = runtimeState.lastRefreshError

    val lastRefreshFailureCode: StrategyPackHealthFailureCodeUiModel?
        get() = runtimeState.toStrategyPackHealthFailureCode()

    val lastAcceptedSequence: Long?
        get() = runtimeState.lastAcceptedSequence

    val lastRejectedSequence: Long?
        get() = runtimeState.lastRejectedSequence

    val cacheDegradationCode: ControlPlaneCacheDegradationCode?
        get() = runtimeState.cacheDegradationCode

    val cacheDegradationDetail: String?
        get() = runtimeState.cacheDegradationDetail

    val refreshPolicy: String
        get() = runtimeState.refreshPolicy
}

private fun StrategyPackRuntimeState.toStrategyPackHealthFailureCode(): StrategyPackHealthFailureCodeUiModel? =
    when (lastRefreshFailureCode) {
        StrategyPackRefreshFailureCode.RollbackRejected -> {
            StrategyPackHealthFailureCodeUiModel.RollbackRejected
        }

        StrategyPackRefreshFailureCode.StaleCatalog -> {
            StrategyPackHealthFailureCodeUiModel.StaleCatalog
        }

        StrategyPackRefreshFailureCode.MissingSecurityMetadata -> {
            StrategyPackHealthFailureCodeUiModel.MissingSecurityMetadata
        }

        StrategyPackRefreshFailureCode.InvalidIssuedAt -> {
            StrategyPackHealthFailureCodeUiModel.InvalidIssuedAt
        }

        null -> {
            val refreshError = lastRefreshError
            when {
                refreshError == null -> {
                    null
                }

                refreshError.contains("checksum", ignoreCase = true) ||
                    refreshError.contains("signature", ignoreCase = true) ||
                    refreshError.contains("trusted key", ignoreCase = true) ||
                    refreshError.contains("signing key", ignoreCase = true)
                -> {
                    StrategyPackHealthFailureCodeUiModel.VerificationFailed
                }

                refreshError.contains("requires", ignoreCase = true) ||
                    refreshError.contains("unsupported strategy pack schema", ignoreCase = true) ||
                    refreshError.contains("incompatible", ignoreCase = true)
                -> {
                    StrategyPackHealthFailureCodeUiModel.CompatibilityRejected
                }

                else -> {
                    StrategyPackHealthFailureCodeUiModel.DownloadFailed
                }
            }
        }
    }
