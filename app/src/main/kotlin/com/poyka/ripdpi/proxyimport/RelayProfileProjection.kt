package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelaySecurityLayerReality
import com.poyka.ripdpi.data.RelaySecurityLayerTls
import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import com.poyka.ripdpi.data.RelayVlessFlowVision
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.RelayXhttpModeAuto
import com.poyka.ripdpi.data.validateNativeRelayProfile

/** Store-independent native-relay material derived from one parsed import profile. */
internal data class RelayProfileProjection(
    val profile: RelayProfileRecord,
    val credentials: RelayCredentialRecord,
) {
    companion object {
        fun from(
            source: ProxyProfile,
            profileId: String = DefaultRelayProfileId,
            tlsFingerprintOverride: String? = null,
        ): RelayProfileProjection? {
            val relayKind = relayKindFor(source)
            val valid =
                try {
                    validateNativeRelayProfile(source)
                } catch (_: IllegalArgumentException) {
                    false
                }
            if (relayKind == null || !valid) return null
            val endpoint = relayEndpoint(source)
            return RelayProfileProjection(
                profile =
                    RelayProfileRecord(
                        id = profileId,
                        kind = relayKind,
                        server = endpoint.server,
                        serverPort = endpoint.port,
                        serverName = endpoint.serverName,
                        securityLayer =
                            if (source is ProxyProfile.Vless) {
                                RelaySecurityLayerTls
                            } else {
                                RelaySecurityLayerReality
                            },
                        realityPublicKey = (source as? ProxyProfile.VlessReality)?.realityPublicKey.orEmpty(),
                        realityShortId = (source as? ProxyProfile.VlessReality)?.realityShortId.orEmpty(),
                        vlessFlow = source.vlessFlow(),
                        vlessFingerprint = tlsFingerprintOverride ?: source.vlessFingerprint(),
                        vlessTransport =
                            if (source.hasXhttpTransport()) {
                                RelayVlessTransportXhttp
                            } else {
                                RelayVlessTransportRealityTcp
                            },
                        xhttpPath = source.vlessXhttpPath(),
                        xhttpHost = source.vlessXhttpHost(),
                        xhttpMode = source.vlessXhttpMode(),
                        mieruProtocol = (source as? ProxyProfile.Mieru)?.protocol ?: "tcp",
                        mieruMultiplexing = (source as? ProxyProfile.Mieru)?.multiplexing ?: "middle",
                        mieruMtu = (source as? ProxyProfile.Mieru)?.mtu ?: 1400,
                        sshAuthType = (source as? ProxyProfile.Ssh)?.authType ?: RelaySshAuthTypePassword,
                        sshHostKeyFingerprint = (source as? ProxyProfile.Ssh)?.hostKeyFingerprint.orEmpty(),
                        sshStrictHostKey = (source as? ProxyProfile.Ssh)?.strictHostKey == true,
                        udpEnabled = source.relayUdpEnabled(),
                    ),
                credentials = relayCredentials(profileId, source),
            )
        }
    }
}

private data class RelayProjectionEndpoint(
    val server: String,
    val port: Int,
    val serverName: String,
)

private fun relayKindFor(profile: ProxyProfile): String? =
    when (profile) {
        is ProxyProfile.Trojan -> RelayKindTrojan
        is ProxyProfile.Shadowsocks -> RelayKindShadowsocks
        is ProxyProfile.AnyTls -> RelayKindAnyTls
        is ProxyProfile.VlessReality -> RelayKindVlessReality
        is ProxyProfile.Vless -> RelayKindVless.takeIf { profile.hasXhttpTransport() }
        is ProxyProfile.Hysteria2 -> RelayKindHysteria2
        is ProxyProfile.Ssh -> RelayKindSsh
        is ProxyProfile.Mieru -> RelayKindMieru
        is ProxyProfile.RawConfig -> null
    }

private fun ProxyProfile.hasXhttpTransport(): Boolean =
    when (this) {
        is ProxyProfile.Vless -> xhttpPath != null || xhttpHost != null
        is ProxyProfile.VlessReality -> xhttpPath != null || xhttpHost != null
        else -> false
    }

private fun ProxyProfile.vlessFlow(): String =
    when (this) {
        is ProxyProfile.Vless -> flow
        is ProxyProfile.VlessReality -> flow
        else -> RelayVlessFlowVision
    }

private fun ProxyProfile.vlessFingerprint(): String =
    when (this) {
        is ProxyProfile.Vless -> fingerprint.orEmpty()
        is ProxyProfile.VlessReality -> fingerprint.orEmpty()
        else -> ""
    }

