package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionKind

/** HTTP status codes that classify a subscription refresh failure. */
private const val HttpForbidden = 403
private const val HttpGone = 410
private const val HttpTooManyRequests = 429

/** Number of leading characters kept when redacting a secret for display. */
private const val RedactPrefixLength = 8

/**
 * Detail view-state for a single subscription, treated as a device-scoped
 * credential rather than an anonymous URL.
 *
 * Secrets ([displayLink], [displayToken]) are redacted unless [linkRevealed] —
 * the user must explicitly opt in to reveal them, and redacted values are what
 * screenshots and exports capture. [isBootstrap] marks a one-time bootstrap
 * token distinct from a persistent long-lived subscription token, and
 * [refreshable] is false for any bootstrap (consumed or not) since a bootstrap
 * URL must never be re-fetched.
 */
data class SubscriptionDetailUiState(
    val displayLink: String,
    val displayToken: String,
    val linkRevealed: Boolean,
    val isBootstrap: Boolean,
    val refreshable: Boolean,
    val consumedAtMillis: Long?,
    val lastUpdatedMillis: Long,
    val tokenExpiryMillis: Long,
    val credentialExpiryMillis: Long,
)

/**
 * Derives a [SubscriptionDetailUiState] from a stored [Subscription].
 *
 * When [revealSecrets] is false the link and token are redacted to a short
 * prefix; when true the raw values are surfaced. A bootstrap subscription is
 * never [SubscriptionDetailUiState.refreshable] regardless of consumed state.
 */
fun subscriptionDetailUiState(
    subscription: Subscription,
    revealSecrets: Boolean,
): SubscriptionDetailUiState {
    val isBootstrap = subscription.kind == SubscriptionKind.BOOTSTRAP
    return SubscriptionDetailUiState(
        displayLink = if (revealSecrets) subscription.link else redactSecret(subscription.link),
        displayToken = if (revealSecrets) subscription.token else redactSecret(subscription.token),
        linkRevealed = revealSecrets,
        isBootstrap = isBootstrap,
        refreshable = !isBootstrap,
        consumedAtMillis = subscription.consumedAt,
        lastUpdatedMillis = subscription.lastUpdated,
        tokenExpiryMillis = subscription.expiryDate,
        credentialExpiryMillis = subscription.expiryDate,
    )
}

/**
 * Redacts a secret to a short non-reversible preview: the first
 * [RedactPrefixLength] characters followed by an ellipsis marker. A blank
 * secret renders as an explicit placeholder so the UI never shows an empty
 * field that could be mistaken for "no credential".
 */
private fun redactSecret(secret: String): String =
    when {
        secret.isBlank() -> "(none)"
        secret.length <= RedactPrefixLength -> "*".repeat(secret.length)
        else -> secret.take(RedactPrefixLength) + "..."
    }

/**
 * Outcome of the shared-link heuristic: a subscription payload that looks like
 * it was issued to a fleet rather than a single device.
 */
sealed interface SharedLinkWarning {
    /** The payload looks single-user; no warning surfaced. */
    data object None : SharedLinkWarning

    /** The payload looks shared; [reason] is a short heuristic explanation. */
    data class Detected(
        val reason: String,
    ) : SharedLinkWarning
}

/** A UUID is shared when it appears in more than this many distinct profile lines. */
private const val SharedUuidThreshold = 2

/**
 * Heuristic that flags a subscription payload as shared when the same UUID is
 * reused across multiple nodes — a strong signal the link is a fleet-wide
 * credential rather than a per-device one. Pure, string-only; never throws.
 *
 * The language is intentionally heuristic: third-party providers may not expose
 * enough metadata to prove a token is per-device, so this is a warning, not a
 * verdict.
 */
fun detectSharedLinkWarning(payload: String): SharedLinkWarning {
    val uuidCounts = mutableMapOf<String, Int>()
    payload.lineSequence().forEach { line ->
        val uuid = extractUuid(line) ?: return@forEach
        uuidCounts[uuid] = (uuidCounts[uuid] ?: 0) + 1
    }
    val reusedUuid = uuidCounts.entries.firstOrNull { it.value > SharedUuidThreshold }
    return if (reusedUuid != null) {
        SharedLinkWarning.Detected("the same credential UUID is reused across ${reusedUuid.value} nodes")
    } else {
        SharedLinkWarning.None
    }
}

private val uuidRegex =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private fun extractUuid(line: String): String? = uuidRegex.find(line)?.value?.lowercase()

/**
 * Typed classification of a subscription refresh failure.
 *
 * Each variant is a bare enum constant carrying no URL or token, so a failure
 * can be surfaced to the user and logged without leaking the subscription
 * source.
 */
enum class SubscriptionRefreshFailure {
    /** The subscription token or credential window has expired (HTTP 410). */
    EXPIRED,

    /** The subscription token was revoked server-side (HTTP 403). */
    REVOKED,

    /** The provider is rate-limiting refreshes (HTTP 429). */
    RATE_LIMITED,

    /** The subscription host was unreachable, or answered an unexpected code. */
    UNREACHABLE,
}

/**
 * Classifies a refresh failure from its [httpCode] (or `null` for a transport
 * failure). [url] is accepted only so callers do not have to strip it before
 * calling — it is never read into the result, which keeps the URL out of logs
 * and crash reports.
 */
@Suppress("UNUSED_PARAMETER")
fun classifyRefreshFailure(
    httpCode: Int?,
    url: String,
): SubscriptionRefreshFailure =
    when (httpCode) {
        HttpGone -> SubscriptionRefreshFailure.EXPIRED
        HttpForbidden -> SubscriptionRefreshFailure.REVOKED
        HttpTooManyRequests -> SubscriptionRefreshFailure.RATE_LIMITED
        else -> SubscriptionRefreshFailure.UNREACHABLE
    }
