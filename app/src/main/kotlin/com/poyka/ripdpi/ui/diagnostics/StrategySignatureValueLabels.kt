package com.poyka.ripdpi.ui.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.adaptiveOffsetPreset
import com.poyka.ripdpi.platform.StringResolver
import java.util.Locale

internal class StrategySignatureValueLabels(
    private val stringResolver: StringResolver,
) {
    fun lane(value: String): String {
        val normalized = value.trim().lowercase(Locale.US)
        val circularBase = normalized.removePrefix("circular_")
        if (normalized.startsWith("circular_") && circularBase in laneResources) {
            return stringResolver.getString(
                R.string.diagnostics_signature_lane_circular,
                lane(circularBase),
            )
        }
        return laneResources[normalized]?.let(stringResolver::getString) ?: value
    }

    /** Formats an aggregate winning family such as `split + quic_disabled` the way Remembered networks shows it. */
    fun winningFamily(value: String): String = value.split(" + ").joinToString(" · ") { lane(it) }

    fun offset(value: String): String {
        val resource =
            when (adaptiveOffsetPreset(value)) {
                "balanced" -> R.string.diagnostics_signature_offset_balanced
                "host" -> R.string.diagnostics_signature_offset_host
                "midsld" -> R.string.diagnostics_signature_offset_midsld
                "endhost" -> R.string.diagnostics_signature_offset_endhost
                "method" -> R.string.diagnostics_signature_offset_method
                "sniext" -> R.string.diagnostics_signature_offset_sniext
                "extlen" -> R.string.diagnostics_signature_offset_extlen
                else -> return value
            }
        return stringResolver.getString(resource)
    }

    fun knownValue(value: String): String =
        when (value.lowercase(Locale.US)) {
            "true", "enabled" -> stringResolver.getString(R.string.diagnostics_value_enabled)
            "false", "disabled" -> stringResolver.getString(R.string.adaptive_fallback_badge_disabled)
            "ui" -> stringResolver.getString(R.string.diagnostics_signature_config_ui)
            "command_line" -> stringResolver.getString(R.string.diagnostics_signature_config_command_line)
            "proxy" -> stringResolver.getString(R.string.ripdpi_proxy)
            "none" -> stringResolver.getString(R.string.asset_provider_version_none)
            "plain dns" -> stringResolver.getString(R.string.dns_mode_plain)
            else -> value
        }

    private val laneResources =
        mapOf(
            "hostfake" to R.string.adaptive_fake_ttl_badge_hostfake,
            "seqovl" to R.string.config_chain_step_seqovl_label,
            "tlsrec_seqovl" to R.string.diagnostics_signature_lane_tlsrec_seqovl,
            "fake_approx" to R.string.diagnostics_signature_lane_fake_approx,
            "split" to R.string.config_chain_step_split_label,
            "multidisorder" to R.string.root_mode_strategy_multidisorder_title,
            "disorder" to R.string.config_chain_step_disorder_label,
            "fake" to R.string.adaptive_fake_ttl_badge_fake,
            "oob" to R.string.diagnostics_signature_lane_oob,
            "disoob" to R.string.config_chain_step_disoob_label,
            "tlsrec" to R.string.diagnostics_signature_lane_tlsrec,
            "tlsrandrec" to R.string.diagnostics_signature_lane_tlsrandrec,
            "ipfrag2" to R.string.root_mode_strategy_ipfrag2_title,
            "ech_split" to R.string.diagnostics_signature_lane_ech_split,
            "ech_tlsrec" to R.string.diagnostics_signature_lane_ech_tlsrec,
            "tlsrec_split" to R.string.blockcheck_bypass_tls_record_split,
            "tlsrec_multidisorder" to R.string.diagnostics_signature_lane_tlsrec_multidisorder,
            "tlsrec_disorder" to R.string.diagnostics_signature_lane_tlsrec_disorder,
            "tlsrec_fake" to R.string.diagnostics_signature_lane_tlsrec_fake,
            "fake_flags" to R.string.tcp_flag_card_title,
            "quic_ipfrag2" to R.string.diagnostics_signature_lane_quic_ipfrag2,
            "quic_disabled" to R.string.diagnostics_signature_lane_quic_disabled,
            "quic_compat_burst" to R.string.diagnostics_signature_lane_quic_compat_burst,
            "quic_realistic_burst" to R.string.diagnostics_signature_lane_quic_realistic_burst,
            "quic_burst" to R.string.diagnostics_signature_lane_quic_burst,
            "quic_dummy_prepend" to R.string.diagnostics_signature_lane_quic_dummy_prepend,
            "quic_sni_split" to R.string.config_chain_step_quic_sni_split_label,
            "quic_fake_version" to R.string.config_chain_step_quic_fake_version_label,
            "quic_crypto_split" to R.string.diagnostics_signature_lane_quic_crypto_split,
            "quic_padding_ladder" to R.string.config_chain_step_quic_padding_ladder_label,
            "quic_cid_churn" to R.string.config_chain_step_quic_cid_churn_label,
            "quic_packet_number_gap" to R.string.diagnostics_signature_lane_quic_packet_number_gap,
            "quic_version_negotiation_decoy" to R.string.diagnostics_signature_lane_quic_version_negotiation_decoy,
            "quic_multi_initial_realistic" to R.string.diagnostics_signature_lane_quic_multi_initial_realistic,
            "dns_plain_udp" to R.string.dns_mode_plain,
            "dns_encrypted_doh" to R.string.diagnostics_signature_lane_dns_encrypted_doh,
            "dns_encrypted_dot" to R.string.diagnostics_signature_lane_dns_encrypted_dot,
            "dns_encrypted_dnscrypt" to R.string.diagnostics_signature_lane_dns_encrypted_dnscrypt,
            "dns_encrypted" to R.string.dns_mode_doh,
        )
}
