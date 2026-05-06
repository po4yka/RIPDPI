package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedShadowTlsInnerRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode

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
    val innerProfile = loadShadowTlsInnerProfile(innerProfileId, relayProfileStore)
    rejectSelfReferentialShadowTlsProfile(innerProfile.id, outerProfileId)
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
        val profile = loadChainRelayHopProfile(hopName, profileId, relayProfileStore)
        rejectSelfReferentialChainRelayHop(hopName, profile.id, chainProfileId)
        rejectUnsupportedChainRelayHopKind(hopName, profile.kind)
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
    kind: String,
) {
    if (kind != RelayKindVlessReality) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected(
                "Chain relay $hopName profile kind $kind is not supported yet",
            ),
        )
    }
}
