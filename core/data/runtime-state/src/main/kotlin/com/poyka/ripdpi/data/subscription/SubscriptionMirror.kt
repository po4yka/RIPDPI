package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.Serializable

/**
 * Delivery-plane classification for a [SubscriptionMirror].
 *
 * [CLOUDFLARE] mirrors are treated as optional rather than authoritative: a
 * single bearer URL behind Cloudflare is a critical failure point, so
 * [SubscriptionMirrorSet.refreshOrder] floats [DIRECT] mirrors ahead of
 * Cloudflare ones.
 */
@Serializable
enum class SubscriptionMirrorTransport {
    /** Direct, non-Cloudflare delivery — preferred for refresh attempts. */
    DIRECT,

    /** A Cloudflare-fronted mirror — usable, but never the authoritative plane. */
    CLOUDFLARE,
}

/**
 * Health of a single mirror after the most recent refresh attempt.
 */
@Serializable
enum class SubscriptionMirrorState {
    /** Not yet attempted, or attempted and succeeded. */
    HEALTHY,

    /** The most recent attempt against this mirror failed. */
    DEGRADED,
}

/**
 * One scoped delivery mirror for a per-device subscription. Each mirror carries
 * its own scoped bearer [token]; a shared all-user URL is intentionally not
 * representable — mirror support must not weaken per-device token scope.
 *
 * Neither [url] nor [token] may appear in any log or diagnostic surface; use
 * [toRedactedLine] for anything user- or log-facing.
 */
@Serializable
data class SubscriptionMirror(
    val id: String,
    val url: String,
    val token: String = "",
    val transport: SubscriptionMirrorTransport = SubscriptionMirrorTransport.DIRECT,
) {
    /** Host component of [url], or `"<unparseable-host>"` when the URL has none. */
    val host: String
        get() =
            runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "<unparseable-host>"

    /**
     * A redacted, log-safe single-line description: transport and host only,
     * never the full URL path and never the token.
     */
    fun toRedactedLine(): String = "${transport.name.lowercase()} mirror $id @ $host"
}

/** Result of fetching one mirror inside a [SubscriptionMirrorSet.runRefresh] pass. */
sealed interface MirrorFetchOutcome {
    /** The mirror returned a usable subscription [payload]. */
    data class Success(
        val payload: String,
    ) : MirrorFetchOutcome

    /** The mirror failed; [reason] is a short, non-secret diagnostic string. */
    data class Failure(
        val reason: String,
    ) : MirrorFetchOutcome
}

/**
 * UI-facing summary of a refresh run. Carries only redacted, host-level labels
 * so the screen can show which mirror last succeeded and which are degraded,
 * without exposing any secret.
 */
data class SubscriptionMirrorUiSummary(
    val lastRefreshMirrorLabel: String,
    val degradedMirrorLabels: List<String>,
)

/** Outcome of a full [SubscriptionMirrorSet.runRefresh] pass. */
data class SubscriptionRefreshResult(
    val succeeded: Boolean,
    val winningMirrorId: String?,
    val payload: String?,
    val mirrorStates: Map<String, SubscriptionMirrorState>,
    private val mirrorsById: Map<String, SubscriptionMirror>,
) {
    /**
     * Builds the redacted UI summary: the host-level label of the mirror that
     * last succeeded, plus the host-level labels of every degraded mirror.
     */
    fun toUiSummary(): SubscriptionMirrorUiSummary {
        val winningLabel =
            winningMirrorId
                ?.let { mirrorsById[it]?.toRedactedLine() }
                ?: "no mirror succeeded"
        val degraded =
            mirrorStates
                .filterValues { it == SubscriptionMirrorState.DEGRADED }
                .keys
                .mapNotNull { mirrorsById[it]?.toRedactedLine() }
        return SubscriptionMirrorUiSummary(
            lastRefreshMirrorLabel = winningLabel,
            degradedMirrorLabels = degraded,
        )
    }
}

/**
 * The ordered set of delivery mirrors attached to one physical device's
 * subscription.
 *
 * [refreshOrder] is stable: [SubscriptionMirrorTransport.DIRECT] mirrors come
 * first in their declared relative order, then [SubscriptionMirrorTransport.CLOUDFLARE]
 * mirrors in their declared relative order. [runRefresh] walks that order,
 * stopping at the first [MirrorFetchOutcome.Success]; a Cloudflare failure
 * never blocks a later direct mirror because the direct mirrors are tried
 * first anyway, and any failure simply advances to the next mirror.
 */
@Serializable
data class SubscriptionMirrorSet(
    val mirrors: List<SubscriptionMirror> = emptyList(),
) {
    /**
     * Returns [mirrors] reordered so non-Cloudflare direct delivery is tried
     * first. Relative declared order within each transport class is preserved
     * (a stable partition).
     */
    fun refreshOrder(): List<SubscriptionMirror> {
        val (direct, cloudflare) =
            mirrors.partition { it.transport == SubscriptionMirrorTransport.DIRECT }
        return direct + cloudflare
    }

    /**
     * Walks [refreshOrder], invoking [fetch] per mirror until one succeeds. The
     * first success short-circuits the remaining mirrors — they are not
     * fetched. The winning mirror is recorded [SubscriptionMirrorState.HEALTHY];
     * every other mirror in the set (whether it failed before the winner or was
     * never reached) is recorded [SubscriptionMirrorState.DEGRADED]. A
     * Cloudflare mirror failure therefore never blocks a direct mirror: direct
     * mirrors are tried first, and any failure simply advances to the next.
     *
     * An empty mirror set is a no-op: a failed result with no states.
     */
    fun runRefresh(fetch: (SubscriptionMirror) -> MirrorFetchOutcome): SubscriptionRefreshResult {
        val byId = mirrors.associateBy { it.id }
        var winner: SubscriptionMirror? = null
        var winningPayload: String? = null
        for (mirror in refreshOrder()) {
            when (val outcome = fetch(mirror)) {
                is MirrorFetchOutcome.Success -> {
                    winner = mirror
                    winningPayload = outcome.payload
                    break
                }

                is MirrorFetchOutcome.Failure -> {
                    Unit
                }
            }
        }
        // Every mirror except the winner is reported degraded: a non-winning
        // mirror is, for this refresh, not the plane that delivered.
        val states =
            mirrors.associate { mirror ->
                mirror.id to
                    if (mirror.id == winner?.id) {
                        SubscriptionMirrorState.HEALTHY
                    } else {
                        SubscriptionMirrorState.DEGRADED
                    }
            }
        return SubscriptionRefreshResult(
            succeeded = winner != null,
            winningMirrorId = winner?.id,
            payload = winningPayload,
            mirrorStates = states,
            mirrorsById = byId,
        )
    }

    /**
     * Renders the whole set as a redacted, log-safe multi-line block: one
     * [SubscriptionMirror.toRedactedLine] per mirror. No token, no full URL.
     */
    fun toRedactedDiagnostics(): String =
        if (mirrors.isEmpty()) {
            "subscription mirror set: empty"
        } else {
            buildString {
                append("subscription mirror set (${mirrors.size}):")
                mirrors.forEach { append("\n  - ").append(it.toRedactedLine()) }
            }
        }
}
