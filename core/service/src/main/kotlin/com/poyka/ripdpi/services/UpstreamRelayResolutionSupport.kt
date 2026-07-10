package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedChainRelayHopConfig
import com.poyka.ripdpi.core.ResolvedRelayFinalmaskConfig
import com.poyka.ripdpi.core.ResolvedShadowTlsInnerRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayTrustDomain
import com.poyka.ripdpi.data.RelayTrustDomainWarning
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.detectRelayChainTrustWarning
import com.poyka.ripdpi.data.isSupportedChainEntryHop
import com.poyka.ripdpi.data.isSupportedChainExitHop
import com.poyka.ripdpi.data.isSupportedChainNonEntryHop
import com.poyka.ripdpi.data.isValidVlessUuid
import com.poyka.ripdpi.data.normalizeImportedTlsFingerprint
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import com.poyka.ripdpi.data.toRelayTrustDomain
import com.poyka.ripdpi.data.validateVlessRealityEndpointFields

internal data class ResolvedChainRelayHop(
    val profileId: String,
    val trustDomain: RelayTrustDomain,
    val server: String,
    val serverPort: Int,
    val serverName: String,
    val publicKey: String,
    val shortId: String,
    val uuid: String,
    val config: ResolvedChainRelayHopConfig,
)

/**
 * The resolved hops of a composed chain, in order. [hops] is the N-hop source
 * of truth (`hops.first()` is the entry, `hops.last()` is the exit); [entry] and
 * [exit] are convenience accessors over it for the two-hop call sites. The
 * [trustWarning] reports per-pair shared trust domains, missing trust domains,
 * and the cumulative latency caveat (hop count) across every hop — see
 * [detectRelayChainTrustWarning].
 */
internal data class ResolvedChainRelayConfig(
    val hops: List<ResolvedChainRelayHop>,
    val trustWarning: RelayTrustDomainWarning? = detectRelayChainTrustWarning(hops.map { it.trustDomain }),
) {
    init {
        require(hops.size >= 2) { "A chain relay must resolve at least two hops, got ${hops.size}" }
    }

    val entry: ResolvedChainRelayHop get() = hops.first()
    val exit: ResolvedChainRelayHop get() = hops.last()
}

internal suspend fun resolveChainRelayConfigSupport(
    chainProfileId: String,
    config: RipDpiRelayConfig,
    credentials: RelayCredentialRecord?,
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialStore,
    fallbackTlsFingerprintProfile: String,
): ResolvedChainRelayConfig {
    val entry =
        resolveChainRelayHopSupport(
            hopName = "entry",
            chainProfileId = chainProfileId,
            profileId = config.chainEntryProfileId,
            legacyServer = config.chainEntryServer,
            legacyServerPort = config.chainEntryPort,
            legacyServerName = config.chainEntryServerName,
            legacyPublicKey = config.chainEntryPublicKey,
            legacyShortId = config.chainEntryShortId,
            legacyUuid = credentials?.chainEntryUuid,
            relayProfileStore = relayProfileStore,
            relayCredentialStore = relayCredentialStore,
            fallbackTlsFingerprintProfile = fallbackTlsFingerprintProfile,
        )
    // Intermediate hops are always referenced-profile hops (no legacy inline
    // fallback exists for them). They must support connect-over just like exit
    // hops; QUIC-only carriers are valid only at index zero.
    val middle =
        config.chainMiddleProfileIds
            .filter(String::isNotBlank)
            .map { middleProfileId ->
                resolveChainRelayHopSupport(
                    hopName = "intermediate",
                    chainProfileId = chainProfileId,
                    profileId = middleProfileId,
                    legacyServer = "",
                    legacyServerPort = DefaultChainHopPortSupport,
                    legacyServerName = "",
                    legacyPublicKey = "",
                    legacyShortId = "",
                    legacyUuid = null,
                    relayProfileStore = relayProfileStore,
                    relayCredentialStore = relayCredentialStore,
                    fallbackTlsFingerprintProfile = fallbackTlsFingerprintProfile,
                )
            }
    val exit =
        resolveChainRelayHopSupport(
            hopName = "exit",
            chainProfileId = chainProfileId,
            profileId = config.chainExitProfileId,
            legacyServer = config.chainExitServer,
            legacyServerPort = config.chainExitPort,
            legacyServerName = config.chainExitServerName,
            legacyPublicKey = config.chainExitPublicKey,
            legacyShortId = config.chainExitShortId,
            legacyUuid = credentials?.chainExitUuid,
            relayProfileStore = relayProfileStore,
            relayCredentialStore = relayCredentialStore,
            fallbackTlsFingerprintProfile = fallbackTlsFingerprintProfile,
        )
    return ResolvedChainRelayConfig(
        hops =
            buildList {
                add(entry)
                addAll(middle)
                add(exit)
            },
    )
}

