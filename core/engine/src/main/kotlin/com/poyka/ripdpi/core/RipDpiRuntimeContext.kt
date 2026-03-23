package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh

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

data class RipDpiRuntimeContext(
    val encryptedDns: RipDpiEncryptedDnsContext? = null,
)

internal fun normalizeRuntimeContext(runtimeContext: RipDpiRuntimeContext?): RipDpiRuntimeContext? {
    val encryptedDns =
        runtimeContext
            ?.encryptedDns
            ?.let { value ->
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
            ?: return null
    return RipDpiRuntimeContext(encryptedDns = encryptedDns)
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
