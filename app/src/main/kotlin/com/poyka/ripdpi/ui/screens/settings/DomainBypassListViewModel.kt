package com.poyka.ripdpi.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.rules.DomainBypassList
import com.poyka.ripdpi.data.rules.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Persisted state of the domain bypass list. [initialText] is the editor text reconstructed from
 * the stored managed rule; the screen owns the live (unsaved) edit buffer.
 */
data class DomainBypassListUiState(
    val initialText: String = "",
    val hasRule: Boolean = false,
)

/**
 * Backs the "Domain bypass list" screen (`add-custom-domain-bypass-list-screen`). Persistence and
 * compilation live in [RuleRepository] / [DomainBypassList]; this ViewModel is deliberately thin so
 * live per-line validation can run in the composable without a round trip.
 */
@HiltViewModel
class DomainBypassListViewModel
    @Inject
    constructor(
        private val ruleRepository: RuleRepository,
    ) : ViewModel() {
        val uiState: StateFlow<DomainBypassListUiState> =
            ruleRepository
                .domainBypassRule()
                .map { rule ->
                    DomainBypassListUiState(
                        initialText = DomainBypassList.toEditorText(rule),
                        hasRule = rule != null,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = DomainBypassListUiState(),
                )

        /** Compiles and persists [text] as the single managed bypass rule (or deletes it when empty). */
        fun save(
            text: String,
            onSaved: (savedCount: Int) -> Unit,
        ) {
            viewModelScope.launch {
                val result = ruleRepository.saveDomainBypassList(text)
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

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
