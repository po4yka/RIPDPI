@file:Suppress("MaxLineLength", "MagicNumber")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.WarpAccountKindZeroTrust
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.WarpScannerModeManual
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal val BuiltInWarpEndpointPoolV4: List<String> =
    listOf(
        "162.159.192.1",
        "162.159.195.1",
        "188.114.96.1",
        "188.114.97.1",
        "188.114.98.1",
        "188.114.99.1",
    )

internal val BuiltInWarpEndpointPorts: List<Int> =
    listOf(
        500,
        854,
        859,
        864,
        878,
        880,
        890,
        891,
        894,
        903,
        908,
        928,
        934,
        939,
        942,
        943,
        945,
        946,
        955,
        968,
        987,
        988,
        1002,
        1010,
        1014,
        1018,
        1070,
        1074,
        1180,
        1387,
        1701,
        1843,
        2371,
        2408,
        2506,
        3138,
        3476,
        3581,
        3854,
        4177,
        4198,
        4233,
        4500,
        5279,
        5956,
        7103,
        7152,
        7156,
        7281,
        7559,
        8319,
        8742,
        8854,
        8886,
    )

internal val BuiltInWarpEndpointPoolV6: List<String> =
    listOf(
        "2606:4700:d0::a29f:c001",
        "2606:4700:d0::a29f:c101",
        "2606:4700:d0::a29f:c201",
        "2606:4700:d0::a29f:c301",
    )

internal const val ScopedWarpEndpointFreshnessTtlMs: Long = 15L * 60L * 1_000L

internal suspend fun probeCachedWarpEntry(
    endpointProbe: WarpEndpointProbe,
    endpointStore: WarpEndpointStore,
    profileId: String,
    networkScopeKey: String,
    entry: WarpEndpointCacheEntry,
    timeoutMillis: Int,
): WarpEndpointCacheEntry? {
    val probed =
        endpointProbe.probe(
            candidate = entry.copy(profileId = profileId, networkScopeKey = networkScopeKey),
            timeoutMillis = timeoutMillis,
        )
    if (probed == null) {
        endpointStore.clear(profileId, networkScopeKey)
    }
    return probed
}

internal suspend fun scanWarpCandidatePool(
    endpointProbe: WarpEndpointProbe,
    candidates: List<WarpEndpointCacheEntry>,
    timeoutMillis: Int,
    parallelism: Int,
): WarpEndpointCacheEntry? =
    coroutineScope {
        var bestWithinBudget: WarpEndpointCacheEntry? = null
        var bestOverall: WarpEndpointCacheEntry? = null
        for (batch in candidates.chunked(parallelism.coerceAtLeast(1))) {
            val batchResults =
                batch
                    .map { candidate ->
                        async { endpointProbe.probe(candidate, timeoutMillis) }
                    }.awaitAll()
                    .filterNotNull()
            val batchBestOverall = batchResults.minByOrNull(::warpCandidateScore)
            if (batchBestOverall != null &&
                (bestOverall == null || warpCandidateScore(batchBestOverall) < warpCandidateScore(bestOverall))
            ) {
                bestOverall = batchBestOverall
            }
            val batchBestWithinBudget =
                batchResults
                    .filter { warpCandidateScore(it) <= timeoutMillis.toLong() }
                    .minByOrNull(::warpCandidateScore)
            if (batchBestWithinBudget != null &&
                (
                    bestWithinBudget == null ||
                        warpCandidateScore(batchBestWithinBudget) < warpCandidateScore(bestWithinBudget)
                )
            ) {
                bestWithinBudget = batchBestWithinBudget
            }
        }
        bestWithinBudget ?: bestOverall
    }

internal suspend fun buildWarpCandidatePool(
    endpointStore: WarpEndpointStore,
    profileId: String,
    provisioned: WarpEndpointCacheEntry?,
): List<WarpEndpointCacheEntry> {
    val remembered = endpointStore.loadAll(profileId)
    val expanded =
        buildList {
            remembered.forEach { addAll(expandWarpCandidate(it)) }
            provisioned?.let { addAll(expandWarpCandidate(it)) }
            addAll(builtInWarpPoolCandidates(profileId, provisioned))
        }
    return expanded.distinctBy(WarpEndpointCacheEntry::candidateIdentity)
}

internal suspend fun persistWarpBestCandidate(
    endpointStore: WarpEndpointStore,
    profileId: String,
    networkScopeKey: String,
    candidate: WarpEndpointCacheEntry,
): WarpEndpointCacheEntry {
    val scoped = candidate.copy(profileId = profileId, networkScopeKey = networkScopeKey)
    endpointStore.save(scoped)
    endpointStore.save(scoped.copy(networkScopeKey = GlobalWarpEndpointScopeKey))
    return scoped
}

