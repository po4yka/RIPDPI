package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.DnsMode
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.PreferredStack
import com.poyka.ripdpi.data.QuicMode
import com.poyka.ripdpi.data.StrategyPackMorphPolicy
import com.poyka.ripdpi.data.TcpFamily
import com.poyka.ripdpi.data.normalizePreferredEdgeCandidates
import kotlinx.serialization.Serializable
import java.util.Locale

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
data class RipDpiDirectPathCapability(
    val authority: String,
    val quicUsable: Boolean? = null,
    val udpUsable: Boolean? = null,
    val fallbackRequired: Boolean? = null,
    val repeatedHandshakeFailureClass: String? = null,
    val transportPolicyVersion: Int = 0,
    val ipSetDigest: String = "",
    val quicMode: QuicMode = QuicMode.ALLOW,
    val preferredStack: PreferredStack = PreferredStack.H3,
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val tcpFamily: TcpFamily = TcpFamily.NONE,
    val outcome: DirectModeOutcome = DirectModeOutcome.TRANSPARENT_OK,
    val transportClass: DirectTransportClass? = null,
    val reasonCode: DirectModeReasonCode? = null,
    val cooldownUntil: Long? = null,
    val updatedAt: Long = 0L,
)

@Serializable
data class RipDpiMorphPolicy(
    val id: String,
    val firstFlightSizeMin: Int = 0,
    val firstFlightSizeMax: Int = 0,
    val paddingEnvelopeMin: Int = 0,
    val paddingEnvelopeMax: Int = 0,
    val entropyTargetPermil: Int = 0,
    val tcpBurstCadenceMs: List<Int> = emptyList(),
    val tlsBurstCadenceMs: List<Int> = emptyList(),
    val quicBurstProfile: String = "",
    val fakePacketShapeProfile: String = "",
)

@Serializable
data class RipDpiRuntimeContext(
    val encryptedDns: RipDpiEncryptedDnsContext? = null,
    val protectPath: String? = null,
    val preferredEdges: Map<String, List<PreferredEdgeCandidate>> = emptyMap(),
    val directPathCapabilities: List<RipDpiDirectPathCapability> = emptyList(),
    val morphPolicy: RipDpiMorphPolicy? = null,
)

fun StrategyPackMorphPolicy.toRipDpiMorphPolicy(): RipDpiMorphPolicy =
    RipDpiMorphPolicy(
        id = id,
        firstFlightSizeMin = firstFlightSizeMin,
        firstFlightSizeMax = firstFlightSizeMax,
        paddingEnvelopeMin = paddingEnvelopeMin,
        paddingEnvelopeMax = paddingEnvelopeMax,
        entropyTargetPermil = entropyTargetPermil,
        tcpBurstCadenceMs = tcpBurstCadenceMs.map { it.coerceAtLeast(0) },
        tlsBurstCadenceMs = tlsBurstCadenceMs.map { it.coerceAtLeast(0) },
        quicBurstProfile = quicBurstProfile.trim(),
        fakePacketShapeProfile = fakePacketShapeProfile.trim(),
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
        if (listOfNotNull(runtimeId, mode, policySignature, fingerprintHash, diagnosticsSessionId).isEmpty()) {
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

@Suppress("LongMethod")
internal fun normalizeRuntimeContext(runtimeContext: RipDpiRuntimeContext?): RipDpiRuntimeContext? =
    runtimeContext?.let { ctx ->
        val encryptedDns =
            ctx.encryptedDns?.let { value ->
                val normalizedHost = value.host.trim().takeIf { it.isNotEmpty() } ?: return@let null
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
        val protectPath = ctx.protectPath?.trim()?.takeIf { it.isNotEmpty() }
        val preferredEdges =
            ctx.preferredEdges
                .mapNotNull { (host, candidates) ->
                    val normalizedHost = host.trim().lowercase().takeIf { it.isNotEmpty() }
                    val normalizedCandidates = normalizePreferredEdgeCandidates(candidates)
                    if (normalizedHost == null || normalizedCandidates.isEmpty()) {
                        null
                    } else {
                        normalizedHost to normalizedCandidates
                    }
                }.toMap()
        val directPathCapabilities =
            ctx.directPathCapabilities
                .mapNotNull { capability ->
                    val authority =
                        capability.authority
                            .trim()
                            .trimEnd('.')
                            .lowercase(Locale.US)
                            .takeIf { it.isNotEmpty() }
                            ?: return@mapNotNull null
                    RipDpiDirectPathCapability(
                        authority = authority,
                        quicUsable = capability.quicUsable,
                        udpUsable = capability.udpUsable,
                        fallbackRequired = capability.fallbackRequired,
                        repeatedHandshakeFailureClass =
                            capability.repeatedHandshakeFailureClass
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() },
                        transportPolicyVersion = capability.transportPolicyVersion.coerceAtLeast(0),
                        ipSetDigest = capability.ipSetDigest.trim(),
                        quicMode = capability.quicMode,
                        preferredStack = capability.preferredStack,
                        dnsMode = capability.dnsMode,
                        tcpFamily = capability.tcpFamily,
                        outcome = capability.outcome,
                        transportClass = capability.transportClass,
                        reasonCode = capability.reasonCode,
                        cooldownUntil = capability.cooldownUntil?.takeIf { it > 0L },
                        updatedAt = capability.updatedAt.coerceAtLeast(0L),
                    )
                }.distinctBy { capability ->
                    capability.authority to capability.ipSetDigest
                }
        val morphPolicy =
            ctx.morphPolicy?.let { policy ->
                val normalizedId = policy.id.trim().takeIf { it.isNotEmpty() } ?: return@let null
                val firstFlightMin = policy.firstFlightSizeMin.coerceAtLeast(0)
                val firstFlightMax = policy.firstFlightSizeMax.coerceAtLeast(firstFlightMin)
                val paddingMin = policy.paddingEnvelopeMin.coerceAtLeast(0)
                val paddingMax = policy.paddingEnvelopeMax.coerceAtLeast(paddingMin)
                RipDpiMorphPolicy(
                    id = normalizedId,
                    firstFlightSizeMin = firstFlightMin,
                    firstFlightSizeMax = firstFlightMax,
                    paddingEnvelopeMin = paddingMin,
                    paddingEnvelopeMax = paddingMax,
                    entropyTargetPermil = policy.entropyTargetPermil.coerceAtLeast(0),
                    tcpBurstCadenceMs = policy.tcpBurstCadenceMs.map { it.coerceAtLeast(0) }.distinct(),
                    tlsBurstCadenceMs = policy.tlsBurstCadenceMs.map { it.coerceAtLeast(0) }.distinct(),
                    quicBurstProfile = policy.quicBurstProfile.trim().lowercase(Locale.US),
                    fakePacketShapeProfile = policy.fakePacketShapeProfile.trim().lowercase(Locale.US),
                )
            }
        RipDpiRuntimeContext(
            encryptedDns = encryptedDns,
            protectPath = protectPath,
            preferredEdges = preferredEdges,
            directPathCapabilities = directPathCapabilities,
            morphPolicy = morphPolicy,
        ).takeIf {
            encryptedDns != null ||
                protectPath != null ||
                preferredEdges.isNotEmpty() ||
                directPathCapabilities.isNotEmpty() ||
                morphPolicy != null
        }
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
