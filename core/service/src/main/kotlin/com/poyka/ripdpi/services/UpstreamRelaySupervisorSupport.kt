@file:Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount", "MaxLineLength")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedShadowTlsInnerRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindGoogleAppsScript
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.StrategyFeatureCloudflareConsumeValidation
import com.poyka.ripdpi.data.StrategyFeatureCloudflarePublish
import com.poyka.ripdpi.data.StrategyFeatureFinalmask
import com.poyka.ripdpi.data.StrategyFeatureMasqueCloudflareDirect
import com.poyka.ripdpi.data.StrategyFeatureNaiveProxyWatchdog
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import java.net.URI

internal data class ResolvedChainRelayHop(
    val profileId: String,
    val server: String,
    val serverPort: Int,
    val serverName: String,
    val publicKey: String,
    val shortId: String,
    val uuid: String,
)

internal data class ResolvedChainRelayConfig(
    val entry: ResolvedChainRelayHop,
    val exit: ResolvedChainRelayHop,
)

internal fun mergeRelayConfig(
    config: RipDpiRelayConfig,
    profile: RelayProfileRecord?,
): RipDpiRelayConfig {
    if (profile == null) return config
    return config.copy(
        kind = profile.kind.ifBlank { config.kind },
        outboundBindIp = profile.outboundBindIp.ifBlank { config.outboundBindIp },
        server = profile.server.ifBlank { config.server },
        serverPort = profile.serverPort,
        serverName = profile.serverName.ifBlank { config.serverName },
        realityPublicKey = profile.realityPublicKey.ifBlank { config.realityPublicKey },
        realityShortId = profile.realityShortId.ifBlank { config.realityShortId },
        vlessTransport = profile.vlessTransport.ifBlank { config.vlessTransport },
        xhttpPath = profile.xhttpPath.ifBlank { config.xhttpPath },
        xhttpHost = profile.xhttpHost.ifBlank { config.xhttpHost },
        cloudflareTunnelMode = profile.cloudflareTunnelMode.ifBlank { config.cloudflareTunnelMode },
        cloudflarePublishLocalOriginUrl =
            profile.cloudflarePublishLocalOriginUrl.ifBlank { config.cloudflarePublishLocalOriginUrl },
        cloudflareCredentialsRef = profile.cloudflareCredentialsRef.ifBlank { config.cloudflareCredentialsRef },
        chainEntryServer = profile.chainEntryServer.ifBlank { config.chainEntryServer },
        chainEntryPort = profile.chainEntryPort,
        chainEntryServerName = profile.chainEntryServerName.ifBlank { config.chainEntryServerName },
        chainEntryPublicKey = profile.chainEntryPublicKey.ifBlank { config.chainEntryPublicKey },
        chainEntryShortId = profile.chainEntryShortId.ifBlank { config.chainEntryShortId },
        chainEntryProfileId = profile.chainEntryProfileId.ifBlank { config.chainEntryProfileId },
        chainExitServer = profile.chainExitServer.ifBlank { config.chainExitServer },
        chainExitPort = profile.chainExitPort,
        chainExitServerName = profile.chainExitServerName.ifBlank { config.chainExitServerName },
        chainExitPublicKey = profile.chainExitPublicKey.ifBlank { config.chainExitPublicKey },
        chainExitShortId = profile.chainExitShortId.ifBlank { config.chainExitShortId },
        chainExitProfileId = profile.chainExitProfileId.ifBlank { config.chainExitProfileId },
        masqueUrl = profile.masqueUrl.ifBlank { config.masqueUrl },
        masqueUseHttp2Fallback = profile.masqueUseHttp2Fallback,
        masqueCloudflareGeohashEnabled = profile.masqueCloudflareGeohashEnabled,
        tuicZeroRtt = profile.tuicZeroRtt,
        tuicCongestionControl = profile.tuicCongestionControl,
        shadowTlsInnerProfileId = profile.shadowTlsInnerProfileId.ifBlank { config.shadowTlsInnerProfileId },
        naivePath = profile.naivePath.ifBlank { config.naivePath },
        appsScriptScriptIds =
            profile.appsScriptScriptIds.takeIf {
                it.isNotEmpty()
            } ?: config.appsScriptScriptIds,
        appsScriptGoogleIp = profile.appsScriptGoogleIp.ifBlank { config.appsScriptGoogleIp },
        appsScriptFrontDomain = profile.appsScriptFrontDomain.ifBlank { config.appsScriptFrontDomain },
        appsScriptSniHosts =
            profile.appsScriptSniHosts.takeIf {
                it.isNotEmpty()
            } ?: config.appsScriptSniHosts,
        appsScriptVerifySsl = profile.appsScriptVerifySsl,
        appsScriptParallelRelay = profile.appsScriptParallelRelay,
        appsScriptDirectHosts =
            profile.appsScriptDirectHosts.takeIf {
                it.isNotEmpty()
            } ?: config.appsScriptDirectHosts,
        ptBridgeLine = profile.ptBridgeLine.ifBlank { config.ptBridgeLine },
        ptWebTunnelUrl = profile.ptWebTunnelUrl.ifBlank { config.ptWebTunnelUrl },
        ptSnowflakeBrokerUrl = profile.ptSnowflakeBrokerUrl.ifBlank { config.ptSnowflakeBrokerUrl },
        ptSnowflakeFrontDomain = profile.ptSnowflakeFrontDomain.ifBlank { config.ptSnowflakeFrontDomain },
        localSocksHost = profile.localSocksHost.ifBlank { config.localSocksHost },
        localSocksPort = profile.localSocksPort,
        udpEnabled = profile.udpEnabled,
        tcpFallbackEnabled = profile.tcpFallbackEnabled,
        finalmask =
            config.finalmask.copy(
                type = profile.finalmaskType.ifBlank { config.finalmask.type },
                headerHex = profile.finalmaskHeaderHex.ifBlank { config.finalmask.headerHex },
                trailerHex = profile.finalmaskTrailerHex.ifBlank { config.finalmask.trailerHex },
                randRange = profile.finalmaskRandRange.ifBlank { config.finalmask.randRange },
                sudokuSeed = profile.finalmaskSudokuSeed.ifBlank { config.finalmask.sudokuSeed },
                fragmentPackets =
                    profile.finalmaskFragmentPackets.takeIf {
                        it > 0
                    } ?: config.finalmask.fragmentPackets,
                fragmentMinBytes =
                    profile.finalmaskFragmentMinBytes.takeIf { it > 0 } ?: config.finalmask.fragmentMinBytes,
                fragmentMaxBytes =
                    profile.finalmaskFragmentMaxBytes.takeIf { it > 0 } ?: config.finalmask.fragmentMaxBytes,
            ),
    )
}

