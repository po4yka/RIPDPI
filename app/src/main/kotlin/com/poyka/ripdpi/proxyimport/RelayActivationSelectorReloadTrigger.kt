package com.poyka.ripdpi.proxyimport

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.selector.SelectorReloadTrigger
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The seam that re-pins the running foreground service onto a freshly-activated
 * relay profile. Split out (and injectable) so a selector hot-reload is testable
 * without driving the real Android service.
 *
 * The default [ServiceControllerRunningRelayRefresher] mirrors the process-level
 * `restartRuntime` semantics: it stops then starts the *current* mode only when a
 * runtime is actually running, so the just-written relay-profile settings are
 * re-read by the relay supervisor on the next start. A teardown-then-bring-up is
 * used rather than an in-place relay reload because the latter lives behind the
 * service-session control plane, which the app process cannot reach directly.
 */
interface RunningRelayRefresher {
    /** Re-pins the running runtime onto the newly-active relay profile. No-op when halted. */
    suspend fun refresh()

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
    ) : SelectorReloadTrigger {
        private val log = Logger.withTag("selector-reload")

        override suspend fun hotReload(profileId: String) {
            val profile = memberProfile(profileId)
            if (profile == null) {
                log.w { "selector reload requested for unknown member profile $profileId; skipping" }
                return
            }
            val activated = relayProfileActivator.activate(profile = profile, profileId = profileId)
            if (!activated) {
                log.w { "selector member $profileId is not a relay-activatable kind; skipping reload" }
                return
            }
            relayRefresher.refresh()
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

/**
 * [RunningRelayRefresher] over [ServiceController]. Reads the current
 * `(AppStatus, Mode)` and, only while [AppStatus.Running], issues a stop then a
 * start of the same mode — the minimal, mode-preserving restart that makes the
 * relay supervisor re-read the active relay profile.
 */
@Singleton
class ServiceControllerRunningRelayRefresher
    @Inject
    constructor(
        private val serviceController: ServiceController,
        private val serviceStateStore: ServiceStateStore,
    ) : RunningRelayRefresher {
        override suspend fun refresh() {
            val (status, mode) = serviceStateStore.status.value
            if (status != AppStatus.Running) return
            serviceController.stop()
            awaitHalted()
            serviceController.start(mode)
        }

        override suspend fun teardown() {
            serviceController.stop()
        }

        /** Best-effort wait for the runtime to halt before the restart's start leg. */
        private suspend fun awaitHalted() {
            repeat(RestartHaltPollAttempts) {
                if (serviceStateStore.status.value.first == AppStatus.Halted) return
                delay(RestartHaltPollDelayMs)
            }
        }

        private companion object {
            const val RestartHaltPollAttempts = 50
            const val RestartHaltPollDelayMs = 100L
        }
    }
