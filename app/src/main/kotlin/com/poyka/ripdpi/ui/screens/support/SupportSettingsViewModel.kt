package com.poyka.ripdpi.ui.screens.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.support.SupportSettingsApplyResult
import com.poyka.ripdpi.data.support.SupportSettingsApplyUseCase
import com.poyka.ripdpi.data.support.SupportSettingsFieldChange
import com.poyka.ripdpi.data.support.SupportSettingsPreview
import com.poyka.ripdpi.data.support.SupportSettingsPreviewResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class SupportSettingsUiState(
    val packageJson: String = "",
    val loading: Boolean = false,
    val applying: Boolean = false,
    val preview: SupportSettingsPreview? = null,
    val changes: ImmutableList<SupportSettingsFieldChange> = persistentListOf(),
    val invalid: Boolean = false,
    val storageError: Boolean = false,
)

@HiltViewModel
class SupportSettingsViewModel
    @Inject
    constructor(
        private val applyUseCase: SupportSettingsApplyUseCase,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SupportSettingsUiState())
        val uiState: StateFlow<SupportSettingsUiState> = _uiState.asStateFlow()
        private val appliedEventChannel = Channel<Unit>(capacity = Channel.BUFFERED)
        val appliedEvents: Flow<Unit> = appliedEventChannel.receiveAsFlow()
        private var completed = false

        fun setPackage(packageJson: String) {
            if (_uiState.value.packageJson == packageJson && !_uiState.value.storageError) return
            completed = false
            _uiState.update { SupportSettingsUiState(packageJson = packageJson, loading = true) }
            viewModelScope.launch {
                val result =
                    try {
                        applyUseCase.preview(packageJson)
                    } catch (_: IOException) {
                        _uiState.update { it.copy(loading = false, storageError = true) }
                        return@launch
                    }
                when (result) {
                    is SupportSettingsPreviewResult.Invalid -> {
                        _uiState.update { it.copy(loading = false, invalid = true) }
                    }

                    is SupportSettingsPreviewResult.Ready -> {
                        _uiState.update {
                            it.copy(
                                loading = false,
                                preview = result.preview,
                                changes = result.preview.changes.toImmutableList(),
                                invalid = false,
                            )
                        }
                    }
                }
            }
        }

        fun apply() {
            val state = _uiState.value
            if (!state.canApply || completed) return
            _uiState.update { it.copy(applying = true, storageError = false) }
            viewModelScope.launch {
                val result =
                    try {
                        applyUseCase.apply(state.packageJson)
                    } catch (_: IOException) {
                        _uiState.update { it.copy(applying = false, storageError = true) }
                        return@launch
                    }
                when (result) {
                    is SupportSettingsApplyResult.Invalid -> {
                        _uiState.update { it.copy(applying = false, invalid = true) }
                    }

                    is SupportSettingsApplyResult.Success -> {
                        _uiState.update {
                            it.copy(
                                applying = false,
                                changes = result.changes.toImmutableList(),
                            )
                        }
                        completed = true
                        appliedEventChannel.send(Unit)
                    }
                }
            }
        }
    }

private val SupportSettingsUiState.canApply: Boolean
    get() = packageJson.isNotBlank() && preview != null && !loading && !applying && !invalid
