package com.poyka.ripdpi.service.runtime.control

/**
 * Why a [RuntimeControlCommand] was issued.
 *
 * The reason is carried for diagnostics and telemetry only — it never changes
 * how a command is executed. Tagging every runtime-change request with a typed
 * reason lets the control plane log a coherent audit trail and lets future
 * features reason about *who* asked for a change without each caller inventing
 * its own string.
 */
internal enum class RuntimeControlReason {
    /** The service is being started as part of normal startup. */
    ServiceStart,

    /** The user explicitly asked the runtime to stop. */
    UserStop,

    /** The underlying network changed (Wi-Fi <-> cellular) and the runtime is re-pinned. */
    NetworkHandover,

    /** A settings change requires the runtime to pick up new configuration. */
    SettingsChange,

    /** A diagnostics raw-path scan needs the service stopped/resumed around probing. */
    DiagnosticsRawPathScan,

    /** The active relay profile changed and the relay layer must be refreshed. */
    RelayProfileChange,

    /** The privileged root helper became available or unavailable. */
    RootHelperAvailabilityChange,

    /** The per-network policy memory was updated and should be re-applied. */
    PolicyMemoryUpdate,
}