private fun ProxyProfile.vlessXhttpPath(): String =
    when (this) {
        is ProxyProfile.Vless -> xhttpPath.orEmpty()
        is ProxyProfile.VlessReality -> xhttpPath.orEmpty()
        else -> ""
    }

private fun ProxyProfile.vlessXhttpHost(): String =
    when (this) {
        is ProxyProfile.Vless -> xhttpHost.orEmpty()
        is ProxyProfile.VlessReality -> xhttpHost.orEmpty()
        else -> ""
    }

private fun ProxyProfile.vlessXhttpMode(): String =
    when (this) {
        is ProxyProfile.Vless -> xhttpMode
        is ProxyProfile.VlessReality -> xhttpMode
        else -> RelayXhttpModeAuto
    }

private fun ProxyProfile.relayUdpEnabled(): Boolean =
    when (this) {
        is ProxyProfile.Ssh,
        is ProxyProfile.Vless,
        is ProxyProfile.VlessReality,
        is ProxyProfile.Mieru,
        -> false

        else -> true
    }

private fun relayEndpoint(profile: ProxyProfile): RelayProjectionEndpoint =
    when (profile) {
        is ProxyProfile.Trojan -> {
            RelayProjectionEndpoint(profile.server, profile.serverPort, profile.serverName ?: profile.server)
        }

        is ProxyProfile.Shadowsocks -> {
            RelayProjectionEndpoint(profile.server, profile.serverPort, profile.server)
        }

        is ProxyProfile.AnyTls -> {
            RelayProjectionEndpoint(profile.server, profile.serverPort, profile.serverName)
        }

        is ProxyProfile.VlessReality -> {
            RelayProjectionEndpoint(profile.server, profile.serverPort, profile.serverName)
        }

        is ProxyProfile.Vless -> {
            RelayProjectionEndpoint(profile.server, profile.serverPort, profile.serverName ?: profile.server)
        }

        is ProxyProfile.Hysteria2 -> {
            RelayProjectionEndpoint(profile.server, profile.serverPort, profile.serverName ?: profile.server)
        }

        is ProxyProfile.Ssh -> {
            RelayProjectionEndpoint(profile.server, profile.serverPort, profile.server)
        }

        is ProxyProfile.Mieru -> {
            RelayProjectionEndpoint(profile.server, profile.serverPort, profile.server)
        }

        is ProxyProfile.RawConfig -> {
            error("unsupported relay projection")
        }
    }

private fun relayCredentials(
    profileId: String,
    profile: ProxyProfile,
): RelayCredentialRecord =
    when (profile) {
        is ProxyProfile.Trojan -> {
            RelayCredentialRecord(profileId = profileId, trojanPassword = profile.password)
        }

        is ProxyProfile.Shadowsocks -> {
            RelayCredentialRecord(
                profileId = profileId,
                shadowsocksMethod = profile.method,
                shadowsocksPassword = profile.password,
            )
        }

        is ProxyProfile.AnyTls -> {
            RelayCredentialRecord(profileId = profileId, anyTlsPassword = profile.password)
        }

        is ProxyProfile.VlessReality -> {
            RelayCredentialRecord(profileId = profileId, vlessUuid = profile.uuid)
        }

        is ProxyProfile.Vless -> {
            RelayCredentialRecord(profileId = profileId, vlessUuid = profile.uuid)
        }

        is ProxyProfile.Hysteria2 -> {
            RelayCredentialRecord(
                profileId = profileId,
                hysteriaPassword = profile.password,
                hysteriaSalamanderKey = profile.obfsPassword,
                hysteriaInsecure = profile.insecure ?: false,
            )
        }

        is ProxyProfile.Ssh -> {
            val isKey = profile.authType == RelaySshAuthTypePrivateKey
            RelayCredentialRecord(
                profileId = profileId,
                sshUsername = profile.username,
                sshPassword = profile.password?.takeIf { !isKey },
                sshPrivateKey = profile.privateKey?.takeIf { isKey },
                sshPrivateKeyPassphrase = profile.privateKeyPassphrase?.takeIf { isKey },
            )
        }

        is ProxyProfile.Mieru -> {
            RelayCredentialRecord(
                profileId = profileId,
                mieruUsername = profile.username,
                mieruPassword = profile.password,
            )
        }

        is ProxyProfile.RawConfig -> {
            error("unsupported relay projection")
        }
    }
