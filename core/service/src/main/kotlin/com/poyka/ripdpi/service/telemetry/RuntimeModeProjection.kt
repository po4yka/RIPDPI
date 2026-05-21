package com.poyka.ripdpi.service.telemetry

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode

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
 * when root is absent — see `docs/architecture/RUNTIME_MODES.md` §5. These are
 * the three states a read-only observer can distinguish.
 */
internal enum class RootHelperState {
    /** Root mode is on and the helper socket is connectable — privileged ops are usable. */
    Available,

    /** Root mode is on, but the helper socket is not (yet) connectable. */
    Enabled,

    /**
     * Root mode is off — the non-root baseline, so there is no root-helper
     * layer to report — or the inputs were not observed.
     */
    Unknown,
}

/**
 * A derived, read-only snapshot of RIPDPI's runtime state.
 *
 * RIPDPI deliberately has no single unified `RuntimeMode` type: runtime state
 * is the `(AppStatus, Mode)` pair in `ServiceStateStore.status` plus the relay,
 * root-helper, and diagnostics layers, each *inferred* from settings and native
 * handles (see `docs/architecture/RUNTIME_MODES.md`, "The runtime mode state
 * model"). That document names a *derived, read-only projection* over those
 * sources as the safe first step toward an eventual unified type.
 *
 * This is exactly that projection — and only that. It is **purely derived**: it
 * owns no state, replaces neither [Mode] nor [AppStatus] nor any of their
 * consumers, and never drives start/stop. It only re-presents already-decided
 * state in one place. The full unified-`RuntimeMode` refactor is explicitly
 * *not* begun here.
 *
 * Build instances with [from]; the relay / diagnostics / root inputs are
 * nullable so a caller that cannot observe a layer leaves it
 * [RuntimeLayerState.Unknown] / [RootHelperState.Unknown] rather than guessing.
 *
 * The projection is currently a tested internal API: wiring it into a live
 * observer would mean injecting the relay / diagnostics / root coordinators
 * into an existing class, which is the broad rewiring `RUNTIME_MODES.md` warns
 * against. Callers adopt it incrementally as low-risk read-only paths appear.
 */
internal data class RuntimeModeProjection(
    /** Coarse "is a runtime active" — taken straight from `ServiceStateStore.status`. */
    val status: AppStatus,
    /**
     * The active runtime kind — `Proxy` or `VPN` — or `null` when no runtime is
     * running. `ServiceStateStore.status` always carries a [Mode], but it is
     * only a meaningful *active* mode while [status] is [AppStatus.Running];
     * when halted the pair merely retains the last/default mode.
     */
    val activeMode: Mode?,
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
         * coordinator state the caller can observe.
         *
         * @param status the canonical `(AppStatus, Mode)` runtime observable.
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
            relayActive: Boolean? = null,
            diagnosticsScanActive: Boolean? = null,
            rootModeEnabled: Boolean? = null,
            rootHelperSocketAvailable: Boolean? = null,
        ): RuntimeModeProjection {
            val (appStatus, mode) = status
            return RuntimeModeProjection(
                status = appStatus,
                activeMode = mode.takeIf { appStatus == AppStatus.Running },
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
                rootModeEnabled != true -> RootHelperState.Unknown
                socketAvailable == true -> RootHelperState.Available
                else -> RootHelperState.Enabled
            }
    }
}
