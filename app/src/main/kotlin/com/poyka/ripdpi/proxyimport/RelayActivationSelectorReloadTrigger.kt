package com.poyka.ripdpi.proxyimport

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.PauseAuthorityRef
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RejectedSwitchReverter
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyFailure
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.runningConfiguration
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
import com.poyka.ripdpi.services.RuntimePolicyReloadIntent
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import com.poyka.ripdpi.services.selector.SelectorReloadTrigger
import com.poyka.ripdpi.subscription.isAppliedSelectorSuccessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Applies profile changes through the running session; only explicit teardown stops the service. */
interface RunningRelayRefresher {
    /**
     * Re-pins the running runtime onto the newly-active relay profile. No-op when halted. Returns false when a
     * running session rejected the reload and kept its previous configuration.
     */
    suspend fun refresh(
        intent: com.poyka.ripdpi.services.RuntimePolicyReloadIntent,
        isCurrent: suspend () -> Boolean,
    ): Boolean

    /** Stops the runtime entirely. */
    suspend fun teardown()
}

/**
 * Production [SelectorReloadTrigger]. When a selector group's active member
 * changes, [hotReload] resolves that member's persisted [ProxyProfile] from the
 * group's stored [com.poyka.ripdpi.data.ProxyGroup.members], activates it as the
 * live native relay through [RelayProfileActivator] (the single profile→relay
 * mapping site), then refreshes the running runtime so the live exit switches.
 *
 * This is the production wiring that was missing: `SelectorReloadCoordinator` had
 * no production trigger, so a manual selection change never hot-switched the live
 * exit (audit finding P1-8).
 */
