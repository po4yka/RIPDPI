package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelaySecurityLayerReality
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.relayProfileSupportsUdpAssociation
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import com.poyka.ripdpi.data.validateNativeRelayProfileResult
import java.util.UUID

data class ShareableRelayProfile(
    val profile: ProxyProfile,
    val shareUri: String,
)

/**
 * Converts a saved relay profile plus secure credentials into the same canonical
 * per-protocol URI shape accepted by the import codecs.
 */
object RelayProfileShareMapper {
    fun toShareable(
        record: RelayProfileRecord,
        credentials: RelayCredentialRecord?,
    ): ShareableRelayProfile? {
        if (!record.canRepresentConnection(credentials)) return null
        return record.toProxyProfile(credentials)?.let { profile ->
            ProxyUriCodec
                .encodeOrNull(profile)
                ?.takeIf(::canImportShareUri)
                ?.let { shareUri -> ShareableRelayProfile(profile, shareUri) }
        }
    }

    private fun RelayProfileRecord.toProxyProfile(credentials: RelayCredentialRecord?): ProxyProfile? =
        when (kind) {
            RelayKindVlessReality -> toVlessReality(credentials)
            RelayKindVless -> toVless(credentials)
            RelayKindTrojan -> toTrojan(credentials)
            RelayKindTuicV5 -> toTuic(credentials)
            RelayKindHysteria2 -> toHysteria2(credentials)
            RelayKindAnyTls -> toAnyTls(credentials)
            RelayKindShadowsocks -> toShadowsocks(credentials)
            RelayKindMieru -> toMieru(credentials)
            RelayKindNaiveProxy -> toNaiveShareProfile(server, serverPort, shareName(), credentials)
            RelayKindSsh -> toSsh(credentials)
            else -> null
        }

    private fun RelayProfileRecord.toVlessReality(credentials: RelayCredentialRecord?): ProxyProfile? =
        when (val uuid = credentials?.vlessUuid?.takeIf { it.isNotBlank() }) {
            null -> {
                null
            }

            else -> {
                ProxyProfile
                    .VlessReality(
                        id = UUID.randomUUID().toString(),
                        displayName = shareName(),
                        groupId = "",
                        server = server,
                        serverPort = serverPort,
                        uuid = uuid,
                        realityPublicKey = realityPublicKey,
                        realityShortId = realityShortId,
                        serverName = serverName.ifBlank { server },
                        flow = vlessFlow,
                        fingerprint = vlessFingerprint.ifBlank { null },
                        xhttpPath = xhttpPath.takeIf { vlessTransport == RelayVlessTransportXhttp },
                        xhttpHost = xhttpHost.takeIf { vlessTransport == RelayVlessTransportXhttp },
                        xhttpMode = xhttpMode,
                        xhttpMaxConnections = xhttpMaxConnections,
                    ).takeIf { server.isNotBlank() && serverPort > 0 && realityPublicKey.isNotBlank() }
            }
        }

    private fun RelayProfileRecord.toVless(credentials: RelayCredentialRecord?): ProxyProfile? =
        when (val uuid = credentials?.vlessUuid?.takeIf { it.isNotBlank() }) {
            null -> {
                null
            }

            else -> {
                when {
                    server.isBlank() || serverPort <= 0 -> {
                        null
                    }

                    securityLayer == RelaySecurityLayerReality ||
                        realityPublicKey.isNotBlank() -> {
                        toVlessReality(credentials)
                    }

                    else -> {
                        ProxyProfile.Vless(
                            id = UUID.randomUUID().toString(),
                            displayName = shareName(),
                            groupId = "",
                            server = server,
                            serverPort = serverPort,
                            uuid = uuid,
                            serverName = serverName.ifBlank { server },
                            flow = vlessFlow,
                            fingerprint = vlessFingerprint.ifBlank { null },
                            xhttpPath = xhttpPath.takeIf { vlessTransport == RelayVlessTransportXhttp },
                            xhttpHost = xhttpHost.takeIf { vlessTransport == RelayVlessTransportXhttp },
                            xhttpMode = xhttpMode,
                            xhttpMaxConnections = xhttpMaxConnections,
                        )
                    }
                }
            }
        }

    private fun RelayProfileRecord.toTrojan(credentials: RelayCredentialRecord?): ProxyProfile? =
        when (val password = credentials?.trojanPassword?.takeIf { it.isNotBlank() }) {
            null -> {
                null
            }

            else -> {
                ProxyProfile
                    .Trojan(
                        id = UUID.randomUUID().toString(),
                        displayName = shareName(),
                        groupId = "",
                        server = server,
                        serverPort = serverPort,
                        password = password,
                        serverName = serverName.ifBlank { null },
                    ).takeIf { server.isNotBlank() && serverPort > 0 }
            }
        }

    private fun RelayProfileRecord.toHysteria2(credentials: RelayCredentialRecord?): ProxyProfile? =
        when (val password = credentials?.hysteriaPassword?.takeIf { it.isNotBlank() }) {
            null -> {
                null
            }

            else -> {
                ProxyProfile
                    .Hysteria2(
                        id = UUID.randomUUID().toString(),
                        displayName = shareName(),
                        groupId = "",
                        server = server,
                        serverPort = serverPort,
                        password = password,
                        serverName = serverName.ifBlank { null },
                        obfsPassword = credentials.hysteriaSalamanderKey,
                        insecure = credentials.hysteriaInsecure,
                        pinSha256 = credentials.hysteriaPinSha256,
                        portHopping = hysteriaPortHopping,
                    ).takeIf { server.isNotBlank() && serverPort > 0 }
            }
        }

