package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RttBand
import com.poyka.ripdpi.platform.StringResolver
import java.util.Locale

private const val LowRttThresholdMs = 50
private const val MediumRttThresholdMs = 100
private const val HighRttThresholdMs = 250
private const val VeryHighRttThresholdMs = 500

internal fun StringResolver.connectionStateLabel(state: String): String =
    when (state.lowercase(Locale.ROOT)) {
        "running" -> getString(R.string.history_connection_state_running)
        "failed" -> getString(R.string.history_connection_state_failed)
        "stopped" -> getString(R.string.history_connection_state_stopped)
        "halted" -> getString(R.string.history_connection_state_halted)
        "reconnecting" -> getString(R.string.history_connection_state_reconnecting)
        else -> state
    }

internal fun StringResolver.networkTypeLabel(networkType: String): String =
    if (networkType == "unknown") getString(R.string.diagnostics_field_unknown) else networkType

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
