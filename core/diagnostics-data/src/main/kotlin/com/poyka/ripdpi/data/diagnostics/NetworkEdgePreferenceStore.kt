package com.poyka.ripdpi.data.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.PreferredEdgeIpVersionV4
import com.poyka.ripdpi.data.PreferredEdgeIpVersionV6
import com.poyka.ripdpi.data.normalizePreferredEdgeCandidates
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

interface NetworkEdgePreferenceStore {
    suspend fun getPreferredEdges(
        fingerprintHash: String,
        host: String,
        transportKind: String,
    ): List<PreferredEdgeCandidate>

    suspend fun getPreferredEdgesForRuntime(fingerprintHash: String): Map<String, List<PreferredEdgeCandidate>>

    suspend fun clearAll()

    suspend fun rememberPreferredEdges(
        fingerprint: NetworkFingerprint,
        host: String,
        transportKind: String,
        edges: List<PreferredEdgeCandidate>,
        recordedAt: Long? = null,
    ): NetworkEdgePreferenceEntity

    suspend fun recordEdgeResult(
        fingerprint: NetworkFingerprint,
        host: String,
        transportKind: String,
        ip: String,
        success: Boolean,
        recordedAt: Long? = null,
        echCapable: Boolean = false,
        cdnProvider: String? = null,
    ): NetworkEdgePreferenceEntity
}

