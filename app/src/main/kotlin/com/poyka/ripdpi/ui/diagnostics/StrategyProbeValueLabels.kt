package com.poyka.ripdpi.ui.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver
import java.util.Locale

/** Display labels for known report tokens. Unknown and user-supplied values stay exact. */
internal class StrategyProbeValueLabels(
    private val stringResolver: StringResolver,
) {
    private val signatureLabels = StrategySignatureValueLabels(stringResolver)

    fun family(value: String): String {
        val token = value.lowercase(Locale.US)
        return familyResources[token]?.let(stringResolver::getString)
            ?: signatureLabels.lane(if (token == "multi_disorder") "multidisorder" else value)
    }

    fun dnsLane(value: String): String =
        when (value) {
            "Plain DNS" -> stringResolver.getString(R.string.dns_mode_plain)
            "Encrypted DNS" -> stringResolver.getString(R.string.dns_mode_doh)
            else -> value
        }

    fun outcome(
        value: String,
        skipped: Boolean,
    ): String {
        val resource =
            if (skipped) {
                R.string.diagnostics_probe_status_skipped
            } else {
                when (value.lowercase(Locale.US)) {
                    "success" -> R.string.diagnostics_approach_metric_success
                    "partial" -> R.string.diagnostics_stages_partial
                    "failed" -> R.string.diagnostics_stages_failed
                    "not_applicable" -> R.string.diagnostics_stages_not_applicable
                    "skipped" -> R.string.diagnostics_probe_status_skipped
                    "eliminated" -> R.string.diagnostics_strategy_outcome_eliminated
                    "unverified_execution" -> R.string.diagnostics_strategy_outcome_unverified_execution
                    else -> return value
                }
            }
        return stringResolver.getString(resource)
    }

    private val familyResources =
        mapOf(
            "baseline" to R.string.diagnostics_strategy_family_baseline,
            "baseline_current" to R.string.diagnostics_strategy_family_baseline_current,
            "parser" to R.string.diagnostics_strategy_family_parser,
            "parser_aggressive" to R.string.diagnostics_strategy_family_parser_aggressive,
            "split" to R.string.diagnostics_strategy_family_host_split,
            "split_delayed" to R.string.diagnostics_strategy_family_split_delayed,
            "split_tfo" to R.string.diagnostics_strategy_family_split_tfo,
            "tlsrec_seqovl" to R.string.diagnostics_strategy_family_tlsrec_seqovl,
            "tlsrec_split_tfo" to R.string.diagnostics_strategy_family_tlsrec_split_tfo,
            "tlsrandrec_split" to R.string.diagnostics_strategy_family_tlsrandrec_split,
            "tlsrandrec_disorder" to R.string.diagnostics_strategy_family_tlsrandrec_disorder,
            "tlsrec_oob" to R.string.diagnostics_strategy_family_tlsrec_oob,
            "tlsrec_disoob" to R.string.diagnostics_strategy_family_tlsrec_disoob,
            "fake_rst" to R.string.config_chain_step_fakerst_label,
            "activation_window" to R.string.activation_window_section_title,
            "adaptive_fake_ttl" to R.string.adaptive_fake_ttl_selector_title,
            "fake_payload_library" to R.string.fake_payload_library_section_title,
            "ipfrag2_ipv6_ext" to R.string.diagnostics_strategy_family_ipfrag2_ipv6_ext,
            "quic_ipfrag2_ipv6_ext" to R.string.diagnostics_strategy_family_quic_ipfrag2_ipv6_ext,
        )
}
