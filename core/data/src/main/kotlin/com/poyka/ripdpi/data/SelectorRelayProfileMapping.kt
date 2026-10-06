package com.poyka.ripdpi.data

data class SelectorRelayProfileMapping(
    val profile: RelayProfileRecord,
    val credentials: RelayCredentialRecord,
)

/** Pure canonical mapping; transient credentials have unknown timestamp 0, stamped by the persistence owner. */
fun mapRelayProfile(
    profile: ProxyProfile,
    profileId: String = profile.id,
    tlsFingerprintOverride: String? = null,
): SelectorRelayProfileMapping? {
    val relayKind = relayKindFor(profile)
    if (relayKind == null || !validateNativeRelayProfile(profile)) return null
    val endpoint = relayEndpoint(profile)
    val udpEnabled = relayUdpEnabled(profile)
    val vlessTransport =
        if (profile.hasXhttpTransport()) {
            RelayVlessTransportXhttp
        } else {
            RelayVlessTransportRealityTcp
        }
    val relayProfile =
        RelayProfileRecord(
            id = profileId,
            kind = relayKind,
            server = endpoint.server,
            serverPort = endpoint.serverPort,
            serverName = endpoint.serverName,
            securityLayer =
                if (profile is ProxyProfile.Vless) {
                    RelaySecurityLayerTls
                } else {
                    RelaySecurityLayerReality
                },
            realityPublicKey = if (profile is ProxyProfile.VlessReality) profile.realityPublicKey else "",
            realityShortId = if (profile is ProxyProfile.VlessReality) profile.realityShortId else "",
            vlessFlow = profile.vlessFlow(),
            vlessFingerprint = tlsFingerprintOverride ?: profile.vlessFingerprint(),
            vlessTransport = vlessTransport,
            xhttpPath = profile.vlessXhttpPath(),
            xhttpHost = profile.vlessXhttpHost(),
            xhttpMode = profile.vlessXhttpMode(),
            mieruProtocol = if (profile is ProxyProfile.Mieru) profile.protocol else "tcp",
            mieruMultiplexing = if (profile is ProxyProfile.Mieru) profile.multiplexing else "middle",
            mieruMtu = if (profile is ProxyProfile.Mieru) profile.mtu else 1400,
            sshAuthType = if (profile is ProxyProfile.Ssh) profile.authType else RelaySshAuthTypePassword,
            sshHostKeyFingerprint =
                if (profile is ProxyProfile.Ssh) profile.hostKeyFingerprint.orEmpty() else "",
            sshStrictHostKey = profile is ProxyProfile.Ssh && profile.strictHostKey,
            udpEnabled = udpEnabled,
        )
    return SelectorRelayProfileMapping(relayProfile, relayCredentials(profileId, profile))
}

/** Relay-kind id for a relay-activatable [profile], or `null` for non-relay kinds. */
private fun relayKindFor(profile: ProxyProfile): String? =
    when (profile) {
        is ProxyProfile.Trojan -> RelayKindTrojan
        is ProxyProfile.Shadowsocks -> RelayKindShadowsocks
        is ProxyProfile.AnyTls -> RelayKindAnyTls
        is ProxyProfile.VlessReality -> RelayKindVlessReality
        is ProxyProfile.Vless -> if (profile.hasXhttpTransport()) RelayKindVless else null
        is ProxyProfile.Hysteria2 -> RelayKindHysteria2
        is ProxyProfile.Ssh -> RelayKindSsh
        is ProxyProfile.Mieru -> RelayKindMieru
        else -> null
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
        else -> com.poyka.ripdpi.data.RelayVlessFlowVision
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
        else -> com.poyka.ripdpi.data.RelayXhttpModeAuto
    }

private fun relayUdpEnabled(profile: ProxyProfile): Boolean =
    when (profile) {
        is ProxyProfile.Ssh,
        is ProxyProfile.Vless,
        is ProxyProfile.VlessReality,
        is ProxyProfile.Mieru,
        -> false

        else -> true
    }

