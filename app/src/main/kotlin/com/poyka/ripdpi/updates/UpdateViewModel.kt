package com.poyka.ripdpi.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel
    @Inject
    constructor(
        private val updateManager: AppUpdateManager,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                UpdateUiState(channelInfo = updateManager.channelInfo),
            )
        private val _effects =
            MutableSharedFlow<UpdateEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()
        val effects: SharedFlow<UpdateEffect> = _effects.asSharedFlow()

        fun checkForUpdates() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(status = UpdateUiStatus.Checking)
                when (val result = updateManager.checkForUpdate()) {
                    UpdateCheckResult.ExternalAuthority -> {
                        _uiState.value = _uiState.value.copy(status = UpdateUiStatus.ExternalAuthority)
                    }

                    UpdateCheckResult.NoUpdate -> {
                        _uiState.value = _uiState.value.copy(status = UpdateUiStatus.NoUpdate)
                    }

                    is UpdateCheckResult.Available -> {
                        _uiState.value =
                            _uiState.value.copy(
                                status = UpdateUiStatus.Idle,
                                availableUpdate = result.update,
                            )
                    }

                    is UpdateCheckResult.Failed -> {
                        _uiState.value = _uiState.value.copy(status = UpdateUiStatus.Failed(result.reason))
                    }
                }
            }
        }

        fun installAvailableUpdate() {
            val update = _uiState.value.availableUpdate
            if (update == null) {
                _uiState.value = _uiState.value.copy(status = UpdateUiStatus.Failed(UpdateFailureReason.NotAvailable))
                return
            }

            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(status = UpdateUiStatus.Downloading)
                when (val result = updateManager.install(update)) {
                    UpdateInstallResult.ExternalAuthority -> {
                        _uiState.value = _uiState.value.copy(status = UpdateUiStatus.ExternalAuthority)
                    }

                    is UpdateInstallResult.Failed -> {
                        _uiState.value = _uiState.value.copy(status = UpdateUiStatus.Failed(result.reason))
                    }

                    is UpdateInstallResult.PermissionRequired -> {
                        _uiState.value = _uiState.value.copy(status = UpdateUiStatus.PermissionRequired)
                        _effects.tryEmit(UpdateEffect.OpenIntent(result.intent))
                    }

                    UpdateInstallResult.Started -> {
                        _uiState.value = _uiState.value.copy(status = UpdateUiStatus.InstallerStarted)
                    }
                }
            }
        }
    }
