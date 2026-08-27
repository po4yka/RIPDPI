package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.wireguard.requireAmneziaWgArm64Safe
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64

/**
 * A fully-resolved, self-contained request to activate a standalone AmneziaWG
 * profile through the native AmneziaWG runtime (`ripdpi-warp-core`'s
 * `AmneziaWgProfileConfig` / engine-api's `ResolvedRipDpiAmneziaWgConfig`).
 *
 * This type lives in `:core:data:runtime-state` (which `:app` depends on)
 * rather than in `:core:engine-api` (which is **not** on the `:app` compile
 * classpath) so the profile editor can build an activation request without a
 * forbidden module dependency. The service layer is responsible for the final
 * hop -- translating this into `ResolvedRipDpiAmneziaWgConfig` and handing it to
 * `RipDpiAmneziaWgRuntime.start(...)`, the WARP-engine-derived AmneziaWG runtime
 * path -- so no new `ProxyProfile` subtype is introduced.
 *
 * Native fields mirror `ResolvedRipDpiAmneziaWgConfig`, including
 * [presharedKey] / [persistentKeepalive] and AmneziaWG 2.0 special-junk frames.
 * [dnsServers] and [allowedIps] are owned by the Android VPN interface and
 * deliberately remain outside the native configuration.
 */
@Serializable
data class AwgActivationRequest(
    val profileId: String,
    val privateKey: String,
    val peerPublicKey: String,
    val presharedKey: String = "",
    val endpointHost: String,
    val endpointPort: Int,
    val interfaceAddressV4: String,
    val interfaceAddressV6: String = "",
    /** Android VPN interface DNS servers; these do not cross the native runtime boundary. */
    val dnsServers: List<String> = emptyList(),
    /** Android VPN interface routes; these do not cross the native runtime boundary. */
    val allowedIps: List<String> = listOf("0.0.0.0/0"),
    val mtu: Int = DEFAULT_MTU,
    val persistentKeepalive: Int = 0,
    val obfuscation: AwgActivationObfuscation = AwgActivationObfuscation(),
    /**
     * Transport carrier the WireGuard datagrams egress over. [CARRIER_UDP]
     * (the default) is plain WireGuard-over-UDP; [CARRIER_WS] selects the
     * WG-over-WebSocket carrier, which requires a non-blank [carrierWsUrl].
     * Mirrors the engine-api `RipDpiAmneziaWgCarrierKind` snake_case wire token
     * (`udp`/`ws`) so the service-layer translation stays a structural copy.
     * Additive + defaulted: an older persisted request decodes as UDP unchanged.
     */
    val carrier: String = CARRIER_UDP,
    /**
     * WebSocket carrier request URL (e.g. `wss://host:443/path`); only consulted
     * when [carrier] is [CARRIER_WS]. User-pasted config — never logged or sent
     * to telemetry in plain form (network-fingerprint-privacy).
     */
    val carrierWsUrl: String = "",
) {
    companion object {
        /** Native AmneziaWG tunnel MTU default; mirrors `DefaultAmneziaWgTunnelMtu`. */
        const val DEFAULT_MTU: Int = 1330

        /** Plain WireGuard-over-UDP carrier token (the default). */
        const val CARRIER_UDP: String = "udp"

        /** WG-over-WebSocket carrier token. */
        const val CARRIER_WS: String = "ws"
    }
}

/** Rejects requests that cannot reach the owned AmneziaWG runtime. */
fun AwgActivationRequest.requireRuntimeReady() {
    obfuscation.requireArm64Safe()
    require(privateKey.isWireGuardKey()) { "AmneziaWG interface private key is invalid" }
    require(peerPublicKey.isWireGuardKey()) { "AmneziaWG peer public key is invalid" }
    require(presharedKey.isEmpty() || presharedKey.isWireGuardKey()) { "AmneziaWG preshared key is invalid" }
    require(endpointHost.isNotBlank()) { "AmneziaWG endpoint host missing" }
    require(endpointPort in ValidEndpointPorts) { "AmneziaWG endpoint port invalid" }
    require(interfaceAddressV4.isIpv4Cidr()) { "AmneziaWG interface address must be an IPv4 CIDR" }
    require(interfaceAddressV6.isEmpty() || interfaceAddressV6.isIpv6Cidr()) {
        "AmneziaWG IPv6 interface address must be an IPv6 CIDR"
    }
    val ipv6Enabled = interfaceAddressV6.isNotEmpty()
    require(dnsServers.all { it.isIpv4Address() || (ipv6Enabled && it.isIpv6Address()) }) {
        "AmneziaWG DNS servers must be numeric addresses of a configured interface family"
    }
    require(allowedIps.isNotEmpty() && allowedIps.all { it.isIpv4Cidr() || (ipv6Enabled && it.isIpv6Cidr()) }) {
        "AmneziaWG allowed IPs must be CIDRs of a configured interface family"
    }
    require(carrier != AwgActivationRequest.CARRIER_WS || carrierWsUrl.isNotBlank()) {
        "AmneziaWG WS carrier requires a carrier URL"
    }
}

private fun String.isWireGuardKey(): Boolean =
    runCatching { Base64.getDecoder().decode(this) }.getOrNull()?.let { decoded ->
        decoded.size == WireGuardKeyBytes && Base64.getEncoder().encodeToString(decoded) == this
    } == true

