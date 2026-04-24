package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

const val NetworkEdgePreferenceRetentionLimit = 256
const val NetworkEdgePreferenceRetentionMaxAgeMs = 30L * 24L * 60L * 60L * 1_000L
const val NetworkEdgePreferencePerHostLimit = 6
const val NetworkEdgePreferencePlannerLimit = 4
const val PreferredEdgeRuntimeFallbackCount = 2

const val PreferredEdgeTransportTcp = "tcp"
const val PreferredEdgeTransportQuic = "quic"
const val PreferredEdgeTransportThroughput = "throughput"

const val PreferredEdgeIpVersionV4 = "ipv4"
const val PreferredEdgeIpVersionV6 = "ipv6"

@Serializable
data class PreferredEdgeCandidate(
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

fun normalizePreferredEdgeCandidates(candidates: List<PreferredEdgeCandidate>): List<PreferredEdgeCandidate> =
    candidates
        .mapNotNull { candidate ->
            val normalizedIp = candidate.ip.trim()
            if (normalizedIp.isEmpty()) {
                null
            } else {
                candidate.copy(
                    ip = normalizedIp,
                    transportKind = candidate.transportKind.trim().lowercase(),
                    ipVersion =
                        when (candidate.ipVersion.trim().lowercase()) {
                            PreferredEdgeIpVersionV6 -> PreferredEdgeIpVersionV6
                            else -> PreferredEdgeIpVersionV4
                        },
                    successCount = candidate.successCount.coerceAtLeast(0),
                    failureCount = candidate.failureCount.coerceAtLeast(0),
                    cdnProvider = candidate.cdnProvider?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }.distinctBy { candidate -> "${candidate.transportKind}|${candidate.ip}" }
        .sortedWith(
            compareByDescending<PreferredEdgeCandidate> { it.successCount }
                .thenBy { it.failureCount }
                .thenByDescending { it.lastValidatedAt ?: Long.MIN_VALUE }
                .thenByDescending { it.echCapable }
                .thenBy { it.ip },
        ).take(NetworkEdgePreferencePerHostLimit)
