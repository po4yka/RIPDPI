package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

interface NetworkDnsPathPreferenceStore {
    suspend fun getPreferredPath(fingerprintHash: String): EncryptedDnsPathCandidate?

    suspend fun clearAll()

    suspend fun rememberPreferredPath(
        fingerprint: NetworkFingerprint,
        path: EncryptedDnsPathCandidate,
        recordedAt: Long? = null,
    ): NetworkDnsPathPreferenceEntity
}

@Singleton
class DefaultNetworkDnsPathPreferenceStore
    @Inject
    constructor(
        private val recordStore: NetworkDnsPathPreferenceRecordStore,
        private val clock: DiagnosticsHistoryClock,
    ) : NetworkDnsPathPreferenceStore {
        private val json =
            Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            }

        override suspend fun getPreferredPath(fingerprintHash: String): EncryptedDnsPathCandidate? =
            recordStore
                .getNetworkDnsPathPreference(fingerprintHash)
                ?.decodePath(json)

        override suspend fun clearAll() {
            recordStore.clearNetworkDnsPathPreferences()
        }

        override suspend fun rememberPreferredPath(
            fingerprint: NetworkFingerprint,
            path: EncryptedDnsPathCandidate,
            recordedAt: Long?,
        ): NetworkDnsPathPreferenceEntity {
            val fingerprintHash = fingerprint.scopeKey()
            val existing = recordStore.getNetworkDnsPathPreference(fingerprintHash)
            val effectiveRecordedAt = recordedAt ?: clock.now()
            val persisted =
                NetworkDnsPathPreferenceEntity(
                    id = existing?.id ?: 0L,
                    fingerprintHash = fingerprintHash,
                    summaryJson = json.encodeToString(NetworkFingerprintSummary.serializer(), fingerprint.summary()),
                    pathJson = json.encodeToString(EncryptedDnsPathCandidate.serializer(), path),
                    updatedAt = effectiveRecordedAt,
                )
            val id = recordStore.upsertNetworkDnsPathPreference(persisted)
            recordStore.pruneNetworkDnsPathPreferences()
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
