package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records the non-secret last-active-session pointer (`profileId` + service
 * [com.poyka.ripdpi.data.Mode]) into device-protected storage every time a
 * RIPDPI session reaches [AppStatus.Running].
 *
 * The pointer is what the boot receiver reads at `BOOT_COMPLETED` to decide
 * which service mode to resume after credential storage becomes available. The active
 * profile id is sourced from `AppSettings.diagnosticsActiveProfileId`, the app's
 * canonical "active profile" notion; the secret-bearing profile bean is never
 * touched here.
 *
 * `open` so the app DI layer / tests can substitute a no-op or recording double.
 */
@Singleton
open class BootSessionRecorder
    @Inject
    constructor(
        private val serviceStateStore: ServiceStateStore,
        private val appSettingsRepository: AppSettingsRepository,
        private val bootSessionStateStore: BootSessionStateStore,
        @param:ApplicationScope private val appScope: CoroutineScope,
    ) {
        private companion object {
            private val log = Logger.withTag("BootSessionRecorder")
        }

        /** Begin observing runtime status. Call once during application startup. */
        open fun register() {
            appScope.launch {
                serviceStateStore.status.collect { (status, mode) ->
                    if (status != AppStatus.Running) return@collect
                    val profileId =
                        runCatching { appSettingsRepository.snapshot().diagnosticsActiveProfileId }
                            .getOrDefault("")
                    runCatching {
                        bootSessionStateStore.recordSession(profileId, mode)
                        // A running session is, by definition, "running at update": if the
                        // process is killed for an app update now, MY_PACKAGE_REPLACED should
                        // resume it. An explicit stop later clears this via ServiceController.stop().
                        bootSessionStateStore.setWasRunningAtUpdate(true)
                    }.onFailure { error -> log.w(error) { "Failed to record boot-session pointer" } }
                }
            }
        }
    }
