package com.poyka.ripdpi.ui.screens.profiles

import com.poyka.ripdpi.data.ProfileUtilityReference
import kotlinx.collections.immutable.ImmutableList

internal data class ProfileUtilityUiState(
    val catalogGeneration: Long,
    val profiles: ImmutableList<ProfileUtilityItem>,
    val probeUrl: String,
    val catalogState: ProfileCatalogState,
    val canMeasure: Boolean,
    val cleanupPending: Boolean,
    val failure: ProfileUtilityFailure?,
) {
    val loading: Boolean get() = catalogState == ProfileCatalogState.Loading
}

internal enum class ProfileCatalogState { Loading, Ready, Failed }

internal data class ProfileUtilityItem(
    val reference: ProfileUtilityReference,
    val label: String,
    val groupLabel: String?,
    val kind: String,
    val favorite: Boolean,
    val applied: Boolean,
    val lastUsedAtMillis: Long?,
    val recentSequence: Long?,
    val measurement: ProfileMeasurementUiState,
)

internal sealed interface ProfileMeasurementUiState {
    data object NotChecked : ProfileMeasurementUiState

    data object Checking : ProfileMeasurementUiState

    data class Measured(
        val latencyMillis: Long,
        val observedAtMillis: Long,
    ) : ProfileMeasurementUiState {
        init {
            require(latencyMillis >= 0 && observedAtMillis > 0)
        }
    }

    data class Failed(
        val reason: ProfileUtilityFailure,
    ) : ProfileMeasurementUiState
}

internal enum class ProfileUtilityFailure {
    Persistence,
    EnvironmentChanged,
    Unsupported,
    Busy,
    HttpFailed,
    TimedOut,
    CleanupPending,
    SelectionFailed,
}
