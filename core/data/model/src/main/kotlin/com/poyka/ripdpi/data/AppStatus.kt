package com.poyka.ripdpi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Coarse run status — is a RIPDPI runtime active or not.
 *
 * One of the two explicit runtime-state axes; the other is [Mode]. The
 * canonical runtime observable is `ServiceStateStore.status`, an
 * `(AppStatus, Mode)` pair. Finer per-supervisor health is [ServiceStatus],
 * and the service-internal start/stop phases are
 * `ServiceLifecycleStateMachine.State`. See
 * `docs/architecture/RUNTIME_MODES.md`, "The runtime mode state model".
 */
enum class AppStatus {
    Halted,

    /**
     * Transient bring-up state written by the boot-resume and LMK
     * (sticky-restart) paths while the previously-active service is restarting,
     * so the UI does not flash [Halted] during the brief restart window.
     *
     * It is never a persisted or default value of the canonical runtime store:
     * `ServiceStateStore` initializes to [Halted] on every fresh process (no Drop
     * runs on a SIGKILL), and [Reconnecting] is only ever written by code that is
     * actively resuming — so a killed process can never come back up *as*
     * [Reconnecting]. It is always overwritten by [Running] once the runtime
     * connects, or [Halted] if the resume fails (the runtime start path
     * guarantees a terminal status on every exit). See
     * `.claude/rules/android-vpn-lifecycle.md`.
     */
    Reconnecting,

    Running,
}

/**
 * The runtime mode — *which kind* of runtime the foreground service runs.
 *
 * The one explicitly modelled, persisted mode axis (`@SerialName` values are
 * written to settings). `Proxy` and `VPN` are mutually exclusive at runtime —
 * one foreground service at a time. Relay, the root helper, and diagnostics
 * are **not** `Mode` values: they are layers and sessions inferred from
 * settings and native handles. See `docs/architecture/RUNTIME_MODES.md`,
 * "The runtime mode state model".
 */
@Serializable
enum class Mode {
    @SerialName("proxy")
    Proxy,

    @SerialName("vpn")
    VPN,
    ;

    val preferenceValue: String
        get() =
            when (this) {
                Proxy -> "proxy"
                VPN -> "vpn"
            }

    companion object {
        fun fromString(name: String): Mode =
            entries.firstOrNull { it.preferenceValue.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("Invalid mode: $name")
    }
}