private const val DefaultChainHopPortSupport = 443

internal suspend fun resolveShadowTlsInnerConfigSupport(
    outerProfileId: String,
    innerProfileId: String,
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialStore,
    fallbackTlsFingerprintProfile: String,
): ResolvedShadowTlsInnerRelayConfig {
    val innerProfile = loadShadowTlsInnerProfile(innerProfileId, relayProfileStore)
    rejectSelfReferentialShadowTlsProfile(innerProfile.id, outerProfileId)
    val innerCredentials = relayCredentialStore.load(innerProfileId)
    return when (innerProfile.kind) {
        RelayKindVlessReality -> {
            if (innerProfile.vlessTransport == RelayVlessTransportXhttp) {
                rejectRelayConfig("ShadowTLS currently supports only VLESS Reality TCP as an inner profile")
            }
            if (innerCredentials?.vlessUuid.isNullOrBlank()) {
                rejectRelayConfig("Relay credentials missing for profile $innerProfileId")
            }
            ResolvedShadowTlsInnerRelayConfig(
                kind = innerProfile.kind,
                profileId = innerProfile.id,
                server = innerProfile.server,
                serverPort = innerProfile.serverPort,
                serverName = innerProfile.serverName,
                realityPublicKey = innerProfile.realityPublicKey,
                realityShortId = innerProfile.realityShortId,
                vlessFlow = innerProfile.vlessFlow,
                vlessTransport = innerProfile.vlessTransport.ifBlank { RelayVlessTransportRealityTcp },
                xhttpMode = innerProfile.xhttpMode.ifBlank { com.poyka.ripdpi.data.RelayXhttpModeAuto },
                vlessUuid = innerCredentials.vlessUuid,
                tlsFingerprintProfile = resolveProfileTlsFingerprint(innerProfile, fallbackTlsFingerprintProfile),
            )
        }

        else -> {
            throw ServiceStartupRejectedException(
                FailureReason.RelayConfigRejected(
                    "ShadowTLS inner profile kind ${innerProfile.kind} is not supported yet",
                ),
            )
        }
    }
}

internal fun resolveMasqueAuthModeSupport(credentials: RelayCredentialRecord?): String? =
    normalizeRelayMasqueAuthMode(credentials?.masqueAuthMode)
        ?: when {
            !credentials?.masqueClientCertificateChainPem.isNullOrBlank() &&
                !credentials.masqueClientPrivateKeyPem.isNullOrBlank() -> RelayMasqueAuthModeCloudflareMtls

            !credentials?.masqueAuthToken.isNullOrBlank() -> RelayMasqueAuthModeBearer

            else -> null
        }

