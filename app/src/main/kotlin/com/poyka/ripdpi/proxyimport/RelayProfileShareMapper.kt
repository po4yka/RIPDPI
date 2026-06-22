package com.poyka.ripdpi.proxyimport

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
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.uri.ProxyUriCodec
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
    ): ShareableRelayProfile? =
        record.toProxyProfile(credentials)?.let { profile ->
            ProxyUriCodec.encodeOrNull(profile)?.let { shareUri ->
                ShareableRelayProfile(profile = profile, shareUri = shareUri)
            }
        }

    private fun RelayProfileRecord.toProxyProfile(credentials: RelayCredentialRecord?): ProxyProfile? =
        when (kind) {
            RelayKindVlessReality -> toVlessReality(credentials)
            RelayKindVless -> toVless(credentials)
            RelayKindTrojan -> toTrojan(credentials)
            RelayKindHysteria2 -> toHysteria2(credentials)
            RelayKindAnyTls -> toAnyTls(credentials)
            RelayKindShadowsocks -> toShadowsocks(credentials)
            RelayKindMieru -> toMieru(credentials)
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
                        xhttpPath = xhttpPath.takeIf { vlessTransport == RelayVlessTransportXhttp && it.isNotBlank() },
                        xhttpHost = xhttpHost.takeIf { vlessTransport == RelayVlessTransportXhttp && it.isNotBlank() },
                        xhttpMode = xhttpMode,
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
                        obfsPassword = credentials.hysteriaSalamanderKey,
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
                privateKeyPassphrase = credentials.sshPrivateKeyPassphrase?.takeIf { isKey && it.isNotBlank() },
                hostKeyFingerprint = sshHostKeyFingerprint.ifBlank { null },
                strictHostKey = sshStrictHostKey,
            ).takeIf { hasAuthSecret && server.isNotBlank() && serverPort > 0 }
    }
}

private fun RelayProfileRecord.shareName(): String =
    listOf(operatorName, jurisdiction)
        .filter { it.isNotBlank() }
        .joinToString(" / ")
        .ifBlank { presetId.ifBlank { id } }
