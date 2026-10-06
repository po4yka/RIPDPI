package com.poyka.ripdpi.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess
import com.poyka.ripdpi.data.ProfileUtilityCatalogReader
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.services.CandidateRelayMeasurements
import com.poyka.ripdpi.services.CandidateXrayMeasurements
import com.poyka.ripdpi.services.MeasuredProfileActivationFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
internal class ProfileUtilityViewModel
    @Inject
    constructor(
        catalog: ProfileUtilityCatalogReader,
        private val authority: PauseIntentAuthority,
        private val recovery: ProfileMutationRecoveryAccess,
        applied: AppliedRuntimeConfigurationSource,
        private val measurement: ProfileUtilityMeasurementCoordinator,
        private val selection: com.poyka.ripdpi.services.MeasuredProfileActivationCoordinator,
        private val native: CandidateRelayMeasurements,
        private val xray: CandidateXrayMeasurements,
    ) : ViewModel() {
        private val failure = MutableStateFlow<ProfileUtilityFailure?>(null)
        private val results = MutableStateFlow<Map<ProfileUtilityReference, ProfileMeasurementUiState>>(emptyMap())
        private val url = MutableStateFlow("")
        private var operation: Job? = null

        @Volatile private var operationToken: Any = Any()
        private val committedCatalog =
            catalog
                .observe()
                .map<com.poyka.ripdpi.data.ProfileUtilityCatalog, CatalogObservation> { CatalogObservation.Ready(it) }
                .catch {
                    if (it is CancellationException) throw it
                    failure.value = ProfileUtilityFailure.Persistence
                    emit(CatalogObservation.Failed)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogObservation.Loading)
        private val cleanup = combine(native.cleanupPending, xray.cleanupPending) { n, x -> n || x }
        private val command = combine(results, url, failure) { r, u, f -> CommandState(r, u, f) }
        val uiState =
            combine(
                committedCatalog,
                authority.states,
                applied.applications,
                cleanup,
                command,
            ) { observation, a, applications, pending, work ->
                val c = (observation as? CatalogObservation.Ready)?.catalog
                ProfileUtilityUiState(
                    catalogGeneration = c?.generation ?: 0,
                    profiles =
                        if (c != null &&
                            a != null
                        ) {
                            profileUtilityItems(c, a.profileUtility, applications, work.results)
                        } else {
                            persistentListOf()
                        },
                    probeUrl = work.url,
                    catalogState =
                        when (observation) {
                            CatalogObservation.Loading -> ProfileCatalogState.Loading
                            CatalogObservation.Failed -> ProfileCatalogState.Failed
                            is CatalogObservation.Ready -> ProfileCatalogState.Ready
                        },
                    canMeasure = work.url.startsWith("https://") || work.url.startsWith("http://"),
                    cleanupPending = pending,
                    failure =
                        if (observation ==
                            CatalogObservation.Failed
                        ) {
                            ProfileUtilityFailure.Persistence
                        } else {
                            work.failure
                        },
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ProfileUtilityUiState(0, persistentListOf(), "", ProfileCatalogState.Loading, false, false, null),
            )

        fun updateUrl(value: String) {
            if (value != url.value) cancel()
            url.value = value
        }

        fun favorite(
            reference: ProfileUtilityReference,
            generation: Long,
            value: Boolean,
        ) = command {
            recovery.readRecovered {
                authority.intentLinearizer.serialize {
                    val utility = checkNotNull(authority.states.value).profileUtility
                    check(utility.catalogReady && utility.catalogGeneration == generation) { "Catalog changed" }
                    authority.profileUtility.setFavorite(reference, value)
                }
            }
        }

        fun check(reference: ProfileUtilityReference) = measure(reference, select = false)

        fun checkAndSelect(reference: ProfileUtilityReference) = measure(reference, select = true)

        fun checkAndSelectFastest() {
            cancel()
            val token = operationToken
            val catalog = (committedCatalog.value as? CatalogObservation.Ready)?.catalog
            val original = authority.states.value?.let { authority.snapshotAuthority() }
            val capturedUrl = url.value
            val scope = measurement.captureOperationScope()
            operation =
                command {
                    if (catalog == null || original == null) {
                        failure.value = ProfileUtilityFailure.Persistence
                    } else {
                        val current: suspend () -> Boolean = {
                            val scopeCurrent = scope()
                            val utility = authority.states.value?.profileUtility
                            scopeCurrent && token === operationToken && authority.snapshotAuthority() == original &&
                                utility?.catalogReady == true && utility.catalogGeneration == catalog.generation
                        }
                        try {
                            val rejected =
                                ProfileFastestSelectionOperation(measurement, selection).run(
                                    catalog.entries.map { it.reference },
                                    capturedUrl,
                                    current,
                                ) { reference, value -> publishMeasurement(token, reference, value) }
                            if (token === operationToken) failure.value = rejected
                        } finally {
                            clearChecking(token)
                        }
                    }
                }
        }

        fun cancel() {
            operationToken = Any()
            operation?.cancel()
            operation = null
            results.update { current -> current.filterValues { it != ProfileMeasurementUiState.Checking } }
        }

        fun retryCleanup() =
            command {
                native.retryCleanup()
                xray.retryCleanup()
            }

        private fun measure(
            reference: ProfileUtilityReference,
            select: Boolean,
        ) {
            cancel()
            val capturedUrl = url.value
            val token = operationToken
            operation =
                command {
                    publishMeasurement(token, reference, ProfileMeasurementUiState.Checking)
                    try {
                        when (val checked = measurement.check(reference, capturedUrl)) {
                            is ProfileUtilityCheckResult.Failed -> {
                                publishMeasurement(token, reference, ProfileMeasurementUiState.Failed(checked.reason))
                            }

                            is ProfileUtilityCheckResult.Measured -> {
                                if (token !== operationToken) return@command
                                val rejected =
                                    if (select) {
                                        selection.select(checked.lease)?.let { domain ->
                                            when (domain) {
                                                MeasuredProfileActivationFailure.EnvironmentChanged -> {
                                                    ProfileUtilityFailure.EnvironmentChanged
                                                }

                                                MeasuredProfileActivationFailure.SelectionFailed -> {
                                                    ProfileUtilityFailure.SelectionFailed
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                publishMeasurement(
                                    token,
                                    reference,
                                    if (rejected == null) {
                                        ProfileMeasurementUiState.Measured(
                                            checked.latencyMillis,
                                            checked.observedAtMillis,
                                        )
                                    } else {
                                        ProfileMeasurementUiState.Failed(rejected)
                                    },
                                )
                            }
                        }
                    } finally {
                        results.update { current ->
                            if (token === operationToken && current[reference] == ProfileMeasurementUiState.Checking) {
                                current - reference
                            } else {
                                current
                            }
                        }
                    }
                }
        }

        private fun clearChecking(token: Any) {
            results.update { current ->
                if (token === operationToken) {
                    current.filterValues { it != ProfileMeasurementUiState.Checking }
                } else {
                    current
                }
            }
        }

        private sealed interface CatalogObservation {
            data object Loading : CatalogObservation

            data object Failed : CatalogObservation

            class Ready(
                val catalog: com.poyka.ripdpi.data.ProfileUtilityCatalog,
            ) : CatalogObservation
        }

        private fun publishMeasurement(
            token: Any,
            reference: ProfileUtilityReference,
            value: ProfileMeasurementUiState,
        ) {
            results.update { current -> if (token === operationToken) current + (reference to value) else current }
        }

        private fun command(block: suspend () -> Unit) =
            viewModelScope.launch(Dispatchers.IO) {
                failure.value = null
                try {
                    block()
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    _: IllegalStateException,
                ) {
                    failure.value = ProfileUtilityFailure.Persistence
                } catch (
                    _: IllegalArgumentException,
                ) {
                    failure.value = ProfileUtilityFailure.Persistence
                } catch (
                    _: ArithmeticException,
                ) {
                    failure.value = ProfileUtilityFailure.Persistence
                } catch (
                    _: IOException,
                ) {
                    failure.value = ProfileUtilityFailure.Persistence
                }
            }

        private class CommandState(
            val results: Map<ProfileUtilityReference, ProfileMeasurementUiState>,
            val url: String,
            val failure: ProfileUtilityFailure?,
        )
    }