private suspend fun resolveChainRelayHopSupport(
    hopName: String,
    chainProfileId: String,
    profileId: String,
    legacyServer: String,
    legacyServerPort: Int,
    legacyServerName: String,
    legacyPublicKey: String,
    legacyShortId: String,
    legacyUuid: String?,
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialStore,
    fallbackTlsFingerprintProfile: String,
): ResolvedChainRelayHop {
    if (profileId.isNotBlank()) {
        val profile = loadChainRelayHopProfile(hopName, profileId, relayProfileStore)
        rejectSelfReferentialChainRelayHop(hopName, profile.id, chainProfileId)
        rejectUnsupportedChainRelayHopKind(hopName, profile)
        val hopCredentials = relayCredentialStore.load(profileId)
        val tlsFingerprintProfile = resolveProfileTlsFingerprint(profile, fallbackTlsFingerprintProfile)
        val shadowTlsInner =
            if (profile.kind == RelayKindShadowTlsV3) {
                resolveShadowTlsInnerConfigSupport(
                    outerProfileId = profile.id,
                    innerProfileId = profile.shadowTlsInnerProfileId,
                    relayProfileStore = relayProfileStore,
                    relayCredentialStore = relayCredentialStore,
                    fallbackTlsFingerprintProfile = fallbackTlsFingerprintProfile,
                )
            } else {
                null
            }
        val hopConfig =
            profile.toResolvedChainRelayHopConfig(
                credentials = hopCredentials,
                tlsFingerprintProfile = tlsFingerprintProfile,
                shadowTlsInner = shadowTlsInner,
            )
        return ResolvedChainRelayHop(
            profileId = hopConfig.profileId,
            trustDomain = profile.toRelayTrustDomain(),
            server = hopConfig.server,
            serverPort = hopConfig.serverPort,
            serverName = hopConfig.serverName,
            publicKey = hopConfig.realityPublicKey,
            shortId = hopConfig.realityShortId,
            uuid = hopConfig.vlessUuid.orEmpty(),
            config = hopConfig,
        )
    }
    require(legacyServer.isNotBlank()) { "Chain relay $hopName profile reference is required" }
    require(legacyServerName.isNotBlank() && legacyPublicKey.isNotBlank() && legacyShortId.isNotBlank()) {
        "Chain relay legacy $hopName settings are incomplete"
    }
    val checkedLegacyUuid = legacyUuid.orEmpty()
    require(isValidVlessUuid(checkedLegacyUuid)) { "Relay credentials missing for chain relay $hopName" }
    validateVlessRealityEndpointFields(
        server = legacyServer,
        serverPort = legacyServerPort,
        serverName = legacyServerName,
        realityPublicKey = legacyPublicKey,
        realityShortId = legacyShortId,
    )
    return ResolvedChainRelayHop(
        profileId = "",
        trustDomain = RelayTrustDomain(),
        server = legacyServer,
        serverPort = legacyServerPort,
        serverName = legacyServerName,
        publicKey = legacyPublicKey,
        shortId = legacyShortId,
        uuid = checkedLegacyUuid,
        config =
            ResolvedChainRelayHopConfig(
                kind = RelayKindVlessReality,
                server = legacyServer,
                serverPort = legacyServerPort,
                serverName = legacyServerName,
                realityPublicKey = legacyPublicKey,
                realityShortId = legacyShortId,
                vlessFlow = com.poyka.ripdpi.data.RelayVlessFlowVision,
                vlessTransport = RelayVlessTransportRealityTcp,
                xhttpMode = com.poyka.ripdpi.data.RelayXhttpModeAuto,
                vlessUuid = checkedLegacyUuid,
                tlsFingerprintProfile = normalizeTlsFingerprintProfile(fallbackTlsFingerprintProfile),
            ),
    )
}

private suspend fun loadShadowTlsInnerProfile(
    innerProfileId: String,
    relayProfileStore: RelayProfileStore,
): RelayProfileRecord =
    relayProfileStore.load(innerProfileId)
        ?: throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected("ShadowTLS inner profile $innerProfileId was not found"),
        )

private fun rejectSelfReferentialShadowTlsProfile(
    innerProfileId: String,
    outerProfileId: String,
) {
    if (innerProfileId == outerProfileId) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected("ShadowTLS inner profile cannot reference itself"),
        )
    }
}

private suspend fun loadChainRelayHopProfile(
    hopName: String,
    profileId: String,
    relayProfileStore: RelayProfileStore,
): RelayProfileRecord =
    relayProfileStore.load(profileId)
        ?: throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected("Chain relay $hopName profile $profileId was not found"),
        )

private fun rejectSelfReferentialChainRelayHop(
    hopName: String,
    profileId: String,
    chainProfileId: String,
) {
    if (profileId == chainProfileId) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected("Chain relay $hopName profile cannot reference itself"),
        )
    }
}