private fun String.isIpv4Cidr(): Boolean = isCidr(Ipv4PrefixRange, String::isIpv4Address)

private fun String.isIpv6Cidr(): Boolean = isCidr(Ipv6PrefixRange, String::isIpv6Address)

private fun String.isCidr(
    prefixRange: IntRange,
    addressIsValid: (String) -> Boolean,
): Boolean {
    val parts = split('/')
    val prefix = parts.getOrNull(1)?.toIntOrNull()
    return parts.size == CidrPartCount &&
        prefix?.let { it in prefixRange && it.toString() == parts[1] } == true &&
        addressIsValid(parts[0])
}

private fun String.isIpv4Address(): Boolean {
    val octets = split('.')
    return octets.size == Ipv4OctetCount &&
        octets.all { octet ->
            octet.isNotEmpty() &&
                octet.all { it in '0'..'9' } &&
                (octet == "0" || !octet.startsWith('0')) &&
                octet.toIntOrNull()?.let { it in Ipv4OctetRange } == true
        }
}

private fun String.isIpv6Address(): Boolean {
    if (':' !in this || any { it !in Ipv6LiteralCharacters }) return false
    // URI validates a bracketed IPv6 literal without resolving names or opening a socket.
    return try {
        URI("https://[$this]/").host != null
    } catch (_: URISyntaxException) {
        false
    }
}

private val ValidEndpointPorts = 1..65_535
private val Ipv4PrefixRange = 0..32
private val Ipv6PrefixRange = 0..128
private val Ipv4OctetRange = 0..255
private const val Ipv6LiteralCharacters = "0123456789abcdefABCDEF:"
private const val WireGuardKeyBytes = 32
private const val CidrPartCount = 2
private const val Ipv4OctetCount = 4

/**
 * AmneziaWG obfuscation knobs in activation-request form. Mirrors the
 * `RipDpiAmneziaWgObfuscationConfig` field set: `jc`/`jmin`/`jmax` size the junk
 * padding, `s1`..`s4` the per-message-type junk-size knobs (`s1`/`s2` the
 * handshake-init/response prefixes, `s3`/`s4` the AWG-2.x cookie/transport
 * padding), `h1`..`h4` the 64-bit magic headers, and `i1`..`i5` the optional
 * AWG-2.0 special-junk templates (empty = unused). All knobs carry through to
 * the native runtime; the additive `s3`/`s4` default to `0` and must remain
 * zero for Android arm64 safety (amneziawg-go#110).
 */
@Serializable
data class AwgActivationObfuscation(
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val s1: Int = 0,
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
    val h1: Long = 0L,
    val h2: Long = 0L,
    val h3: Long = 0L,
    val h4: Long = 0L,
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
) {
    /** Rejects the known Android arm64 transport-drop configuration from amneziawg-go#110. */
    fun requireArm64Safe() {
        requireAmneziaWgArm64Safe(s3 = s3, s4 = s4)
    }
}

/**
 * Builds an [AwgActivationRequest] from an [AwgProfileForm] plus the editor-only
 * transport fields the form does not carry as first-class columns
 * ([interfaceAddressV4], [mtu], [persistentKeepalive]).
 *
 * The obfuscation group (including the AWG-2.0 `i1`..`i5` payloads and the full
 * `s1`..`s4` junk-size knobs), the identity + PSK fields, and the transport
 * carrier ([AwgProfileForm.carrier] / [AwgProfileForm.carrierWsUrl]) come
 * straight from [form]; `s3`/`s4` remain in the wire shape, but the service
 * layer rejects non-zero values before native startup. A blank
 * [interfaceAddressV4] yields a request the service layer is expected to reject
 * -- the mapper does not invent a default address.
 */
fun AwgProfileForm.toActivationRequest(
    profileId: String,
    interfaceAddressV4: String,
    interfaceAddressV6: String = "",
    dnsServers: List<String> = emptyList(),
    allowedIps: List<String> = emptyList(),
    mtu: Int = AwgActivationRequest.DEFAULT_MTU,
    persistentKeepalive: Int = 0,
): AwgActivationRequest =
    AwgActivationRequest(
        profileId = profileId,
        privateKey = interfacePrivateKey,
        peerPublicKey = peerPublicKey,
        presharedKey = presharedKey,
        endpointHost = server,
        endpointPort = serverPort,
        interfaceAddressV4 = interfaceAddressV4,
        interfaceAddressV6 = interfaceAddressV6,
        dnsServers = dnsServers,
        allowedIps =
            allowedIps.ifEmpty {
                if (interfaceAddressV6.isEmpty()) listOf("0.0.0.0/0") else listOf("0.0.0.0/0", "::/0")
            },
        mtu = mtu,
        persistentKeepalive = persistentKeepalive,
        carrier = carrier,
        carrierWsUrl = carrierWsUrl,
        obfuscation =
            AwgActivationObfuscation(
                jc = jc,
                jmin = jmin,
                jmax = jmax,
                s1 = s1,
                s2 = s2,
                s3 = s3,
                s4 = s4,
                h1 = h1,
                h2 = h2,
                h3 = h3,
                h4 = h4,
                i1 = i1,
                i2 = i2,
                i3 = i3,
                i4 = i4,
                i5 = i5,
            ),
    )
