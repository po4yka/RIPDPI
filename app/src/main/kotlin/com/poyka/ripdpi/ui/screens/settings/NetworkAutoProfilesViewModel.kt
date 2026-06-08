package com.poyka.ripdpi.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NetworkAutoProfileBinding
import com.poyka.ripdpi.data.NetworkAutoProfileBindingStore
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.displayLabel
import com.poyka.ripdpi.data.toJson
import com.poyka.ripdpi.proto.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class NetworkAutoProfilesUiState(
    val currentNetwork: CurrentNetworkBindingUiState = CurrentNetworkBindingUiState.Unavailable,
    val currentProfileName: String = "",
    val bindings: ImmutableList<NetworkAutoProfileBindingUiState> = persistentListOf(),
)

sealed interface CurrentNetworkBindingUiState {
    data object Unavailable : CurrentNetworkBindingUiState

    data class Available(
        val fingerprintHash: String,
        val label: String,
    ) : CurrentNetworkBindingUiState
}

data class NetworkAutoProfileBindingUiState(
    val id: String,
    val fingerprintHash: String,
    val networkLabel: String,
    val profileName: String,
    val updatedAt: Long,
)

@HiltViewModel
class NetworkAutoProfilesViewModel
    @Inject
    constructor(
        private val bindingStore: NetworkAutoProfileBindingStore,
        private val appSettingsRepository: AppSettingsRepository,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
    ) : ViewModel() {
        private val currentFingerprint = MutableStateFlow(captureFingerprint())

        val uiState: StateFlow<NetworkAutoProfilesUiState> =
            combine(
                currentFingerprint,
                appSettingsRepository.settings,
                bindingStore.bindings(),
            ) { fingerprint, settings, bindings ->
                NetworkAutoProfilesUiState(
                    currentNetwork = fingerprint.toCurrentNetworkUiState(),
                    currentProfileName = settings.profileLabel(),
                    bindings =
                        bindings
                            .map { binding ->
                                NetworkAutoProfileBindingUiState(
                                    id = binding.id,
                                    fingerprintHash = binding.fingerprintHash,
                                    networkLabel = binding.summary.displayLabel(),
                                    profileName = binding.profileName,
                                    updatedAt = binding.updatedAt,
                                )
                            }.toImmutableList(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = NetworkAutoProfilesUiState(),
            )

        fun refreshCurrentNetwork() {
            currentFingerprint.value = captureFingerprint()
        }

        fun bindCurrentProfileToCurrentNetwork() {
            val fingerprint = currentFingerprint.value ?: return
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                val existing = bindingStore.find(fingerprint.scopeKey())
                val settings = appSettingsRepository.snapshot()
                bindingStore.upsert(
                    NetworkAutoProfileBinding(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        fingerprintHash = fingerprint.scopeKey(),
                        summary = fingerprint.summary(),
                        profileName = settings.profileLabel(),
                        settingsJson = settings.toJson(),
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }
        }

        fun deleteBinding(id: String) {
            viewModelScope.launch {
                bindingStore.delete(id)
            }
        }

        private fun captureFingerprint(): NetworkFingerprint? =
            runCatching { networkFingerprintProvider.capture() }.getOrNull()
    }

private fun NetworkFingerprint?.toCurrentNetworkUiState(): CurrentNetworkBindingUiState =
    this?.let {
        CurrentNetworkBindingUiState.Available(
            fingerprintHash = it.scopeKey(),
            label = it.summary().displayLabel(),
        )
    } ?: CurrentNetworkBindingUiState.Unavailable

private fun AppSettings.profileLabel(): String {
    val draft = toConfigDraft()
    return listOf(
        draft.mode.name,
        draft.chainSummary,
        draft.relaySummary,
    ).filter(String::isNotBlank).joinToString(separator = " · ")
}
