package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface NetworkDnsPathPreferenceStore {
    suspend fun getPreferredPath(fingerprintHash: String): EncryptedDnsPathCandidate?

    suspend fun rememberPreferredPath(
        fingerprint: NetworkFingerprint,
        path: EncryptedDnsPathCandidate,
        updatedAt: Long = System.currentTimeMillis(),
    ): NetworkDnsPathPreferenceEntity
}

@Singleton
class DefaultNetworkDnsPathPreferenceStore
    @Inject
    constructor(
        private val historyRepository: DiagnosticsHistoryRepository,
    ) : NetworkDnsPathPreferenceStore {
        private val json =
            Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            }

        override suspend fun getPreferredPath(fingerprintHash: String): EncryptedDnsPathCandidate? =
            historyRepository
                .getNetworkDnsPathPreference(fingerprintHash)
                ?.decodePath(json)

        override suspend fun rememberPreferredPath(
            fingerprint: NetworkFingerprint,
            path: EncryptedDnsPathCandidate,
            updatedAt: Long,
        ): NetworkDnsPathPreferenceEntity {
            val fingerprintHash = fingerprint.scopeKey()
            val existing = historyRepository.getNetworkDnsPathPreference(fingerprintHash)
            val persisted =
                NetworkDnsPathPreferenceEntity(
                    id = existing?.id ?: 0L,
                    fingerprintHash = fingerprintHash,
                    summaryJson = json.encodeToString(NetworkFingerprintSummary.serializer(), fingerprint.summary()),
                    pathJson = json.encodeToString(EncryptedDnsPathCandidate.serializer(), path),
                    updatedAt = updatedAt,
                )
            val id = historyRepository.upsertNetworkDnsPathPreference(persisted)
            historyRepository.pruneNetworkDnsPathPreferences()
            return persisted.copy(id = if (persisted.id > 0L) persisted.id else id)
        }
    }

fun NetworkDnsPathPreferenceEntity.decodePath(
    json: Json = Json { ignoreUnknownKeys = true },
): EncryptedDnsPathCandidate? =
    runCatching { json.decodeFromString(EncryptedDnsPathCandidate.serializer(), pathJson) }.getOrNull()

fun NetworkDnsPathPreferenceEntity.decodeSummary(
    json: Json = Json { ignoreUnknownKeys = true },
): NetworkFingerprintSummary? =
    runCatching { json.decodeFromString(NetworkFingerprintSummary.serializer(), summaryJson) }.getOrNull()

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkDnsPathPreferenceStoreModule {
    @Binds
    @Singleton
    abstract fun bindNetworkDnsPathPreferenceStore(
        store: DefaultNetworkDnsPathPreferenceStore,
    ): NetworkDnsPathPreferenceStore
}
