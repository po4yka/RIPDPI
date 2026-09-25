package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindGoogleAppsScript
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayMieruMtuMax
import com.poyka.ripdpi.data.RelayMieruMtuMin
import com.poyka.ripdpi.data.RelayMieruMultiplexingHigh
import com.poyka.ripdpi.data.RelayMieruMultiplexingLow
import com.poyka.ripdpi.data.RelayMieruMultiplexingMiddle
import com.poyka.ripdpi.data.RelayMieruMultiplexingOff
import com.poyka.ripdpi.data.RelayMieruProtocolTcp
import com.poyka.ripdpi.data.RelayMieruProtocolUdp
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.isSupportedChainEntryHop
import com.poyka.ripdpi.data.isSupportedChainExitHop
import com.poyka.ripdpi.data.isValidRelayXhttpMode
import com.poyka.ripdpi.data.isValidVlessUuid
import com.poyka.ripdpi.data.normalizeRelayCloudflareTunnelMode
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.data.validateVlessRealityProfileFields
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import java.util.Base64

private const val Shadowsocks2022Aes128KeyBytes = 16
private const val Shadowsocks2022Aes256KeyBytes = 32

internal fun validateConfigDraft(
    draft: ConfigDraft,
    supportsMasquePrivacyPass: Boolean = false,
    relayProfiles: List<RelayProfileRecord> = emptyList(),
): ImmutableMap<String, String> =
    buildMap {
        putAll(validateBaseDraft(draft))
        val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
        if (relayProfiles.any { profile ->
                profile.id == profileId &&
                    (draft.editingRelayProfileId != profileId || draft.editingRelayProfileAtOpen != profile)
            }
        ) {
            put(ConfigFieldRelayProfileId, "taken")
        }
        if (draft.relayEnabled && !draft.useCommandLineSettings) {
            putAll(validateRelayDraft(draft, supportsMasquePrivacyPass, relayProfiles))
        }
        validateStrategyDraft(draft)?.let { put(ConfigFieldStrategyChain, it) }
    }.toImmutableMap()

private fun validateBaseDraft(draft: ConfigDraft): Map<String, String> =
    buildMap {
        if (!checkIp(draft.proxyIp)) {
            put(ConfigFieldProxyIp, "invalid_proxy_ip")
        }
        if (!validatePort(draft.proxyPort)) {
            put(ConfigFieldProxyPort, "invalid_port")
        }
        if (!validateIntRange(draft.maxConnections, 1, Short.MAX_VALUE.toInt())) {
            put(ConfigFieldMaxConnections, "out_of_range")
        }
        if (!validateIntRange(draft.bufferSize, 1, Int.MAX_VALUE / bufferSizeDiv)) {
            put(ConfigFieldBufferSize, "out_of_range")
        }
        if (draft.defaultTtl.isNotEmpty() && !validateIntRange(draft.defaultTtl, 0, defaultTtlMax)) {
            put(ConfigFieldDefaultTtl, "out_of_range")
        }
    }

private fun validateRelayDraft(
    draft: ConfigDraft,
    supportsMasquePrivacyPass: Boolean,
    relayProfiles: List<RelayProfileRecord>,
): Map<String, String> =
    buildMap {
        if (!validatePort(draft.relayLocalSocksPort)) {
            put(ConfigFieldRelayLocalSocksPort, "invalid_port")
        }
        putAll(validateRelayKindDraft(draft, supportsMasquePrivacyPass, relayProfiles))
        validateRelayFinalmaskDraft(draft)?.let { put(ConfigFieldRelayFinalmask, it) }
        if (draft.relayUdpEnabled && !draft.supportsUdpRelay()) {
            put(ConfigFieldRelayCredentials, "unsupported")
        }
    }

