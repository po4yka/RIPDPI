package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.stripRipDpiRuntimeContext
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ConnectionPolicySignatureBuilder
    @Inject
    constructor() {
        fun build(
            mode: Mode,
            proxyPreferences: RipDpiProxyPreferences,
            activeDns: ActiveDnsSettings,
            resolverFallbackReason: String?,
            matchedPolicy: RememberedNetworkPolicyEntity?,
        ): String =
            buildConnectionPolicySignature(
                mode = mode,
                proxyPreferences = proxyPreferences,
                activeDns = activeDns,
                resolverFallbackReason = resolverFallbackReason,
                matchedPolicy = matchedPolicy,
            )
    }

internal fun buildConnectionPolicySignature(
    mode: Mode,
    proxyPreferences: RipDpiProxyPreferences,
    activeDns: ActiveDnsSettings,
    resolverFallbackReason: String?,
    matchedPolicy: RememberedNetworkPolicyEntity?,
): String =
    listOf(
        mode.preferenceValue,
        stripRipDpiRuntimeContext(proxyPreferences.toNativeConfigJson()),
        activeDns.mode,
        activeDns.providerId,
        activeDns.dnsIp,
        activeDns.encryptedDnsProtocol,
        activeDns.encryptedDnsHost,
        activeDns.encryptedDnsPort.toString(),
        activeDns.encryptedDnsTlsServerName,
        activeDns.encryptedDnsBootstrapIps.joinToString(","),
        activeDns.encryptedDnsDohUrl,
        activeDns.encryptedDnsDnscryptProviderName,
        activeDns.encryptedDnsDnscryptPublicKey,
        resolverFallbackReason.orEmpty(),
        matchedPolicy?.id?.toString().orEmpty(),
    ).joinToString("|").encodeSha256()

private const val HexRadix = 16
private const val HexNibbleShift = 4

private fun String.encodeSha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            append(((byte.toInt() shr HexNibbleShift) and 0xF).toString(HexRadix))
            append((byte.toInt() and 0xF).toString(HexRadix))
        }
    }
}
