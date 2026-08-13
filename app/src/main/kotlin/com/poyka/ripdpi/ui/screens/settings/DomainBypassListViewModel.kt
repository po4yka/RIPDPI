package com.poyka.ripdpi.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.rules.DomainBypassList
import com.poyka.ripdpi.data.rules.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Persisted rule status plus the retained, unsaved editor draft.
 */
data class DomainBypassListUiState(
    val text: String = "",
    val hasRule: Boolean = false,
    val isDraftInitialized: Boolean = false,
    val replacementRevision: Long = 0,
)

/**
 * Backs the "Domain bypass list" screen (`add-custom-domain-bypass-list-screen`). Persistence and
 * compilation live in [RuleRepository] / [DomainBypassList]. The edit buffer stays in this retained
 * state holder so an Activity recreation cannot discard unsaved user input.
 */
@HiltViewModel
class DomainBypassListViewModel
    @Inject
    constructor(
        private val ruleRepository: RuleRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(DomainBypassListUiState())
        val uiState: StateFlow<DomainBypassListUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                ruleRepository.domainBypassRule().collect { rule ->
                    _uiState.update { current ->
                        if (current.isDraftInitialized) {
                            current.copy(hasRule = rule != null)
                        } else {
                            current.copy(
                                text = boundedDomainBypassDraft(DomainBypassList.toEditorText(rule)),
                                hasRule = rule != null,
                                isDraftInitialized = true,
                                replacementRevision = current.replacementRevision + 1,
                            )
                        }
                    }
                }
            }
        }

        fun updateDraft(text: String) {
            _uiState.update {
                it.copy(
                    text = boundedDomainBypassDraft(text),
                    isDraftInitialized = true,
                )
            }
        }

        fun clearDraft() {
            replaceDraft("")
        }

        fun replaceDraft(text: String) {
            _uiState.update {
                it.copy(
                    text = boundedDomainBypassDraft(text),
                    isDraftInitialized = true,
                    replacementRevision = it.replacementRevision + 1,
                )
            }
        }

        /** Compiles and persists the retained draft as the single managed bypass rule. */
        fun save(onSaved: (savedCount: Int) -> Unit) {
            viewModelScope.launch {
                val result = ruleRepository.saveDomainBypassList(_uiState.value.text)
                onSaved(result.cleanLines.size)
            }
        }

        /** Promotes the managed bypass rule into a normal editable rule named [displayName]. */
        fun moveToRuleEditor(
            displayName: String,
            onResult: (moved: Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                onResult(ruleRepository.moveDomainBypassListToEditor(displayName))
            }
        }
    }
