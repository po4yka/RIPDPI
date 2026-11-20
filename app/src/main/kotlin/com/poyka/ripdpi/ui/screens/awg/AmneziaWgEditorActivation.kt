package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgActivationField
import com.poyka.ripdpi.data.awg.AwgActivationInputException
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileForm
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
    val (ipv4, ipv6) = activationAddresses()
    val mtu = activationMtu()
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
        interfaceAddressV4 = ipv4,
        interfaceAddressV6 = ipv6,
        dnsServers = rawText(AwgEditorField.DNS).commaSeparatedValues(),
        allowedIps = rawTextByField[AwgEditorField.ALLOWED_IPS]?.commaSeparatedValues(),
        mtu = mtu,
        persistentKeepalive = keepalive,
        persistentKeepaliveMax = keepaliveMax ?: 0,
    )
}

/** Opens a saved request in the editor; [toActivationRequest] maps the result back to the same request. */
fun AwgActivationRequest.toEditorState(): AmneziaWgEditorState {
    val form =
        AwgProfileForm(
            wireProfile = obfuscation.wireProfile,
            server = endpointHost,
            serverPort = endpointPort,
            interfacePrivateKey = privateKey,
            peerPublicKey = peerPublicKey,
            presharedKey = presharedKey,
            jc = obfuscation.jc,
            jmin = obfuscation.jmin,
            jmax = obfuscation.jmax,
            s1 = obfuscation.s1,
            s2 = obfuscation.s2,
            s3 = obfuscation.s3,
            s4 = obfuscation.s4,
            h1 = obfuscation.h1,
            h2 = obfuscation.h2,
            h3 = obfuscation.h3,
            h4 = obfuscation.h4,
            i1 = obfuscation.i1,
            i2 = obfuscation.i2,
            i3 = obfuscation.i3,
            i4 = obfuscation.i4,
            i5 = obfuscation.i5,
            v3 = obfuscation.v3,
            carrier = carrier,
            carrierWsUrl = carrierWsUrl,
        )
    val transport =
        mapOf(
            AwgEditorField.ADDRESS to
                listOf(interfaceAddressV4, interfaceAddressV6)
                    .filter(String::isNotEmpty)
                    .joinToString(", "),
            AwgEditorField.DNS to dnsServers.joinToString(", "),
            AwgEditorField.MTU to mtu.toString(),
            AwgEditorField.ALLOWED_IPS to allowedIps.joinToString(", "),
            AwgEditorField.PERSISTENT_KEEPALIVE to
                if (persistentKeepaliveMax > 0) {
                    "$persistentKeepalive-$persistentKeepaliveMax"
                } else {
                    persistentKeepalive.toString()
                },
        )
    return AmneziaWgEditorState(
        form = form,
        rawTextByField = form.identityRawText() + form.obfuscationRawText() + transport,
        obfuscationLocked = false,
    )
}

private fun AmneziaWgEditorState.activationAddresses(): Pair<String, String> {
    val addresses = rawText(AwgEditorField.ADDRESS).split(',').map(String::trim).filter(String::isNotEmpty)
    val (ipv6, ipv4) = addresses.partition { ':' in it }
    if (ipv4.size != 1 || ipv6.size > 1) {
        throw AwgActivationInputException(AwgActivationField.Address)
    }
    return ipv4.single() to ipv6.singleOrNull().orEmpty()
}

private fun AmneziaWgEditorState.activationMtu(): Int =
    rawText(AwgEditorField.MTU)
        .trim()
        .ifEmpty { AwgActivationRequest.DEFAULT_MTU.toString() }
        .toIntOrNull()
        ?.takeIf(::isValidAwgMtu)
        ?: throw AwgActivationInputException(AwgActivationField.Mtu)

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