    private fun RelayProfileRecord.toAnyTls(credentials: RelayCredentialRecord?): ProxyProfile? =
        when (val password = credentials?.anyTlsPassword?.takeIf { it.isNotBlank() }) {
            null -> {
                null
            }

            else -> {
                ProxyProfile
                    .AnyTls(
                        id = UUID.randomUUID().toString(),
                        displayName = shareName(),
                        groupId = "",
                        server = server,
                        serverPort = serverPort,
                        serverName = serverName.ifBlank { server },
                        password = password,
                        pinSha256 = credentials.anyTlsPinSha256,
                    ).takeIf { server.isNotBlank() && serverPort > 0 }
            }
        }

    private fun RelayProfileRecord.toShadowsocks(credentials: RelayCredentialRecord?): ProxyProfile? =
        credentials
            ?.shadowsocksMethod
            ?.takeIf { it.isNotBlank() }
            ?.let { method ->
                credentials.shadowsocksPassword?.takeIf { it.isNotBlank() }?.let { password ->
                    ProxyProfile
                        .Shadowsocks(
                            id = UUID.randomUUID().toString(),
                            displayName = shareName(),
                            groupId = "",
                            server = server,
                            serverPort = serverPort,
                            method = method,
                            password = password,
                        ).takeIf { server.isNotBlank() && serverPort > 0 }
                }
            }

    private fun RelayProfileRecord.toMieru(credentials: RelayCredentialRecord?): ProxyProfile? =
        credentials
            ?.mieruUsername
            ?.takeIf { it.isNotBlank() }
            ?.let { username ->
                credentials.mieruPassword?.takeIf { it.isNotBlank() }?.let { password ->
                    ProxyProfile
                        .Mieru(
                            id = UUID.randomUUID().toString(),
                            displayName = shareName(),
                            groupId = "",
                            server = server,
                            serverPort = serverPort,
                            username = username,
                            password = password,
                            protocol = mieruProtocol,
                            multiplexing = mieruMultiplexing,
                            mtu = mieruMtu,
                            handshakeMode = mieruHandshakeMode,
                            trafficPattern = mieruTrafficPattern,
                        ).takeIf { server.isNotBlank() && serverPort > 0 }
                }
            }

    private fun RelayProfileRecord.toSsh(credentials: RelayCredentialRecord?): ProxyProfile? {
        val username = credentials?.sshUsername?.takeIf { it.isNotBlank() } ?: return null
        val isKey = sshAuthType == RelaySshAuthTypePrivateKey
        val password = credentials.sshPassword?.takeIf { it.isNotBlank() }
        val privateKey = credentials.sshPrivateKey?.takeIf { it.isNotBlank() }
        // The auth-relevant secret must be present, or the reconstructed profile
        // could never authenticate.
        val hasAuthSecret = if (isKey) privateKey != null else password != null
        return ProxyProfile
            .Ssh(
                id = UUID.randomUUID().toString(),
                displayName = shareName(),
                groupId = "",
                server = server,
                serverPort = serverPort,
                username = username,
                authType = sshAuthType,
                password = if (isKey) null else password,
                privateKey = if (isKey) privateKey else null,
                privateKeyPassphrase = credentials.sshPrivateKeyPassphrase?.takeIf { isKey && it.isNotEmpty() },
                hostKeyFingerprint = sshHostKeyFingerprint.ifBlank { null },
                strictHostKey = sshStrictHostKey,
            ).takeIf { hasAuthSecret && server.isNotBlank() && serverPort > 0 }
    }
}

private fun RelayProfileRecord.toTuic(credentials: RelayCredentialRecord?): ProxyProfile? {
    if (tuicZeroRtt || tuicRootCertificatePem.isNotBlank()) return null
    val uuid = credentials?.tuicUuid
    val password = credentials?.tuicPassword
    return if (uuid == null || password == null) {
        null
    } else {
        ProxyProfile
            .Tuic(
                id = UUID.randomUUID().toString(),
                displayName = shareName(),
                groupId = "",
                server = server,
                serverPort = serverPort,
                uuid = uuid,
                password = password,
                serverName = serverName.ifBlank { server },
                congestionControl = tuicCongestionControl,
                alpn = tuicAlpn,
            ).takeIf { validateNativeRelayProfileResult(it).getOrDefault(false) }
    }
}

private fun RelayProfileRecord.canRepresentConnection(credentials: RelayCredentialRecord?): Boolean {
    // Local listener settings belong to the receiving device. These settings affect the remote connection.
    if (outboundBindIp.isNotBlank() || finalmaskType !in setOf("", RelayFinalmaskTypeOff)) return false
    val uriEnablesUdp =
        relayProfileSupportsUdpAssociation(kind, true, vlessTransport, vlessFlow, credentials?.shadowsocksMethod)
    return (!uriEnablesUdp || udpEnabled) &&
        when (kind) {
            RelayKindTrojan -> trojanRootCertificatePem.isBlank()
            RelayKindNaiveProxy -> naivePath.isBlank() && (serverName.isBlank() || serverName == server)
            RelayKindVless -> vlessTransport == RelayVlessTransportXhttp
            RelayKindVlessReality -> vlessTransport in setOf(RelayVlessTransportRealityTcp, RelayVlessTransportXhttp)
            else -> true
        }
}

private fun canImportShareUri(uri: String): Boolean {
    val imported = ProxyUriCodec.parse(uri) ?: return false
    // An SSH link without a pin opens the host-key approval editor before activation.
    val needsHostKeyApproval = imported is ProxyProfile.Ssh && imported.hostKeyFingerprint.isNullOrBlank()
    return needsHostKeyApproval || validateNativeRelayProfileResult(imported).getOrDefault(false)
}

private fun RelayProfileRecord.shareName(): String =
    listOf(operatorName, jurisdiction)
        .filter { it.isNotBlank() }
        .joinToString(" / ")
        .ifBlank { presetId.ifBlank { id } }
