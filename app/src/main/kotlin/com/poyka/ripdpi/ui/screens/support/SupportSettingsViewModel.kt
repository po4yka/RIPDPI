package com.poyka.ripdpi.ui.screens.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.support.SupportSettingsApplyResult
import com.poyka.ripdpi.data.support.SupportSettingsApplyUseCase
import com.poyka.ripdpi.data.support.SupportSettingsFieldChange
import com.poyka.ripdpi.data.support.SupportSettingsPreview
import com.poyka.ripdpi.data.support.SupportSettingsPreviewResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SupportSettingsUiState(
    val packageJson: String = "",
    val loading: Boolean = false,
    val applying: Boolean = false,
    val applied: Boolean = false,
    val preview: SupportSettingsPreview? = null,
    val changes: List<SupportSettingsFieldChange> = emptyList(),
    val invalid: Boolean = false,
)

@HiltViewModel
class SupportSettingsViewModel
    @Inject
    constructor(
        private val applyUseCase: SupportSettingsApplyUseCase,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SupportSettingsUiState())
        val uiState: StateFlow<SupportSettingsUiState> = _uiState.asStateFlow()

        fun setPackage(packageJson: String) {
            if (_uiState.value.packageJson == packageJson) return
            _uiState.update { SupportSettingsUiState(packageJson = packageJson, loading = true) }
            viewModelScope.launch {
                when (val result = applyUseCase.preview(packageJson)) {
                    is SupportSettingsPreviewResult.Invalid -> {
                        _uiState.update { it.copy(loading = false, invalid = true) }
                    }

                    is SupportSettingsPreviewResult.Ready -> {
                        _uiState.update {
                            it.copy(
                                loading = false,
                                preview = result.preview,
                                changes = result.preview.changes,
                                invalid = false,
                            )
                        }
                    }
                }
            }
        }

        fun apply() {
            val state = _uiState.value
            if (state.packageJson.isBlank() || state.loading || state.applying || state.applied || state.invalid) return
            _uiState.update { it.copy(applying = true) }
            viewModelScope.launch {
                when (val result = applyUseCase.apply(state.packageJson)) {
                    is SupportSettingsApplyResult.Invalid -> {
                        _uiState.update { it.copy(applying = false, invalid = true) }
                    }

                    is SupportSettingsApplyResult.Success -> {
                        _uiState.update {
                            it.copy(
                                applying = false,
                                applied = true,
                                changes = result.changes,
                            )
                        }
                    }
                }
            }
        }
    }
