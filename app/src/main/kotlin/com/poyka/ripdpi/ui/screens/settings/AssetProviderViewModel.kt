package com.poyka.ripdpi.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.assets.GeoAssetIntegrityException
import com.poyka.ripdpi.assets.GeoAssetIntegrityFailure
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
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
    val activeOperation: AssetProviderOperation? = null,
    val lastResult: AssetProviderCheckOutcome? = null,
)

enum class AssetProviderOperation {
    CheckUpdates,
    ImportGeoip,
    ImportGeosite,
}

enum class AssetProviderFailureReason {
    UnableToOpen,
    InvalidPayload,
    TooLarge,
    Storage,
    Network,
    Unexpected,
}

/** Terminal outcome of a "Check for updates" run, surfaced inline as a banner. */
sealed interface AssetProviderCheckOutcome {
    data class Updated(
        val geoipTag: String?,
        val geositeTag: String?,
    ) : AssetProviderCheckOutcome

    data object UpToDate : AssetProviderCheckOutcome

    data class Failed(
        val reason: AssetProviderFailureReason,
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
        private val configurationOperationMutex = Mutex()

        val uiState: StateFlow<AssetProviderUiState> =
            combinePersistedAndTransient()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = AssetProviderUiState(),
                )

        fun selectProvider(providerId: String) {
            if (!acceptConfigurationDraft { it.copy(providerIdDraft = providerId) }) return
            viewModelScope.launch {
                configurationOperationMutex.withLock {
                    persistLatestConfigurationDrafts()
                }
            }
        }

        fun updateCustomBaseUrl(url: String) {
            if (!acceptConfigurationDraft { it.copy(customBaseUrlDraft = url) }) return
            viewModelScope.launch {
                configurationOperationMutex.withLock {
                    persistLatestConfigurationDrafts()
                }
            }
        }

        fun checkForUpdates() {
            launchOperation(AssetProviderOperation.CheckUpdates) {
                persistLatestConfigurationDrafts()
                mapResult(geoAssetRepository.checkAndUpdate())
            }
        }

        fun importLocalAsset(
            kind: GeoAssetKind,
            uri: Uri,
        ) {
            val operation =
                when (kind) {
                    GeoAssetKind.Geoip -> AssetProviderOperation.ImportGeoip
                    GeoAssetKind.Geosite -> AssetProviderOperation.ImportGeosite
                }
            launchOperation(operation) {
                geoAssetRepository.importLocalAsset(kind, uri)
                AssetProviderCheckOutcome.Imported
            }
        }

        fun dismissResult() {
            transient.update { it.copy(lastResult = null) }
        }

        private fun combinePersistedAndTransient() =
            kotlinx.coroutines.flow
                .combine(settingsRepository.settings, transient.asStateFlow()) { settings, t ->
                    AssetProviderUiState(
                        providerId =
                            t.providerIdDraft
                                ?: settings.geoAssetProviderId.ifEmpty { DefaultAssetProviderId },
                        customBaseUrl = t.customBaseUrlDraft ?: settings.geoAssetCustomBaseUrl,
                        geoipTag = settings.geoAssetGeoipVersionTag,
                        geositeTag = settings.geoAssetGeositeVersionTag,
                        staleness = computeStaleness(settings.geoAssetLastUpdatedEpochMillis),
                        activeOperation = t.activeOperation,
                        lastResult = t.lastResult,
                    )
                }

        private fun acceptConfigurationDraft(transform: (TransientState) -> TransientState): Boolean {
            while (true) {
                val current = transient.value
                if (current.activeOperation != null) return false
                if (transient.compareAndSet(current, transform(current))) return true
            }
        }

        private suspend fun persistLatestConfigurationDrafts() {
            val current = transient.value
            if (current.providerIdDraft == null && current.customBaseUrlDraft == null) return
            settingsRepository.update {
                current.providerIdDraft?.let { geoAssetProviderId = it }
                current.customBaseUrlDraft?.let { geoAssetCustomBaseUrl = it.trim() }
            }
        }

        private fun launchOperation(
            operation: AssetProviderOperation,
            block: suspend () -> AssetProviderCheckOutcome,
        ) {
            if (!claimOperation(operation)) return
            viewModelScope.launch {
                var outcome: AssetProviderCheckOutcome? = null
                try {
                    configurationOperationMutex.withLock {
                        outcome =
                            runCatching { block() }.fold(
                                onSuccess = { it },
                                onFailure = { error ->
                                    when (error) {
                                        is CancellationException -> throw error
                                        is Exception -> AssetProviderCheckOutcome.Failed(mapFailure(error))
                                        else -> throw error
                                    }
                                },
                            )
                    }
                } finally {
                    transient.update { current ->
                        if (current.activeOperation == operation) {
                            current.copy(activeOperation = null, lastResult = outcome)
                        } else {
                            current
                        }
                    }
                }
            }
        }

        private fun claimOperation(operation: AssetProviderOperation): Boolean {
            while (true) {
                val current = transient.value
                if (current.activeOperation != null) return false
                if (
                    transient.compareAndSet(
                        current,
                        current.copy(activeOperation = operation, lastResult = null),
                    )
                ) {
                    return true
                }
            }
        }

        private fun mapFailure(error: Exception): AssetProviderFailureReason =
            when (error) {
                is GeoAssetIntegrityException -> {
                    when (error.reason) {
                        GeoAssetIntegrityFailure.UnableToOpen -> AssetProviderFailureReason.UnableToOpen
                        GeoAssetIntegrityFailure.InvalidPayload -> AssetProviderFailureReason.InvalidPayload
                        GeoAssetIntegrityFailure.TooLarge -> AssetProviderFailureReason.TooLarge
                        GeoAssetIntegrityFailure.InstallFailed -> AssetProviderFailureReason.Storage
                    }
                }

                is IOException -> {
                    AssetProviderFailureReason.Network
                }

                else -> {
                    AssetProviderFailureReason.Unexpected
                }
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
            val providerIdDraft: String? = null,
            val customBaseUrlDraft: String? = null,
            val activeOperation: AssetProviderOperation? = null,
            val lastResult: AssetProviderCheckOutcome? = null,
        )

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val MILLIS_PER_DAY = 86_400_000L
        }
    }
