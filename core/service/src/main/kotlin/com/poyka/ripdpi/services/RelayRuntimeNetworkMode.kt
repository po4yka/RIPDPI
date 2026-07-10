package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.ServiceStartupRejectedException

/** Service-owned network context applied after persisted relay profile resolution. */
internal enum class RelayRuntimeNetworkMode {
    Proxy,
    Vpn,
}

internal fun ResolvedRipDpiRelayConfig.withNetworkMode(mode: RelayRuntimeNetworkMode): ResolvedRipDpiRelayConfig =
    copy(
        socketProtection =
            when (mode) {
                RelayRuntimeNetworkMode.Proxy -> RelaySocketProtection.Inactive
                RelayRuntimeNetworkMode.Vpn -> RelaySocketProtection.VpnRequired
            },
    ).also(::validateRelaySocketProtectionPolicy)

/** Reject VPN-mode paths that cannot protect every non-loopback carrier before any runtime is created. */
internal fun validateRelaySocketProtectionPolicy(config: ResolvedRipDpiRelayConfig) {
    if (config.socketProtection != RelaySocketProtection.VpnRequired) {
        return
    }
    val unsupportedComponent =
        when {
            config.kind == RelayKindGoogleAppsScript -> {
                "Apps Script HTTP client"
            }

            config.kind == RelayKindTor -> {
                "Arti and its managed transport"
            }

            config.kind == RelayKindMasque && config.masqueAuthMode == RelayMasqueAuthModePrivacyPass -> {
                "MASQUE Privacy Pass provider client"
            }

            config.kind == RelayKindSnowflake -> {
                "Snowflake helper"
            }

            config.kind == RelayKindObfs4 -> {
                "obfs4 helper"
            }

            config.kind == RelayKindCloudflareTunnel &&
                config.cloudflareTunnelMode == RelayCloudflareTunnelModePublishLocalOrigin -> {
                "cloudflared helper"
            }

            else -> {
                null
            }
        }
    if (unsupportedComponent != null) {
        throw ServiceStartupRejectedException(
            FailureReason.RelayConfigRejected(
                "${config.kind} cannot run in VPN mode because the $unsupportedComponent does not support Android socket protection",
            ),
        )
    }
}