internal fun validateCloudflareTunnelCredentials(
    profileId: String,
    credentials: RelayCredentialRecord?,
) {
    requireRelayCredentials(profileId, !credentials?.vlessUuid.isNullOrBlank())
}

internal fun validateDefaultRelayCredentials(
    profileId: String,
    relayKind: String,
    credentials: RelayCredentialRecord?,
) {
    val hasRequiredCredentials =
        when (relayKind) {
            RelayKindVlessReality -> !credentials?.vlessUuid.isNullOrBlank()
            RelayKindHysteria2 -> !credentials?.hysteriaPassword.isNullOrBlank()
            RelayKindTuicV5 -> !credentials?.tuicUuid.isNullOrBlank() && !credentials.tuicPassword.isNullOrBlank()
            RelayKindGoogleAppsScript -> !credentials?.appsScriptAuthKey.isNullOrBlank()
            else -> true
        }
    requireRelayCredentials(profileId, hasRequiredCredentials)
}

internal fun validateMasqueRelayCredentials(
    profileId: String,
    masqueAuthMode: String?,
    credentials: RelayCredentialRecord?,
) {
    val hasRequiredCredentials =
        when (masqueAuthMode) {
            RelayMasqueAuthModeBearer, RelayMasqueAuthModePreshared -> {
                !credentials?.masqueAuthToken.isNullOrBlank()
            }

            RelayMasqueAuthModeCloudflareMtls -> {
                !credentials?.masqueClientCertificateChainPem.isNullOrBlank() &&
                    !credentials.masqueClientPrivateKeyPem.isNullOrBlank()
            }

            RelayMasqueAuthModePrivacyPass -> {
                true
            }

            else -> {
                false
            }
        }
    requireRelayCredentials(profileId, hasRequiredCredentials)
}

