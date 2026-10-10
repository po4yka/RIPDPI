package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.PmtuProbeStatus

internal fun PmtuProbeStatus.pmtuLabelResource(): Int =
    when (this) {
        PmtuProbeStatus.OBSERVED -> R.string.diagnostics_pmtu_observed
        PmtuProbeStatus.INCONCLUSIVE -> R.string.diagnostics_pmtu_inconclusive
        PmtuProbeStatus.FAILED -> R.string.diagnostics_ip_failed
        PmtuProbeStatus.TIMEOUT -> R.string.diagnostics_ip_timeout
        PmtuProbeStatus.CANCELLED -> R.string.diagnostics_ip_cancelled
        PmtuProbeStatus.UNSUPPORTED -> R.string.diagnostics_ip_unsupported
        PmtuProbeStatus.INVALID -> R.string.diagnostics_ip_invalid
        PmtuProbeStatus.NOT_OBSERVED -> R.string.diagnostics_ip_missing
    }

internal fun pmtuReasonResource(reason: String?): Int =
    when (reason) {
        "no_acknowledged_probe" -> R.string.diagnostics_pmtu_no_ack
        "fragmentation_possible", "fragmentation_unsupported" -> R.string.diagnostics_pmtu_fragmentation_unavailable
        "connection_closed" -> R.string.diagnostics_pmtu_partial
        "no_address", "no_family_address" -> R.string.diagnostics_http3_no_addresses
        "quic_error" -> R.string.diagnostics_http3_connect_error
        "observation_complete" -> R.string.diagnostics_pmtu_window
        else -> http3ReasonResource(reason)
    }
