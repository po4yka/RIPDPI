package com.poyka.ripdpi.ui.screens.settings

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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Persisted + transient state of the geo asset provider picker. */
data class AssetProviderUiState(
    val providerId: String = DefaultAssetProviderId,
    val customBaseUrl: String = "",
    val geoipTag: String = "",
    val geositeTag: String = "",
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
            bytes: ByteArray,
        ) {
            transient.update { it.copy(lastResult = null) }
            viewModelScope.launch {
                val outcome =
                    runCatching { geoAssetRepository.importLocalAsset(kind, bytes) }
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
                        checking = t.checking,
                        lastResult = t.lastResult,
                    )
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
        }
    }
