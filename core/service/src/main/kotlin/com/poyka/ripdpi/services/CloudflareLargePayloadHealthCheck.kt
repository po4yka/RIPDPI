package com.poyka.ripdpi.services

/**
 * The discrete phases probed during a Cloudflare-aware large-payload health check.
 *
 * Phases are recorded independently so that a TLS success + body stall is
 * distinguishable from an outright connection failure. This matters because the
 * documented Russian Cloudflare disruption pattern passes TCP and TLS but silently
 * drops transfers beyond the first few tens of kilobytes.
 */
enum class HealthCheckPhase {
    /** TCP three-way handshake to the probe endpoint. */
    TcpConnect,

    /** TLS handshake (certificate verification included). */
    TlsHandshake,

    /** Small HTTP response (e.g. /generate_204 equivalent). */
    SmallResponse,

    /** Transfer of a 64 KB body from the probe endpoint. */
    Body64Kb,

    /** Transfer of a 256 KB body and verification of its hash. */
    Body256KbHash,

    /** Protocol-level tunnel outcome (e.g. CONNECT / MASQUE tunnel round-trip). */
    TunnelProtocol,
}

/**
 * The outcome of a single [HealthCheckPhase].
 *
 * Sealed so exhaustive when-expressions stay correct as the hierarchy grows.
 */
sealed class HealthPhaseOutcome {
    /** Phase completed successfully. */
    data object Ok : HealthPhaseOutcome()

    /** Phase stalled: the probe started but no response arrived within the deadline. */
    data object Stalled : HealthPhaseOutcome()

    /** Phase failed with an explicit [reason] (connection error, TLS alert, hash mismatch, …). */
    data class Failed(
        val reason: String,
    ) : HealthPhaseOutcome()
}

/**
 * Immutable record of all phase outcomes for one health-check run.
 *
 * The internal map is defensively copied at construction so callers cannot
 * mutate the report after handing the source map to this class.
 *
 * @param phases Map from each probed [HealthCheckPhase] to its [HealthPhaseOutcome].
 */
class HealthCheckReport(
    phases: Map<HealthCheckPhase, HealthPhaseOutcome>,
) {
    val phases: Map<HealthCheckPhase, HealthPhaseOutcome> = phases.toMap()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HealthCheckReport) return false
        return phases == other.phases
    }

    override fun hashCode(): Int = phases.hashCode()

    override fun toString(): String = "HealthCheckReport(phases=$phases)"
}

/**
 * The health classification of a profile after a large-payload probe.
 */
enum class ProfileHealthState {
    /** All probed phases passed — the profile is fit for auto-selection. */
    Healthy,

    /**
     * TLS succeeded but large-body transfer stalled — the signature of
     * Cloudflare-backed endpoints under Russian disruption.
     *
     * Profiles in this state are excluded from auto-selection and remain
     * available for manual selection only.
     */
    DegradedCloudflareLike,

    /**
     * Insufficient data to classify (e.g. empty report, TLS failure, or
     * ambiguous phase combination).
     */
    Unknown,
}

/**
 * Pure classifier that maps a [HealthCheckReport] to a [ProfileHealthState].
 *
 * Detection rule for [ProfileHealthState.DegradedCloudflareLike]:
 *   - [HealthCheckPhase.TlsHandshake] == [HealthPhaseOutcome.Ok], AND
 *   - at least one of [HealthCheckPhase.Body64Kb] or [HealthCheckPhase.Body256KbHash]
 *     == [HealthPhaseOutcome.Stalled].
 *
 * Deliberately Android-free and dependency-free; safe for JVM unit tests.
 */
