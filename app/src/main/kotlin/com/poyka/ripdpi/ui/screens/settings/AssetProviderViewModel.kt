package com.poyka.ripdpi.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.assets.GeoAssetRepository
import com.poyka.ripdpi.assets.GeoAssetUpdateResult
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.assets.DefaultAssetProviderId
import com.poyka.ripdpi.data.assets.GeoAssetKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How stale the on-disk geo databases are, surfaced as a passive informational line. */
sealed interface GeoAssetStaleness {
    data object Never : GeoAssetStaleness

    data object Today : GeoAssetStaleness

    data class DaysAgo(
        val days: Long,
    ) : GeoAssetStaleness
}

/** Persisted + transient state of the geo asset provider picker. */
data class AssetProviderUiState(
    val providerId: String = DefaultAssetProviderId,
    val customBaseUrl: String = "",
    val geoipTag: String = "",
    val geositeTag: String = "",
    val staleness: GeoAssetStaleness = GeoAssetStaleness.Never,
    val checking: Boolean = false,
    val lastResult: AssetProviderCheckOutcome? = null,
)

/** Terminal outcome of a "Check for updates" run, surfaced inline as a banner. */
sealed interface AssetProviderCheckOutcome {
    data class Updated(
        val geoipTag: String?,
        val geositeTag: String?,
    ) : AssetProviderCheckOutcome

    data object UpToDate : AssetProviderCheckOutcome

    data class Failed(
        val message: String,
    ) : AssetProviderCheckOutcome

    data object Imported : AssetProviderCheckOutcome
}

@HiltViewModel
class AssetProviderViewModel
    @Inject
    constructor(
        private val settingsRepository: AppSettingsRepository,
        private val geoAssetRepository: GeoAssetRepository,
    ) : ViewModel() {
        private val transient = MutableStateFlow(TransientState())

        val uiState: StateFlow<AssetProviderUiState> =
            combinePersistedAndTransient()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = AssetProviderUiState(),
                )

        fun selectProvider(providerId: String) {
            viewModelScope.launch {
                settingsRepository.update { geoAssetProviderId = providerId }
            }
        }

        fun updateCustomBaseUrl(url: String) {
            viewModelScope.launch {
                settingsRepository.update { geoAssetCustomBaseUrl = url.trim() }
            }
        }

        fun checkForUpdates() {
            if (transient.value.checking) {
                return
            }
            transient.update { it.copy(checking = true, lastResult = null) }
            viewModelScope.launch {
                val outcome =
                    runCatching { geoAssetRepository.checkAndUpdate() }
                        .fold(
                            onSuccess = ::mapResult,
                            onFailure = { error ->
                                if (error is CancellationException) throw error
                                AssetProviderCheckOutcome.Failed(error.localizedMessage ?: error.toString())
                            },
                        )
                transient.update { it.copy(checking = false, lastResult = outcome) }
            }
        }

        fun importLocalAsset(
            kind: GeoAssetKind,
            uri: Uri,
        ) {
            transient.update { it.copy(lastResult = null) }
            viewModelScope.launch {
                val outcome =
                    runCatching { geoAssetRepository.importLocalAsset(kind, uri) }
                        .fold(
                            onSuccess = { AssetProviderCheckOutcome.Imported },
                            onFailure = { error ->
                                if (error is CancellationException) throw error
                                AssetProviderCheckOutcome.Failed(error.localizedMessage ?: error.toString())
                            },
                        )
                transient.update { it.copy(lastResult = outcome) }
            }
        }

        fun dismissResult() {
            transient.update { it.copy(lastResult = null) }
        }

        private fun combinePersistedAndTransient() =
            kotlinx.coroutines.flow
                .combine(settingsRepository.settings, transient.asStateFlow()) { settings, t ->
                    AssetProviderUiState(
                        providerId = settings.geoAssetProviderId.ifEmpty { DefaultAssetProviderId },
                        customBaseUrl = settings.geoAssetCustomBaseUrl,
                        geoipTag = settings.geoAssetGeoipVersionTag,
                        geositeTag = settings.geoAssetGeositeVersionTag,
                        staleness = computeStaleness(settings.geoAssetLastUpdatedEpochMillis),
                        checking = t.checking,
                        lastResult = t.lastResult,
                    )
                }

        private fun computeStaleness(lastUpdatedEpochMillis: Long): GeoAssetStaleness {
            if (lastUpdatedEpochMillis <= 0L) {
                return GeoAssetStaleness.Never
            }
            val days = (System.currentTimeMillis() - lastUpdatedEpochMillis) / MILLIS_PER_DAY
            return if (days <= 0L) GeoAssetStaleness.Today else GeoAssetStaleness.DaysAgo(days)
        }

        private fun mapResult(result: GeoAssetUpdateResult): AssetProviderCheckOutcome =
            if (result.updatedAny) {
                AssetProviderCheckOutcome.Updated(result.geoipTag, result.geositeTag)
            } else {
                AssetProviderCheckOutcome.UpToDate
            }

        private data class TransientState(
            val checking: Boolean = false,
            val lastResult: AssetProviderCheckOutcome? = null,
        )

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val MILLIS_PER_DAY = 86_400_000L
        }
    }
