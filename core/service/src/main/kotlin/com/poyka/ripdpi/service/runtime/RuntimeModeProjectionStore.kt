package com.poyka.ripdpi.service.runtime

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The process-level read-only observable for [RuntimeModeProjection].
 *
 * [RuntimeModeProjection] is a *purely derived* snapshot; this store turns it
 * into a live [Flow] so diagnostics, telemetry, and future UI can observe one
 * runtime-state view without each reaching into the individual state machines.
 *
 * The store is **read-only**: it only derives. It issues no start/stop, mutates
 * no runtime state, and never feeds back into the runtime. It re-presents
 * already-decided state — see `docs/architecture/RUNTIME_MODES.md`,
 * "The runtime mode state model".
 *
 * ### Wired sources
 *
 * The store derives from every input observable from a process-level singleton:
 * - `appStatus` / `mode` — `ServiceStateStore.status`, the canonical pair.
 * - `relay` — inferred from `ServiceStateStore.telemetry`: a relay is composed
 *   into the path exactly while relay telemetry is being produced, i.e. while
 *   `relayTelemetryStatus` is anything other than [RuntimeTelemetryState.NoData]
 *   (the value reported when no relay runtime exists).
 * - `rootHelper` — `root_mode_enabled` from `AppSettingsRepository`.
 * - `diagnosticsScan` — published by the diagnostics raw-path scan coordinator
 *   through [markDiagnosticsScanActive]; `Unknown` until the first scan runs.
 *
 * ### Deferred sources
 *
 * `serviceStatus` and `lifecyclePhase` come from the per-supervisor status
 * reporter and the service-internal `ServiceLifecycleStateMachine`, neither of
 * which is a process-level singleton; `rootHelperSocketAvailable` is a
 * non-reactive `RootHelperManager` getter. Observing them would mean the broad
 * rewiring `RUNTIME_MODES.md` warns against, so the store leaves them
 * unobserved (`null`) and the projection keeps them `Unknown` / `null`. They
 * are wired incrementally as low-risk read-only seams appear.
 */
@Singleton
internal class RuntimeModeProjectionStore
    @Inject
    constructor(
        private val serviceStateStore: ServiceStateStore,
        private val appSettingsRepository: AppSettingsRepository,
    ) {
        private val diagnosticsScanActive = MutableStateFlow<Boolean?>(null)

        /**
         * A live, read-only view of [RuntimeModeProjection], re-derived whenever
         * any wired source changes.
         *
         * Cold: it does no work until collected, holds no scope, and collapses
         * re-derivations that leave the projection unchanged.
         */
        val projection: Flow<RuntimeModeProjection> =
            combine(
                serviceStateStore.status,
                serviceStateStore.telemetry,
                appSettingsRepository.settings,
                diagnosticsScanActive,
            ) { status, telemetry, settings, scanActive ->
                RuntimeModeProjection.from(
                    status = status,
                    serviceStatus = null,
                    lifecyclePhase = null,
                    relayActive = relayActive(telemetry),
                    diagnosticsScanActive = scanActive,
                    rootModeEnabled = settings.rootModeEnabled,
                    rootHelperSocketAvailable = null,
                )
            }.distinctUntilChanged()

        /**
         * Record whether a diagnostics scan is in progress.
         *
         * Called by the diagnostics raw-path scan coordinator around a scan.
         * This publishes an *observed fact* into the projection — it starts and
         * stops nothing.
         */
        fun markDiagnosticsScanActive(active: Boolean) {
            diagnosticsScanActive.value = active
        }

        private fun relayActive(telemetry: ServiceTelemetrySnapshot): Boolean =
            telemetry.relayTelemetryStatus.state != RuntimeTelemetryState.NoData
    }
