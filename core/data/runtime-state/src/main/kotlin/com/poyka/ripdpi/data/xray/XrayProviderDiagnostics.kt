package com.poyka.ripdpi.data.xray

import com.poyka.ripdpi.data.XrayConfigValidator
import kotlinx.serialization.Serializable

/**
 * Typed diagnostics + telemetry surface for the embedded Xray provider.
 *
 * This models the *provider* health axis (the Xray-core engine, its local
 * inbound listener, and its outbound) DISTINCTLY from the tunnel-data-plane
 * telemetry already captured by
 * [com.poyka.ripdpi.data.NativeRuntimeSnapshot]. The two are surfaced together
 * — a green tunnel with a dead Xray outbound, or a healthy outbound behind a
 * stalled TUN forwarder, must be diagnosable independently. Home connection
 * staging consumes [XrayConnectionStage]; Diagnostics consumes
 * [XrayProviderSnapshot] + [XrayProviderProbeReport].
 *
 * ### Privacy
 * No field here carries a secret or a live endpoint in plain form. The
 * [redactedSummary] builders route every server address / UUID / key through
 * [XrayProfileRedactor], reusing the task-2 redaction surface. Pass only
 * already-safe values (versions, states, error *classes*, latency numbers)
 * into the data classes; never the raw profile.
 *
 * ### Verification boundary
 * The data model and its summary/fixture logic are pure Kotlin and run in the
 * offline `:core:data:runtime-state` unit-test lane. Population of a live
 * [XrayProviderSnapshot] from a running Xray-core (the libXray `Ping` /
 * `XrayVersion` / listener-readiness calls, or the Xray stat API) happens in
 * `:core:service` against the gomobile bridge and is verified on device — see
 * [XrayProviderProbeKind] for which probe paths are device/child-process only.
 */
object XrayProviderDiagnostics {
    /** Schema marker for the typed Xray provider telemetry payload. */
    const val SCHEMA_VERSION: Int = 1
}

/**
 * Provider engine readiness — distinct from [VpnProviderState] (the lifecycle
 * the service drives) and from tunnel data-plane health.
 *
 * - [Unknown] — never observed (no snapshot yet).
 * - [Starting] — engine process/bridge is coming up; listener not yet bound.
 * - [ListenerReady] — local inbound is bound and accepting, but no outbound
 *   handshake has been confirmed.
 * - [OutboundHealthy] — a probe through the active outbound succeeded.
 * - [Degraded] — engine is up but the outbound or listener is unhealthy.
 * - [Failed] — a provider-level failure prevents the engine from serving.
 */
enum class XrayProviderReadiness {
    Unknown,
    Starting,
    ListenerReady,
    OutboundHealthy,
    Degraded,
    Failed,
    ;

    /** True when the provider can carry traffic right now. */
    val isServing: Boolean
        get() = this == OutboundHealthy
}

/**
 * Local-inbound listener state for the
 * [XrayTunnelTopology.TunToLocalInbound] topology. The TUN forwarder cannot
 * dial Xray until this reaches [Bound].
 */
enum class XrayListenerState {
    Unknown,
    Binding,
    Bound,
    BindFailed,
}

/**
 * Outbound health as observed by a provider-path probe. Separate from the
 * tunnel rx/tx counters: a healthy outbound with a stalled tunnel is a
 * distinct, diagnosable condition.
 */
enum class XrayOutboundHealth {
    Unknown,
    Probing,
    Reachable,
    Unreachable,
}

/**
 * Classifies a PROVIDER-level failure so Home can render it DISTINCTLY from a
 * tunnel failure. Each variant maps to a different user-facing remediation.
 *
 * `protect`-loop and DNS-loop variants exist because those are the two
 * Android-topology hazards called out for the Xray provider (the
 * [XrayTunnelTopology] doc and the VpnService.protect invariant): they present
 * as "connected but no traffic" and are otherwise indistinguishable from a
 * dead server without this typing.
 */
@Serializable
enum class XrayProviderFailureClass {
    /** No provider failure observed. */
    None,

    /** Engine binary/bridge failed to start or crashed. */
    EngineStartFailure,

    /** Local inbound could not bind its loopback port. */
    ListenerBindFailure,

    /** Rendered config was rejected by [XrayConfigValidator] before start. */
    ConfigInvalid,

