package com.poyka.ripdpi.ui.screens.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.data.rules.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One installed app shown in the app picker. */
data class InstalledAppItem(
    val packageName: String,
    val label: String,
)

/** Editable, in-memory form state for a single routing rule. */
data class RuleEditorUiState(
    val ruleId: Long = 0L,
    val userOrder: Int = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val domains: String = "",
    val ipCidrs: String = "",
    val ports: String = "",
    val sourcePorts: String = "",
    val network: RuleNetwork = RuleNetwork.BOTH,
    val processName: String = "",
    val packages: ImmutableSet<String> = persistentSetOf(),
    val outboundTag: OutboundTag = OutboundTag.Proxy,
    val outboundTargets: ImmutableList<OutboundTarget> = persistentListOf(),
    val installedApps: ImmutableList<InstalledAppItem> = persistentListOf(),
    val loaded: Boolean = false,
    val textFieldReplacementRevision: Long = 0,
) {
    /**
     * True when every matcher field is empty. An empty rule cannot be saved even when it has a name
     * and a chosen outbound, because it would match nothing.
     */
    val isEmpty: Boolean
        get() =
            domains.isBlank() &&
                ipCidrs.isBlank() &&
                ports.isBlank() &&
                sourcePorts.isBlank() &&
                processName.isBlank() &&
                packages.isEmpty()

    /** Label for the currently selected outbound, resolved against [outboundTargets]. */
    val outboundLabel: String
        get() = outboundTargets.firstOrNull { it.tag == outboundTag }?.label.orEmpty()
}

/**
 * Backs the full rule editor. The `ruleId` arrives from the Navigation 3 key;
 * `0L` means "new rule". Installed apps are resolved by [InstalledAppCatalog]
 * (which owns the `PackageManager`) so the composable never touches it directly.
 */
@HiltViewModel
class RuleEditorViewModel
    @Inject
    constructor(
        private val ruleRepository: RuleRepository,
        private val outboundTargetCatalog: OutboundTargetCatalog,
        private val installedAppCatalog: InstalledAppCatalog,
    ) : ViewModel() {
        private val state = MutableStateFlow(RuleEditorUiState())
        val uiState: StateFlow<RuleEditorUiState> = state.asStateFlow()
        private var initialized = false

        fun initialize(ruleId: Long) {
            if (initialized) return
            initialized = true
            state.update { it.copy(ruleId = ruleId) }
            viewModelScope.launch {
                val targets = outboundTargetCatalog.targets()
                val apps = installedAppCatalog.installedApps()
                // RuleRepository exposes reads only as Flows; take the first emission for the
                // edit-existing case. A new rule (ruleId == 0L) starts from the blank default state.
                val allRules = ruleRepository.allRules().first()
                val loadedRule = if (ruleId != 0L) allRules.firstOrNull { it.id == ruleId } else null
                // New rules append to the end of the order so first-match-wins stays predictable.
                val nextOrder = (allRules.maxOfOrNull { it.userOrder } ?: -1) + 1
                state.update { current ->
                    if (loadedRule != null) {
                        current.copy(
                            ruleId = loadedRule.id,
                            userOrder = loadedRule.userOrder,
                            name = loadedRule.name,
                            enabled = loadedRule.enabled,
                            domains = loadedRule.domains,
                            ipCidrs = loadedRule.ipCidrs,
                            ports = loadedRule.ports,
                            sourcePorts = loadedRule.sourcePorts,
                            network = loadedRule.network,
                            processName = loadedRule.processName,
                            packages = loadedRule.packages.toImmutableSet(),
                            outboundTag = loadedRule.outboundTag,
                            outboundTargets = targets.toImmutableList(),
                            installedApps = apps.toImmutableList(),
                            loaded = true,
                            textFieldReplacementRevision = current.textFieldReplacementRevision + 1,
                        )
                    } else {
                        current.copy(
                            userOrder = nextOrder,
                            outboundTargets = targets.toImmutableList(),
                            installedApps = apps.toImmutableList(),
                            loaded = true,
                            textFieldReplacementRevision = current.textFieldReplacementRevision + 1,
                        )
                    }
                }
            }
        }

        fun setName(value: String) = state.update { it.copy(name = value) }

        fun setEnabled(value: Boolean) = state.update { it.copy(enabled = value) }

        fun setDomains(value: String) = state.update { it.copy(domains = value) }

        fun setIpCidrs(value: String) = state.update { it.copy(ipCidrs = value) }

        fun setPorts(value: String) = state.update { it.copy(ports = value) }

        fun setSourcePorts(value: String) = state.update { it.copy(sourcePorts = value) }

        fun setNetwork(value: RuleNetwork) = state.update { it.copy(network = value) }

        fun setProcessName(value: String) = state.update { it.copy(processName = value) }

        fun setPackages(value: Set<String>) = state.update { it.copy(packages = value.toImmutableSet()) }

        fun setOutboundTag(value: OutboundTag) = state.update { it.copy(outboundTag = value) }

        /**
         * Persists the current form as a routing rule. No-ops when the rule has no matcher fields
         * (see [RuleEditorUiState.isEmpty]); the screen disables Save in that case as a UX guard, but
         * this is the authoritative gate. Invokes [onSaved] on success.
         */
        fun save(onSaved: () -> Unit) {
            val snapshot = state.value
            if (snapshot.isEmpty) return
            viewModelScope.launch {
                val rule =
                    RuleEntity(
                        id = snapshot.ruleId,
                        name = snapshot.name,
                        userOrder = snapshot.userOrder,
                        enabled = snapshot.enabled,
                        domains = snapshot.domains,
                        ipCidrs = snapshot.ipCidrs,
                        ports = snapshot.ports,
                        sourcePorts = snapshot.sourcePorts,
                        network = snapshot.network,
                        processName = snapshot.processName,
                        packages = snapshot.packages,
                        outboundTag = snapshot.outboundTag,
                    )
                if (snapshot.ruleId == 0L) {
                    ruleRepository.insert(rule)
                } else {
                    ruleRepository.update(rule)
                }
                onSaved()
            }
        }
    }