private fun validateRelayKindDraft(
    draft: ConfigDraft,
    supportsMasquePrivacyPass: Boolean,
    relayProfiles: List<RelayProfileRecord>,
): Map<String, String> =
    when (draft.relayKind) {
        RelayKindVlessReality -> {
            validateVlessRealityDraft(draft)
        }

        RelayKindVless, RelayKindTrojan, RelayKindShadowsocks,
        RelayKindGoogleAppsScript, RelayKindMieru, RelayKindSsh,
        -> {
            validateImportedKindDraft(draft)
        }

        RelayKindCloudflareTunnel -> {
            validateCloudflareTunnelDraft(draft)
        }

        RelayKindHysteria2 -> {
            validateEndpointRelayDraft(
                draft = draft,
                credentialsMissing = draft.relayServerName.isBlank() || draft.relayHysteriaPassword.isBlank(),
            )
        }

        RelayKindTuicV5 -> {
            validateEndpointRelayDraft(
                draft = draft,
                credentialsMissing =
                    draft.relayServerName.isBlank() ||
                        draft.relayTuicUuid.isBlank() ||
                        draft.relayTuicPassword.isBlank(),
            )
        }

        RelayKindAnyTls -> {
            validateEndpointRelayDraft(
                draft = draft,
                credentialsMissing = draft.relayAnyTlsPassword.isBlank(),
            )
        }

        RelayKindShadowTlsV3 -> {
            validateShadowTlsDraft(draft)
        }

        RelayKindNaiveProxy -> {
            validateNaiveProxyDraft(draft)
        }

        RelayKindSnowflake -> {
            validateTcpOnlyCredentialDraft(draft.relaySnowflakeBrokerUrl.isBlank(), draft.relayUdpEnabled)
        }

        RelayKindWebTunnel -> {
            validateTcpOnlyCredentialDraft(draft.relayWebTunnelUrl.isBlank(), draft.relayUdpEnabled)
        }

        RelayKindObfs4 -> {
            validateTcpOnlyCredentialDraft(draft.relayPtBridgeLine.isBlank(), draft.relayUdpEnabled)
        }

        RelayKindChainRelay -> {
            validateChainRelayDraft(draft, relayProfiles)
        }

        RelayKindMasque -> {
            validateMasqueDraft(draft, supportsMasquePrivacyPass)
        }

        else -> {
            emptyMap()
        }
    }

private fun validateImportedKindDraft(draft: ConfigDraft): Map<String, String> =
    when (draft.relayKind) {
        RelayKindVless -> {
            validatePlainVlessDraft(draft)
        }

        RelayKindTrojan -> {
            validateEndpointRelayDraft(
                draft,
                draft.relayServerName.isBlank() || draft.relayTrojanPassword.isBlank(),
            )
        }

        RelayKindShadowsocks -> {
            validateShadowsocksDraft(draft)
        }

        RelayKindGoogleAppsScript -> {
            validateAppsScriptDraft(draft)
        }

        RelayKindMieru -> {
            validateMieruDraft(draft)
        }

        RelayKindSsh -> {
            validateSshDraft(draft)
        }

        else -> {
            emptyMap()
        }
    }

private fun validatePlainVlessDraft(draft: ConfigDraft): Map<String, String> =
    buildMap {
        putAll(
            validateEndpointRelayDraft(
                draft,
                draft.relayServerName.isBlank() || !isValidVlessUuid(draft.relayVlessUuid),
            ),
        )
        if (draft.relayVlessTransport != RelayVlessTransportXhttp || draft.relayUdpEnabled) {
            put(ConfigFieldRelayCredentials, "unsupported")
        } else if (!isValidRelayXhttpMode(draft.relayXhttpMode)) {
            put(ConfigFieldRelayCredentials, "invalid")
        }
    }

private fun validateShadowsocksDraft(draft: ConfigDraft): Map<String, String> =
    buildMap {
        putAll(
            validateEndpointRelayDraft(
                draft,
                draft.relayShadowsocksMethod.isBlank() || draft.relayShadowsocksPassword.isBlank(),
            ),
        )
        if (draft.relayShadowsocksMethod.isNotBlank() &&
            draft.relayShadowsocksMethod.trim().lowercase() !in supportedShadowsocksMethods
        ) {
            put(ConfigFieldRelayCredentials, "unsupported")
        } else if (!hasValidShadowsocks2022Key(draft.relayShadowsocksMethod, draft.relayShadowsocksPassword)) {
            put(ConfigFieldRelayCredentials, "invalid")
        }
    }

