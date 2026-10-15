package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.platform.StringResolver

internal fun StringResolver.homeAppliedSetting(setting: DiagnosticsAppliedSetting): DiagnosticsAppliedSetting {
    val resource =
        homeGeneratedAppliedLabelResources[setting.label]
            ?: homeGeneratedWarpLabelResources[setting.label]
            ?: return setting
    val value =
        when (setting.label) {
            "Resolver", "Protocol", "Chain", "WARP AmneziaWG" -> setting.value
            else -> homeAppliedValue(setting.label, setting.value)
        }
    return setting.copy(label = getString(resource), value = value)
}

private fun StringResolver.homeAppliedValue(
    label: String,
    raw: String,
): String =
    when (label) {
        "TCP/TLS lane", "QUIC lane", "TLS fingerprint", "WARP routing", "WARP control-plane",
        -> {
            homeGeneratedValue(raw)
        }

        "DNS lane" -> {
            if (raw == "Plain DNS") getString(R.string.dns_mode_plain) else raw
        }

        "WARP endpoint" -> {
            if (raw == "Automatic") homeGeneratedValue(raw) else homeAppliedValueTemplate(raw) ?: raw
        }

        "WARP scanner" -> {
            if (raw == "Disabled") homeGeneratedValue(raw) else homeAppliedValueTemplate(raw) ?: raw
        }

        "QUIC resistance" -> {
            raw.split(" · ").joinToString(" · ", transform = ::homeGeneratedValue)
        }

        else -> {
            homeAppliedValueTemplate(raw) ?: raw
        }
    }

private fun StringResolver.homeAppliedValueTemplate(raw: String): String? {
    val template =
        homeAppliedValueTemplates.firstNotNullOfOrNull { (pattern, resource) ->
            pattern.matchEntire(raw)?.let { it to resource }
        }
    val hostCount = homeAppliedHostCount.matchEntire(raw)?.groupValues?.get(1)
    val quantity = hostCount?.toIntOrNull()
    return when {
        template != null -> {
            val (match, resource) = template
            when (resource) {
                R.string.home_generated_morphing -> {
                    getString(
                        resource,
                        homeEntropyMode(match.groups["mode"]?.value.orEmpty()),
                        match.groups["padding"]?.value.orEmpty(),
                        match.groups["shannon"]?.value.orEmpty(),
                    )
                }

                R.string.home_generated_scanner -> {
                    getString(
                        resource,
                        match.groups["parallelism"]?.value.orEmpty(),
                        match.groups["rtt"]?.value.orEmpty(),
                    )
                }

                else -> {
                    getString(resource, match.groupValues[1])
                }
            }
        }

        quantity != null -> {
            getQuantityString(R.plurals.home_generated_host_count, quantity, homeCount(hostCount.orEmpty()))
        }

        else -> {
            null
        }
    }
}

private val homeAppliedHostCount = Regex("([0-9]+) hosts")
private val homeAppliedValueTemplates =
    listOf(
        Regex("Manual port ([0-9]+)") to R.string.home_generated_manual_port,
        Regex("(?<parallelism>[0-9]+) parallel · (?<rtt>[0-9]+) ms max RTT") to R.string.home_generated_scanner,
        Regex("(?<mode>popcount|shannon|combined) · pad (?<padding>[0-9]+) · shannon (?<shannon>[0-9]+)") to
            R.string.home_generated_morphing,
        Regex("([0-9]+) bytes") to R.string.home_generated_bytes,
        Regex("Epsilon (\\p{Nd}+[.,\u066B]\\p{Nd}{2})") to R.string.home_generated_epsilon,
    )

private fun StringResolver.homeEntropyMode(raw: String): String =
    getString(
        when (raw) {
            "popcount" -> R.string.settings_option_popcount_shaping
            "shannon" -> R.string.settings_option_shannon_shaping
            else -> R.string.settings_option_combined_shaping
        },
    )