internal fun validateNaiveRelayCredentials(
    profileId: String,
    credentials: RelayCredentialRecord?,
) {
    requireRelayCredentials(
        profileId = profileId,
        hasRequiredCredentials =
            !credentials?.naiveUsername.isNullOrBlank() &&
                !credentials.naivePassword.isNullOrBlank(),
    )
}

internal fun validateShadowTlsRelayCredentials(
    profileId: String,
    credentials: RelayCredentialRecord?,
) {
    requireRelayCredentials(profileId, !credentials?.shadowTlsPassword.isNullOrBlank())
}

internal fun validateSharedRelayTransportFeatures(config: RipDpiRelayConfig) {
    require(
        !config.udpEnabled || config.kind == RelayKindHysteria2 || config.kind == RelayKindMasque ||
            config.kind == RelayKindTuicV5,
    ) {
        "Relay UDP mode is only available for Hysteria2, MASQUE, and TUIC profiles"
    }
    require(!(config.vlessTransport == RelayVlessTransportXhttp && config.udpEnabled)) {
        "xHTTP transport does not support UDP mode"
    }
}

internal fun validateCloudflareTunnelFeatures(
    config: RipDpiRelayConfig,
    credentials: RelayCredentialRecord?,
    tlsFingerprintProfile: String,
    featureFlags: Map<String, Boolean>,
) {
    if (tlsFingerprintProfile != TlsFingerprintProfileChromeStable) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayFingerprintPolicyRejected(
                "Cloudflare Tunnel requires the Chrome stable TLS fingerprint profile",
            ),
        )
    }
    require(config.server.isNotBlank()) { "Cloudflare Tunnel requires a tunnel hostname" }
    require(config.serverName.isNotBlank()) { "Cloudflare Tunnel requires a TLS server name" }
    if (config.cloudflareTunnelMode == RelayCloudflareTunnelModePublishLocalOrigin &&
        !featureFlags.isEnabled(StrategyFeatureCloudflarePublish)
    ) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected("Cloudflare local-origin publish mode is feature-gated"),
        )
    }
    if (config.cloudflareTunnelMode == RelayCloudflareTunnelModePublishLocalOrigin) {
        require(config.cloudflarePublishLocalOriginUrl.isNotBlank()) {
            "Cloudflare publish mode requires a local origin URL"
        }
        parseCloudflareLocalOriginSpec(config.cloudflarePublishLocalOriginUrl)
        require(
            !credentials?.cloudflareTunnelToken.isNullOrBlank() ||
                !credentials?.cloudflareTunnelCredentialsJson.isNullOrBlank(),
        ) {
            "Cloudflare publish mode requires a tunnel token or named-tunnel credentials"
        }
    } else if (!featureFlags.isEnabled(StrategyFeatureCloudflareConsumeValidation)) {
        require(config.server.isNotBlank()) { "Cloudflare Tunnel requires a tunnel hostname" }
    }
}

internal fun validateDefaultRelayFeatures(
    config: RipDpiRelayConfig,
    tlsFingerprintProfile: String,
) {
    if (config.kind == RelayKindHysteria2 && tlsFingerprintProfile == TlsFingerprintProfileChromeStable) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayFingerprintPolicyRejected(
                "Hysteria2 is blocked until Chrome-like QUIC fingerprinting is implemented",
            ),
        )
    }
    if (config.kind == RelayKindGoogleAppsScript) {
        require(config.appsScriptScriptIds.isNotEmpty()) {
            "Google Apps Script relay requires at least one Apps Script deployment id"
        }
    }
}