    /**
     * A socket the Xray runtime opened was not protected via VpnService.protect
     * (or the protect call failed), so its traffic is captured by the TUN route.
     * Presents as an outbound that "connects" but never completes a handshake.
     */
    ProtectFailure,

    /**
     * Xray DNS egress re-entered the tunnel (the DNS-loop hazard of
     * [XrayTunnelTopology.LibXraySetTunFd] without a bypass rule). Suspected,
     * not proven, from a symptom heuristic.
     */
    DnsLoopSuspected,

    /** Outbound handshake/probe never reached the upstream server. */
    OutboundUnreachable,

    /** Engine up and reachable, but throughput is pathologically degraded. */
    OutboundDegraded,
}

/**
 * A single config-validation finding carried into telemetry. Mirrors
 * [XrayConfigValidator.ValidationError] but is [Serializable] and free of any
 * config content beyond the (already non-secret) error code, JSON path, and
 * message.
 */
@Serializable
data class XrayConfigValidationFinding(
    val code: XrayConfigValidator.ErrorCode,
    val path: String,
    val message: String,
) {
    companion object {
        fun from(error: XrayConfigValidator.ValidationError): XrayConfigValidationFinding =
            XrayConfigValidationFinding(
                code = error.code,
                path = error.path,
                message = error.message,
            )
    }
}

/**
 * Provider-side runtime snapshot for the Xray engine.
 *
 * Every field is already privacy-safe: versions, states, latency numbers, and
 * error *classes* only. The active profile is referenced by [profileName]
 * (a human label, not the config) so summaries can name it without leaking the
 * endpoint. Outbound identity, when surfaced, must go through [redactedSummary].
 *
 * This is the provider analogue of [com.poyka.ripdpi.data.NativeRuntimeSnapshot];
 * a consumer holds BOTH — this for the engine, that for the tunnel data plane.
 */
@Serializable
data class XrayProviderSnapshot(
    val schemaVersion: Int = XrayProviderDiagnostics.SCHEMA_VERSION,
    /** libXray / xray-core version string, e.g. `25.x.y`. Null until probed. */
    val xrayVersion: String? = null,
    val readiness: XrayProviderReadiness = XrayProviderReadiness.Unknown,
    val listenerState: XrayListenerState = XrayListenerState.Unknown,
    val outboundHealth: XrayOutboundHealth = XrayOutboundHealth.Unknown,
    val failureClass: XrayProviderFailureClass = XrayProviderFailureClass.None,
    /** Tunnel topology in effect; affects DNS-loop / protect surface. */
    val tunnelTopology: String? = null,
    /** Loopback inbound the TUN forwarder dials (host:port, both loopback). */
    val localInboundListen: String? = null,
    val localInboundPort: Int? = null,
    /** Human label of the active profile. NOT the config; safe to display. */
    val profileName: String? = null,
    /** Outbound protocol kind, e.g. `vless`. Safe; no endpoint. */
    val outboundProtocol: String? = null,
    /** Stream security, e.g. `reality` / `tls`. Safe; no endpoint. */
    val outboundSecurity: String? = null,
    /** Last safe ping RTT through the provider path, if measured. */
    val lastPingRttMs: Long? = null,
    /** Config-validation findings carried from the last render. */
    val configFindings: List<XrayConfigValidationFinding> = emptyList(),
    /**
     * Last provider failure detail as an ALREADY-REDACTED string. Producers
     * MUST pass this through [XrayProfileRedactor.redactText]. Null when no
     * failure.
     */
    val lastFailureDetailRedacted: String? = null,
    val capturedAt: Long = 0,
) {
    /** True when the rendered config had at least one validation finding. */
    val hasConfigErrors: Boolean
        get() = configFindings.isNotEmpty()

    companion object {
        fun idle(): XrayProviderSnapshot = XrayProviderSnapshot()
    }
}

/**
 * Home connection staging for the Xray provider, kept DISTINCT from tunnel
 * staging so the UI can tell "provider not ready" apart from "tunnel down".
 *
 * Linear happy path:
 * ```
 * Idle -> ValidatingConfig -> StartingEngine -> ListenerReady -> ProbingOutbound -> Connected
 * ```
 * Any stage may transition to [ProviderFailed] (carrying a
 * [XrayProviderFailureClass]) — that is the provider-failure branch the task
 * requires to be visually separate from a tunnel failure.
 */
