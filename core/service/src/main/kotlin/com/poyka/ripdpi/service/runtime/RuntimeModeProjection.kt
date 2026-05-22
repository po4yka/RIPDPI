package com.poyka.ripdpi.service.runtime

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.services.ServiceLifecycleStateMachine

/**
 * Tristate for an inferred runtime layer (relay, diagnostics scan) whose live
 * state is not always observable from a given caller's vantage point.
 *
 * `Unknown` is a first-class value, not a failure: a read-only observer that
 * cannot reach a layer's coordinator records `Unknown` rather than guessing.
 */
internal enum class RuntimeLayerState {
    /** The layer is confirmed active. */
    Active,

    /** The layer is confirmed inactive. */
    Inactive,

    /** The layer's state could not be observed. */
    Unknown,
}

/**
 * The root helper's observable availability.
 *
 * The root helper is opt-in behind `root_mode_enabled` and degrades gracefully
 * when root is absent — see `docs/architecture/RUNTIME_MODES.md` §5. A read-only
 * observer distinguishes four cases. [Disabled] (root mode is provably off, the
 * non-root baseline) and [Unknown] (the root-mode setting was not observed) are
 * deliberately separate: collapsing them would hide whether the non-root
 * baseline is in effect or merely unmeasured.
 */
internal enum class RootHelperState {
    /** Root mode is off — the non-root baseline; there is no root-helper layer. */
    Disabled,

    /** Root mode is on, but the helper socket is not (yet) connectable. */
    EnabledUnavailable,

    /** Root mode is on and the helper socket is connectable — privileged ops are usable. */
    EnabledAvailable,

    /** The root-mode setting was not observed, so availability is indeterminate. */
    Unknown,
}

/**
 * A derived, read-only snapshot of RIPDPI's runtime state.
 *
 * RIPDPI deliberately has no single unified `RuntimeMode` type: runtime state
 * is the `(AppStatus, Mode)` pair in `ServiceStateStore.status`, the finer
 * per-supervisor [ServiceStatus] and the service-internal lifecycle phase, plus
 * the relay, diagnostics, and root-helper layers — each *inferred* from settings
 * and native handles (see `docs/architecture/RUNTIME_MODES.md`, "The runtime
 * mode state model"). That document names a *derived, read-only projection*
 * over those sources as the safe first step toward an eventual unified type.
 *
 * This is exactly that projection — and only that. It is **purely derived**: it
 * owns no state, persists nothing, replaces neither [Mode] nor [AppStatus] nor
 * [ServiceStatus] nor any of their consumers, and never drives start/stop. It
 * only re-presents already-decided state in one place. The full
 * unified-`RuntimeMode` refactor is explicitly *not* begun here.
 *
 * Build instances with [from]; every input beyond the canonical
 * `(AppStatus, Mode)` pair is optional, so a caller that cannot observe a layer
 * leaves it `Unknown` / `null` rather than guessing.
 *
 * The projection is a tested internal API: wiring it into a live observer would
 * mean injecting the relay / diagnostics / root coordinators into an existing
 * class, which is the broad rewiring `RUNTIME_MODES.md` warns against. Callers
 * adopt it incrementally as low-risk read-only paths appear.
 */
internal data class RuntimeModeProjection(
    /** Coarse "is a runtime active" — taken straight from `ServiceStateStore.status`. */
    val appStatus: AppStatus,
    /**
     * The runtime kind from `ServiceStateStore.status`. The pair always carries
     * a [Mode]; it names the *active* runtime only while [appStatus] is
     * [AppStatus.Running] — while halted it is merely the last/default mode.
     */
    val mode: Mode,
    /**
     * The finer per-supervisor connection health, when a coordinator reported
     * it; `null` when it was not observed. It is not part of
     * `ServiceStateStore.status`, which carries only the coarse [appStatus].
     */
    val serviceStatus: ServiceStatus?,
    /**
     * The service-internal start/stop phase, when observed; `null` otherwise.
     */
    val lifecyclePhase: ServiceLifecycleStateMachine.State?,
    /** Whether an upstream relay is composed into the active path. */
    val relay: RuntimeLayerState,
    /** Whether a diagnostics scan is currently in progress. */
    val diagnosticsScan: RuntimeLayerState,
    /** Whether the privileged root helper is enabled and available. */
    val rootHelper: RootHelperState,
) {
    companion object {
        /**
         * Derive a projection from `ServiceStateStore.status` and whatever
         * coordinator state the caller can observe. Only [status] is required;
         * every other input defaults to "not observed".
         *
         * @param status the canonical `(AppStatus, Mode)` runtime observable.
         * @param serviceStatus the finer per-supervisor health, or `null` when
         *   it was not observed.
         * @param lifecyclePhase the service start/stop phase, or `null` when it
         *   was not observed.
         * @param relayActive `true`/`false` when relay activity is known, `null`
         *   when it was not observed.
         * @param diagnosticsScanActive `true`/`false` when scan activity is
         *   known, `null` when it was not observed.
         * @param rootModeEnabled the `root_mode_enabled` setting, or `null` when
         *   it was not observed.
         * @param rootHelperSocketAvailable whether the root helper socket is
         *   connectable (e.g. `RootHelperManager.socketPath != null`), or `null`
         *   when it was not observed.
         */
        fun from(
            status: Pair<AppStatus, Mode>,
            serviceStatus: ServiceStatus? = null,
            lifecyclePhase: ServiceLifecycleStateMachine.State? = null,
            relayActive: Boolean? = null,
            diagnosticsScanActive: Boolean? = null,
            rootModeEnabled: Boolean? = null,
            rootHelperSocketAvailable: Boolean? = null,
        ): RuntimeModeProjection {
            val (appStatus, mode) = status
            return RuntimeModeProjection(
                appStatus = appStatus,
                mode = mode,
                serviceStatus = serviceStatus,
                lifecyclePhase = lifecyclePhase,
                relay = layerStateOf(relayActive),
                diagnosticsScan = layerStateOf(diagnosticsScanActive),
                rootHelper = rootHelperStateOf(rootModeEnabled, rootHelperSocketAvailable),
            )
        }

        private fun layerStateOf(active: Boolean?): RuntimeLayerState =
            when (active) {
                true -> RuntimeLayerState.Active
                false -> RuntimeLayerState.Inactive
                null -> RuntimeLayerState.Unknown
            }

        private fun rootHelperStateOf(
            rootModeEnabled: Boolean?,
            socketAvailable: Boolean?,
        ): RootHelperState =
            when {
                rootModeEnabled == null -> RootHelperState.Unknown
                !rootModeEnabled -> RootHelperState.Disabled
                socketAvailable == true -> RootHelperState.EnabledAvailable
                else -> RootHelperState.EnabledUnavailable
            }
    }
}