private fun hasValidShadowsocks2022Key(
    method: String,
    password: String,
): Boolean {
    val keyLength =
        when (method.trim().lowercase()) {
            "2022-blake3-aes-128-gcm" -> Shadowsocks2022Aes128KeyBytes
            "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305" -> Shadowsocks2022Aes256KeyBytes
            else -> return true
        }
    val encoded = password.trim()
    return runCatching {
        val key = Base64.getDecoder().decode(encoded)
        key.size == keyLength && Base64.getEncoder().encodeToString(key) == encoded
    }.getOrDefault(false)
}

private fun validateAppsScriptDraft(draft: ConfigDraft): Map<String, String> =
    buildMap {
        if (draft.mode != Mode.Proxy) {
            put(ConfigFieldRelayCredentials, "unsupported")
        } else if (draft.relayAppsScriptScriptIds.relayLines().isEmpty() || draft.relayAppsScriptAuthKey.isBlank()) {
            put(ConfigFieldRelayCredentials, "required")
        } else if (draft.relayAppsScriptGoogleIp.isNotBlank() && !checkIp(draft.relayAppsScriptGoogleIp)) {
            put(ConfigFieldRelayCredentials, "invalid")
        } else if (!draft.relayAppsScriptVerifySsl) {
            put(ConfigFieldRelayCredentials, "unsupported")
        }
    }

private fun validateMieruDraft(draft: ConfigDraft): Map<String, String> =
    buildMap {
        putAll(
            validateEndpointRelayDraft(
                draft,
                draft.relayMieruUsername.isBlank() || draft.relayMieruPassword.isBlank(),
            ),
        )
        val protocolInvalid = draft.relayMieruProtocol !in setOf(RelayMieruProtocolTcp, RelayMieruProtocolUdp)
        val multiplexingInvalid =
            draft.relayMieruMultiplexing !in
                setOf(
                    RelayMieruMultiplexingOff,
                    RelayMieruMultiplexingLow,
                    RelayMieruMultiplexingMiddle,
                    RelayMieruMultiplexingHigh,
                )
        val mtuInvalid = draft.relayMieruMtu.toIntOrNull() !in RelayMieruMtuMin..RelayMieruMtuMax
        if (protocolInvalid || multiplexingInvalid || mtuInvalid) {
            put(ConfigFieldRelayCredentials, "invalid")
        }
    }

private fun validateSshDraft(draft: ConfigDraft): Map<String, String> =
    buildMap {
        putAll(validateEndpointRelayDraft(draft, draft.relaySshUsername.isBlank()))
        val authTypeInvalid = draft.relaySshAuthType !in setOf(RelaySshAuthTypePassword, RelaySshAuthTypePrivateKey)
        val credentialMissing =
            when (draft.relaySshAuthType) {
                RelaySshAuthTypePassword -> draft.relaySshPassword.isBlank()
                RelaySshAuthTypePrivateKey -> draft.relaySshPrivateKey.isBlank()
                else -> false
            }
        if (authTypeInvalid || credentialMissing) {
            put(ConfigFieldRelayCredentials, "required")
        }
    }

private val supportedShadowsocksMethods =
    setOf(
        "aes-128-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
    )

private fun validateVlessRealityDraft(draft: ConfigDraft): Map<String, String> =
    buildMap {
        if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
        if (!validatePort(draft.relayServerPort)) put(ConfigFieldRelayServerPort, "invalid_port")
        vlessRealityCredentialError(draft)?.let { put(ConfigFieldRelayCredentials, it) }
        if (draft.relayVlessTransport == RelayVlessTransportXhttp && draft.relayUdpEnabled) {
            put(ConfigFieldRelayCredentials, "unsupported")
        }
    }

