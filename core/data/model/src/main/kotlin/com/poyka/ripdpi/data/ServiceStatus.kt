package com.poyka.ripdpi.data

/**
 * Per-runtime-supervisor connection status — the finer health signal a
 * runtime coordinator reports as a session connects, drops, or fails.
 *
 * Projected into the coarser [AppStatus] (`Connected` → `Running`;
 * `Disconnected` / `Failed` → not `Running`) by `RuntimeTelemetryProjection`.
 * See `docs/architecture/RUNTIME_MODES.md`, "The runtime mode state model".
 */
enum class ServiceStatus {
    Disconnected,
    Connected,
    Failed,
}
