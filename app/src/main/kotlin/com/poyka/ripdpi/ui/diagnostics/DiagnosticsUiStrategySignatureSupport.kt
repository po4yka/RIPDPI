package com.poyka.ripdpi.ui.diagnostics

import com.poyka.ripdpi.activities.DiagnosticsFieldUiModel
import com.poyka.ripdpi.activities.DiagnosticsUiFactorySupport
import com.poyka.ripdpi.data.formatOffsetExpressionLabel
import com.poyka.ripdpi.data.strategyLaneFamilyLabel
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import java.util.Locale

internal class StrategySignaturePresenter {
    fun fields(signature: BypassStrategySignature): List<DiagnosticsFieldUiModel> =
        buildList {
            addCoreSignatureFields(signature)
            addStrategyLaneSignatureFields(signature)
            addProtocolSignatureFields(signature)
            addFakeTtlSignatureFields(signature)
            addFakeTlsSignatureFields(signature)
            addFakePayloadSignatureFields(signature)
            add(DiagnosticsFieldUiModel("Route group", signature.routeGroup ?: "Unknown"))
        }
}

private val strategySignaturePresenter = StrategySignaturePresenter()

internal fun DiagnosticsUiFactorySupport.strategySignatureFields(
    signature: BypassStrategySignature,
): List<DiagnosticsFieldUiModel> = strategySignaturePresenter.fields(signature)

private fun MutableList<DiagnosticsFieldUiModel>.addCoreSignatureFields(signature: BypassStrategySignature) {
    add(DiagnosticsFieldUiModel("Mode", signature.mode))
    add(DiagnosticsFieldUiModel("Config source", signature.configSource))
    add(DiagnosticsFieldUiModel("Autolearn", signature.hostAutolearn))
    add(DiagnosticsFieldUiModel("Chain", signature.chainSummary))
    add(DiagnosticsFieldUiModel("Desync", signature.desyncMethod))
}

private fun MutableList<DiagnosticsFieldUiModel>.addStrategyLaneSignatureFields(signature: BypassStrategySignature) {
    signature.tcpStrategyFamily?.let {
        add(DiagnosticsFieldUiModel("TCP/TLS lane", strategyLaneFamilyLabel(it)))
    }
    signature.quicStrategyFamily?.let {
        add(DiagnosticsFieldUiModel("QUIC lane", strategyLaneFamilyLabel(it)))
    }
    signature.dnsStrategyLabel?.let {
        add(DiagnosticsFieldUiModel("DNS lane", it))
    }
}

private fun MutableList<DiagnosticsFieldUiModel>.addProtocolSignatureFields(signature: BypassStrategySignature) {
    add(DiagnosticsFieldUiModel("Protocols", signature.protocolToggles.joinToString("/")))
    if (signature.httpParserEvasions.isNotEmpty()) {
        add(DiagnosticsFieldUiModel("HTTP parser evasions", formatHttpParserEvasions(signature.httpParserEvasions)))
    }
    add(DiagnosticsFieldUiModel("TLS record split", signature.tlsRecordSplitEnabled.toString()))
    signature.tlsRecordMarker?.let {
        add(DiagnosticsFieldUiModel("TLS record marker", formatOffsetExpressionLabel(it)))
    }
    signature.splitMarker?.let {
        add(DiagnosticsFieldUiModel("Split marker", formatOffsetExpressionLabel(it)))
    }
    signature.activationRound?.let {
        add(DiagnosticsFieldUiModel("Activation round", it))
    }
    signature.activationPayloadSize?.let {
        add(DiagnosticsFieldUiModel("Activation payload size", it))
    }
    signature.activationStreamBytes?.let {
        add(DiagnosticsFieldUiModel("Activation stream bytes", it))
    }
}

private fun MutableList<DiagnosticsFieldUiModel>.addFakeTtlSignatureFields(signature: BypassStrategySignature) {
    signature.fakeTtlMode?.let {
        add(DiagnosticsFieldUiModel("Fake TTL mode", formatFakeTtlMode(it)))
    }
    signature.adaptiveFakeTtlWindow?.let {
        add(DiagnosticsFieldUiModel("Adaptive fake TTL window", it))
    }
    signature.adaptiveFakeTtlFallback?.let {
        add(DiagnosticsFieldUiModel("Adaptive fake TTL fallback", it.toString()))
    }
    signature.adaptiveFakeTtlBias?.let {
        add(DiagnosticsFieldUiModel("Adaptive fake TTL bias", formatAdaptiveFakeTtlBias(it)))
    }
}

private fun MutableList<DiagnosticsFieldUiModel>.addFakeTlsSignatureFields(signature: BypassStrategySignature) {
    signature.fakeTlsBaseMode?.let {
        add(DiagnosticsFieldUiModel("Fake TLS base", formatFakeTlsBaseMode(it)))
    }
    signature.fakeSniMode?.let {
        add(
            DiagnosticsFieldUiModel(
                "Fake TLS SNI",
                formatFakeTlsSni(mode = it, fixedValue = signature.fakeSniValue),
            ),
        )
    }
    if (signature.fakeTlsMods.isNotEmpty()) {
        add(DiagnosticsFieldUiModel("Fake TLS mods", formatFakeTlsMods(signature.fakeTlsMods)))
    }
    signature.fakeTlsSize?.let {
        add(DiagnosticsFieldUiModel("Fake TLS size", formatFakeTlsSize(it)))
    }
}

