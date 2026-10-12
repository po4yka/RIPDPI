package com.poyka.ripdpi.ui.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsFieldUiModel
import com.poyka.ripdpi.activities.DiagnosticsUiFactorySupport
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.platform.StringResolver
import java.util.Locale

internal class StrategySignaturePresenter(
    private val stringResolver: StringResolver,
) {
    private val valueLabels = StrategySignatureValueLabels(stringResolver)

    fun fields(signature: BypassStrategySignature): List<DiagnosticsFieldUiModel> =
        buildList {
            addCoreSignatureFields(signature)
            addStrategyLaneSignatureFields(signature)
            addProtocolSignatureFields(signature)
            addFakeTtlSignatureFields(signature)
            addFakeTlsSignatureFields(signature)
            addFakePayloadSignatureFields(signature)
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_field_route_group),
                    signature.routeGroup ?: stringResolver.getString(R.string.diagnostics_local_unknown),
                ),
            )
        }

    private fun MutableList<DiagnosticsFieldUiModel>.addCoreSignatureFields(signature: BypassStrategySignature) {
        add(
            DiagnosticsFieldUiModel(
                stringResolver.getString(R.string.home_stat_mode),
                valueLabels.knownValue(signature.mode),
            ),
        )
        add(
            DiagnosticsFieldUiModel(
                stringResolver.getString(R.string.diagnostics_field_config_source),
                valueLabels.knownValue(signature.configSource),
            ),
        )
        add(
            DiagnosticsFieldUiModel(
                stringResolver.getString(R.string.diagnostics_field_autolearn),
                valueLabels.knownValue(signature.hostAutolearn),
            ),
        )
        add(DiagnosticsFieldUiModel(stringResolver.getString(R.string.diagnostics_field_chain), signature.chainSummary))
        add(
            DiagnosticsFieldUiModel(
                stringResolver.getString(R.string.diagnostics_field_desync),
                valueLabels.knownValue(signature.desyncMethod),
            ),
        )
    }

    private fun MutableList<DiagnosticsFieldUiModel>.addStrategyLaneSignatureFields(
        signature: BypassStrategySignature,
    ) {
        signature.tcpStrategyFamily?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_tcp_tls_lane),
                    valueLabels.lane(it),
                ),
            )
        }
        signature.quicStrategyFamily?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_quic_lane),
                    valueLabels.lane(it),
                ),
            )
        }
        signature.dnsStrategyLabel?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_audit_winning_dns_lane),
                    valueLabels.knownValue(it),
                ),
            )
        }
    }

    private fun MutableList<DiagnosticsFieldUiModel>.addProtocolSignatureFields(signature: BypassStrategySignature) {
        add(
            DiagnosticsFieldUiModel(
                stringResolver.getString(R.string.adaptive_split_summary_label_protocols),
                signature.protocolToggles.joinToString("/"),
            ),
        )
        if (signature.httpParserEvasions.isNotEmpty()) {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_http_parser_evasions),
                    formatHttpParserEvasions(signature.httpParserEvasions),
                ),
            )
        }
        add(
            DiagnosticsFieldUiModel(
                stringResolver.getString(R.string.blockcheck_bypass_tls_record_split),
                valueLabels.knownValue(signature.tlsRecordSplitEnabled.toString()),
            ),
        )
        signature.tlsRecordMarker?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.ripdpi_tlsrec_position_setting),
                    valueLabels.offset(it),
                ),
            )
        }
        signature.splitMarker?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.ripdpi_seqovl_summary_label_marker),
                    valueLabels.offset(it),
                ),
            )
        }
        signature.activationRound?.let {
            add(DiagnosticsFieldUiModel(stringResolver.getString(R.string.diagnostics_signature_activation_round), it))
        }
        signature.activationPayloadSize?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_activation_payload_size),
                    it,
                ),
            )
        }
        signature.activationStreamBytes?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_activation_stream_bytes),
                    it,
                ),
            )
        }
    }

    private fun MutableList<DiagnosticsFieldUiModel>.addFakeTtlSignatureFields(signature: BypassStrategySignature) {
        signature.fakeTtlMode?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_fake_ttl_mode),
                    formatFakeTtlMode(it),
                ),
            )
        }
        signature.adaptiveFakeTtlWindow?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_adaptive_fake_ttl_window),
                    it,
                ),
            )
        }
        signature.adaptiveFakeTtlFallback?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_adaptive_fake_ttl_fallback),
                    it.toString(),
                ),
            )
        }
        signature.adaptiveFakeTtlBias?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_adaptive_fake_ttl_bias),
                    formatAdaptiveFakeTtlBias(it),
                ),
            )
        }
    }

    private fun MutableList<DiagnosticsFieldUiModel>.addFakeTlsSignatureFields(signature: BypassStrategySignature) {
        signature.fakeTlsBaseMode?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_fake_tls_base),
                    formatFakeTlsBaseMode(it),
                ),
            )
        }
        signature.fakeSniMode?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_fake_tls_sni),
                    formatFakeTlsSni(mode = it, fixedValue = signature.fakeSniValue),
                ),
            )
        }
        if (signature.fakeTlsMods.isNotEmpty()) {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_fake_tls_mods),
                    formatFakeTlsMods(signature.fakeTlsMods),
                ),
            )
        }
        signature.fakeTlsSize?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_fake_tls_size),
                    formatFakeTlsSize(it),
                ),
            )
        }
    }

    private fun MutableList<DiagnosticsFieldUiModel>.addFakePayloadSignatureFields(signature: BypassStrategySignature) {
        signature.httpFakeProfile?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_http_fake_profile),
                    formatHttpFakeProfile(it),
                ),
            )
        }
        signature.tlsFakeProfile?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_tls_fake_profile),
                    formatTlsFakeProfile(it),
                ),
            )
        }
        signature.udpFakeProfile?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_udp_fake_profile),
                    formatUdpFakeProfile(it),
                ),
            )
        }
        signature.fakePayloadSource?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_fake_payload_source),
                    formatFakePayloadSource(it),
                ),
            )
        }
        signature.quicFakeProfile?.let {
            add(
                DiagnosticsFieldUiModel(
                    stringResolver.getString(R.string.diagnostics_signature_quic_fake_profile),
                    formatQuicFakeProfile(it),
                ),
            )
        }
        signature.quicFakeHost?.let {
            add(DiagnosticsFieldUiModel(stringResolver.getString(R.string.diagnostics_signature_quic_fake_host), it))
        }
        signature.fakeOffsetMarker?.let {
            add(DiagnosticsFieldUiModel(stringResolver.getString(R.string.ripdpi_fake_offset_setting), it))
        }
    }

    private fun formatFakeTlsBaseMode(value: String): String =
        when (value.lowercase(Locale.US)) {
            "default" -> stringResolver.getString(R.string.ripdpi_fake_tls_summary_base_default)
            "original" -> stringResolver.getString(R.string.diagnostics_signature_original_clienthello)
            else -> value
        }

    private fun formatFakeTtlMode(value: String): String =
        when (value.lowercase(Locale.US)) {
            "fixed" -> stringResolver.getString(R.string.adaptive_fake_ttl_mode_fixed_title)
            "adaptive" -> stringResolver.getString(R.string.adaptive_fake_ttl_mode_adaptive_title)
            "adaptive_custom" -> stringResolver.getString(R.string.diagnostics_signature_custom_adaptive_ttl)
            else -> value
        }

    private fun formatAdaptiveFakeTtlBias(value: Int): String =
        when {
            value < 0 -> stringResolver.getString(R.string.diagnostics_signature_bias_lower, value)
            value > 0 -> stringResolver.getString(R.string.diagnostics_signature_bias_higher, value)
            else -> stringResolver.getString(R.string.diagnostics_signature_bias_zero)
        }

    private fun formatFakeTlsSni(
        mode: String,
        fixedValue: String?,
    ): String =
        when (mode.lowercase(Locale.US)) {
            "fixed" -> {
                fixedValue
                    ?.takeIf {
                        it.isNotBlank()
                    }?.let { stringResolver.getString(R.string.diagnostics_signature_sni_fixed_value, it) }
                    ?: stringResolver.getString(R.string.adaptive_fake_ttl_badge_fixed)
            }

            "randomized" -> {
                stringResolver.getString(R.string.diagnostics_signature_randomized)
            }

            else -> {
                mode
            }
        }

    private fun formatFakeTlsMods(values: List<String>): String =
        values.joinToString(", ") { value ->
            when (value.lowercase(Locale.US)) {
                "rand" -> stringResolver.getString(R.string.ripdpi_fake_tls_randomize_title)
                "dupsid" -> stringResolver.getString(R.string.ripdpi_fake_tls_dup_sid_title)
                "padencap" -> stringResolver.getString(R.string.ripdpi_fake_tls_summary_mutation_pad_encap)
                else -> value
            }
        }

    private fun formatFakeTlsSize(value: Int): String =
        when {
            value > 0 -> stringResolver.getString(R.string.ripdpi_fake_tls_summary_size_exact, value)
            value < 0 -> stringResolver.getString(R.string.ripdpi_fake_tls_summary_size_minus, -value)
            else -> stringResolver.getString(R.string.diagnostics_signature_match_input_size)
        }

    private fun formatHttpFakeProfile(value: String): String =
        when (value.lowercase(Locale.US)) {
            "compat_default" -> stringResolver.getString(R.string.diagnostics_signature_compatibility_default)
            "iana_get" -> "IANA GET"
            "cloudflare_get" -> "Cloudflare GET"
            else -> value
        }

    private fun formatHttpParserEvasions(values: List<String>): String =
        values.joinToString(", ") { value ->
            when (value.lowercase(Locale.US)) {
                "host_mixed_case" -> stringResolver.getString(R.string.ripdpi_host_mixed_case_setting)
                "domain_mixed_case" -> stringResolver.getString(R.string.ripdpi_domain_mixed_case_setting)
                "host_remove_spaces" -> stringResolver.getString(R.string.ripdpi_host_remove_spaces_setting)
                "method_eol" -> stringResolver.getString(R.string.ripdpi_http_method_eol_setting)
                "unix_eol" -> stringResolver.getString(R.string.ripdpi_http_unix_eol_setting)
                "host_extra_space" -> stringResolver.getString(R.string.ripdpi_http_host_extra_space_setting)
                "host_tab" -> stringResolver.getString(R.string.ripdpi_http_host_tab_setting)
                else -> value
            }
        }

    private fun formatTlsFakeProfile(value: String): String =
        when (value.lowercase(Locale.US)) {
            "compat_default" -> stringResolver.getString(R.string.diagnostics_signature_compatibility_default)
            "iana_firefox" -> "IANA Firefox"
            "google_chrome" -> "Google Chrome"
            "vk_chrome" -> "VK Chrome"
            "sberbank_chrome" -> "Sberbank Chrome"
            "rutracker_kyber" -> "Rutracker Kyber"
            "bigsize_iana" -> "IANA bigsize"
            else -> value
        }

    private fun formatUdpFakeProfile(value: String): String =
        when (value.lowercase(Locale.US)) {
            "compat_default" -> stringResolver.getString(R.string.diagnostics_signature_compatibility_default)
            "zero_256" -> stringResolver.getString(R.string.diagnostics_signature_zero_blob_256)
            "zero_512" -> stringResolver.getString(R.string.diagnostics_signature_zero_blob_512)
            "dns_query" -> stringResolver.getString(R.string.diagnostics_signature_dns_query)
            "stun_binding" -> stringResolver.getString(R.string.diagnostics_signature_stun_binding)
            "wireguard_initiation" -> stringResolver.getString(R.string.diagnostics_signature_wireguard_initiation)
            "dht_get_peers" -> "DHT get_peers"
            else -> value
        }

    private fun formatFakePayloadSource(value: String): String =
        when (value.lowercase(Locale.US)) {
            "custom_raw" -> stringResolver.getString(R.string.diagnostics_signature_custom_raw_fake_payload)
            else -> value
        }

    private fun formatQuicFakeProfile(value: String): String =
        when (value.lowercase(Locale.US)) {
            "compat_default" -> stringResolver.getString(R.string.quic_fake_preset_compat_title)
            "realistic_initial" -> stringResolver.getString(R.string.quic_fake_preset_realistic_title)
            "disabled" -> stringResolver.getString(R.string.config_local_bypass_disabled)
            else -> value
        }
}

internal fun DiagnosticsUiFactorySupport.strategySignatureFields(
    signature: BypassStrategySignature,
): List<DiagnosticsFieldUiModel> = StrategySignaturePresenter(context).fields(signature)