@Serializable
sealed interface XrayConnectionStage {
    @Serializable
    data object Idle : XrayConnectionStage

    @Serializable
    data object ValidatingConfig : XrayConnectionStage

    @Serializable
    data object StartingEngine : XrayConnectionStage

    @Serializable
    data object ListenerReady : XrayConnectionStage

    @Serializable
    data object ProbingOutbound : XrayConnectionStage

    @Serializable
    data object Connected : XrayConnectionStage

    /** PROVIDER-level failure. Distinct from any tunnel-data-plane failure. */
    @Serializable
    data class ProviderFailed(
        val failureClass: XrayProviderFailureClass,
        /** Already-redacted detail; never a raw endpoint or secret. */
        val detailRedacted: String? = null,
    ) : XrayConnectionStage

    companion object {
        /**
         * Whether transitioning `from -> to` is permitted. The happy path is
         * linear; [ProviderFailed] is reachable from any non-terminal stage and
         * [Idle] is reachable from any stage (reset / stop).
         */
        fun canTransition(
            from: XrayConnectionStage,
            to: XrayConnectionStage,
        ): Boolean {
            if (to is Idle) return true
            if (to is ProviderFailed) return from !is ProviderFailed
            return when (from) {
                is Idle -> to is ValidatingConfig
                is ValidatingConfig -> to is StartingEngine
                is StartingEngine -> to is ListenerReady
                is ListenerReady -> to is ProbingOutbound
                is ProbingOutbound -> to is Connected
                is Connected -> false
                is ProviderFailed -> false
            }
        }

        /** Derive the Home stage from a provider snapshot. */
        fun fromSnapshot(snapshot: XrayProviderSnapshot): XrayConnectionStage =
            when {
                snapshot.failureClass != XrayProviderFailureClass.None -> {
                    ProviderFailed(
                        failureClass = snapshot.failureClass,
                        detailRedacted = snapshot.lastFailureDetailRedacted,
                    )
                }

                snapshot.hasConfigErrors -> {
                    ProviderFailed(failureClass = XrayProviderFailureClass.ConfigInvalid)
                }

                snapshot.readiness == XrayProviderReadiness.OutboundHealthy -> {
                    Connected
                }

                snapshot.readiness == XrayProviderReadiness.ListenerReady -> {
                    ProbingOutbound
                }

                snapshot.readiness == XrayProviderReadiness.Starting -> {
                    StartingEngine
                }

                else -> {
                    Idle
                }
            }
    }
}

/**
 * Which provider-path check a Diagnostics run executed. NOT a reachability
 * scanner — every kind is tied to the ACTIVE profile / running provider and is
 * user-triggered.
 */
@Serializable
enum class XrayProviderProbeKind {
    /** libXray wrapper `XrayVersion` — pure version read; safe everywhere. */
    Version,

    /** libXray wrapper `Ping` through the active outbound — safe in TUN mode. */
    WrapperPing,

    /** Local inbound listener readiness check over loopback — safe everywhere. */
    ListenerReadiness,

    /**
     * Xray stat API read. UNVERIFIED IN CI: only valid when the engine runs as
     * a child process with the gRPC stat API enabled, which is NOT the default
     * Android in-process topology. Flagged so callers gate it behind a
     * capability check before invoking.
     */
    StatApi,
}

/** Result of one provider-path probe. */
@Serializable
data class XrayProviderProbeResult(
    val kind: XrayProviderProbeKind,
    val ok: Boolean,
    val latencyMs: Long? = null,
    /** Already-redacted detail string; never a raw endpoint. */
    val detailRedacted: String? = null,
)

/**
 * Aggregated, user-triggered provider-path diagnostics tied to the active
 * profile. Built from [XrayProviderProbeResult]s plus the engine snapshot.
 */
@Serializable
data class XrayProviderProbeReport(
    val snapshot: XrayProviderSnapshot,
    val probes: List<XrayProviderProbeResult> = emptyList(),
) {
    /** Overall verdict: every executed probe succeeded and no config errors. */
    val allHealthy: Boolean
        get() =
            !snapshot.hasConfigErrors &&
                snapshot.failureClass == XrayProviderFailureClass.None &&
                probes.isNotEmpty() &&
                probes.all { it.ok }
}
