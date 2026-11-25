package com.poyka.ripdpi.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import com.poyka.ripdpi.services.AwgEndpointCheck
import com.poyka.ripdpi.services.AwgEndpointCheckResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class SavedAwgProfilesViewModel
    @Inject
    constructor(
        repository: AwgProfileRepository,
        private val endpointCheck: AwgEndpointCheck,
    ) : ViewModel() {
        private val checks = MutableStateFlow<Map<String, ProfileMeasurementUiState>>(emptyMap())

        private val saved: StateFlow<List<SavedAwgProfile>> =
            repository
                .observeProfiles()
                .catch {
                    if (it is CancellationException) throw it
                    // Like the relay catalog: a failed recovery or read leaves the picker usable without AWG rows.
                    emit(emptyList())
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val profiles: StateFlow<ImmutableList<AwgProfileItem>> =
            combine(saved, checks) { profiles, results ->
                profiles
                    .map { it.toAwgProfileItem(results[it.id] ?: ProfileMeasurementUiState.NotChecked) }
                    .toImmutableList()
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

        fun check(id: String) {
            val profile = saved.value.firstOrNull { it.id == id } ?: return
            if (checks.value[id] == ProfileMeasurementUiState.Checking) return
            checks.update { it + (id to ProfileMeasurementUiState.Checking) }
            viewModelScope.launch {
                val result = endpointCheck.check(profile.request).toMeasurement(System.currentTimeMillis())
                checks.update { it + (id to result) }
            }
        }
    }

internal fun AwgEndpointCheckResult.toMeasurement(nowMillis: Long): ProfileMeasurementUiState =
    when (this) {
        is AwgEndpointCheckResult.Answered -> {
            ProfileMeasurementUiState.Measured(handshakeMillis, nowMillis)
        }

        AwgEndpointCheckResult.NoAnswer -> {
            ProfileMeasurementUiState.Failed(ProfileUtilityFailure.TimedOut)
        }

        AwgEndpointCheckResult.Unsupported -> {
            ProfileMeasurementUiState.Failed(ProfileUtilityFailure.Unsupported)
        }

        AwgEndpointCheckResult.EnvironmentChanged -> {
            ProfileMeasurementUiState.Failed(ProfileUtilityFailure.EnvironmentChanged)
        }
    }
