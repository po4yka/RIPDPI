package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale

const val RememberedNetworkPolicyStatusObserved = "observed"
const val RememberedNetworkPolicyStatusValidated = "validated"
const val RememberedNetworkPolicyStatusSuppressed = "suppressed"
const val RememberedNetworkPolicyProofDurationMs = 60_000L
const val RememberedNetworkPolicyProofTransferBytes = 256L * 1024L
const val RememberedNetworkPolicySuppressionDurationMs = 24L * 60L * 60L * 1_000L
const val RememberedNetworkPolicyRetentionLimit = 64
const val RememberedNetworkPolicyRetentionMaxAgeMs = 90L * 24L * 60L * 60L * 1_000L
const val LegacyRememberedNetworkPolicySourceStrategyProbe = "strategy_probe"

@Serializable
enum class RememberedNetworkPolicySource(
    val storageValue: String,
) {
    MANUAL_SESSION("manual_session"),
    AUTOMATIC_PROBING_BACKGROUND("automatic_probing_background"),
    AUTOMATIC_PROBING_MANUAL("automatic_probing_manual"),
    AUTOMATIC_AUDIT_MANUAL("automatic_audit_manual"),
    STRATEGY_PROBE_MANUAL("strategy_probe_manual"),
    UNKNOWN("unknown"),
    ;

    fun encodeStorageValue(): String {
        require(this != UNKNOWN) { "UNKNOWN remembered policy source must not be persisted" }
        return storageValue
    }

    companion object {
        fun fromStorageValue(value: String?): RememberedNetworkPolicySource {
            val normalized = value?.trim()?.lowercase(Locale.US).orEmpty()
            return entries.firstOrNull { it.storageValue == normalized } ?: UNKNOWN
        }
    }
}

@Serializable
data class WifiNetworkIdentityTuple(
    val ssid: String = "unknown",
    val bssid: String = "unknown",
    val gateway: String = "unknown",
)

@Serializable
data class CellularNetworkIdentityTuple(
    val operatorCode: String = "unknown",
    val simOperatorCode: String = "unknown",
    val carrierId: Int? = null,
    val dataNetworkType: String = "unknown",
    val roaming: Boolean? = null,
)

@Serializable
data class NetworkFingerprint(
    val transport: String,
    val networkValidated: Boolean,
    val captivePortalDetected: Boolean,
    val privateDnsMode: String,
    val dnsServers: List<String>,
    val wifi: WifiNetworkIdentityTuple? = null,
    val cellular: CellularNetworkIdentityTuple? = null,
    val metered: Boolean = false,
) {
    fun scopeKey(): String =
        canonicalParts()
            .joinToString(separator = "|")
            .encodeSha256()

    fun summary(): NetworkFingerprintSummary =
        NetworkFingerprintSummary(
            transport = transport.normalizeFingerprintValue(),
            networkState =
                when {
                    captivePortalDetected -> "captive"
                    networkValidated -> "validated"
                    else -> "unvalidated"
                },
            identityKind =
                when {
                    wifi != null -> "wifi"
                    cellular != null -> "cellular"
                    else -> transport.normalizeFingerprintValue()
                },
            privateDnsMode =
                if (privateDnsMode.normalizeFingerprintValue() == "system") {
                    "system"
                } else {
                    "custom"
                },
            dnsServerCount = dnsServers.distinct().size,
        )

    fun canonicalParts(): List<String> {
        val normalizedTransport = transport.normalizeFingerprintValue()
        val normalizedDns =
            dnsServers
                .map(String::normalizeFingerprintValue)
                .filter(String::isNotBlank)
                .sorted()
        val identity =
            when {
                wifi != null -> {
                    listOf(
                        "wifi",
                        wifi.ssid.normalizeFingerprintValue(),
                        wifi.bssid.normalizeFingerprintValue(),
                        wifi.gateway.normalizeFingerprintValue(),
                    )
                }

                cellular != null -> {
                    listOf(
                        "cellular",
                        cellular.operatorCode.normalizeFingerprintValue(),
                        cellular.simOperatorCode.normalizeFingerprintValue(),
                        cellular.carrierId?.toString().orEmpty(),
                        cellular.dataNetworkType.normalizeFingerprintValue(),
                        cellular.roaming?.toString().orEmpty(),
                    )
                }

                else -> {
                    listOf(
                        "other",
                        normalizedTransport,
                        normalizedDns.joinToString(","),
                        privateDnsMode.normalizeFingerprintValue(),
                    )
                }
            }
        return buildList {
            add(normalizedTransport)
            add(networkValidated.toString())
            add(captivePortalDetected.toString())
            add(privateDnsMode.normalizeFingerprintValue())
            add(normalizedDns.joinToString(","))
            addAll(identity)
        }
    }
}

