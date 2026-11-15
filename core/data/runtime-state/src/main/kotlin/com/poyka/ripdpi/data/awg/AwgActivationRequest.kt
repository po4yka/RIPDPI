package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.data.redacted
import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import com.poyka.ripdpi.data.wireguard.AmneziaWgWireProfile
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * [presharedKey] / [persistentKeepalive] and AmneziaWG 3.1 signature programs.
 * [allowedIps] selects Android traffic and restricts native peer IPs. [dnsServers] also supplies
 * scoped resolver routing to the native proxy while this AWG request is active.
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
    /** Android VPN interface DNS servers and active AWG proxy resolver routes. */
    val dnsServers: List<String> = emptyList(),
    /** Android traffic selection routes and native peer source/destination permissions. */
    val allowedIps: List<String> = listOf("0.0.0.0/0"),
    val mtu: Int = DEFAULT_MTU,
    val persistentKeepalive: Int = 0,
    /** High bound of an AWG 3.x keepalive range; `0` = the fixed [persistentKeepalive] interval. */
    val persistentKeepaliveMax: Int = 0,
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
    /** Keys, endpoints, tunnel addresses and the carrier URL show only their presence. */
    override fun toString(): String =
        "AwgActivationRequest(profileId=$profileId, privateKey=${redacted(privateKey)}, " +
            "peerPublicKey=$peerPublicKey, presharedKey=${redacted(presharedKey)}, " +
            "endpointHost=${redacted(endpointHost)}, endpointPort=$endpointPort, " +
            "interfaceAddressV4=${redacted(interfaceAddressV4)}, " +
            "interfaceAddressV6=${redacted(interfaceAddressV6)}, " +
            "dnsServers=$dnsServers, allowedIps=$allowedIps, mtu=$mtu, " +
            "persistentKeepalive=$persistentKeepalive, persistentKeepaliveMax=$persistentKeepaliveMax, " +
            "obfuscation=$obfuscation, carrier=$carrier, carrierWsUrl=${redacted(carrierWsUrl)})"

    companion object {
        /** Native AmneziaWG tunnel MTU default; mirrors `DefaultAmneziaWgTunnelMtu`. */
        const val DEFAULT_MTU: Int = 1330

        /** Plain WireGuard-over-UDP carrier token (the default). */
        const val CARRIER_UDP: String = "udp"

        /** WG-over-WebSocket carrier token. */
        const val CARRIER_WS: String = "ws"
    }
}

/** A field the editor can highlight without rendering an exception message. */
enum class AwgActivationField(
    internal val error: String,
) {
    PrivateKey("AmneziaWG interface private key is invalid"),
    PeerPublicKey("AmneziaWG peer public key is invalid"),
    PresharedKey("AmneziaWG preshared key is invalid"),
    EndpointHost("AmneziaWG endpoint host invalid"),
    EndpointPort("AmneziaWG endpoint port invalid"),
    Address("AmneziaWG requires one IPv4 CIDR and at most one IPv6 CIDR"),
    Dns("AmneziaWG DNS servers must be numeric addresses of a configured interface family"),
    AllowedIps("AmneziaWG allowed IPs must contain valid CIDRs with a route for a configured interface family"),
    Mtu("AmneziaWG MTU must be between 1280 and 1420"),
    PersistentKeepalive("AmneziaWG persistent keepalive must be zero or an ordered positive range in 1..65535"),
    Carrier("Unsupported AmneziaWG carrier"),
    CarrierWsUrl("AmneziaWG WS carrier requires a valid WSS URL without userinfo or a fragment"),
}

class AwgActivationInputException(
    val field: AwgActivationField,
) : IllegalArgumentException(field.error)

