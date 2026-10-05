package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.deriveStrategyLaneFamilies
import com.poyka.ripdpi.proto.AppSettings

internal fun ActiveDnsSettings.runtimeDnsSummary(): RuntimeConfigurationDns =
    RuntimeConfigurationDns(mode, providerId, encryptedDnsProtocol.takeIf { isEncrypted })

internal fun AppSettings.strategySummary(): RuntimeConfigurationStrategy {
    val family = deriveStrategyLaneFamilies().tcpStrategyFamily
    return RuntimeConfigurationStrategy(
        enabled = family != null || strategyChainYaml.isNotBlank() || enableCmdSettings,
        packId = strategyPackPinnedId.takeIf(String::isNotBlank),
        family = family,
        custom = enableCmdSettings || strategyChainYaml.isNotBlank(),
    )
}

internal fun vpnConfigurationMaterial(
    mode: Mode,
    settings: AppSettings,
): List<String> =
    if (mode != Mode.VPN) {
        emptyList()
    } else {
        with(settings) {
            listOf(
                ipv6Enable.toString(),
                fullTunnelMode.toString(),
                webrtcProtectionEnabled.toString(),
                dhtMitigationMode,
                strategyChainYaml,
                splitTunnelMode,
                splitTunnelPackagesList.sorted().joinToString("\n"),
                appRoutingPolicyMode,
                appRoutingEnabledPresetIdsList.sorted().joinToString("\n"),
                excludeRussianAppsEnabled.toString(),
            )
        }
    }

internal fun requestedWarpCredentialMaterial(credentials: com.poyka.ripdpi.data.WarpCredentials): List<String> =
    with(credentials) {
        listOf(
            profileId,
            accountKind,
            deviceId,
            accessToken,
            clientId.orEmpty(),
            privateKey.orEmpty(),
            publicKey.orEmpty(),
            peerPublicKey.orEmpty(),
            interfaceAddressV4.orEmpty(),
            interfaceAddressV6.orEmpty(),
        )
    }
