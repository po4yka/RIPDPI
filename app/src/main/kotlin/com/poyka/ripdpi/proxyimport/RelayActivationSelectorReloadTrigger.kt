package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import com.poyka.ripdpi.services.selector.SelectorReloadTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Applies profile changes through the running session; only explicit teardown stops the service. */
interface RunningRelayRefresher {
    /** Re-pins the running runtime onto the newly-active relay profile. No-op when halted. */
    suspend fun refresh(
        intent: com.poyka.ripdpi.services.RuntimePolicyReloadIntent,
        isCurrent: suspend () -> Boolean,
    )

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
    ) : SelectorReloadTrigger {
        private val activationMutex = Mutex()
        private var preparedProfile: ProxyProfile? = null

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
            if (needsReload) hotReload(request)
        }

        override suspend fun hotReload(request: com.poyka.ripdpi.services.selector.SelectorReloadRequest) {
            val group = request.groupId
            val profileId = request.memberId
            val profile =
                if (selectionProvider.activeGroupId() == group &&
                    selections.snapshot(group).profileId == profileId
                ) {
                    memberProfile(group, profileId)
                } else {
                    null
                }
            if (profile == null) return
            val intent = reloadIntent(request) ?: return
            relayRefresher.refresh(intent) {
                selectionProvider.activeGroupId() == group &&
                    selections.snapshot(group).profileId == profileId && memberProfile(group, profileId) == profile
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
        ) {
            val (status, mode) = serviceStateStore.status.value
            if (status == AppStatus.Running) runtimeRegistry.current(mode)?.reloadConnectionPolicy(intent, isCurrent)
        }

        override suspend fun teardown() {
            serviceController.stop()
        }
    }