/** Rejects requests that cannot reach the owned AmneziaWG runtime. */
fun AwgActivationRequest.requireRuntimeReady() {
    obfuscation.requireSupportedProfile()
    AwgActivationField.PrivateKey.requireValid(privateKey.isWireGuardKey())
    AwgActivationField.PeerPublicKey.requireValid(peerPublicKey.isWireGuardKey())
    AwgActivationField.PresharedKey.requireValid(presharedKey.isEmpty() || presharedKey.isWireGuardKey())
    // Only the UDP carrier dials this host. The WS carrier dials carrierWsUrl.
    val usesWsCarrier = carrier == AwgActivationRequest.CARRIER_WS
    val validEndpointHost = if (usesWsCarrier) endpointHost.isNotBlank() else endpointHost.isAwgEndpointHost()
    AwgActivationField.EndpointHost.requireValid(validEndpointHost)
    AwgActivationField.EndpointPort.requireValid(endpointPort in ValidEndpointPorts)
    AwgActivationField.Mtu.requireValid(isValidAwgMtu(mtu))
    AwgActivationField.PersistentKeepalive.requireValid(
        persistentKeepalive in ValidKeepalive && persistentKeepaliveMax in ValidKeepalive &&
            (persistentKeepaliveMax == 0 || (persistentKeepalive > 0 && persistentKeepaliveMax >= persistentKeepalive)),
    )
    val ipv6Enabled = interfaceAddressV6.isNotEmpty()
    AwgActivationField.Address.requireValid(
        interfaceAddressV4.isIpv4Cidr() && (!ipv6Enabled || interfaceAddressV6.isIpv6Cidr()),
    )
    AwgActivationField.Dns.requireValid(dnsServers.all { it.isIpv4Address() || (ipv6Enabled && it.isIpv6Address()) })
    AwgActivationField.AllowedIps.requireValid(
        allowedIps.all { it.isIpv4Cidr() || it.isIpv6Cidr() } &&
            allowedIps.any { it.isIpv4Cidr() || (ipv6Enabled && it.isIpv6Cidr()) },
    )
    AwgActivationField.Carrier.requireValid(
        carrier == AwgActivationRequest.CARRIER_UDP || carrier == AwgActivationRequest.CARRIER_WS,
    )
    AwgActivationField.CarrierWsUrl.requireValid(
        !usesWsCarrier || isValidAwgWssUrl(carrierWsUrl),
    )
}

private fun AwgActivationField.requireValid(valid: Boolean) {
    if (!valid) throw AwgActivationInputException(this)
}

private fun String.isAwgEndpointHost(): Boolean =
    when {
        length !in 1..MaxEndpointHostLength -> {
            false
        }

        NumericIp.parseAddress(this) != null -> {
            true
        }

        all { it in '0'..'9' || it == '.' } -> {
            false
        }

        else -> {
            try {
                URI("https://$this").host == this && '[' !in this
            } catch (_: URISyntaxException) {
                false
            }
        }
    }

/** Checks the packet size supported by the standalone runtime. */
fun isValidAwgMtu(mtu: Int): Boolean = mtu in ValidAmneziaWgMtu

/** Validates the WSS authority and request target without DNS or network access. */
fun isValidAwgWssUrl(value: String): Boolean {
    if (!value.startsWith(WssSchemePrefix, ignoreCase = true)) return false
    return ("https://" + value.substring(WssSchemePrefix.length)).toHttpUrlOrNull()?.let { url ->
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null
    } == true
}

private fun String.isWireGuardKey(): Boolean =
    length == WireGuardBase64KeyLength &&
        runCatching { Base64.getDecoder().decode(this) }.getOrNull()?.let { decoded ->
            decoded.size == WireGuardKeyBytes && Base64.getEncoder().encodeToString(decoded) == this
        } == true

internal fun String.isIpv4Cidr(): Boolean = NumericIp.parseCidr(this)?.address?.isIPv4 == true

internal fun String.isIpv6Cidr(): Boolean = '.' !in this && NumericIp.parseCidr(this)?.address?.isIPv6 == true

/** Validates an AWG resolver literal without DNS resolution. */
fun isAwgNumericAddress(address: String): Boolean = address.isIpv4Address() || address.isIpv6Address()

private fun String.isIpv4Address(): Boolean = NumericIp.parseAddress(this)?.isIPv4 == true

