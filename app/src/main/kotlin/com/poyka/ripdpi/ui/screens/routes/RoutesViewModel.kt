package com.poyka.ripdpi.ui.screens.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.data.rules.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val rows: List<RuleRow> = emptyList(),
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

        val uiState: StateFlow<RoutesUiState> =
            combine(
                ruleRepository.userRules(),
                proxyGroupRepository.groups(),
                profiles,
            ) { rules, groups, relayProfiles ->
                RoutesUiState(
                    rows =
                        rules.map { rule ->
                            RuleRow(
                                rule = rule,
                                outboundLabel =
                                    outboundTargetCatalog.labelFor(
                                        tag = rule.outboundTag,
                                        groups = groups,
                                        profiles = relayProfiles,
                                    ),
                            )
                        },
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
                ruleRepository.reorder(orderedIds)
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

/** Builds a one-line summary string for a rule row, e.g. "3 domains · TCP · → Bypass". */
internal fun ruleSummaryParts(rule: RuleEntity): List<String> =
    buildList {
        val domains = rule.domains.lines().count { it.isNotBlank() }
        val ips = rule.ipCidrs.lines().count { it.isNotBlank() }
        val ports = rule.ports.lines().count { it.isNotBlank() }
        val sourcePorts = rule.sourcePorts.lines().count { it.isNotBlank() }
        if (domains > 0) add("$domains domains")
        if (ips > 0) add("$ips IPs")
        if (ports > 0) add("$ports ports")
        if (sourcePorts > 0) add("$sourcePorts src ports")
        if (rule.processName.isNotBlank()) add("process")
        if (rule.packages.isNotEmpty()) add("${rule.packages.size} apps")
        add(
            when (rule.network) {
                RuleNetwork.TCP -> "TCP"
                RuleNetwork.UDP -> "UDP"
                RuleNetwork.BOTH -> "TCP+UDP"
            },
        )
    }

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
