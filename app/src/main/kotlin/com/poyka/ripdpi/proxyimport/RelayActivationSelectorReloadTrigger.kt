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
    suspend fun refresh(isCurrent: suspend () -> Boolean)

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
        private val relayProfileActivator: RelayProfileActivator,
        private val relayRefresher: RunningRelayRefresher,
        private val selectionProvider: ActiveSelectorSelectionProvider,
    ) : SelectorReloadTrigger {
        private val activationMutex = Mutex()
        private var preparedProfile: ProxyProfile? = null

        suspend fun prepare() =
            activationMutex.withLock {
                repeat(StartupReconcileAttempts) {
                    val profileId = selectionProvider.selectedProfileId().first() ?: return@withLock
                    val profile = memberProfile(profileId) ?: return@withLock
                    check(relayProfileActivator.activate(profile, profileId)) { "Selected profile cannot be activated" }
                    if (selectionProvider.selectedProfileId().first() == profileId &&
                        memberProfile(profileId) == profile
                    ) {
                        preparedProfile = profile
                        return@withLock
                    }
                }
                error("Selector changed repeatedly during startup")
            }

        suspend fun afterStart() {
            val selected = selectionProvider.selectedProfileId().first() ?: return
            val needsReload = activationMutex.withLock { memberProfile(selected) != preparedProfile }
            if (needsReload) hotReload(selected)
        }

        override suspend fun hotReload(profileId: String) {
            val activatedProfile =
                activationMutex.withLock {
                    if (selectionProvider.selectedProfileId().first() != profileId) return@withLock null
                    val profile = memberProfile(profileId) ?: return@withLock null
                    if (relayProfileActivator.activate(profile, profileId)) profile else null
                }
            // Release activation ownership before entering the service lifecycle mutex.
            if (activatedProfile != null) {
                relayRefresher.refresh {
                    selectionProvider.selectedProfileId().first() == profileId &&
                        memberProfile(profileId) == activatedProfile
                }
            }
        }

        override suspend fun teardown() {
            relayRefresher.teardown()
        }

        /** Finds the selected member's profile across every selector group's stored members. */
        private suspend fun memberProfile(profileId: String): ProxyProfile? =
            groupRepository
                .list()
                .asSequence()
                .flatMap { it.members.asSequence() }
                .firstOrNull { it.id == profileId }
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
        override suspend fun refresh(isCurrent: suspend () -> Boolean) {
            val (status, mode) = serviceStateStore.status.value
            if (status == AppStatus.Running) runtimeRegistry.current(mode)?.reloadConnectionPolicy(isCurrent)
        }

        override suspend fun teardown() {
            serviceController.stop()
        }
    }

private const val StartupReconcileAttempts = 3
