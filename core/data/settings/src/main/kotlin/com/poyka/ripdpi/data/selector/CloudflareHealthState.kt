package com.poyka.ripdpi.data.selector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Health state of the Cloudflare-backed transport paths.
 *
 * [Healthy] — Cloudflare paths are functioning; they are included in auto selection
 * at the lowest priority tier.
 * [Degraded] — Cloudflare payload transfer is failing (rate-limiting, whitelist shutdown,
 * or similar); profiles are excluded from auto selection until recovery.
 */
enum class CloudflareHealthState {
    Healthy,
    Degraded,
}

/**
 * Observable holder for [CloudflareHealthState] transitions.
 *
 * Starts in [CloudflareHealthState.Healthy]. Callers emit transitions via
 * [onDegraded] and [onHealthy]. The current value is readable synchronously
 * via [currentStateValue] and reactively via [currentState].
 */
class CloudflareHealthStateObserver {
    private val stateFlow = MutableStateFlow(CloudflareHealthState.Healthy)

    val currentState: StateFlow<CloudflareHealthState> = stateFlow.asStateFlow()

    val currentStateValue: CloudflareHealthState
        get() = stateFlow.value

    fun onDegraded() {
        stateFlow.value = CloudflareHealthState.Degraded
    }

    fun onHealthy() {
        stateFlow.value = CloudflareHealthState.Healthy
    }
}
