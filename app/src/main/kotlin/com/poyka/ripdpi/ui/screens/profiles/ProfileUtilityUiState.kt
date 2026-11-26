package com.poyka.ripdpi.ui.screens.profiles

import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import com.poyka.ripdpi.data.awg.isConfExportable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

internal data class ProfileUtilityUiState(
    val catalogGeneration: Long,
    val profiles: ImmutableList<ProfileUtilityItem>,
    val probeUrl: String,
    val catalogState: ProfileCatalogState,
    val canMeasure: Boolean,
    val cleanupPending: Boolean,
    val failure: ProfileUtilityFailure?,
    /** Selector groups with a failover policy, mapped to whether their member is chosen automatically. */
    val automaticGroups: ImmutableMap<String, Boolean> = persistentMapOf(),
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
    val quality: ProfileQualityUiState? = null,
)

/**
 * A saved AmneziaWG profile. It opens in its editor; its [measurement] comes from a handshake check, not the relay
 * HTTP flow. [needsPrivateKey] marks a subscription row delivered without its key.
 */
data class AwgProfileItem(
    val id: String,
    val name: String,
    val needsPrivateKey: Boolean,
    val measurement: ProfileMeasurementUiState = ProfileMeasurementUiState.NotChecked,
    val shareable: Boolean = false,
)

internal fun SavedAwgProfile.toAwgProfileItem(
    measurement: ProfileMeasurementUiState = ProfileMeasurementUiState.NotChecked,
): AwgProfileItem =
    AwgProfileItem(
        id,
        name,
        needsPrivateKey = request.privateKey.isBlank(),
        measurement,
        shareable = !subscriptionOwned && request.isConfExportable,
    )

sealed interface ProfileMeasurementUiState {
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

enum class ProfileUtilityFailure {
    Persistence,
    EnvironmentChanged,
    Unsupported,
    Busy,
    HttpFailed,
    TimedOut,
    CleanupPending,
    SelectionFailed,
}