private fun rejectUnsupportedChainRelayHopKind(
    hopName: String,
    profile: RelayProfileRecord,
) {
    val isSupported =
        when (hopName) {
            "entry" -> profile.isSupportedChainEntryHop()

            // Every non-entry hop must implement connect-over; QUIC-only
            // carriers open an independent socket and are entry-only.
            "intermediate" -> profile.isSupportedChainNonEntryHop()

            "exit" -> profile.isSupportedChainExitHop()

            else -> false
        }
    if (isSupported) {
        rejectUnsupportedChainRelayHopMode(hopName, profile)
        return
    }
    val isSupportedInOtherRole = profile.isSupportedChainEntryHop() || profile.isSupportedChainExitHop()
    val message =
        if (profile.isSupportedChainEntryHop() && !profile.isSupportedChainNonEntryHop()) {
            "chain relay $hopName profile kind ${profile.kind} is entry-only because it requires an independent QUIC carrier"
        } else if (isSupportedInOtherRole) {
            "chain relay $hopName profile kind ${profile.kind} is not supported by chain relay as $hopName hop"
        } else {
            "chain relay $hopName profile kind ${profile.kind} is not supported by chain relay"
        }
    throw ServiceStartupRejectedException(
        FailureReason.RelayConfigRejected(message),
    )
}

private fun rejectUnsupportedChainRelayHopMode(
    hopName: String,
    profile: RelayProfileRecord,
) {
    if (hopName != "entry" && profile.kind == RelayKindMasque && !profile.masqueUseHttp2Fallback) {
        rejectRelayConfig(
            "chain relay $hopName MASQUE profile requires HTTP/2 fallback; H3-only MASQUE is valid only as the entry hop",
        )
    }
}

private fun resolveProfileTlsFingerprint(
    profile: RelayProfileRecord,
    fallbackTlsFingerprintProfile: String,
): String {
    if (profile.vlessFingerprint.isBlank()) {
        return normalizeTlsFingerprintProfile(fallbackTlsFingerprintProfile)
    }
    return normalizeImportedTlsFingerprint(profile.vlessFingerprint)
        ?: rejectRelayConfig("Relay profile ${profile.id} has an unsupported TLS fingerprint")
}

private fun rejectRelayConfig(message: String): Nothing =
    throw ServiceStartupRejectedException(FailureReason.RelayConfigRejected(message))

