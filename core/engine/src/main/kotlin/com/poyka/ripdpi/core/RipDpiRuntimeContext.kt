package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import kotlinx.serialization.Serializable

@Serializable
data class RipDpiEncryptedDnsContext(
    val resolverId: String? = null,
    val protocol: String,
    val host: String,
    val port: Int,
    val tlsServerName: String? = null,
    val bootstrapIps: List<String> = emptyList(),
    val dohUrl: String? = null,
    val dnscryptProviderName: String? = null,
    val dnscryptPublicKey: String? = null,
)

@Serializable
data class RipDpiLogContext(
    val runtimeId: String? = null,
    val mode: String? = null,
    val policySignature: String? = null,
    val fingerprintHash: String? = null,
    val diagnosticsSessionId: String? = null,
)

@Serializable
data class RipDpiRuntimeContext(
    val encryptedDns: RipDpiEncryptedDnsContext? = null,
    val protectPath: String? = null,
)

internal fun normalizeLogContext(logContext: RipDpiLogContext?): RipDpiLogContext? =
    logContext?.let { value ->
        val runtimeId = value.runtimeId?.trim()?.takeIf { it.isNotEmpty() }
        val mode =
            value.mode
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
        val policySignature = value.policySignature?.trim()?.takeIf { it.isNotEmpty() }
        val fingerprintHash = value.fingerprintHash?.trim()?.takeIf { it.isNotEmpty() }
        val diagnosticsSessionId = value.diagnosticsSessionId?.trim()?.takeIf { it.isNotEmpty() }
        if (
            runtimeId == null &&
            mode == null &&
            policySignature == null &&
            fingerprintHash == null &&
            diagnosticsSessionId == null
        ) {
            null
        } else {
            RipDpiLogContext(
                runtimeId = runtimeId,
                mode = mode,
                policySignature = policySignature,
                fingerprintHash = fingerprintHash,
                diagnosticsSessionId = diagnosticsSessionId,
            )
        }
    }

internal fun normalizeRuntimeContext(runtimeContext: RipDpiRuntimeContext?): RipDpiRuntimeContext? {
    runtimeContext ?: return null
    val encryptedDns =
        runtimeContext.encryptedDns?.let { value ->
            val normalizedHost = value.host.trim()
            if (normalizedHost.isEmpty()) {
                return@let null
            }
            RipDpiEncryptedDnsContext(
                resolverId = value.resolverId?.trim()?.takeIf { it.isNotEmpty() },
                protocol = value.protocol.trim().lowercase(),
                host = normalizedHost,
                port = value.port.takeIf { it > 0 } ?: 443,
                tlsServerName = value.tlsServerName?.trim()?.takeIf { it.isNotEmpty() },
                bootstrapIps = value.bootstrapIps.map(String::trim).filter { it.isNotEmpty() },
                dohUrl = value.dohUrl?.trim()?.takeIf { it.isNotEmpty() },
                dnscryptProviderName = value.dnscryptProviderName?.trim()?.takeIf { it.isNotEmpty() },
                dnscryptPublicKey = value.dnscryptPublicKey?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    val protectPath = runtimeContext.protectPath?.trim()?.takeIf { it.isNotEmpty() }
    if (encryptedDns == null && protectPath == null) return null
    return RipDpiRuntimeContext(encryptedDns = encryptedDns, protectPath = protectPath)
}

fun ActiveDnsSettings.toRipDpiRuntimeContext(): RipDpiRuntimeContext? {
    val normalizedHost = encryptedDnsHost.trim()
    if (mode != DnsModeEncrypted || normalizedHost.isEmpty()) {
        return null
    }
    val normalizedProtocol =
        when (encryptedDnsProtocol.trim().lowercase()) {
            EncryptedDnsProtocolDnsCrypt -> EncryptedDnsProtocolDnsCrypt
            EncryptedDnsProtocolDoh -> EncryptedDnsProtocolDoh
            else -> encryptedDnsProtocol.trim().lowercase()
        }
    return normalizeRuntimeContext(
        RipDpiRuntimeContext(
            encryptedDns =
                RipDpiEncryptedDnsContext(
                    resolverId = providerId.takeIf { it.isNotBlank() },
                    protocol = normalizedProtocol,
                    host = normalizedHost,
                    port = encryptedDnsPort.takeIf { it > 0 } ?: 443,
                    tlsServerName = encryptedDnsTlsServerName.takeIf { it.isNotBlank() },
                    bootstrapIps = encryptedDnsBootstrapIps,
                    dohUrl = encryptedDnsDohUrl.takeIf { it.isNotBlank() },
                    dnscryptProviderName = encryptedDnsDnscryptProviderName.takeIf { it.isNotBlank() },
                    dnscryptPublicKey = encryptedDnsDnscryptPublicKey.takeIf { it.isNotBlank() },
                ),
        ),
    )
}
