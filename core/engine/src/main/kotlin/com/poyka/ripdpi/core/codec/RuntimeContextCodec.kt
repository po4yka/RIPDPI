package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.core.RipDpiDirectPathCapability
import com.poyka.ripdpi.core.RipDpiEncryptedDnsContext
import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.RipDpiMorphPolicy
import com.poyka.ripdpi.core.RipDpiRuntimeContext
import com.poyka.ripdpi.core.normalizeLogContext
import com.poyka.ripdpi.core.normalizeRuntimeContext
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import kotlinx.serialization.Serializable

@Serializable
internal data class NativeEncryptedDnsContext(
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
internal data class NativePreferredEdge(
    val ip: String,
    val transportKind: String,
    val ipVersion: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastValidatedAt: Long? = null,
    val lastFailedAt: Long? = null,
    val echCapable: Boolean = false,
    val cdnProvider: String? = null,
)

@Serializable
internal data class NativeRuntimeContext(
    val encryptedDns: NativeEncryptedDnsContext? = null,
    val protectPath: String? = null,
    val preferredEdges: Map<String, List<NativePreferredEdge>> = emptyMap(),
    val directPathCapabilities: List<NativeDirectPathCapability> = emptyList(),
    val morphPolicy: NativeMorphPolicy? = null,
)

@Serializable
internal data class NativeDirectPathCapability(
    val authority: String,
    val quicUsable: Boolean? = null,
    val udpUsable: Boolean? = null,
    val fallbackRequired: Boolean? = null,
    val repeatedHandshakeFailureClass: String? = null,
    val updatedAt: Long = 0L,
)

@Serializable
internal data class NativeMorphPolicy(
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
internal data class NativeLogContext(
    val runtimeId: String? = null,
    val mode: String? = null,
    val policySignature: String? = null,
    val fingerprintHash: String? = null,
    val diagnosticsSessionId: String? = null,
)

@Serializable
internal data class NativeSessionLocalProxyOverrides(
    val listenPortOverride: Int? = null,
    val authToken: String? = null,
)

internal object SessionOverrideCodec {
    fun toNative(
        listenPortOverride: Int?,
        authToken: String?,
    ): NativeSessionLocalProxyOverrides? {
        val normalizedToken = authToken?.takeIf { it.isNotBlank() }
        return if (listenPortOverride == null && normalizedToken == null) {
            null
        } else {
            NativeSessionLocalProxyOverrides(
                listenPortOverride = listenPortOverride,
                authToken = normalizedToken,
            )
        }
    }

    fun merge(
        existing: NativeSessionLocalProxyOverrides?,
        listenPortOverride: Int?,
        authToken: String?,
    ): NativeSessionLocalProxyOverrides? =
        toNative(
            listenPortOverride = listenPortOverride ?: existing?.listenPortOverride,
            authToken = authToken ?: existing?.authToken,
        )
}

internal object ProxyRuntimeContextCodec {
    fun toModel(value: NativeRuntimeContext?): RipDpiRuntimeContext? =
        normalizeRuntimeContext(
            value?.let {
                RipDpiRuntimeContext(
                    encryptedDns =
                        it.encryptedDns?.let { dns ->
                            RipDpiEncryptedDnsContext(
                                resolverId = dns.resolverId,
                                protocol = dns.protocol,
                                host = dns.host,
                                port = dns.port,
                                tlsServerName = dns.tlsServerName,
                                bootstrapIps = dns.bootstrapIps,
                                dohUrl = dns.dohUrl,
                                dnscryptProviderName = dns.dnscryptProviderName,
                                dnscryptPublicKey = dns.dnscryptPublicKey,
                            )
                        },
                    protectPath = it.protectPath,
                    preferredEdges =
                        it.preferredEdges.mapValues { (_, candidates) ->
                            candidates.map { edge ->
                                PreferredEdgeCandidate(
                                    ip = edge.ip,
                                    transportKind = edge.transportKind,
                                    ipVersion = edge.ipVersion,
                                    successCount = edge.successCount,
                                    failureCount = edge.failureCount,
                                    lastValidatedAt = edge.lastValidatedAt,
                                    lastFailedAt = edge.lastFailedAt,
                                    echCapable = edge.echCapable,
                                    cdnProvider = edge.cdnProvider,
                                )
                            }
                        },
                    directPathCapabilities =
                        it.directPathCapabilities.map { capability ->
                            RipDpiDirectPathCapability(
                                authority = capability.authority,
                                quicUsable = capability.quicUsable,
                                udpUsable = capability.udpUsable,
                                fallbackRequired = capability.fallbackRequired,
                                repeatedHandshakeFailureClass = capability.repeatedHandshakeFailureClass,
                                updatedAt = capability.updatedAt,
                            )
                        },
                    morphPolicy =
                        it.morphPolicy?.let { policy ->
                            RipDpiMorphPolicy(
                                id = policy.id,
                                firstFlightSizeMin = policy.firstFlightSizeMin,
                                firstFlightSizeMax = policy.firstFlightSizeMax,
                                paddingEnvelopeMin = policy.paddingEnvelopeMin,
                                paddingEnvelopeMax = policy.paddingEnvelopeMax,
                                entropyTargetPermil = policy.entropyTargetPermil,
                                tcpBurstCadenceMs = policy.tcpBurstCadenceMs,
                                tlsBurstCadenceMs = policy.tlsBurstCadenceMs,
                                quicBurstProfile = policy.quicBurstProfile,
                                fakePacketShapeProfile = policy.fakePacketShapeProfile,
                            )
                        },
                )
            },
        )

    fun toNative(value: RipDpiRuntimeContext?): NativeRuntimeContext? =
        normalizeRuntimeContext(value)?.let { context ->
            NativeRuntimeContext(
                encryptedDns =
                    context.encryptedDns?.let {
                        NativeEncryptedDnsContext(
                            resolverId = it.resolverId,
                            protocol = it.protocol,
                            host = it.host,
                            port = it.port,
                            tlsServerName = it.tlsServerName,
                            bootstrapIps = it.bootstrapIps,
                            dohUrl = it.dohUrl,
                            dnscryptProviderName = it.dnscryptProviderName,
                            dnscryptPublicKey = it.dnscryptPublicKey,
                        )
                    },
                protectPath = context.protectPath,
                preferredEdges =
                    context.preferredEdges.mapValues { (_, candidates) ->
                        candidates.map { edge ->
                            NativePreferredEdge(
                                ip = edge.ip,
                                transportKind = edge.transportKind,
                                ipVersion = edge.ipVersion,
                                successCount = edge.successCount,
                                failureCount = edge.failureCount,
                                lastValidatedAt = edge.lastValidatedAt,
                                lastFailedAt = edge.lastFailedAt,
                                echCapable = edge.echCapable,
                                cdnProvider = edge.cdnProvider,
                            )
                        }
                    },
                directPathCapabilities =
                    context.directPathCapabilities.map { capability ->
                        NativeDirectPathCapability(
                            authority = capability.authority,
                            quicUsable = capability.quicUsable,
                            udpUsable = capability.udpUsable,
                            fallbackRequired = capability.fallbackRequired,
                            repeatedHandshakeFailureClass = capability.repeatedHandshakeFailureClass,
                            updatedAt = capability.updatedAt,
                        )
                    },
                morphPolicy =
                    context.morphPolicy?.let { policy ->
                        NativeMorphPolicy(
                            id = policy.id,
                            firstFlightSizeMin = policy.firstFlightSizeMin,
                            firstFlightSizeMax = policy.firstFlightSizeMax,
                            paddingEnvelopeMin = policy.paddingEnvelopeMin,
                            paddingEnvelopeMax = policy.paddingEnvelopeMax,
                            entropyTargetPermil = policy.entropyTargetPermil,
                            tcpBurstCadenceMs = policy.tcpBurstCadenceMs,
                            tlsBurstCadenceMs = policy.tlsBurstCadenceMs,
                            quicBurstProfile = policy.quicBurstProfile,
                            fakePacketShapeProfile = policy.fakePacketShapeProfile,
                        )
                    },
            )
        }
}

internal object ProxyLogContextCodec {
    fun toModel(value: NativeLogContext?): RipDpiLogContext? =
        normalizeLogContext(
            value?.let {
                RipDpiLogContext(
                    runtimeId = it.runtimeId,
                    mode = it.mode,
                    policySignature = it.policySignature,
                    fingerprintHash = it.fingerprintHash,
                    diagnosticsSessionId = it.diagnosticsSessionId,
                )
            },
        )

    fun toNative(value: RipDpiLogContext?): NativeLogContext? =
        normalizeLogContext(value)?.let {
            NativeLogContext(
                runtimeId = it.runtimeId,
                mode = it.mode,
                policySignature = it.policySignature,
                fingerprintHash = it.fingerprintHash,
                diagnosticsSessionId = it.diagnosticsSessionId,
            )
        }
}
