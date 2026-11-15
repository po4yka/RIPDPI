package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgActivationField
import com.poyka.ripdpi.data.awg.AwgActivationInputException
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.isValidAwgMtu
import com.poyka.ripdpi.data.awg.toActivationRequest
import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters

/**
 * Projects the editor into an [AwgActivationRequest] for the standalone
 * AmneziaWG runtime. Identity, PSK and obfuscation come from [AmneziaWgEditorState.form]; the
 * transport fields the form does not carry as columns ([AwgEditorField.ADDRESS],
 * [AwgEditorField.DNS], [AwgEditorField.ALLOWED_IPS], [AwgEditorField.MTU],
 * [AwgEditorField.PERSISTENT_KEEPALIVE]) are read from [AmneziaWgEditorState.rawTextByField].
 * An IPv4 address is required. Blank MTU and keepalive use runtime defaults.
 * An untouched manual route field selects routes for the configured address families.
 */
fun AmneziaWgEditorState.toActivationRequest(profileId: String): AwgActivationRequest {
    val addresses = rawText(AwgEditorField.ADDRESS).split(',').map(String::trim).filter(String::isNotEmpty)
    val (ipv6, ipv4) = addresses.partition { ':' in it }
    if (ipv4.size != 1 || ipv6.size > 1) {
        throw AwgActivationInputException(AwgActivationField.Address)
    }
    val mtu =
        rawText(AwgEditorField.MTU)
            .trim()
            .ifEmpty { AwgActivationRequest.DEFAULT_MTU.toString() }
            .toIntOrNull()
            ?.takeIf(::isValidAwgMtu)
            ?: throw AwgActivationInputException(AwgActivationField.Mtu)
    require(obfuscationConsistent()) { "AmneziaWG obfuscation parameters are invalid" }
    val keepaliveText = rawText(AwgEditorField.PERSISTENT_KEEPALIVE).trim().ifEmpty { "0" }
    val (keepalive, keepaliveMax) =
        AmneziaWgV3Parameters.parsePersistentKeepalive(keepaliveText)
            ?: throw AwgActivationInputException(AwgActivationField.PersistentKeepalive)
    val port =
        rawTextByField[AwgEditorField.SERVER_PORT]?.let {
            it.toIntOrNull()
                ?: throw AwgActivationInputException(AwgActivationField.EndpointPort)
        } ?: form.serverPort
    return form.copy(serverPort = port).toActivationRequest(
        profileId = profileId,
        interfaceAddressV4 = ipv4.single(),
        interfaceAddressV6 = ipv6.singleOrNull().orEmpty(),
        dnsServers = rawText(AwgEditorField.DNS).commaSeparatedValues(),
        allowedIps = rawTextByField[AwgEditorField.ALLOWED_IPS]?.commaSeparatedValues(),
        mtu = mtu,
        persistentKeepalive = keepalive,
        persistentKeepaliveMax = keepaliveMax ?: 0,
    )
}

private fun String.commaSeparatedValues(): List<String> = if (isBlank()) emptyList() else split(',').map(String::trim)

internal fun AwgActivationField.editorField(): AwgEditorField? =
    when (this) {
        AwgActivationField.PrivateKey -> AwgEditorField.INTERFACE_PRIVATE_KEY
        AwgActivationField.PeerPublicKey -> AwgEditorField.PEER_PUBLIC_KEY
        AwgActivationField.PresharedKey -> AwgEditorField.PRESHARED_KEY
        AwgActivationField.EndpointHost -> AwgEditorField.SERVER
        AwgActivationField.EndpointPort -> AwgEditorField.SERVER_PORT
        AwgActivationField.Address -> AwgEditorField.ADDRESS
        AwgActivationField.Dns -> AwgEditorField.DNS
        AwgActivationField.AllowedIps -> AwgEditorField.ALLOWED_IPS
        AwgActivationField.Mtu -> AwgEditorField.MTU
        AwgActivationField.PersistentKeepalive -> AwgEditorField.PERSISTENT_KEEPALIVE
        AwgActivationField.Carrier -> null
        AwgActivationField.CarrierWsUrl -> AwgEditorField.CARRIER_WS_URL
    }
