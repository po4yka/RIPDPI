package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** UI state for the subscription-add import-confirmation screen. */
data class SubscriptionImportConfirmUiState(
    val url: String = "",
    val name: String = "",
    val bootstrap: Boolean = false,
    val importing: Boolean = false,
    val imported: Boolean = false,
)

/**
 * Backing [ViewModel] for the subscription-add import-confirmation destination.
 *
 * This is the import-confirmation surface, not a full subscription editor: it shows the
 * pre-filled URL + name (and a bootstrap badge when the deep link's URL path was
 * `/bootstrap/...`) and an "Add" action that persists a [ProxyGroupType.SUBSCRIPTION]
 * group via [ProxyGroupRepository]. The user confirms here before any network request is
 * made. The handler activity navigates here after parsing a
 * `singbox://import-remote-profile?url=…` deep link.
 */
@HiltViewModel
class SubscriptionImportConfirmViewModel
    @Inject
    constructor(
        private val repository: ProxyGroupRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SubscriptionImportConfirmUiState())
        val uiState: StateFlow<SubscriptionImportConfirmUiState> = _uiState.asStateFlow()

        /** Seeds the screen with the deep link's pre-filled fields. */
        fun setRequest(
            url: String,
            name: String,
            bootstrap: Boolean,
        ) {
            _uiState.update {
                it.copy(url = url, name = name, bootstrap = bootstrap)
            }
        }

        /**
         * Persists the pending subscription into a new [ProxyGroupType.SUBSCRIPTION]
         * group. When no display name was supplied the URL host is used instead. No
         * network request is made here — the subscription is stored for the existing
         * update pipeline to refresh. No-op when the URL is blank or an import is already
         * in flight.
         *
         * The persisted [Subscription.kind] reflects the bootstrap flag: a bootstrap
         * import is stored as [SubscriptionKind.BOOTSTRAP] so the auto-update worker
         * skips it and the UI can mark it as a one-time token; everything else is a
         * refetchable [SubscriptionKind.LONG_LIVED] subscription.
         */
        fun confirm() {
            val state = _uiState.value
            if (state.url.isBlank()) return
            if (state.importing || state.imported) return
            _uiState.update { it.copy(importing = true) }
            viewModelScope.launch {
                val groupId = UUID.randomUUID().toString()
                val groupName = state.name.takeIf { it.isNotBlank() } ?: hostOf(state.url)
                val kind =
                    if (state.bootstrap) SubscriptionKind.BOOTSTRAP else SubscriptionKind.LONG_LIVED
                repository.add(
                    ProxyGroup(
                        id = groupId,
                        name = groupName,
                        type = ProxyGroupType.SUBSCRIPTION,
                        order = repository.list().size,
                        isSelector = false,
                        subscription = Subscription(link = state.url, kind = kind),
                    ),
                )
                _uiState.update { it.copy(importing = false, imported = true) }
            }
        }

        /** Extracts the host from [url] for use as a fallback group name. */
        private fun hostOf(url: String): String {
            val schemeEnd = url.indexOf("://")
            val afterScheme = if (schemeEnd >= 0) url.substring(schemeEnd + "://".length) else url
            return afterScheme
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore(':')
                .takeIf { it.isNotBlank() } ?: url
        }
    }
