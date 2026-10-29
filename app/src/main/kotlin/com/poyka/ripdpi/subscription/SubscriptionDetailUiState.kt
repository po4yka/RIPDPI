package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionKind

/** Number of leading characters kept when redacting a secret for display. */
private const val RedactPrefixLength = 8
private const val MillisPerSecond = 1_000L

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
    val tokenExpiryMillis: Long?,
    val credentialExpiryMillis: Long?,
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
        tokenExpiryMillis = subscription.tokenExpiresAtEpochMillis,
        credentialExpiryMillis = subscription.expiryDate.takeIf { it > 0L }?.times(MillisPerSecond),
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