@Singleton
class DefaultNetworkEdgePreferenceStore
    @Inject
    constructor(
        private val recordStore: NetworkEdgePreferenceRecordStore,
        private val clock: DiagnosticsHistoryClock,
    ) : NetworkEdgePreferenceStore {
        private val json =
            Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            }

        override suspend fun getPreferredEdges(
            fingerprintHash: String,
            host: String,
            transportKind: String,
        ): List<PreferredEdgeCandidate> =
            recordStore
                .getNetworkEdgePreference(
                    fingerprintHash = fingerprintHash,
                    host = host.normalizedEdgeHost(),
                    transportKind = transportKind.normalizedTransportKind(),
                )?.decodeEdges(json)
                .orEmpty()

        override suspend fun getPreferredEdgesForRuntime(
            fingerprintHash: String,
        ): Map<String, List<PreferredEdgeCandidate>> =
            recordStore
                .getNetworkEdgePreferencesForFingerprint(fingerprintHash)
                .groupBy(NetworkEdgePreferenceEntity::host)
                .mapValues { (_, entries) ->
                    normalizePreferredEdgeCandidates(entries.flatMap { entity -> entity.decodeEdges(json) })
                }.filterValues { it.isNotEmpty() }

        override suspend fun clearAll() {
            recordStore.clearNetworkEdgePreferences()
        }

        override suspend fun rememberPreferredEdges(
            fingerprint: NetworkFingerprint,
            host: String,
            transportKind: String,
            edges: List<PreferredEdgeCandidate>,
            recordedAt: Long?,
        ): NetworkEdgePreferenceEntity {
            val fingerprintHash = fingerprint.scopeKey()
            val normalizedHost = host.normalizedEdgeHost()
            val normalizedTransportKind = transportKind.normalizedTransportKind()
            val existing =
                recordStore.getNetworkEdgePreference(
                    fingerprintHash = fingerprintHash,
                    host = normalizedHost,
                    transportKind = normalizedTransportKind,
                )
            val effectiveRecordedAt = recordedAt ?: clock.now()
            val persisted =
                NetworkEdgePreferenceEntity(
                    id = existing?.id ?: 0L,
                    fingerprintHash = fingerprintHash,
                    host = normalizedHost,
                    transportKind = normalizedTransportKind,
                    summaryJson = json.encodeToString(NetworkFingerprintSummary.serializer(), fingerprint.summary()),
                    edgesJson = json.encodeToString(ListSerializer, normalizePreferredEdgeCandidates(edges)),
                    updatedAt = effectiveRecordedAt,
                )
            val id = recordStore.upsertNetworkEdgePreference(persisted)
            recordStore.pruneNetworkEdgePreferences()
            return persisted.copy(id = if (persisted.id > 0L) persisted.id else id)
        }

        override suspend fun recordEdgeResult(
            fingerprint: NetworkFingerprint,
            host: String,
            transportKind: String,
            ip: String,
            success: Boolean,
            recordedAt: Long?,
            echCapable: Boolean,
            cdnProvider: String?,
        ): NetworkEdgePreferenceEntity {
            val fingerprintHash = fingerprint.scopeKey()
            val normalizedHost = host.normalizedEdgeHost()
            val normalizedTransportKind = transportKind.normalizedTransportKind()
            val existing =
                recordStore.getNetworkEdgePreference(
                    fingerprintHash = fingerprintHash,
                    host = normalizedHost,
                    transportKind = normalizedTransportKind,
                )
            val effectiveRecordedAt = recordedAt ?: clock.now()
            val normalizedIp = ip.trim()
            val updatedEdges =
                normalizePreferredEdgeCandidates(
                    buildList {
                        addAll(existing?.decodeEdges(json).orEmpty())
                        if (normalizedIp.isNotEmpty()) {
                            add(
                                PreferredEdgeCandidate(
                                    ip = normalizedIp,
                                    transportKind = normalizedTransportKind,
                                    ipVersion = normalizedIp.detectIpVersion(),
                                    successCount = 0,
                                    failureCount = 0,
                                    echCapable = echCapable,
                                    cdnProvider = cdnProvider,
                                ),
                            )
                        }
                    }.groupBy { candidate -> candidate.ip }
                        .map { (_, candidates) ->
                            candidates.reduce { acc, candidate ->
                                acc.copy(
                                    successCount = maxOf(acc.successCount, candidate.successCount),
                                    failureCount = maxOf(acc.failureCount, candidate.failureCount),
                                    lastValidatedAt = acc.lastValidatedAt ?: candidate.lastValidatedAt,
                                    lastFailedAt = acc.lastFailedAt ?: candidate.lastFailedAt,
                                    echCapable = acc.echCapable || candidate.echCapable,
                                    cdnProvider = acc.cdnProvider ?: candidate.cdnProvider,
                                )
                            }
                        }.map { candidate ->
                            if (candidate.ip != normalizedIp) {
                                candidate
                            } else if (success) {
                                candidate.copy(
                                    successCount = candidate.successCount + 1,
                                    lastValidatedAt = effectiveRecordedAt,
                                    echCapable = candidate.echCapable || echCapable,
                                    cdnProvider = cdnProvider ?: candidate.cdnProvider,
                                )
                            } else {
                                candidate.copy(
                                    failureCount = candidate.failureCount + 1,
                                    lastFailedAt = effectiveRecordedAt,
                                    echCapable = candidate.echCapable || echCapable,
                                    cdnProvider = cdnProvider ?: candidate.cdnProvider,
                                )
                            }
                        },
                )
            return rememberPreferredEdges(
                fingerprint = fingerprint,
                host = normalizedHost,
                transportKind = normalizedTransportKind,
                edges = updatedEdges,
                recordedAt = effectiveRecordedAt,
            )
        }
    }

private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(PreferredEdgeCandidate.serializer())

fun NetworkEdgePreferenceEntity.decodeEdges(
    json: Json = Json { ignoreUnknownKeys = true },
): List<PreferredEdgeCandidate> =
    runCatching {
        json.decodeFromString(ListSerializer, edgesJson)
    }.onFailure { Logger.w(it) { "Failed to decode preferred edge candidates" } }.getOrNull().orEmpty()

fun NetworkEdgePreferenceEntity.decodeSummary(
    json: Json = Json { ignoreUnknownKeys = true },
): NetworkFingerprintSummary? =
    runCatching {
        json.decodeFromString(NetworkFingerprintSummary.serializer(), summaryJson)
    }.onFailure { Logger.w(it) { "Failed to decode network fingerprint summary" } }.getOrNull()

private fun String.normalizedEdgeHost(): String = trim().lowercase()

private fun String.normalizedTransportKind(): String = trim().lowercase()

private fun String.detectIpVersion(): String = if (contains(':')) PreferredEdgeIpVersionV6 else PreferredEdgeIpVersionV4

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkEdgePreferenceStoreModule {
    @Binds
    @Singleton
    abstract fun bindNetworkEdgePreferenceStore(store: DefaultNetworkEdgePreferenceStore): NetworkEdgePreferenceStore
}
