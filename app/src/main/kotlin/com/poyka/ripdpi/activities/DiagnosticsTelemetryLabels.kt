package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RttBand
import com.poyka.ripdpi.platform.StringResolver
import java.util.Locale

private const val LowRttThresholdMs = 50
private const val MediumRttThresholdMs = 100
private const val HighRttThresholdMs = 250
private const val VeryHighRttThresholdMs = 500

private fun connectionStateRes(state: String): Int? =
    when (state.lowercase(Locale.ROOT)) {
        "running" -> R.string.history_connection_state_running
        "failed" -> R.string.history_connection_state_failed
        "stopped" -> R.string.history_connection_state_stopped
        "halted" -> R.string.history_connection_state_halted
        "reconnecting" -> R.string.history_connection_state_reconnecting
        else -> null
    }

internal fun StringResolver.connectionStateLabel(state: String): String =
    connectionStateRes(state)?.let(::getString) ?: state

/** The state label used on its own, as a status line, rather than inside a sentence; unknown states stay raw. */
internal fun StringResolver.connectionStateTitle(state: String): String =
    connectionStateRes(state)?.let { getString(it).replaceFirstChar { char -> char.titlecase() } } ?: state

internal fun StringResolver.networkTypeLabel(networkType: String): String =
    when (networkType.lowercase(Locale.ROOT)) {
        "unknown" -> getString(R.string.diagnostics_field_unknown)
        "wifi" -> getString(R.string.remembered_networks_transport_wifi)
        "cellular" -> getString(R.string.remembered_networks_transport_cellular)
        "ethernet" -> getString(R.string.remembered_networks_transport_ethernet)
        else -> networkType
    }

/** Maps stored session end codes to text; null means the code adds nothing to the title and subtitle. */
internal fun StringResolver.endedReasonLabel(endedReason: String?): String? =
    when {
        endedReason == null || endedReason == "stopped" -> null

        endedReason == "process_exit:stopped" -> getString(R.string.history_ended_reason_process_stopped)

        endedReason.startsWith("process_exit:") -> getString(R.string.history_ended_reason_process_ended)

        endedReason.startsWith("failed:") -> getString(R.string.history_ended_reason_failed)

        // An unrecognized code keeps its exact text rather than disappearing.
        else -> endedReason
    }

internal fun StringResolver.rttBandLabel(band: String): String =
    when (band) {
        RttBand.Lt50.wireValue -> {
            getString(R.string.diagnostics_rtt_band_lt50_format, LowRttThresholdMs)
        }

        RttBand.Between50And99.wireValue -> {
            getString(R.string.diagnostics_rtt_band_range_format, LowRttThresholdMs, MediumRttThresholdMs - 1)
        }

        RttBand.Between100And249.wireValue -> {
            getString(R.string.diagnostics_rtt_band_range_format, MediumRttThresholdMs, HighRttThresholdMs - 1)
        }

        RttBand.Between250And499.wireValue -> {
            getString(R.string.diagnostics_rtt_band_range_format, HighRttThresholdMs, VeryHighRttThresholdMs - 1)
        }

        RttBand.Gte500.wireValue -> {
            getString(R.string.diagnostics_rtt_band_500_plus_format, VeryHighRttThresholdMs)
        }

        RttBand.Unknown.wireValue -> {
            getString(R.string.diagnostics_field_unknown)
        }

        else -> {
            band
        }
    }
