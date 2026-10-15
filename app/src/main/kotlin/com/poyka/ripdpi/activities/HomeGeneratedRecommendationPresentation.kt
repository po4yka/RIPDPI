package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.diagnostics.StrategyProbeValueLabels
import java.util.Locale

internal fun StringResolver.homeRecommendationText(raw: String): String = homeGeneratedRecommendation(raw) ?: raw

internal fun StringResolver.homeGeneratedValue(raw: String): String {
    val resource =
        homeGeneratedFamilyValueResources[raw]
            ?: homeGeneratedSettingValueResources[raw]
            ?: homeGeneratedWarpValueResources[raw]
    val family = homeGeneratedHumanFamilyTokens[raw]
    return when {
        resource != null -> {
            getString(resource)
        }

        raw == "TLS record split + fake" -> {
            getString(R.string.home_generated_family_tlsrec_split_fake)
        }

        raw == "SNI/TLS suspected" -> {
            getString(R.string.home_generated_family_sni_tls_suspect)
        }

        family != null -> {
            StrategyProbeValueLabels(this).family(family)
        }

        else -> {
            raw
        }
    }
}

internal fun StringResolver.homeCount(raw: String): String =
    raw.toLongOrNull()?.let { getString(R.string.diagnostics_http3_number, it) } ?: raw

internal fun StringResolver.homeGeneratedRecommendation(raw: String): String? =
    homeStrategyRationale(raw) ?: homeResolverRationale(raw) ?: homeRecommendationSummary(raw)

private fun StringResolver.homeStrategyRationale(raw: String): String? {
    val reality = homeRealityRationale.matchEntire(raw)
    val strategy = homeStrategyRationale.matchEntire(raw)
    return when {
        reality != null -> {
            getString(R.string.home_generated_reality_rationale, homeCount(reality.groupValues[1]))
        }

        strategy != null -> {
            val (pattern, tcp, services, family) = strategy.destructured
            getString(
                R.string.home_generated_strategy_rationale,
                homeGeneratedValue(pattern),
                tcp
                    .takeIf(
                        String::isNotEmpty,
                    )?.let { getString(R.string.home_generated_tcp_probes, homeCount(it)) }
                    .orEmpty(),
                services
                    .takeIf(String::isNotEmpty)
                    ?.let {
                        getString(R.string.home_generated_blocked_services, homeCount(it))
                    }.orEmpty(),
                homeGeneratedValue(family),
            )
        }

        else -> {
            null
        }
    }
}

private val homeRealityRationale =
    Regex(
        "Reality handshakes completed on ([0-9]+) distinct targets without application data; a QUIC " +
            "Initial response was observed\\. The failure cause is not determined\\.",
    )
private val homeStrategyRationale =
    Regex(
        "Detected (TCP RST injection|threshold-based HTTPS blocking|silent packet drop|mixed DPI " +
            "blocking|DPI blocking)(?: on ([0-9]+) TCP probe\\(s\\))?(?: with ([0-9]+) blocked " +
            "service\\(s\\))?\\. Recommend switching to (.+)\\.",
    )

private fun StringResolver.homeRecommendationSummary(raw: String): String? {
    val confidence = homeAuditSummarySuffix.matchEntire(raw)
    val matrix = homeMatrixRecommendation.matchEntire(raw)
    val quick = homeQuickRecommendation.matchEntire(raw)
    return when {
        confidence != null -> {
            getString(
                R.string.home_generated_audit_suffix,
                homeRecommendationSummary(confidence.groupValues[1]) ?: confidence.groupValues[1],
                homeGeneratedValue(confidence.groupValues[2].lowercase(Locale.ROOT)),
                homeCount(confidence.groupValues[3]),
            )
        }

        matrix != null -> {
            getString(
                R.string.home_generated_recommended_matrix,
                matrix.groupValues[1],
                matrix.groupValues[2],
                *matrix.groupValues
                    .drop(3)
                    .map(::homeCount)
                    .toTypedArray(),
            )
        }

        quick != null -> {
            getString(R.string.home_generated_recommended, quick.groupValues[1], quick.groupValues[2])
        }

        else -> {
            null
        }
    }
}

private val homeAuditSummarySuffix = Regex("(.+) · confidence (LOW|MEDIUM|HIGH) · matrix coverage ([0-9]+)%")
private val homeMatrixRecommendation =
    Regex("Recommended (.+) \\+ (.+)\\. Worked ([0-9]+) · partial ([0-9]+) · failed ([0-9]+) · not applicable ([0-9]+)")
private val homeQuickRecommendation = Regex("Recommended (.+) with (.+)")

private fun StringResolver.homeResolverRationale(raw: String): String? {
    val match = homeResolverRationale.matchEntire(raw) ?: return null
    val values = match.groupValues
    val base =
        getString(
            R.string.home_generated_dns_rationale,
            homeGeneratedValue(values[1]),
            values[2],
            values[3],
            homeGeneratedValue(values[4]),
            values[5],
            homeCount(values[6]),
            homeCount(values[7]),
        )
    return base + homeResolverHints(values[8])
}

private val homeResolverRationale =
    Regex(
        "(UDP|System) DNS showed (\\S+) while (.+) returned (matching|usable) encrypted answers over " +
            "(\\S+) on ([0-9]+) probe\\(s\\) with ([0-9]+) ms average latency\\.(.*)",
    )

private fun StringResolver.homeResolverHints(raw: String): String {
    if (raw.isEmpty()) return raw
    val hint = homeResolverHintResources.entries.firstOrNull { raw.startsWith(" ${it.key}") }
    val template =
        homeResolverHintTemplates.firstNotNullOfOrNull { (pattern, resource) ->
            pattern.find(raw)?.takeIf { it.range.first == 0 }?.let { it to resource }
        }
    return when {
        hint != null -> {
            " " + getString(hint.value) + homeResolverHints(raw.removePrefix(" ${hint.key}"))
        }

        template != null -> {
            val (match, resource) = template
            val value = match.groupValues[1]
            val argument = if (resource == R.string.home_generated_dns_bootstrap) homeCount(value) else value
            " " + getString(resource, argument) + homeResolverHints(raw.substring(match.value.length))
        }

        else -> {
            raw
        }
    }
}

private val homeResolverHintResources =
    mapOf(
        "This network already preferred this encrypted DNS path." to R.string.home_generated_dns_preferred,
        "This matches the currently active encrypted DNS path." to R.string.home_generated_dns_active,
        "This keeps the current encrypted DNS protocol family." to R.string.home_generated_dns_family,
    )
private val homeResolverHintTemplates =
    listOf(
        Regex(" Bootstrap reachability validated on ([0-9]+) probe\\(s\\)\\.") to R.string.home_generated_dns_bootstrap,
        Regex(" This network already leaned toward (\\S+)\\.") to R.string.home_generated_dns_leaned,
        Regex(" Avoided bootstrap IPs blocked by DPI: (.+)\\.$") to R.string.home_generated_dns_avoided,
    )
