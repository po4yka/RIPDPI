package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.toActivationRequest

/**
 * Projects the editor into an [AwgActivationRequest] for the standalone
 * AmneziaWG runtime. Identity, PSK and obfuscation come from [AmneziaWgEditorState.form]; the
 * transport fields the form does not carry as columns ([AwgEditorField.ADDRESS],
 * [AwgEditorField.DNS], [AwgEditorField.ALLOWED_IPS], [AwgEditorField.MTU],
 * [AwgEditorField.PERSISTENT_KEEPALIVE]) are read from [AmneziaWgEditorState.rawTextByField].
 * An IPv4 address is required; blank MTU and keepalive use runtime defaults.
 * Blank AllowedIPs selects full routes for the configured address families.
 */
fun AmneziaWgEditorState.toActivationRequest(profileId: String): AwgActivationRequest {
    val addresses = rawText(AwgEditorField.ADDRESS).split(',').map(String::trim).filter(String::isNotEmpty)
    val (ipv6, ipv4) = addresses.partition { ':' in it }
    require(ipv4.size == 1 && ipv6.size <= 1) { "AmneziaWG requires one IPv4 and at most one IPv6 address" }
    val mtu =
        rawText(AwgEditorField.MTU).trim().toIntOrNull()?.takeIf { it > 0 }
            ?: AwgActivationRequest.DEFAULT_MTU
    val keepalive = rawText(AwgEditorField.PERSISTENT_KEEPALIVE).trim().toIntOrNull()?.takeIf { it >= 0 } ?: 0
    return form.toActivationRequest(
        profileId = profileId,
        interfaceAddressV4 = ipv4.single(),
        interfaceAddressV6 = ipv6.singleOrNull().orEmpty(),
        dnsServers = rawText(AwgEditorField.DNS).commaSeparatedValues(),
        allowedIps = rawText(AwgEditorField.ALLOWED_IPS).commaSeparatedValues(),
        mtu = mtu,
        persistentKeepalive = keepalive,
    )
}

private fun String.commaSeparatedValues(): List<String> = if (isBlank()) emptyList() else split(',').map(String::trim)
