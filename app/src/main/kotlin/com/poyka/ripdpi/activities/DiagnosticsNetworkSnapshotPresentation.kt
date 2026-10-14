package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver

internal fun networkSnapshotTitle(
    kind: String,
    strings: StringResolver,
): String =
    when (kind) {
        "pre_scan" -> strings.getString(R.string.diagnostics_snapshot_pre_scan)
        "post_scan" -> strings.getString(R.string.diagnostics_snapshot_post_scan)
        "connection_sample" -> strings.getString(R.string.diagnostics_snapshot_connection_sample)
        "failure" -> strings.getString(R.string.diagnostics_snapshot_failure)
        else -> kind
    }