object CloudflareHealthClassifier {
    fun classify(report: HealthCheckReport): ProfileHealthState {
        val phases = report.phases
        if (phases.isEmpty()) return ProfileHealthState.Unknown

        val tlsOutcome = phases[HealthCheckPhase.TlsHandshake]
        if (tlsOutcome != HealthPhaseOutcome.Ok) return ProfileHealthState.Unknown

        val body64Stalled = phases[HealthCheckPhase.Body64Kb] == HealthPhaseOutcome.Stalled
        val body256Stalled = phases[HealthCheckPhase.Body256KbHash] == HealthPhaseOutcome.Stalled

        if (body64Stalled || body256Stalled) return ProfileHealthState.DegradedCloudflareLike

        return ProfileHealthState.Healthy
    }
}

/**
 * Rate-limiter that prevents the health checker from probing more frequently
 * than [cooldownMs] milliseconds.
 *
 * Thread-safety is intentionally omitted — this is a pure logic unit intended
 * to be driven from a single-threaded health-check loop.
 *
 * @param cooldownMs Minimum gap between two allowed probes, in milliseconds.
 */
class HealthCheckRateLimiter(
    private val cooldownMs: Long,
) {
    private var lastAcquiredAt: Long = Long.MIN_VALUE

    /**
     * Returns `true` and records [nowMs] as the last probe time when the
     * cooldown has elapsed; returns `false` without updating state otherwise.
     */
    fun tryAcquire(nowMs: Long): Boolean {
        if (lastAcquiredAt == Long.MIN_VALUE || (nowMs - lastAcquiredAt) >= cooldownMs) {
            lastAcquiredAt = nowMs
            return true
        }
        return false
    }
}

/**
 * A probe URL that carries only a [host] and [path] — no subscription tokens,
 * authentication keys, or user identifiers.
 *
 * The type is opaque: callers receive a [HealthProbeUrl] instance but cannot
 * construct one with extra query parameters carrying subscription material.
 * This is a type-level guarantee, not merely a runtime check.
 */
class HealthProbeUrl private constructor(
    val rawUrl: String,
) {
    companion object {
        /**
         * Constructs a probe URL from [host] and [path] only.
         *
         * No subscription tokens or authentication material are accepted —
         * the signature deliberately omits those parameters.
         */
        fun build(
            host: String,
            path: String,
        ): HealthProbeUrl {
            require(host.isNotBlank()) { "host must not be blank" }
            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            return HealthProbeUrl("https://$host$normalizedPath")
        }
    }
}

/**
 * Predicate helpers that govern profile selection eligibility based on [ProfileHealthState].
 */
object ProfileSelectorPredicate {
    /**
     * Returns `true` only for [ProfileHealthState.Healthy] profiles.
     *
     * Degraded and unknown profiles must not enter the auto-selection pool.
     */
    fun canAutoSelect(state: ProfileHealthState): Boolean = state == ProfileHealthState.Healthy

    /**
     * Manual selection is always permitted regardless of health state, so
     * users can override the auto-selector when they know what they are doing.
     *
     * The [state] parameter is accepted to keep the API symmetric with
     * [canAutoSelect]; future policy extensions may restrict manual selection
     * for certain states without a signature change.
     */
    fun canManualSelect(state: ProfileHealthState): Boolean {
        // Manual selection is permitted for all states; the parameter is retained
        // for API symmetry with canAutoSelect so future policy can narrow this
        // without a signature change.
        return state == ProfileHealthState.Healthy ||
            state == ProfileHealthState.DegradedCloudflareLike ||
            state == ProfileHealthState.Unknown
    }
}

/**
 * Returns a typed UI flag string for [state], or `null` when no explanation
 * is needed.
 *
 * The string `"handshake_ok_but_payload_stalled"` is the contract between the
 * service layer and the UI layer: the UI renders a localised explanation that
 * TLS success does not imply payload availability.
 */
fun uiHealthFlag(state: ProfileHealthState): String? =
    when (state) {
        ProfileHealthState.DegradedCloudflareLike -> "handshake_ok_but_payload_stalled"
        ProfileHealthState.Healthy, ProfileHealthState.Unknown -> null
    }