internal fun validateMasqueRelayFeatures(
    profileId: String,
    config: RipDpiRelayConfig,
    masqueAuthMode: String?,
    privacyPassRuntime: MasquePrivacyPassRuntimeConfig?,
    privacyPassReadiness: MasquePrivacyPassReadiness?,
    featureFlags: Map<String, Boolean>,
) {
    if (masqueAuthMode == RelayMasqueAuthModeCloudflareMtls &&
        !featureFlags.isEnabled(StrategyFeatureMasqueCloudflareDirect)
    ) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected("Cloudflare-direct MASQUE is feature-gated"),
        )
    }
    validateMasqueRelayUrl(config.masqueUrl)
    if (masqueAuthMode == RelayMasqueAuthModePrivacyPass) {
        requireNotNull(privacyPassRuntime) {
            masquePrivacyPassReadinessMessage(profileId, privacyPassReadiness)
        }
    }
}

internal fun validateNaiveRelayFeatures(
    config: RipDpiRelayConfig,
    featureFlags: Map<String, Boolean>,
) {
    require(!config.udpEnabled) { "NaiveProxy does not support UDP mode" }
    if (!featureFlags.isEnabled(StrategyFeatureNaiveProxyWatchdog)) {
        require(config.naivePath.isBlank() || config.naivePath.startsWith("/")) {
            "NaiveProxy custom path must be absolute"
        }
    }
}

internal fun validatePluggableTransportLoopbackFeatures(config: RipDpiRelayConfig) {
    require(!config.udpEnabled) { "Pluggable transports are exposed as TCP SOCKS5 loopback relays" }
}

internal fun validateSnowflakeRelayFeatures(config: RipDpiRelayConfig) {
    require(config.ptSnowflakeBrokerUrl.isNotBlank()) { "Snowflake requires a broker URL" }
}

internal fun validateWebTunnelRelayFeatures(config: RipDpiRelayConfig) {
    require(config.ptWebTunnelUrl.isNotBlank()) { "WebTunnel requires a target URL" }
}

internal fun validateObfs4RelayFeatures(config: RipDpiRelayConfig) {
    require(config.ptBridgeLine.isNotBlank()) { "obfs4 requires a bridge line" }
}

internal fun validateShadowTlsRelayFeatures(
    profileId: String,
    config: RipDpiRelayConfig,
) {
    require(!config.udpEnabled) { "ShadowTLS v3 is TCP-only" }
    require(config.shadowTlsInnerProfileId.isNotBlank()) { "ShadowTLS v3 requires an inner profile reference" }
    require(config.vlessTransport == RelayVlessTransportRealityTcp) {
        "ShadowTLS outer profile must use direct TCP transport"
    }
    if (config.shadowTlsInnerProfileId == profileId) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected("ShadowTLS inner profile cannot reference itself"),
        )
    }
}

internal fun validateFinalmaskFeature(
    config: RipDpiRelayConfig,
    featureFlags: Map<String, Boolean>,
) {
    if (config.finalmask.type != RelayFinalmaskTypeOff && !featureFlags.isEnabled(StrategyFeatureFinalmask)) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected("Finalmask is feature-gated"),
        )
    }
}

internal suspend fun resolveChainRelayConfigSupport(
    chainProfileId: String,
    config: RipDpiRelayConfig,
    credentials: RelayCredentialRecord?,
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialStore,
): ResolvedChainRelayConfig =
    ResolvedChainRelayConfig(
        entry =
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
            ),
        exit =
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
            ),
    )

