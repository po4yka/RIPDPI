package com.poyka.ripdpi.updates

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

        // One check or install at a time: a check that ends during a download would re-enable Install, and two
        // downloads share one temporary APK file.
        private var operation: Job? = null

        private fun launchOperation(block: suspend () -> Unit) {
            if (operation?.isActive == true) return
            operation = viewModelScope.launch { block() }
        }

        fun checkForUpdates() {
            launchOperation {
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

        fun installPermissionSettings(): Intent? {
            if (_uiState.value.status != UpdateUiStatus.PermissionRequired) return null
            return updateManager.installPermissionSettings().also { intent ->
                if (intent == null) setIdle()
            }
        }

        fun onResume() {
            when (_uiState.value.status) {
                UpdateUiStatus.PermissionRequired -> installPermissionSettings()
                UpdateUiStatus.InstallerStarted -> setIdle()
                else -> Unit
            }
        }

        private fun setIdle() {
            _uiState.value = _uiState.value.copy(status = UpdateUiStatus.Idle)
        }

        fun installAvailableUpdate() {
            val update = _uiState.value.availableUpdate
            if (update == null) {
                _uiState.value = _uiState.value.copy(status = UpdateUiStatus.Failed(UpdateFailureReason.NotAvailable))
                return
            }

            launchOperation {
                _uiState.value = _uiState.value.copy(status = UpdateUiStatus.Downloading)
                when (val result = updateManager.install(update)) {
                    UpdateInstallResult.ExternalAuthority -> {
                        _uiState.value = _uiState.value.copy(status = UpdateUiStatus.ExternalAuthority)
                    }

                    is UpdateInstallResult.Failed -> {
                        _uiState.value = _uiState.value.copy(status = UpdateUiStatus.Failed(result.reason))
                    }

                    UpdateInstallResult.PermissionRequired -> {
                        _uiState.value = _uiState.value.copy(status = UpdateUiStatus.PermissionRequired)
                    }

                    UpdateInstallResult.Started -> {
                        _uiState.value = _uiState.value.copy(status = UpdateUiStatus.InstallerStarted)
                    }
                }
            }
        }
    }