private fun validateCloudflareTunnelDraft(draft: ConfigDraft): Map<String, String> =
    buildMap {
        if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
        when (normalizeRelayCloudflareTunnelMode(draft.relayCloudflareTunnelMode)) {
            RelayCloudflareTunnelModeConsumeExisting -> {
                if (draft.relayVlessUuid.isBlank()) {
                    put(ConfigFieldRelayCredentials, "required")
                }
            }

            RelayCloudflareTunnelModePublishLocalOrigin -> {
                if (draft.relayCloudflarePublishLocalOriginUrl.isBlank()) {
                    put(ConfigFieldRelayCloudflarePublishOrigin, "required")
                }
                if (
                    draft.relayCloudflareTunnelToken.isBlank() &&
                    draft.relayCloudflareTunnelCredentialsJson.isBlank()
                ) {
                    put(ConfigFieldRelayCredentials, "required")
                }
            }
        }
        if (draft.relayUdpEnabled) {
            put(ConfigFieldRelayCredentials, "unsupported")
        }
    }

private fun validateEndpointRelayDraft(
    draft: ConfigDraft,
    credentialsMissing: Boolean,
): Map<String, String> =
    buildMap {
        if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
        if (!validatePort(draft.relayServerPort)) put(ConfigFieldRelayServerPort, "invalid_port")
        if (credentialsMissing) {
            put(ConfigFieldRelayCredentials, "required")
        }
    }

private fun validateShadowTlsDraft(draft: ConfigDraft): Map<String, String> =
    validateTcpOnlyCredentialDraft(
        credentialsMissing = draft.relayShadowTlsInnerProfileId.isBlank() || draft.relayShadowTlsPassword.isBlank(),
        udpEnabled = draft.relayUdpEnabled,
    )

private fun validateNaiveProxyDraft(draft: ConfigDraft): Map<String, String> =
    buildMap {
        putAll(
            validateEndpointRelayDraft(
                draft = draft,
                credentialsMissing =
                    draft.relayServerName.isBlank() ||
                        draft.relayNaiveUsername.isBlank() ||
                        draft.relayNaivePassword.isBlank(),
            ),
        )
        if (draft.relayNaivePath.isNotBlank() && !draft.relayNaivePath.startsWith("/")) {
            put(ConfigFieldRelayNaivePath, "absolute_path")
        }
        if (draft.relayUdpEnabled) {
            put(ConfigFieldRelayCredentials, "unsupported")
        }
    }

private fun validateTcpOnlyCredentialDraft(
    credentialsMissing: Boolean,
    udpEnabled: Boolean,
): Map<String, String> =
    buildMap {
        if (credentialsMissing) {
            put(ConfigFieldRelayCredentials, "required")
        }
        if (udpEnabled) {
            put(ConfigFieldRelayCredentials, "unsupported")
        }
    }

private fun validateChainRelayDraft(
    draft: ConfigDraft,
    relayProfiles: List<RelayProfileRecord>,
): Map<String, String> =
    buildMap {
        val hasEntryReference =
            draft.relayChainEntryProfileId.isNotBlank() || draft.relayChainEntryServer.isNotBlank()
        val hasExitReference =
            draft.relayChainExitProfileId.isNotBlank() || draft.relayChainExitServer.isNotBlank()
        if (!hasEntryReference || !hasExitReference) {
            put(ConfigFieldRelayServer, "required")
        }
        if (draft.isChainRelayIncomplete()) {
            put(ConfigFieldRelayCredentials, "required")
        }
        validateChainRelayProfileSelection(draft, relayProfiles)?.let {
            put(ConfigFieldRelayChain, it)
        }
    }

private fun ConfigDraft.isChainRelayIncomplete(): Boolean =
    (
        relayChainEntryProfileId.isBlank() &&
            (
                relayChainEntryServerName.isBlank() ||
                    relayChainEntryPublicKey.isBlank() ||
                    relayChainEntryShortId.isBlank() ||
                    relayChainEntryUuid.isBlank()
            )
    ) ||
        (
            relayChainExitProfileId.isBlank() &&
                (
                    relayChainExitServerName.isBlank() ||
                        relayChainExitPublicKey.isBlank() ||
                        relayChainExitShortId.isBlank() ||
                        relayChainExitUuid.isBlank()
                )
        )