internal fun WarpEndpointCacheEntry.isFreshWarpEndpoint(now: Long): Boolean =
    updatedAtEpochMillis > 0L &&
        now >= updatedAtEpochMillis &&
        now - updatedAtEpochMillis <= ScopedWarpEndpointFreshnessTtlMs

internal fun WarpProfile.lastScannerModeOrAutomatic(): String =
    if (accountKind == WarpAccountKindZeroTrust) {
        WarpScannerModeManual
    } else {
        WarpScannerModeAutomatic
    }

internal suspend fun requireActiveWarpProfile(
    appSettingsRepository: AppSettingsRepository,
    profileStore: WarpProfileStore,
): WarpProfile {
    val settingsProfileId = appSettingsRepository.snapshot().warpProfileId
    val candidateId = settingsProfileId.ifBlank { profileStore.activeProfileId().orEmpty() }
    check(candidateId.isNotBlank()) { "No active WARP profile configured" }
    return profileStore.load(candidateId) ?: error("No WARP profile found for $candidateId")
}

internal suspend fun saveWarpEndpoint(
    endpointStore: WarpEndpointStore,
    profileId: String,
    networkScopeKey: String?,
    entry: WarpEndpointCacheEntry?,
): WarpEndpointCacheEntry? {
    val normalizedScope = networkScopeKey?.takeIf(String::isNotBlank) ?: GlobalWarpEndpointScopeKey
    val normalizedEntry =
        entry?.copy(
            profileId = profileId,
            networkScopeKey = normalizedScope,
        ) ?: return null
    endpointStore.save(normalizedEntry)
    return normalizedEntry
}

internal fun normalizeWarpProfileId(
    rawValue: String,
    displayName: String,
): String {
    val base = rawValue.trim().ifBlank { displayName.trim() }.lowercase()
    val sanitized =
        buildString(base.length) {
            base.forEach { char ->
                when {
                    char.isLetterOrDigit() -> append(char)
                    char == '-' || char == '_' -> append(char)
                    char.isWhitespace() -> append('-')
                }
            }
        }.trim('-')
    return sanitized.ifBlank { "warp-${System.currentTimeMillis()}" }
}

private suspend fun expandWarpCandidate(candidate: WarpEndpointCacheEntry): List<WarpEndpointCacheEntry> =
    kotlinx.coroutines.withContext(Dispatchers.IO) {
        val normalizedPort = candidate.port.takeIf { it > 0 } ?: 2408
        val expanded =
            linkedMapOf<String, WarpEndpointCacheEntry>().apply {
                put(
                    candidate.copy(port = normalizedPort).candidateIdentity(),
                    candidate.copy(port = normalizedPort),
                )
            }
        val host = candidate.host?.takeIf(String::isNotBlank)
        if (host != null) {
            runCatching { InetAddress.getAllByName(host).toList() }
                .getOrDefault(emptyList())
                .forEach { address ->
                    val resolvedCandidate =
                        candidate.copy(
                            host = host,
                            ipv4 = (address as? Inet4Address)?.hostAddress,
                            ipv6 = (address as? Inet6Address)?.hostAddress,
                            port = normalizedPort,
                            source = "scanner_dns",
                        )
                    expanded.putIfAbsent(resolvedCandidate.candidateIdentity(), resolvedCandidate)
                }
        }
        expanded.values.toList()
    }

private fun builtInWarpPoolCandidates(
    profileId: String,
    provisioned: WarpEndpointCacheEntry?,
): List<WarpEndpointCacheEntry> {
    val host = provisioned?.host?.takeIf(String::isNotBlank) ?: "engage.cloudflareclient.com"
    val ipv6Port = provisioned?.port?.takeIf { it > 0 } ?: 2408
    return buildList {
        BuiltInWarpEndpointPoolV4.forEach { ipv4 ->
            BuiltInWarpEndpointPorts.forEach { port ->
                add(
                    WarpEndpointCacheEntry(
                        profileId = profileId,
                        networkScopeKey = "",
                        host = host,
                        ipv4 = ipv4,
                        port = port,
                        source = "scanner_builtin",
                    ),
                )
            }
        }
        BuiltInWarpEndpointPoolV6.forEach { ipv6 ->
            add(
                WarpEndpointCacheEntry(
                    profileId = profileId,
                    networkScopeKey = "",
                    host = host,
                    ipv6 = ipv6,
                    port = ipv6Port,
                    source = "scanner_builtin",
                ),
            )
        }
    }
}

private fun WarpEndpointCacheEntry.candidateIdentity(): String =
    listOf(host.orEmpty(), ipv4.orEmpty(), ipv6.orEmpty(), port.toString()).joinToString("|")

private fun warpCandidateScore(candidate: WarpEndpointCacheEntry?): Long = candidate?.rttMs ?: Long.MAX_VALUE
