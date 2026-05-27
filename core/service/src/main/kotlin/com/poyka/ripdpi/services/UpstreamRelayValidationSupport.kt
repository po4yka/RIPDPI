package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayKindGoogleAppsScript
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.StrategyFeatureCloudflareConsumeValidation
import com.poyka.ripdpi.data.StrategyFeatureCloudflarePublish
import com.poyka.ripdpi.data.StrategyFeatureFinalmask
import com.poyka.ripdpi.data.StrategyFeatureMasqueCloudflareDirect
import com.poyka.ripdpi.data.StrategyFeatureNaiveProxyWatchdog
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import java.net.URI

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
            RelayMasqueAuthModeBearer,
            RelayMasqueAuthModePreshared,
            -> {
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

internal fun validateTrojanRelayCredentials(
    profileId: String,
    credentials: RelayCredentialRecord?,
) {
    requireRelayCredentials(profileId, !credentials?.trojanPassword.isNullOrBlank())
}

internal fun validateSharedRelayTransportFeatures(config: RipDpiRelayConfig) {
    // UDP ASSOCIATE support is a generic, relay_kind-keyed capability: the
    // RelayKindDescriptor table is the source of truth, mirroring the Rust
    // RelayTransportDescriptor `udp` flag. An unrecognised kind has no
    // descriptor row and is treated as UDP-incapable, as before.
    require(!config.udpEnabled || relayKindDescriptor(config.kind)?.udp == true) {
        "Relay UDP mode is only available for Hysteria2, MASQUE, Trojan, and TUIC profiles"
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

internal fun validateTrojanRelayFeatures(config: RipDpiRelayConfig) {
    require(config.server.isNotBlank()) { "Trojan requires a server hostname" }
    require(config.serverName.isNotBlank()) { "Trojan requires a TLS server name" }
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
