package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface RipDpiAmneziaWgRuntime {
    suspend fun start(config: ResolvedRipDpiAmneziaWgConfig): Int

    suspend fun awaitReady(timeoutMillis: Long = defaultAmneziaWgReadyTimeoutMs)

    suspend fun stop()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot
}

internal const val defaultAmneziaWgReadyTimeoutMs = 15_000L
private const val wsAmneziaWgReadyTimeoutMs = 40_000L

@Serializable
data class RipDpiAmneziaWgObfuscationConfig(
    @Required
    val wireProfile: String = "awg-3.1-ranges-v1",
    @Required
    val jc: Int = 0,
    @Required
    val jmin: Int = 0,
    @Required
    val jmax: Int = 0,
    @Required
    val s1: Int = 0,
    @Required
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
    @Required
    val h1: String = "1",
    @Required
    val h2: String = "2",
    @Required
    val h3: String = "3",
    @Required
    val h4: String = "4",
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
    val v3: AmneziaWgV3Parameters = AmneziaWgV3Parameters(),
)

@Serializable
enum class RipDpiAmneziaWgCarrierKind {
    @SerialName("udp")
    Udp,

    @SerialName("ws")
    Ws,
    ;

    fun readyTimeoutMillis(): Long =
        when (this) {
            Udp -> defaultAmneziaWgReadyTimeoutMs
            Ws -> wsAmneziaWgReadyTimeoutMs
        }
}

@Serializable
data class ResolvedRipDpiAmneziaWgConfig(
    val enabled: Boolean,
    val profileId: String,
    val privateKey: String,
    val peerPublicKey: String,
    val presharedKey: String = "",
    val endpointHost: String,
    val endpointIpv4: String = "",
    val endpointIpv6: String = "",
    val endpointPort: Int,
    val interfaceAddressV4: String,
    val interfaceAddressV6: String = "",
    @Required
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val mtu: Int = DefaultAmneziaWgTunnelMtu,
    val persistentKeepalive: Int = 0,
    val persistentKeepaliveMax: Int = 0,
    val amnezia: RipDpiAmneziaWgObfuscationConfig = RipDpiAmneziaWgObfuscationConfig(),
    val carrier: RipDpiAmneziaWgCarrierKind = RipDpiAmneziaWgCarrierKind.Udp,
    val carrierWsUrl: String = "",
    val carrierWsAddresses: List<String>? = null,
    val localSocksHost: String,
    val localSocksPort: Int,
) {
    override fun toString(): String =
        "ResolvedRipDpiAmneziaWgConfig(enabled=$enabled, profileId=$profileId, " +
            "privateKey=${redact(privateKey)}, peerPublicKey=$peerPublicKey, presharedKey=${redact(presharedKey)}, " +
            "endpointHost=${redact(endpointHost)}, endpointIpv4=${redact(endpointIpv4)}, " +
            "endpointIpv6=${redact(endpointIpv6)}, endpointPort=$endpointPort, " +
            "interfaceAddressV4=${redact(interfaceAddressV4)}, " +
            "interfaceAddressV6=${redact(interfaceAddressV6)}, allowedIps=<redacted>, mtu=$mtu, " +
            "persistentKeepalive=$persistentKeepalive, persistentKeepaliveMax=$persistentKeepaliveMax, " +
            "amnezia=$amnezia, carrier=$carrier, carrierWsUrl=${redact(carrierWsUrl)}, " +
            "localSocksHost=$localSocksHost, localSocksPort=$localSocksPort)"

    private fun redact(value: String): String = if (value.isEmpty()) "\"\"" else "<redacted>"
}

internal const val DefaultAmneziaWgTunnelMtu = 1330
