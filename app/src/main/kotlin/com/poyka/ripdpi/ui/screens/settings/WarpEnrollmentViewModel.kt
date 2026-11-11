package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.services.WarpProfileSetupService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class WarpSetupOperation { Registration, Import }

enum class WarpSetupFailure { Registration, Import }

@Immutable
data class WarpEnrollmentUiState(
    val operation: WarpSetupOperation? = null,
    val cancellationRequested: Boolean = false,
    val failure: WarpSetupFailure? = null,
    val saved: Boolean = false,
) {
    val busy: Boolean get() = operation != null
}

@HiltViewModel
class WarpEnrollmentViewModel
    @Inject
    constructor(
        private val setup: WarpProfileSetupService,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(WarpEnrollmentUiState())
        val state = mutableState.asStateFlow()
        private var activeJob: Job? = null

        fun register(displayName: String) =
            runSetup(WarpSetupOperation.Registration) { setup.registerConsumerFree(displayName) }

        fun importProfile(readDocument: suspend () -> String?) =
            runSetup(WarpSetupOperation.Import) {
                val document = readDocument() ?: throw IllegalArgumentException("Could not read WARP profile")
                setup.importZeroTrustProfile(document)
            }

        fun cancel() {
            val job = activeJob ?: return
            if (!job.isCompleted) {
                mutableState.update { it.copy(cancellationRequested = true) }
                job.cancel()
            }
        }

        private fun runSetup(
            operation: WarpSetupOperation,
            action: suspend () -> Unit,
        ) {
            if (activeJob?.isCompleted == false) return
            mutableState.value = WarpEnrollmentUiState(operation = operation)
            activeJob =
                viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        action()
                        currentCoroutineContext().ensureActive()
                        mutableState.update { it.copy(saved = true) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        val failure =
                            when (operation) {
                                WarpSetupOperation.Registration -> WarpSetupFailure.Registration
                                WarpSetupOperation.Import -> WarpSetupFailure.Import
                            }
                        mutableState.update { it.copy(failure = failure) }
                    } finally {
                        mutableState.update { it.copy(operation = null, cancellationRequested = false) }
                    }
                }
        }
    }