private fun String.isIpv6Address(): Boolean = '.' !in this && NumericIp.parseAddress(this)?.isIPv6 == true

private val ValidEndpointPorts = 1..65_535
internal val ValidAmneziaWgMtu = 1280..1420
private val ValidKeepalive = 0..AmneziaWgV3Parameters.MaximumRange

private const val WireGuardKeyBytes = 32
private const val WireGuardBase64KeyLength = 44
private const val MaxEndpointHostLength = 254

/**
 * AmneziaWG obfuscation knobs in activation-request form. Mirrors the
 * `RipDpiAmneziaWgObfuscationConfig` field set: `jc`/`jmin`/`jmax` size the junk
 * padding, `s1`..`s4` the per-message-type junk-size knobs (`s1`/`s2` the
 * handshake-init/response prefixes, `s3`/`s4` the AWG-2.x cookie/transport
 * padding), `h1`..`h4` the uint32 header ranges, and `i1`..`i5` the optional
 * AWG 3.1 signature programs (empty = unused), and [v3] the AWG 3.x keys. All
 * knobs carry through to the native runtime; the additive `s3`/`s4` default to
 * `0`.
 */
@Serializable
data class AwgActivationObfuscation(
    val wireProfile: String = "awg-3.1-ranges-v1",
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val s1: Int = 0,
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
    val h1: String = "1",
    val h2: String = "2",
    val h3: String = "3",
    val h4: String = "4",
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
    val v3: AmneziaWgV3Parameters = AmneziaWgV3Parameters(),
) {
    /** Rejects malformed knobs, including the AWG 3.x rules such as S1-S4 >= 12 for header protection. */
    fun requireSupportedProfile() {
        AmneziaWgWireProfile.requireScalars(jc, jmin, jmax, s1, s2, s3, s4)
        AmneziaWgWireProfile.requireValid(
            wireProfile,
            listOf(h1, h2, h3, h4),
            listOf(i1, i2, i3, i4, i5),
        )
        v3.requireValid(listOf(s1, s2, s3, s4))
    }
}

/**
 * Builds an [AwgActivationRequest] from an [AwgProfileForm] plus the editor-only
 * transport fields the form does not carry as first-class columns
 * ([interfaceAddressV4], [mtu], [persistentKeepalive]).
 *
 * The obfuscation group (including the AWG 3.1 `i1`..`i5` programs and the full
 * `s1`..`s4` junk-size knobs), the identity + PSK fields, and the transport
 * carrier ([AwgProfileForm.carrier] / [AwgProfileForm.carrierWsUrl]) come
 * straight from [form]. Supported non-zero `s3`/`s4` values reach the native
 * runtime. A blank
 * [interfaceAddressV4] yields a request the service layer is expected to reject
 * -- the mapper does not invent a default address. Omitted [allowedIps] selects default
 * routes; an explicit empty list stays invalid for runtime activation.
 */
fun AwgProfileForm.toActivationRequest(
    profileId: String,
    interfaceAddressV4: String,
    interfaceAddressV6: String = "",
    dnsServers: List<String> = emptyList(),
    allowedIps: List<String>? = null,
    mtu: Int = AwgActivationRequest.DEFAULT_MTU,
    persistentKeepalive: Int = 0,
    persistentKeepaliveMax: Int = 0,
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
        allowedIps = allowedIps ?: defaultAwgAllowedIps(hasIpv6Address = interfaceAddressV6.isNotEmpty()),
        mtu = mtu,
        persistentKeepalive = persistentKeepalive,
        persistentKeepaliveMax = persistentKeepaliveMax,
        carrier = carrier,
        carrierWsUrl = carrierWsUrl,
        obfuscation =
            AwgActivationObfuscation(
                wireProfile = wireProfile,
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
                v3 = v3,
            ),
    )

/** Default routes for a new manual profile or share link without a route policy. */
fun defaultAwgAllowedIps(hasIpv6Address: Boolean): List<String> =
    if (hasIpv6Address) listOf("0.0.0.0/0", "::/0") else listOf("0.0.0.0/0")

private const val WssSchemePrefix = "wss://"