private fun validateMasqueDraft(
    draft: ConfigDraft,
    supportsMasquePrivacyPass: Boolean,
): Map<String, String> =
    buildMap {
        if (draft.relayMasqueUrl.isBlank()) {
            put(ConfigFieldRelayCredentials, "required")
        }
        masqueAuthError(draft, supportsMasquePrivacyPass)?.let {
            put(ConfigFieldRelayCredentials, it)
        }
    }

private fun masqueAuthError(
    draft: ConfigDraft,
    supportsMasquePrivacyPass: Boolean,
): String? =
    when (normalizeRelayMasqueAuthMode(draft.relayMasqueAuthMode)) {
        RelayMasqueAuthModeBearer,
        RelayMasqueAuthModePreshared,
        -> {
            if (draft.relayMasqueAuthToken.isBlank()) "required" else null
        }

        RelayMasqueAuthModePrivacyPass -> {
            if (supportsMasquePrivacyPass) null else "unsupported"
        }

        RelayMasqueAuthModeCloudflareMtls -> {
            if (draft.relayMasqueClientCertificateChainPem.isBlank() ||
                draft.relayMasqueClientPrivateKeyPem.isBlank()
            ) {
                "required"
            } else {
                null
            }
        }

        else -> {
            "required"
        }
    }

private fun validateStrategyDraft(draft: ConfigDraft): String? =
    if (draft.useCommandLineSettings) {
        null
    } else {
        val chainValidation =
            parseStrategyChainDsl(draft.chainDsl).map { chain ->
                validateStrategyChainUsage(
                    tcpSteps = chain.tcpSteps,
                    udpSteps = chain.udpSteps,
                    mode = draft.mode,
                    useCommandLineSettings = draft.useCommandLineSettings,
                )
            }
        if (chainValidation.isFailure) "invalid_chain" else null
    }

private fun vlessRealityCredentialError(draft: ConfigDraft): String? {
    val isIncomplete =
        draft.relayServerName.isBlank() ||
            draft.relayRealityPublicKey.isBlank() ||
            draft.relayVlessUuid.isBlank()
    return when {
        isIncomplete -> "required"
        draft.relayServer.isBlank() || !validatePort(draft.relayServerPort) -> null
        else -> validateCompleteVlessRealityCredentials(draft)
    }
}

private fun validateCompleteVlessRealityCredentials(draft: ConfigDraft): String? =
    runCatching {
        validateVlessRealityProfileFields(
            server = draft.relayServer,
            serverPort = draft.relayServerPort.toInt(),
            uuid = draft.relayVlessUuid,
            serverName = draft.relayServerName,
            realityPublicKey = draft.relayRealityPublicKey,
            realityShortId = draft.relayRealityShortId,
        )
    }.fold(
        onSuccess = { null },
        onFailure = { "invalid" },
    )

private fun validateChainRelayProfileSelection(
    draft: ConfigDraft,
    relayProfiles: List<RelayProfileRecord>,
): String? =
    when {
        draft.relayChainEntryProfileId.isBlank() || draft.relayChainExitProfileId.isBlank() -> {
            "required"
        }

        draft.relayChainEntryProfileId == draft.relayChainExitProfileId -> {
            "same_hop"
        }

        relayProfiles.isEmpty() -> {
            "required"
        }

        else -> {
            validateResolvedChainProfiles(
                entryId = draft.relayChainEntryProfileId,
                exitId = draft.relayChainExitProfileId,
                relayProfiles = relayProfiles,
            )
        }
    }

private fun validateResolvedChainProfiles(
    entryId: String,
    exitId: String,
    relayProfiles: List<RelayProfileRecord>,
): String? {
    val byId = relayProfiles.associateBy { it.id }
    val entry = byId[entryId]
    val exit = byId[exitId]
    return when {
        entry == null || exit == null -> "required"
        !entry.isSupportedChainEntryHop() && !entry.isSupportedChainExitHop() -> "unsupported"
        !exit.isSupportedChainEntryHop() && !exit.isSupportedChainExitHop() -> "unsupported"
        !entry.isSupportedChainEntryHop() -> "unsupported_entry"
        !exit.isSupportedChainExitHop() -> "unsupported_exit"
        else -> null
    }
}