@Singleton
class RelayActivationSelectorReloadTrigger
    @Inject
    constructor(
        private val groupRepository: ProxyGroupRepository,
        private val selections: com.poyka.ripdpi.data.selector.SelectorSelectionStore,
        private val authority: com.poyka.ripdpi.data.PauseIntentAuthority,
        private val stateStore: ServiceStateStore,
        private val relayRefresher: RunningRelayRefresher,
        private val selectionProvider: ActiveSelectorSelectionProvider,
        private val applied: AppliedRuntimeConfigurationSource,
        private val switches: RejectedSwitchReverter,
    ) : SelectorReloadTrigger {
        private val activationMutex = Mutex()
        private var preparedProfile: ProxyProfile? = null

        private data class AutomaticReload(
            val groupId: String,
            val selection: SelectorSelectionSnapshot,
            val previous: AppliedRuntimeConfiguration,
            val authority: PauseAuthorityRef,
        )

        suspend fun prepare() =
            activationMutex.withLock {
                val request = selectionProvider.selectionChanges().first() ?: return@withLock
                val profile =
                    memberProfile(request.groupId, request.memberId) ?: error("Active selector member is unavailable")
                check(
                    com.poyka.ripdpi.data
                        .mapRelayProfile(profile) != null,
                ) { "Selected profile cannot be activated" }
                preparedProfile = profile
            }

        suspend fun afterStart() {
            val request = selectionProvider.selectionChanges().first() ?: return
            val needsReload =
                activationMutex.withLock {
                    memberProfile(request.groupId, request.memberId) !=
                        preparedProfile
                }
            // The started stack used another profile than the one now selected, even under the same member id.
            if (needsReload) reload(request, skipApplied = false)
        }

        override suspend fun hotReload(request: com.poyka.ripdpi.services.selector.SelectorReloadRequest) =
            reload(request, skipApplied = true)

        private suspend fun reload(
            request: com.poyka.ripdpi.services.selector.SelectorReloadRequest,
            skipApplied: Boolean,
        ) {
            val group = request.groupId
            val profileId = request.memberId
            val selection = selections.snapshot(group)
            val matchesSelection =
                selection.profileId == profileId && (request.manualReceipt != null || !selection.isManual)
            val profile =
                if (matchesSelection && selectionProvider.activeGroupId() == group) {
                    memberProfile(group, profileId)
                } else {
                    null
                }
            val intent = profile?.let { reloadIntent(request) }
            val mode = stateStore.status.value.second
            val current = applied.applications.value[mode]
            // An automatic selection that names the member already carrying traffic needs no stack replacement.
            val alreadyApplied =
                skipApplied && intent == RuntimePolicyReloadIntent.Automatic &&
                    current.appliedMember(group) == profileId
            if (profile == null || intent == null || alreadyApplied) return
            val automatic =
                current?.runningConfiguration()?.takeIf { intent == RuntimePolicyReloadIntent.Automatic }?.let {
                    AutomaticReload(group, selection, it, authority.reference())
                }
            val reason = dispatchReload(intent, automatic != null) { isCurrent(group, selection, profile) }
            val manual = request.manualReceipt
            if (automatic != null) {
                reconcileAutomatic(automatic, reason)
            } else if (reason != null && manual != null) {
                val restored = switches.revertRejectedSwitch(manual)
                Logger.w(tag = LogTags.UI) { "Selector reload $reason; previous selection restored: $restored" }
            }
        }

        private suspend fun dispatchReload(
            intent: RuntimePolicyReloadIntent,
            reconcileTimeout: Boolean,
            isCurrent: suspend () -> Boolean,
        ): String? =
            try {
                if (relayRefresher.refresh(intent, isCurrent)) null else "rejected"
            } catch (timedOut: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (!reconcileTimeout) throw timedOut
                "timed out"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The server name can appear in the exception message.
                "failed"
            }

        private suspend fun isCurrent(
            group: String,
            selection: SelectorSelectionSnapshot,
            profile: ProxyProfile,
        ) = selectionProvider.activeGroupId() == group &&
            selections.snapshot(group) == selection && memberProfile(group, profile.id) == profile

        private suspend fun reconcileAutomatic(
            reload: AutomaticReload,
            dispatchFailure: String?,
        ) {
            val settled = settledApplication(reload)
            val configuration = (settled as? RuntimeConfigurationApplication.Applied)?.configuration
            val live = settled.appliedMember(reload.groupId)
            val desired = reload.selection.profileId
            if (configuration?.isAppliedSelectorSuccessor(reload.previous, reload.groupId, desired) == true) {
                Logger.i(tag = LogTags.UI) { "Automatic selector reload applied at revision ${configuration.revision}" }
            } else {
                val failure =
                    when {
                        (settled as? RuntimeConfigurationApplication.Failed)?.failure ==
                            RuntimeConfigurationApplyFailure.TimedOut -> "timed out"

                        else -> dispatchFailure ?: "unconfirmed"
                    }
                val restored = live != null && live != desired && restoreLiveMember(reload, live, settled)
                Logger.w(tag = LogTags.UI) { "Automatic selector reload $failure; live selection restored: $restored" }
            }
        }

        private suspend fun settledApplication(reload: AutomaticReload): RuntimeConfigurationApplication {
            val mode = reload.previous.mode
            val current = applied.applications.value[mode] ?: RuntimeConfigurationApplication.Unknown
            if (!current.isApplying(reload.previous)) return current
            val changes =
                combine(
                    applied.applications,
                    selections.selection(reload.groupId),
                    selectionProvider.activeGroupChanges,
                    authority.states,
                ) { applications, _, activeGroup, authorityState ->
                    val ownsReload =
                        activeGroup == reload.groupId && authorityState?.generation == reload.authority.generation
                    if (ownsReload && selections.snapshot(reload.groupId) == reload.selection) {
                        applications[mode] ?: RuntimeConfigurationApplication.Unknown
                    } else {
                        RuntimeConfigurationApplication.Unknown
                    }
                }
            return withTimeoutOrNull(SelectorApplySettleTimeoutMillis) {
                changes.first { !it.isApplying(reload.previous) }
            }
                ?: run {
                    Logger.w(
                        tag = LogTags.UI,
                    ) { "Automatic selector reload timed out with an application still in progress" }
                    // Keep the fence until that application settles or a new choice supersedes it.
                    changes.first { !it.isApplying(reload.previous) }
                }
        }

        private suspend fun restoreLiveMember(
            reload: AutomaticReload,
            live: String,
            application: RuntimeConfigurationApplication?,
        ): Boolean {
            val mode = reload.previous.mode
            val configuration = (application as? RuntimeConfigurationApplication.Applied)?.configuration
            return configuration?.runtimeId == reload.previous.runtimeId &&
                selectionProvider.activeGroupId() == reload.groupId && memberProfile(reload.groupId, live) != null &&
                selections.selectAutomatically(reload.groupId, reload.selection, live) {
                    authority.reference() == reload.authority && applied.applications.value[mode] == application &&
                        stateStore.status.value == (AppStatus.Running to mode)
                }
        }

        private fun reloadIntent(
            request: com.poyka.ripdpi.services.selector.SelectorReloadRequest,
        ): com.poyka.ripdpi.services.RuntimePolicyReloadIntent? {
            val manual = request.manualReceipt
            if (manual?.origin is com.poyka.ripdpi.data.RuntimeCommandOrigin.MeasuredActivation) return null
            val record = authority.snapshotAuthority().command
            return if (manual != null && authority.isCurrent(manual) &&
                record?.phase == com.poyka.ripdpi.data.RuntimeActivationPhase.Unbound
            ) {
                val mode = stateStore.status.value.second
                authority.bindProfileActivation(manual, mode)?.let {
                    com.poyka.ripdpi.services.RuntimePolicyReloadIntent
                        .Explicit(it)
                }
            } else if (manual != null) {
                null
            } else {
                com.poyka.ripdpi.services.RuntimePolicyReloadIntent.Automatic
            }
        }

        override suspend fun teardown() {
            relayRefresher.teardown()
        }

        /** Finds the selected member's profile across every selector group's stored members. */
        private suspend fun memberProfile(
            groupId: String,
            profileId: String,
        ): ProxyProfile? =
            groupRepository
                .list()
                .singleOrNull { it.id == groupId }
                ?.members
                ?.singleOrNull { it.id == profileId }
    }

/** Reconfigures the registered runtime without releasing foreground service or VPN ownership. */
@Singleton
class ServiceControllerRunningRelayRefresher
    @Inject
    constructor(
        private val serviceController: ServiceController,
        private val serviceStateStore: ServiceStateStore,
        private val runtimeRegistry: ServiceRuntimeRegistry,
    ) : RunningRelayRefresher {
        override suspend fun refresh(
            intent: com.poyka.ripdpi.services.RuntimePolicyReloadIntent,
            isCurrent: suspend () -> Boolean,
        ): Boolean {
            val (status, mode) = serviceStateStore.status.value
            if (status != AppStatus.Running) return true
            return runtimeRegistry.current(mode)?.reloadConnectionPolicy(intent, isCurrent) ?: false
        }

        override suspend fun teardown() {
            serviceController.stop()
        }
    }

private fun RuntimeConfigurationApplication?.appliedMember(group: String): String? =
    (this as? RuntimeConfigurationApplication.Applied)
        ?.configuration
        ?.effectiveSelection
        ?.takeIf { it.selectorGroupId == group }
        ?.selectorMemberId

private fun RuntimeConfigurationApplication.isApplying(previous: AppliedRuntimeConfiguration): Boolean =
    this is RuntimeConfigurationApplication.Applying && attempt.runtimeId == previous.runtimeId

private const val SelectorApplySettleTimeoutMillis = 60_000L
