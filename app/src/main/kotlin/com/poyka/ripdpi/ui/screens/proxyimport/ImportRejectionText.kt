package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.subscription.ImportRejection
import com.poyka.ripdpi.data.subscription.SingBoxSkipReason
import com.poyka.ripdpi.data.subscription.SingBoxSkippedNode

private const val MaxListedEntries = 5

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

/** The first skipped entries, one per line, as "tag: reason". */
@Composable
internal fun skippedEntriesText(skipped: List<SingBoxSkippedNode>): String {
    val lines = skipped.take(MaxListedEntries).map { skippedEntryText(it) }
    return (if (skipped.size > MaxListedEntries) lines + "…" else lines).joinToString("\n")
}

/** [SingBoxSkippedNode.detail] is a protocol identifier or a static backend reason, so it is shown as is. */
@Composable
private fun skippedEntryText(node: SingBoxSkippedNode): String {
    val detail = node.detail
    val unsupported =
        node.reason != SingBoxSkipReason.INVALID_SETTINGS && node.reason != SingBoxSkipReason.INVALID_NETWORK_POLICY
    return if (unsupported && detail != null) {
        stringResource(R.string.import_entry_unsupported, node.label, detail)
    } else {
        stringResource(R.string.import_entry_invalid, node.label) + detail?.let { " ($it)" }.orEmpty()
    }
}