internal suspend fun resolveShadowTlsInnerConfigSupport(
    outerProfileId: String,
    innerProfileId: String,
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialStore,
): ResolvedShadowTlsInnerRelayConfig {
    val innerProfile =
        relayProfileStore.load(innerProfileId)
            ?: throw ServiceStartupRejectedException(
                FailureReason.RelayConfigRejected("ShadowTLS inner profile $innerProfileId was not found"),
            )
    if (innerProfile.id == outerProfileId) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected("ShadowTLS inner profile cannot reference itself"),
        )
    }
    val innerCredentials = relayCredentialStore.load(innerProfileId)
    return when (innerProfile.kind) {
        RelayKindVlessReality -> {
            require(innerProfile.vlessTransport != RelayVlessTransportXhttp) {
                "ShadowTLS currently supports only VLESS Reality TCP as an inner profile"
            }
            require(!innerCredentials?.vlessUuid.isNullOrBlank()) {
                "Relay credentials missing for profile $innerProfileId"
            }
            ResolvedShadowTlsInnerRelayConfig(
                kind = innerProfile.kind,
                profileId = innerProfile.id,
                server = innerProfile.server,
                serverPort = innerProfile.serverPort,
                serverName = innerProfile.serverName,
                realityPublicKey = innerProfile.realityPublicKey,
                realityShortId = innerProfile.realityShortId,
                vlessTransport = innerProfile.vlessTransport.ifBlank { RelayVlessTransportRealityTcp },
                vlessUuid = innerCredentials.vlessUuid,
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

internal fun validateMasqueRelayUrl(rawUrl: String) {
    val parsed =
        runCatching { URI(rawUrl.trim()) }.getOrElse {
            throw ServiceStartupRejectedException(
                FailureReason.RelayConfigRejected("MASQUE URL must be a valid HTTPS URL"),
            )
        }
    if (!parsed.scheme.equals("https", ignoreCase = true) || parsed.host.isNullOrBlank()) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected("MASQUE URL must be a valid HTTPS URL"),
        )
    }
}

internal fun masquePrivacyPassReadinessMessage(
    profileId: String,
    readiness: MasquePrivacyPassReadiness?,
): String =
    when (readiness) {
        MasquePrivacyPassReadiness.MissingProviderUrl -> {
            "MASQUE privacy_pass auth requires a configured provider URL for profile $profileId"
        }

        MasquePrivacyPassReadiness.InvalidProviderUrl -> {
            "MASQUE privacy_pass auth provider URL is invalid for profile $profileId"
        }

        MasquePrivacyPassReadiness.UnsupportedRelayKind -> {
            "MASQUE privacy_pass auth is only available for MASQUE relay profiles"
        }

        MasquePrivacyPassReadiness.UnsupportedAuthMode -> {
            "MASQUE privacy_pass auth is not selected for profile $profileId"
        }

        MasquePrivacyPassReadiness.Ready, null -> {
            "MASQUE privacy_pass auth requires a configured provider for profile $profileId"
        }
    }

private fun requireRelayCredentials(
    profileId: String,
    hasRequiredCredentials: Boolean,
) {
    require(hasRequiredCredentials) { "Relay credentials missing for profile $profileId" }
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
): ResolvedChainRelayHop {
    if (profileId.isNotBlank()) {
        val profile =
            relayProfileStore.load(profileId)
                ?: throw ServiceStartupRejectedException(
                    FailureReason.RelayConfigRejected("Chain relay $hopName profile $profileId was not found"),
                )
        if (profile.id == chainProfileId) {
            throw ServiceStartupRejectedException(
                FailureReason.RelayConfigRejected("Chain relay $hopName profile cannot reference itself"),
            )
        }
        if (profile.kind != RelayKindVlessReality) {
            throw ServiceStartupRejectedException(
                FailureReason.RelayConfigRejected(
                    "Chain relay $hopName profile kind ${profile.kind} is not supported yet",
                ),
            )
        }
        require(profile.vlessTransport != RelayVlessTransportXhttp) {
            "Chain relay $hopName profile must use direct Reality TCP transport"
        }
        val hopCredentials = relayCredentialStore.load(profileId)
        val hopUuid = hopCredentials?.vlessUuid
        require(!hopUuid.isNullOrBlank()) { "Relay credentials missing for profile $profileId" }
        return ResolvedChainRelayHop(
            profileId = profile.id,
            server = profile.server,
            serverPort = profile.serverPort,
            serverName = profile.serverName,
            publicKey = profile.realityPublicKey,
            shortId = profile.realityShortId,
            uuid = hopUuid,
        )
    }
    require(legacyServer.isNotBlank()) { "Chain relay $hopName profile reference is required" }
    require(legacyServerName.isNotBlank() && legacyPublicKey.isNotBlank() && legacyShortId.isNotBlank()) {
        "Chain relay legacy $hopName settings are incomplete"
    }
    require(!legacyUuid.isNullOrBlank()) { "Relay credentials missing for chain relay $hopName" }
    return ResolvedChainRelayHop(
        profileId = "",
        server = legacyServer,
        serverPort = legacyServerPort,
        serverName = legacyServerName,
        publicKey = legacyPublicKey,
        shortId = legacyShortId,
        uuid = legacyUuid,
    )
}
