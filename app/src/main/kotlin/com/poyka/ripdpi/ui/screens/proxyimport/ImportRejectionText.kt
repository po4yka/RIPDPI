package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.subscription.ImportRejection
import com.poyka.ripdpi.data.subscription.SingBoxSkipReason
import com.poyka.ripdpi.data.subscription.SingBoxSkippedNode

private const val MaxListedEntries = 5

// A tag and a payload identifier are free text from the server; long ones are cut for display.
private const val MaxShownLength = 48

/** Why a payload was refused; the generic text when no cause is known. */
@Composable
internal fun importRejectionText(rejection: ImportRejection?): String =
    when (rejection) {
        is ImportRejection.SchemaVersion -> {
            rejection.found?.let {
                stringResource(R.string.import_failure_schema_version, it, rejection.supported)
            }
        }

        is ImportRejection.Entry -> {
            skippedEntryText(rejection.node)
        }

        null -> {
            null
        }
    } ?: stringResource(R.string.import_profile_confirm_error)

/** Why a subscription import failed: the refused payload, a named cause, the HTTP status, or the transport, by that priority. */
@Composable
internal fun subscriptionFailureText(
    rejection: ImportRejection?,
    failure: SubscriptionRefreshFailure?,
    httpStatus: Int?,
): String {
    val named =
        when (failure) {
            SubscriptionRefreshFailure.PARSE_ERROR,
            SubscriptionRefreshFailure.INVALID_PAYLOAD,
            -> R.string.import_subscription_failed_format

            SubscriptionRefreshFailure.PAYLOAD_TOO_LARGE -> R.string.import_subscription_failed_too_large

            SubscriptionRefreshFailure.EXPIRED -> R.string.import_subscription_failed_expired

            SubscriptionRefreshFailure.INVALIDATED,
            SubscriptionRefreshFailure.REVOKED,
            -> R.string.import_subscription_failed_invalid

            else -> null
        }
    return when {
        rejection != null -> {
            importRejectionText(rejection)
        }

        named != null -> {
            stringResource(named)
        }

        httpStatus != null -> {
            stringResource(R.string.import_subscription_failed_http, httpStatus)
        }

        failure == SubscriptionRefreshFailure.NETWORK_ERROR || failure == SubscriptionRefreshFailure.UNREACHABLE -> {
            stringResource(R.string.import_subscription_failed_network)
        }

        else -> {
            stringResource(R.string.import_subscription_failed)
        }
    }
}

/** The first skipped entries, one per line, as "tag: reason". */
@Composable
internal fun skippedEntriesText(skipped: List<SingBoxSkippedNode>): String {
    val lines = skipped.take(MaxListedEntries).map { skippedEntryText(it) }
    return (if (skipped.size > MaxListedEntries) lines + "…" else lines).joinToString("\n")
}

/** [SingBoxSkippedNode.detail] is a protocol identifier or a static backend reason, so it is shown as is. */
@Composable
private fun skippedEntryText(node: SingBoxSkippedNode): String {
    val detail = node.detail?.take(MaxShownLength)
    val label = node.label.take(MaxShownLength)
    val unsupported =
        node.reason != SingBoxSkipReason.INVALID_SETTINGS && node.reason != SingBoxSkipReason.INVALID_NETWORK_POLICY
    return if (unsupported) {
        // An unknown protocol name is not shown, so the entry is named with the reason alone.
        stringResource(R.string.import_entry_unsupported, label, detail ?: node.reason.name.lowercase())
    } else {
        stringResource(R.string.import_entry_invalid, label) + detail?.let { " ($it)" }.orEmpty()
    }
}