private fun RelayProfileRecord.toResolvedChainRelayHopConfig(
    credentials: RelayCredentialRecord?,
    tlsFingerprintProfile: String,
    shadowTlsInner: ResolvedShadowTlsInnerRelayConfig?,
): ResolvedChainRelayHopConfig =
    ResolvedChainRelayHopConfig(
        kind = kind,
        profileId = id,
        server = server,
        serverPort = serverPort,
        serverName = serverName,
        realityPublicKey = realityPublicKey,
        realityShortId = realityShortId,
        vlessFlow = vlessFlow,
        vlessTransport = vlessTransport.ifBlank { RelayVlessTransportRealityTcp },
        xhttpPath = xhttpPath,
        xhttpHost = xhttpHost,
        xhttpMode = xhttpMode.ifBlank { com.poyka.ripdpi.data.RelayXhttpModeAuto },
        cloudflareTunnelMode = cloudflareTunnelMode,
        cloudflarePublishLocalOriginUrl = cloudflarePublishLocalOriginUrl,
        cloudflareCredentialsRef = cloudflareCredentialsRef,
        masqueUrl = masqueUrl,
        masqueUseHttp2Fallback = masqueUseHttp2Fallback,
        masqueCloudflareGeohashEnabled = masqueCloudflareGeohashEnabled,
        tuicZeroRtt = tuicZeroRtt,
        tuicCongestionControl = tuicCongestionControl,
        shadowTlsInnerProfileId = shadowTlsInnerProfileId,
        shadowTlsInner = shadowTlsInner,
        naivePath = naivePath,
        vlessUuid = credentials?.vlessUuid,
        hysteriaPassword = credentials?.hysteriaPassword,
        hysteriaSalamanderKey = credentials?.hysteriaSalamanderKey,
        anyTlsPassword = credentials?.anyTlsPassword,
        tuicUuid = credentials?.tuicUuid,
        tuicPassword = credentials?.tuicPassword,
        shadowTlsPassword = credentials?.shadowTlsPassword,
        trojanPassword = credentials?.trojanPassword,
        shadowsocksMethod = credentials?.shadowsocksMethod,
        shadowsocksPassword = credentials?.shadowsocksPassword,
        naiveUsername = credentials?.naiveUsername,
        naivePassword = credentials?.naivePassword,
        tlsFingerprintProfile = tlsFingerprintProfile,
        masqueAuthMode = resolveMasqueAuthModeSupport(credentials),
        masqueAuthToken = credentials?.masqueAuthToken,
        masqueClientCertificateChainPem = credentials?.masqueClientCertificateChainPem,
        masqueClientPrivateKeyPem = credentials?.masqueClientPrivateKeyPem,
        cloudflareTunnelToken = credentials?.cloudflareTunnelToken,
        cloudflareTunnelCredentialsJson = credentials?.cloudflareTunnelCredentialsJson,
        finalmask =
            ResolvedRelayFinalmaskConfig(
                type = finalmaskType,
                headerHex = finalmaskHeaderHex,
                trailerHex = finalmaskTrailerHex,
                randRange = finalmaskRandRange,
                sudokuSeed = finalmaskSudokuSeed,
                fragmentPackets = finalmaskFragmentPackets,
                fragmentMinBytes = finalmaskFragmentMinBytes,
                fragmentMaxBytes = finalmaskFragmentMaxBytes,
            ),
    ).also { hop ->
        validateChainHopCredentials(hop)
    }

private fun validateChainHopCredentials(hop: ResolvedChainRelayHopConfig) {
    when (hop.kind) {
        RelayKindVlessReality -> {
            require(isValidVlessUuid(hop.vlessUuid)) { "Relay credentials missing for profile ${hop.profileId}" }
            validateVlessRealityEndpointFields(
                server = hop.server,
                serverPort = hop.serverPort,
                serverName = hop.serverName,
                realityPublicKey = hop.realityPublicKey,
                realityShortId = hop.realityShortId,
            )
        }

        RelayKindCloudflareTunnel -> {
            require(!hop.vlessUuid.isNullOrBlank()) { "Relay credentials missing for profile ${hop.profileId}" }
        }

        RelayKindHysteria2 -> {
            require(!hop.hysteriaPassword.isNullOrBlank()) {
                "Relay credentials missing for profile ${hop.profileId}"
            }
        }

        RelayKindTuicV5 -> {
            require(!hop.tuicUuid.isNullOrBlank() && !hop.tuicPassword.isNullOrBlank()) {
                "Relay credentials missing for profile ${hop.profileId}"
            }
        }

        RelayKindShadowTlsV3 -> {
            require(!hop.shadowTlsPassword.isNullOrBlank()) {
                "Relay credentials missing for profile ${hop.profileId}"
            }
        }

        RelayKindTrojan -> {
            require(!hop.trojanPassword.isNullOrBlank()) {
                "Relay credentials missing for profile ${hop.profileId}"
            }
        }

        RelayKindAnyTls -> {
            require(!hop.anyTlsPassword.isNullOrBlank()) {
                "Relay credentials missing for profile ${hop.profileId}"
            }
        }

        RelayKindShadowsocks -> {
            require(!hop.shadowsocksPassword.isNullOrBlank()) {
                "Relay credentials missing for profile ${hop.profileId}"
            }
        }

        RelayKindNaiveProxy -> {
            require(!hop.naiveUsername.isNullOrBlank() && !hop.naivePassword.isNullOrBlank()) {
                "Relay credentials missing for profile ${hop.profileId}"
            }
        }

        RelayKindMasque -> {
            require(hop.masqueAuthMode != RelayMasqueAuthModeBearer || !hop.masqueAuthToken.isNullOrBlank()) {
                "Relay credentials missing for profile ${hop.profileId}"
            }
        }
    }
}