private fun relayEndpoint(profile: ProxyProfile): RelayActivationEndpoint =
    when (profile) {
        is ProxyProfile.Trojan -> {
            RelayActivationEndpoint(profile.server, profile.serverPort, profile.serverName ?: profile.server)
        }

        is ProxyProfile.Shadowsocks -> {
            RelayActivationEndpoint(profile.server, profile.serverPort, profile.server)
        }

        is ProxyProfile.AnyTls -> {
            RelayActivationEndpoint(profile.server, profile.serverPort, profile.serverName)
        }

        is ProxyProfile.VlessReality -> {
            RelayActivationEndpoint(profile.server, profile.serverPort, profile.serverName)
        }

        is ProxyProfile.Vless -> {
            RelayActivationEndpoint(profile.server, profile.serverPort, profile.serverName ?: profile.server)
        }

        is ProxyProfile.Hysteria2 -> {
            RelayActivationEndpoint(profile.server, profile.serverPort, profile.serverName ?: profile.server)
        }

        is ProxyProfile.Ssh -> {
            RelayActivationEndpoint(profile.server, profile.serverPort, profile.server)
        }

        is ProxyProfile.Mieru -> {
            RelayActivationEndpoint(profile.server, profile.serverPort, profile.server)
        }

        else -> {
            error("unsupported relay activation profile ${profile::class.simpleName}")
        }
    }

private fun relayCredentials(
    profileId: String,
    profile: ProxyProfile,
): RelayCredentialRecord =
    when (profile) {
        is ProxyProfile.Trojan -> {
            RelayCredentialRecord(updatedAtEpochMillis = 0L, profileId = profileId, trojanPassword = profile.password)
        }

        is ProxyProfile.Shadowsocks -> {
            RelayCredentialRecord(
                updatedAtEpochMillis = 0L,
                profileId = profileId,
                shadowsocksMethod = profile.method,
                shadowsocksPassword = profile.password,
            )
        }

        is ProxyProfile.AnyTls -> {
            RelayCredentialRecord(updatedAtEpochMillis = 0L, profileId = profileId, anyTlsPassword = profile.password)
        }

        is ProxyProfile.VlessReality -> {
            RelayCredentialRecord(updatedAtEpochMillis = 0L, profileId = profileId, vlessUuid = profile.uuid)
        }

        is ProxyProfile.Vless -> {
            RelayCredentialRecord(updatedAtEpochMillis = 0L, profileId = profileId, vlessUuid = profile.uuid)
        }

        is ProxyProfile.Hysteria2 -> {
            RelayCredentialRecord(
                updatedAtEpochMillis = 0L,
                profileId = profileId,
                hysteriaPassword = profile.password,
                hysteriaSalamanderKey = profile.obfsPassword,
                hysteriaInsecure = profile.insecure ?: false,
            )
        }

        is ProxyProfile.Ssh -> {
            // Persist only the auth-relevant secret so a profile carrying
            // both never leaks the unused credential into the Keystore,
            // regardless of which caller built it.
            val isKey = profile.authType == RelaySshAuthTypePrivateKey
            RelayCredentialRecord(
                updatedAtEpochMillis = 0L,
                profileId = profileId,
                sshUsername = profile.username,
                sshPassword = profile.password?.takeIf { !isKey },
                sshPrivateKey = profile.privateKey?.takeIf { isKey },
                sshPrivateKeyPassphrase = profile.privateKeyPassphrase?.takeIf { isKey },
            )
        }

        is ProxyProfile.Mieru -> {
            RelayCredentialRecord(
                updatedAtEpochMillis = 0L,
                profileId = profileId,
                mieruUsername = profile.username,
                mieruPassword = profile.password,
            )
        }

        else -> {
            error("unsupported relay activation profile ${profile::class.simpleName}")
        }
    }

private data class RelayActivationEndpoint(
    val server: String,
    val serverPort: Int,
    val serverName: String,
)