private fun MutableList<DiagnosticsFieldUiModel>.addFakePayloadSignatureFields(signature: BypassStrategySignature) {
    signature.httpFakeProfile?.let {
        add(DiagnosticsFieldUiModel("HTTP fake profile", formatHttpFakeProfile(it)))
    }
    signature.tlsFakeProfile?.let {
        add(DiagnosticsFieldUiModel("TLS fake profile", formatTlsFakeProfile(it)))
    }
    signature.udpFakeProfile?.let {
        add(DiagnosticsFieldUiModel("UDP fake profile", formatUdpFakeProfile(it)))
    }
    signature.fakePayloadSource?.let {
        add(DiagnosticsFieldUiModel("Fake payload source", formatFakePayloadSource(it)))
    }
    signature.quicFakeProfile?.let {
        add(DiagnosticsFieldUiModel("QUIC fake profile", formatQuicFakeProfile(it)))
    }
    signature.quicFakeHost?.let {
        add(DiagnosticsFieldUiModel("QUIC fake host", it))
    }
    signature.fakeOffsetMarker?.let {
        add(DiagnosticsFieldUiModel("Fake offset marker", it))
    }
}

private fun formatFakeTlsBaseMode(value: String): String =
    when (value.lowercase(Locale.US)) {
        "default" -> "Default fake ClientHello"
        "original" -> "Original ClientHello"
        else -> value
    }

private fun formatFakeTtlMode(value: String): String =
    when (value.lowercase(Locale.US)) {
        "fixed" -> "Fixed TTL"
        "adaptive" -> "Adaptive TTL"
        "adaptive_custom" -> "Custom adaptive TTL"
        else -> value.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
    }

private fun formatAdaptiveFakeTtlBias(value: Int): String =
    when {
        value < 0 -> "Prefer lower TTLs first ($value)"
        value > 0 -> "Prefer higher TTLs first (+$value)"
        else -> "Alternate around the seed (0)"
    }

private fun formatFakeTlsSni(
    mode: String,
    fixedValue: String?,
): String =
    when (mode.lowercase(Locale.US)) {
        "fixed" -> fixedValue?.takeIf { it.isNotBlank() }?.let { "Fixed ($it)" } ?: "Fixed"
        "randomized" -> "Randomized"
        else -> mode
    }

private fun formatFakeTlsMods(values: List<String>): String =
    values.joinToString(", ") { value ->
        when (value.lowercase(Locale.US)) {
            "rand" -> "Randomize TLS material"
            "dupsid" -> "Copy Session ID"
            "padencap" -> "Padding camouflage"
            else -> value
        }
    }

private fun formatFakeTlsSize(value: Int): String =
    when {
        value > 0 -> "Exactly $value bytes"
        value < 0 -> "Input minus ${-value} bytes"
        else -> "Match input size"
    }

private fun formatHttpFakeProfile(value: String): String =
    when (value.lowercase(Locale.US)) {
        "compat_default" -> "Compatibility default"
        "iana_get" -> "IANA GET"
        "cloudflare_get" -> "Cloudflare GET"
        else -> value
    }

private fun formatHttpParserEvasions(values: List<String>): String =
    values.joinToString(", ") { value ->
        when (value.lowercase(Locale.US)) {
            "host_mixed_case" -> "Host mixed case"
            "domain_mixed_case" -> "Domain mixed case"
            "host_remove_spaces" -> "Host remove spaces"
            "method_eol" -> "Method EOL shift"
            "unix_eol" -> "Unix line endings"
            "host_extra_space" -> "Host extra space"
            "host_tab" -> "Host tab separator"
            else -> value
        }
    }

private fun formatTlsFakeProfile(value: String): String =
    when (value.lowercase(Locale.US)) {
        "compat_default" -> "Compatibility default"
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
        "compat_default" -> "Compatibility default"
        "zero_256" -> "Zero blob 256"
        "zero_512" -> "Zero blob 512"
        "dns_query" -> "DNS query"
        "stun_binding" -> "STUN binding"
        "wireguard_initiation" -> "WireGuard initiation"
        "dht_get_peers" -> "DHT get_peers"
        else -> value
    }

private fun formatFakePayloadSource(value: String): String =
    when (value.lowercase(Locale.US)) {
        "custom_raw" -> "Custom raw fake payload"
        else -> value.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
    }

private fun formatQuicFakeProfile(value: String): String =
    when (value.lowercase(Locale.US)) {
        "compat_default" -> "Compatibility blob"
        "realistic_initial" -> "Realistic Initial"
        "disabled" -> "Off"
        else -> value
    }
