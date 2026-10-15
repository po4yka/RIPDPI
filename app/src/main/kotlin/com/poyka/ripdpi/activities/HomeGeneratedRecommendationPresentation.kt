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
            val pattern = strategy.groups["pattern"]?.value.orEmpty()
            val tcp = strategy.groups["tcp"]?.value.orEmpty()
            val services = strategy.groups["services"]?.value.orEmpty()
            val family = strategy.groups["family"]?.value.orEmpty()
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
        "Detected (?<pattern>TCP RST injection|threshold-based HTTPS blocking|silent packet drop|mixed DPI " +
            "blocking|DPI blocking)(?: on (?<tcp>[0-9]+) TCP probe\\(s\\))?(?: with (?<services>[0-9]+) blocked " +
            "service\\(s\\))?\\. Recommend switching to (?<family>.+)\\.",
    )

private fun StringResolver.homeRecommendationSummary(raw: String): String? {
    val confidence = homeAuditSummarySuffix.matchEntire(raw)
    val matrix = homeMatrixRecommendation.matchEntire(raw)
    val quick = homeQuickRecommendation.matchEntire(raw)
    return when {
        confidence != null -> {
            val summary = confidence.groups["summary"]?.value.orEmpty()
            getString(
                R.string.home_generated_audit_suffix,
                homeRecommendationSummary(summary) ?: summary,
                homeGeneratedValue(
                    confidence.groups["level"]
                        ?.value
                        .orEmpty()
                        .lowercase(Locale.ROOT),
                ),
                homeCount(confidence.groups["coverage"]?.value.orEmpty()),
            )
        }

        matrix != null -> {
            getString(
                R.string.home_generated_recommended_matrix,
                matrix.groups["tcp"]?.value.orEmpty(),
                matrix.groups["quic"]?.value.orEmpty(),
                homeCount(matrix.groups["worked"]?.value.orEmpty()),
                homeCount(matrix.groups["partial"]?.value.orEmpty()),
                homeCount(matrix.groups["failed"]?.value.orEmpty()),
                homeCount(matrix.groups["notApplicable"]?.value.orEmpty()),
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

private val homeAuditSummarySuffix =
    Regex("(?<summary>.+) · confidence (?<level>LOW|MEDIUM|HIGH) · matrix coverage (?<coverage>[0-9]+)%")
private val homeMatrixRecommendation =
    Regex(
        "Recommended (?<tcp>.+) \\+ (?<quic>.+)\\. Worked (?<worked>[0-9]+) · partial " +
            "(?<partial>[0-9]+) · failed (?<failed>[0-9]+) · not applicable (?<notApplicable>[0-9]+)",
    )
private val homeQuickRecommendation = Regex("Recommended (.+) with (.+)")

private fun StringResolver.homeResolverRationale(raw: String): String? {
    val match = homeResolverRationale.matchEntire(raw) ?: return null
    val base =
        getString(
            R.string.home_generated_dns_rationale,
            homeGeneratedValue(match.groups["dnsSource"]?.value.orEmpty()),
            match.groups["trigger"]?.value.orEmpty(),
            match.groups["resolver"]?.value.orEmpty(),
            homeGeneratedValue(match.groups["answerQuality"]?.value.orEmpty()),
            match.groups["protocol"]?.value.orEmpty(),
            homeCount(match.groups["probeCount"]?.value.orEmpty()),
            homeCount(match.groups["latency"]?.value.orEmpty()),
        )
    return base + homeResolverHints(match.groups["hints"]?.value.orEmpty())
}

private val homeResolverRationale =
    Regex(
        "(?<dnsSource>UDP|System) DNS showed (?<trigger>\\S+) while (?<resolver>.+) returned " +
            "(?<answerQuality>matching|usable) encrypted answers over " +
            "(?<protocol>\\S+) on (?<probeCount>[0-9]+) probe\\(s\\) with (?<latency>[0-9]+) ms " +
            "average latency\\.(?<hints>.*)",
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
