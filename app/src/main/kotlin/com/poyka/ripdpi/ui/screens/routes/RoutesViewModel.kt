package com.poyka.ripdpi.ui.screens.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.data.rules.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One row in the rule list: the persisted [rule] plus its resolved outbound [outboundLabel]. */
data class RuleRow(
    val rule: RuleEntity,
    val outboundLabel: String,
)

/** Render state for the routing-rule list screen. */
data class RoutesUiState(
    val rows: ImmutableList<RuleRow> = persistentListOf(),
)

/**
 * Backs the routing-rule list screen. Combines [RuleRepository.userRules] (the managed domain-bypass
 * rule is already excluded) with the live group/profile lists so each row can show a resolved
 * outbound label via [OutboundTargetCatalog.labelFor].
 *
 * First-match-wins ordering equals list order equals [RuleEntity.userOrder]; reorder persists
 * immediately.
 */
@HiltViewModel
class RoutesViewModel
    @Inject
    constructor(
        private val ruleRepository: RuleRepository,
        private val proxyGroupRepository: ProxyGroupRepository,
        private val relayProfileStore: RelayProfileStore,
        private val outboundTargetCatalog: OutboundTargetCatalog,
    ) : ViewModel() {
        // The relay profile list is a one-shot suspend read with no Flow, so it is loaded once and
        // re-read on demand; group changes flow reactively through proxyGroupRepository.groups().
        private val profiles = MutableStateFlow<List<RelayProfileRecord>>(emptyList())

        // Emits when a reorder write fails so the screen can roll its optimistic order back to the
        // persisted state. extraBufferCapacity keeps the emit non-suspending from the launched job.
        private val _reorderFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val reorderFailures: SharedFlow<Unit> = _reorderFailures.asSharedFlow()

        val uiState: StateFlow<RoutesUiState> =
            combine(
                ruleRepository.userRules(),
                proxyGroupRepository.groups(),
                profiles,
            ) { rules, groups, relayProfiles ->
                RoutesUiState(
                    rows =
                        rules
                            .map { rule ->
                                RuleRow(
                                    rule = rule,
                                    outboundLabel =
                                        outboundTargetCatalog.labelFor(
                                            tag = rule.outboundTag,
                                            groups = groups,
                                            profiles = relayProfiles,
                                        ),
                                )
                            }.toImmutableList(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = RoutesUiState(),
            )

        init {
            viewModelScope.launch {
                profiles.value = withContext(Dispatchers.IO) { relayProfileStore.list() }
            }
        }

        fun toggleEnabled(rule: RuleEntity) {
            viewModelScope.launch {
                ruleRepository.update(rule.copy(enabled = !rule.enabled))
            }
        }

        fun delete(rule: RuleEntity) {
            viewModelScope.launch {
                ruleRepository.delete(rule)
            }
        }

        fun reorder(orderedIds: List<Long>) {
            viewModelScope.launch {
                runCatching { ruleRepository.reorder(orderedIds) }
                    .onFailure { _reorderFailures.tryEmit(Unit) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

/**
 * Structured, locale-independent counts for a rule row. The [RoutesScreen] composable layer turns
 * these into a localized one-line summary via `pluralStringResource` / `stringResource` so no English
 * fragments are assembled here. A zero count means the matcher is absent and is omitted from the line.
 */
data class RuleSummaryCounts(
    val domains: Int,
    val ips: Int,
    val ports: Int,
    val sourcePorts: Int,
    val hasProcess: Boolean,
    val apps: Int,
    val network: RuleNetwork,
)

/** Extracts the structured matcher counts for a rule row; formatting happens in the composable layer. */
internal fun ruleSummaryCounts(rule: RuleEntity): RuleSummaryCounts =
    RuleSummaryCounts(
        domains = rule.domains.lines().count { it.isNotBlank() },
        ips = rule.ipCidrs.lines().count { it.isNotBlank() },
        ports = rule.ports.lines().count { it.isNotBlank() },
        sourcePorts = rule.sourcePorts.lines().count { it.isNotBlank() },
        hasProcess = rule.processName.isNotBlank(),
        apps = rule.packages.size,
        network = rule.network,
    )

/**
 * True when every matcher field on [rule] is empty — such a rule cannot be saved (the outbound
 * action is not a matcher, so it is intentionally excluded from this check).
 */
internal fun RuleEntity.hasNoMatchers(): Boolean =
    domains.isBlank() &&
        ipCidrs.isBlank() &&
        ports.isBlank() &&
        sourcePorts.isBlank() &&
        processName.isBlank() &&
        packages.isEmpty()
