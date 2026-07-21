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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    val hasPersistenceError: Boolean = false,
    val isExitPersistenceInProgress: Boolean = false,
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
    MissingConfiguration,
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
        private val configurationPersistence =
            AssetProviderConfigurationPersistence(
                scope = viewModelScope,
                settingsRepository = settingsRepository,
                onPersistenceResult = ::handlePersistenceResult,
            )

        val uiState: StateFlow<AssetProviderUiState> =
            combinePersistedAndTransient()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = AssetProviderUiState(),
                )

        fun selectProvider(providerId: String) {
            val draft = acceptConfigurationDraft { it.copy(providerIdDraft = providerId) } ?: return
            configurationPersistence.persistBestEffort(draft)
        }

        fun updateCustomBaseUrl(url: String) {
            val draft = acceptConfigurationDraft { it.copy(customBaseUrlDraft = url) } ?: return
            configurationPersistence.persistBestEffort(draft)
        }

        fun checkForUpdates() {
            launchOperation(AssetProviderOperation.CheckUpdates) {
                configurationPersistence.persistAndAwait(latestConfigurationDraft())
                mapResult(geoAssetRepository.checkAndUpdate())
            }
        }

        fun retryConfigurationPersistence() {
            configurationPersistence.persistBestEffort(latestConfigurationDraft())
        }

        suspend fun persistConfigurationBeforeExit(): Boolean {
            if (!claimExitPersistence()) return false
            return try {
                configurationPersistence.persistAndAwait(latestConfigurationDraft())
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: AssetProviderConfigurationPersistenceException) {
                false
            } finally {
                transient.update { it.copy(isExitPersistenceInProgress = false) }
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
                        hasPersistenceError = t.hasPersistenceError,
                        isExitPersistenceInProgress = t.isExitPersistenceInProgress,
                    )
                }

        private fun acceptConfigurationDraft(
            transform: (TransientState) -> TransientState,
        ): AssetProviderConfigurationDraft? {
            while (true) {
                val current = transient.value
                if (current.activeOperation != null || current.isExitPersistenceInProgress) return null
                val updated =
                    transform(current).copy(
                        configurationSequence = current.configurationSequence + 1,
                    )
                if (transient.compareAndSet(current, updated)) return updated.toConfigurationDraft()
            }
        }

        private fun claimExitPersistence(): Boolean {
            while (true) {
                val current = transient.value
                if (current.isExitPersistenceInProgress) return false
                if (transient.compareAndSet(current, current.copy(isExitPersistenceInProgress = true))) return true
            }
        }

        private fun latestConfigurationDraft(): AssetProviderConfigurationDraft = transient.value.toConfigurationDraft()

        private fun handlePersistenceResult(
            sequence: Long,
            error: Exception?,
        ) {
            transient.update { current ->
                if (sequence < current.configurationSequence) {
                    current
                } else {
                    current.copy(hasPersistenceError = error != null)
                }
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
                if (current.activeOperation != null || current.isExitPersistenceInProgress) return false
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
                is AssetProviderConfigurationPersistenceException -> {
                    AssetProviderFailureReason.Storage
                }

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
            when {
                !result.anyChecked -> {
                    AssetProviderCheckOutcome.Failed(AssetProviderFailureReason.MissingConfiguration)
                }

                result.updatedAny -> {
                    AssetProviderCheckOutcome.Updated(result.geoipTag, result.geositeTag)
                }

                else -> {
                    AssetProviderCheckOutcome.UpToDate
                }
            }

        private data class TransientState(
            val providerIdDraft: String? = null,
            val customBaseUrlDraft: String? = null,
            val configurationSequence: Long = 0L,
            val hasPersistenceError: Boolean = false,
            val isExitPersistenceInProgress: Boolean = false,
            val activeOperation: AssetProviderOperation? = null,
            val lastResult: AssetProviderCheckOutcome? = null,
        ) {
            fun toConfigurationDraft(): AssetProviderConfigurationDraft =
                AssetProviderConfigurationDraft(
                    sequence = configurationSequence,
                    providerId = providerIdDraft,
                    customBaseUrl = customBaseUrlDraft,
                )
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val MILLIS_PER_DAY = 86_400_000L
        }
    }

private data class AssetProviderConfigurationDraft(
    val sequence: Long,
    val providerId: String?,
    val customBaseUrl: String?,
) {
    val isEmpty: Boolean get() = providerId == null && customBaseUrl == null
}

private class AssetProviderConfigurationPersistenceException(
    cause: Exception,
) : Exception(cause)

private class AssetProviderConfigurationPersistence(
    scope: CoroutineScope,
    private val settingsRepository: AppSettingsRepository,
    private val onPersistenceResult: (Long, Exception?) -> Unit,
) {
    private val commands = Channel<AssetProviderConfigurationDraft>(Channel.CONFLATED)
    private val persistenceMutex = Mutex()
    private var latestSequence = 0L
    private var acknowledgedThroughSequence = 0L

    init {
        scope.launch {
            for (command in commands) {
                persistWithRetry(command, throwOnFailure = false)
            }
        }
    }

    fun persistBestEffort(draft: AssetProviderConfigurationDraft) {
        if (draft.isEmpty) return
        latestSequence = maxOf(latestSequence, draft.sequence)
        commands.trySend(draft)
    }

    suspend fun persistAndAwait(draft: AssetProviderConfigurationDraft) {
        if (draft.isEmpty || draft.sequence <= acknowledgedThroughSequence) return
        latestSequence = maxOf(latestSequence, draft.sequence)
        persistWithRetry(draft, throwOnFailure = true)
    }

    private suspend fun persistWithRetry(
        draft: AssetProviderConfigurationDraft,
        throwOnFailure: Boolean,
    ) {
        var attempt = 0
        while (draft.sequence > acknowledgedThroughSequence) {
            if (!throwOnFailure && draft.sequence < latestSequence) return
            val failure = runCatching { persistOnce(draft) }.exceptionOrNull()
            when (failure) {
                null -> {
                    onPersistenceResult(draft.sequence, null)
                    return
                }

                is CancellationException -> {
                    throw failure
                }

                is Exception -> {
                    onPersistenceResult(draft.sequence, failure)
                    attempt += 1
                    if (attempt >= AutomaticPersistenceAttempts) {
                        if (throwOnFailure) throw AssetProviderConfigurationPersistenceException(failure)
                        return
                    }
                    if (attempt >= ImmediatePersistenceAttempts) delay(PersistenceRetryDelayMillis)
                }

                else -> {
                    throw failure
                }
            }
        }
    }

    private suspend fun persistOnce(draft: AssetProviderConfigurationDraft) {
        persistenceMutex.withLock {
            if (draft.sequence <= acknowledgedThroughSequence) return
            settingsRepository.update {
                draft.providerId?.let { geoAssetProviderId = it }
                draft.customBaseUrl?.let { geoAssetCustomBaseUrl = it.trim() }
            }
            acknowledgedThroughSequence = maxOf(acknowledgedThroughSequence, draft.sequence)
        }
    }

    private companion object {
        const val ImmediatePersistenceAttempts = 2
        const val AutomaticPersistenceAttempts = 3
        const val PersistenceRetryDelayMillis = 1_000L
    }
}