@Serializable
data class NetworkFingerprintSummary(
    val transport: String,
    val networkState: String,
    val identityKind: String,
    val privateDnsMode: String,
    val dnsServerCount: Int,
)

fun NetworkFingerprintSummary.displayLabel(): String =
    listOf(
        identityKind.replaceFirstChar { it.titlecase(Locale.US) },
        networkState.replaceFirstChar { it.titlecase(Locale.US) },
        "DNS ${dnsServerCount.coerceAtLeast(0)}",
        if (privateDnsMode == "custom") "Private DNS" else null,
    ).filterNotNull().joinToString(" · ")

@Serializable
data class VpnDnsPolicyJson(
    val mode: String,
    val providerId: String,
    val dnsIp: String,
    val encryptedDnsProtocol: String = "",
    val encryptedDnsHost: String = "",
    val encryptedDnsPort: Int = 0,
    val encryptedDnsTlsServerName: String = "",
    val encryptedDnsBootstrapIps: List<String> = emptyList(),
    val encryptedDnsDohUrl: String = "",
    val encryptedDnsDnscryptProviderName: String = "",
    val encryptedDnsDnscryptPublicKey: String = "",
)

fun ActiveDnsSettings.toVpnDnsPolicyJson(): VpnDnsPolicyJson =
    VpnDnsPolicyJson(
        mode = mode,
        providerId = providerId,
        dnsIp = dnsIp,
        encryptedDnsProtocol = encryptedDnsProtocol,
        encryptedDnsHost = encryptedDnsHost,
        encryptedDnsPort = encryptedDnsPort,
        encryptedDnsTlsServerName = encryptedDnsTlsServerName,
        encryptedDnsBootstrapIps = normalizeDnsBootstrapIps(encryptedDnsBootstrapIps),
        encryptedDnsDohUrl = encryptedDnsDohUrl,
        encryptedDnsDnscryptProviderName = encryptedDnsDnscryptProviderName,
        encryptedDnsDnscryptPublicKey = encryptedDnsDnscryptPublicKey,
    )

fun VpnDnsPolicyJson.toActiveDnsSettings(): ActiveDnsSettings =
    activeDnsSettings(
        dnsMode = mode,
        dnsProviderId = providerId,
        dnsIp = dnsIp,
        encryptedDnsProtocol = encryptedDnsProtocol,
        encryptedDnsHost = encryptedDnsHost,
        encryptedDnsPort = encryptedDnsPort,
        encryptedDnsTlsServerName = encryptedDnsTlsServerName,
        encryptedDnsBootstrapIps = normalizeDnsBootstrapIps(encryptedDnsBootstrapIps),
        encryptedDnsDohUrl = encryptedDnsDohUrl,
        encryptedDnsDnscryptProviderName = encryptedDnsDnscryptProviderName,
        encryptedDnsDnscryptPublicKey = encryptedDnsDnscryptPublicKey,
    )

@Serializable
data class RememberedNetworkPolicyJson(
    val fingerprintHash: String,
    val mode: String,
    val summary: NetworkFingerprintSummary,
    val proxyConfigJson: String,
    val vpnDnsPolicy: VpnDnsPolicyJson? = null,
    val strategySignatureJson: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val winningDnsStrategyFamily: String? = null,
)

private fun String.normalizeFingerprintValue(): String = trim().lowercase(Locale.US)

private fun String.encodeSha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            append(((byte.toInt() shr 4) and 0xF).toString(16))
            append((byte.toInt() and 0xF).toString(16))
        }
    }
}
