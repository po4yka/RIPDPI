package com.poyka.ripdpi.data.diagnostics

import androidx.room.withTransaction
import com.poyka.ripdpi.data.NetworkDnsPathPreferenceRetentionLimit
import com.poyka.ripdpi.data.NetworkDnsPathPreferenceRetentionMaxAgeMs
import com.poyka.ripdpi.data.RememberedNetworkPolicyRetentionLimit
import com.poyka.ripdpi.data.RememberedNetworkPolicyRetentionMaxAgeMs
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

internal const val DiagnosticsHistoryDayMillis = 24L * 60L * 60L * 1000L

internal fun diagnosticsHistoryRetentionThreshold(
    now: Long,
    retentionDays: Int,
): Long = now - retentionDays.toLong() * DiagnosticsHistoryDayMillis

fun interface DiagnosticsHistoryClock {
    fun now(): Long
}

@Singleton
class SystemDiagnosticsHistoryClock
    @Inject
    constructor() : DiagnosticsHistoryClock {
        override fun now(): Long = System.currentTimeMillis()
    }

interface DiagnosticsProfileCatalog {
    fun observeProfiles(): Flow<List<DiagnosticProfileEntity>>

    suspend fun getProfile(id: String): DiagnosticProfileEntity?

    suspend fun getPackVersion(packId: String): TargetPackVersionEntity?

    suspend fun upsertProfile(profile: DiagnosticProfileEntity)

    suspend fun upsertPackVersion(version: TargetPackVersionEntity)
}

interface DiagnosticsScanRecordStore {
    fun observeRecentScanSessions(limit: Int = 50): Flow<List<ScanSessionEntity>>

    suspend fun getScanSession(sessionId: String): ScanSessionEntity?

    suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity>

    suspend fun upsertScanSession(session: ScanSessionEntity)

    suspend fun replaceProbeResults(
        sessionId: String,
        results: List<ProbeResultEntity>,
    )
}

interface DiagnosticsArtifactReadStore {
    fun observeSnapshots(limit: Int = 100): Flow<List<NetworkSnapshotEntity>>

    suspend fun getSnapshotsForSession(
        sessionId: String,
        limit: Int = 200,
    ): List<NetworkSnapshotEntity>

    fun observeConnectionSnapshots(
        connectionSessionId: String,
        limit: Int = 100,
    ): Flow<List<NetworkSnapshotEntity>>

    fun observeContexts(limit: Int = 100): Flow<List<DiagnosticContextEntity>>

    suspend fun getContextsForSession(
        sessionId: String,
        limit: Int = 200,
    ): List<DiagnosticContextEntity>

    fun observeConnectionContexts(
        connectionSessionId: String,
        limit: Int = 100,
    ): Flow<List<DiagnosticContextEntity>>

    fun observeTelemetry(limit: Int = 200): Flow<List<TelemetrySampleEntity>>

    fun observeConnectionTelemetry(
        connectionSessionId: String,
        limit: Int = 200,
    ): Flow<List<TelemetrySampleEntity>>

    fun observeNativeEvents(limit: Int = 250): Flow<List<NativeSessionEventEntity>>

    suspend fun getNativeEventsForSession(
        sessionId: String,
        limit: Int = 500,
    ): List<NativeSessionEventEntity>

    fun observeConnectionNativeEvents(
        connectionSessionId: String,
        limit: Int = 250,
    ): Flow<List<NativeSessionEventEntity>>

    fun observeExportRecords(limit: Int = 50): Flow<List<ExportRecordEntity>>
}

interface DiagnosticsArtifactWriteStore {
    suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity)

    suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity)

    suspend fun insertTelemetrySample(sample: TelemetrySampleEntity)

    suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity)

    suspend fun insertExportRecord(record: ExportRecordEntity)
}

interface BypassUsageHistoryStore {
    fun observeBypassUsageSessions(limit: Int = 100): Flow<List<BypassUsageSessionEntity>>

    suspend fun getBypassUsageSession(sessionId: String): BypassUsageSessionEntity?

    suspend fun upsertBypassUsageSession(session: BypassUsageSessionEntity)
}

interface RememberedNetworkPolicyRecordStore {
    fun observeRememberedNetworkPolicies(limit: Int = 64): Flow<List<RememberedNetworkPolicyEntity>>

    suspend fun getRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ): RememberedNetworkPolicyEntity?

    suspend fun findValidatedRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ): RememberedNetworkPolicyEntity?

    suspend fun upsertRememberedNetworkPolicy(policy: RememberedNetworkPolicyEntity): Long

    suspend fun clearRememberedNetworkPolicies()

    suspend fun pruneRememberedNetworkPolicies()
}

interface NetworkDnsPathPreferenceRecordStore {
    suspend fun getNetworkDnsPathPreference(fingerprintHash: String): NetworkDnsPathPreferenceEntity?

    suspend fun upsertNetworkDnsPathPreference(preference: NetworkDnsPathPreferenceEntity): Long

    suspend fun clearNetworkDnsPathPreferences()

    suspend fun pruneNetworkDnsPathPreferences()
}

interface DiagnosticsHistoryRetentionStore {
    suspend fun trimOldData(retentionDays: Int)
}

@Singleton
class RoomDiagnosticsProfileCatalog
    @Inject
    constructor(
        private val dao: DiagnosticsDao,
    ) : DiagnosticsProfileCatalog {
        override fun observeProfiles(): Flow<List<DiagnosticProfileEntity>> = dao.observeProfiles()

        override suspend fun getProfile(id: String): DiagnosticProfileEntity? = dao.getProfile(id)

        override suspend fun getPackVersion(packId: String): TargetPackVersionEntity? = dao.getPackVersion(packId)

        override suspend fun upsertProfile(profile: DiagnosticProfileEntity) {
            dao.upsertProfile(profile)
        }

        override suspend fun upsertPackVersion(version: TargetPackVersionEntity) {
            dao.upsertPackVersion(version)
        }
    }

@Singleton
class RoomDiagnosticsScanRecordStore
    @Inject
    constructor(
        private val db: DiagnosticsDatabase,
        private val dao: DiagnosticsDao,
    ) : DiagnosticsScanRecordStore {
        override fun observeRecentScanSessions(limit: Int): Flow<List<ScanSessionEntity>> =
            dao.observeRecentScanSessions(limit)

        override suspend fun getScanSession(sessionId: String): ScanSessionEntity? = dao.getScanSession(sessionId)

        override suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity> =
            dao.getProbeResults(sessionId)

        override suspend fun upsertScanSession(session: ScanSessionEntity) {
            dao.upsertScanSession(session)
        }

        override suspend fun replaceProbeResults(
            sessionId: String,
            results: List<ProbeResultEntity>,
        ) {
            db.withTransaction {
                dao.deleteProbeResultsForSession(sessionId)
                if (results.isNotEmpty()) {
                    dao.insertProbeResults(results)
                }
            }
        }
    }

@Singleton
class RoomDiagnosticsArtifactStore
    @Inject
    constructor(
        private val dao: DiagnosticsDao,
    ) : DiagnosticsArtifactReadStore,
        DiagnosticsArtifactWriteStore {
        override fun observeSnapshots(limit: Int): Flow<List<NetworkSnapshotEntity>> = dao.observeSnapshots(limit)

        override suspend fun getSnapshotsForSession(
            sessionId: String,
            limit: Int,
        ): List<NetworkSnapshotEntity> = dao.getSnapshotsForSession(sessionId = sessionId, limit = limit)

        override fun observeConnectionSnapshots(
            connectionSessionId: String,
            limit: Int,
        ): Flow<List<NetworkSnapshotEntity>> =
            dao.observeSnapshotsForConnectionSession(
                connectionSessionId = connectionSessionId,
                limit = limit,
            )

        override fun observeContexts(limit: Int): Flow<List<DiagnosticContextEntity>> =
            dao.observeContextSnapshots(limit)

        override suspend fun getContextsForSession(
            sessionId: String,
            limit: Int,
        ): List<DiagnosticContextEntity> = dao.getContextsForSession(sessionId = sessionId, limit = limit)

        override fun observeConnectionContexts(
            connectionSessionId: String,
            limit: Int,
        ): Flow<List<DiagnosticContextEntity>> =
            dao.observeContextSnapshotsForConnectionSession(
                connectionSessionId = connectionSessionId,
                limit = limit,
            )

        override fun observeTelemetry(limit: Int): Flow<List<TelemetrySampleEntity>> = dao.observeTelemetry(limit)

        override fun observeConnectionTelemetry(
            connectionSessionId: String,
            limit: Int,
        ): Flow<List<TelemetrySampleEntity>> =
            dao.observeTelemetryForConnectionSession(
                connectionSessionId = connectionSessionId,
                limit = limit,
            )

        override fun observeNativeEvents(limit: Int): Flow<List<NativeSessionEventEntity>> =
            dao.observeNativeEvents(limit)

        override suspend fun getNativeEventsForSession(
            sessionId: String,
            limit: Int,
        ): List<NativeSessionEventEntity> = dao.getNativeEventsForSession(sessionId = sessionId, limit = limit)

        override fun observeConnectionNativeEvents(
            connectionSessionId: String,
            limit: Int,
        ): Flow<List<NativeSessionEventEntity>> =
            dao.observeNativeEventsForConnectionSession(
                connectionSessionId = connectionSessionId,
                limit = limit,
            )

        override fun observeExportRecords(limit: Int): Flow<List<ExportRecordEntity>> = dao.observeExportRecords(limit)

        override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) {
            dao.upsertNetworkSnapshot(snapshot)
        }

        override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) {
            dao.upsertContextSnapshot(snapshot)
        }

        override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) {
            dao.insertTelemetrySample(sample)
        }

        override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) {
            dao.insertNativeSessionEvent(event)
        }

        override suspend fun insertExportRecord(record: ExportRecordEntity) {
            dao.insertExportRecord(record)
        }
    }

@Singleton
class RoomBypassUsageHistoryStore
    @Inject
    constructor(
        private val dao: DiagnosticsDao,
    ) : BypassUsageHistoryStore {
        override fun observeBypassUsageSessions(limit: Int): Flow<List<BypassUsageSessionEntity>> =
            dao.observeBypassUsageSessions(limit)

        override suspend fun getBypassUsageSession(sessionId: String): BypassUsageSessionEntity? =
            dao.getBypassUsageSession(sessionId)

        override suspend fun upsertBypassUsageSession(session: BypassUsageSessionEntity) {
            dao.upsertBypassUsageSession(session)
        }
    }

@Singleton
class RoomRememberedNetworkPolicyRecordStore
    @Inject
    constructor(
        private val dao: DiagnosticsDao,
        private val clock: DiagnosticsHistoryClock,
    ) : RememberedNetworkPolicyRecordStore {
        override fun observeRememberedNetworkPolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> =
            dao.observeRememberedNetworkPolicies(limit)

        override suspend fun getRememberedNetworkPolicy(
            fingerprintHash: String,
            mode: String,
        ): RememberedNetworkPolicyEntity? =
            dao.getRememberedNetworkPolicy(
                fingerprintHash = fingerprintHash,
                mode = mode,
            )

        override suspend fun findValidatedRememberedNetworkPolicy(
            fingerprintHash: String,
            mode: String,
        ): RememberedNetworkPolicyEntity? =
            dao.findValidatedRememberedNetworkPolicy(
                fingerprintHash = fingerprintHash,
                mode = mode,
                validatedStatus = RememberedNetworkPolicyStatusValidated,
                now = clock.now(),
            )

        override suspend fun upsertRememberedNetworkPolicy(policy: RememberedNetworkPolicyEntity): Long =
            dao.upsertRememberedNetworkPolicy(policy)

        override suspend fun clearRememberedNetworkPolicies() {
            dao.clearRememberedNetworkPolicies()
        }

        override suspend fun pruneRememberedNetworkPolicies() {
            val staleThreshold = clock.now() - RememberedNetworkPolicyRetentionMaxAgeMs
            dao.deleteRememberedNetworkPoliciesOlderThan(staleThreshold)
            dao.trimRememberedNetworkPoliciesToCount(RememberedNetworkPolicyRetentionLimit)
        }
    }

@Singleton
class RoomNetworkDnsPathPreferenceRecordStore
    @Inject
    constructor(
        private val dao: DiagnosticsDao,
        private val clock: DiagnosticsHistoryClock,
    ) : NetworkDnsPathPreferenceRecordStore {
        override suspend fun getNetworkDnsPathPreference(fingerprintHash: String): NetworkDnsPathPreferenceEntity? =
            dao.getNetworkDnsPathPreference(fingerprintHash)

        override suspend fun upsertNetworkDnsPathPreference(preference: NetworkDnsPathPreferenceEntity): Long =
            dao.upsertNetworkDnsPathPreference(preference)

        override suspend fun clearNetworkDnsPathPreferences() {
            dao.clearNetworkDnsPathPreferences()
        }

        override suspend fun pruneNetworkDnsPathPreferences() {
            val staleThreshold = clock.now() - NetworkDnsPathPreferenceRetentionMaxAgeMs
            dao.deleteNetworkDnsPathPreferencesOlderThan(staleThreshold)
            dao.trimNetworkDnsPathPreferencesToCount(NetworkDnsPathPreferenceRetentionLimit)
        }
    }

@Singleton
class RoomDiagnosticsHistoryRetentionStore
    @Inject
    constructor(
        private val dao: DiagnosticsDao,
        private val clock: DiagnosticsHistoryClock,
    ) : DiagnosticsHistoryRetentionStore {
        override suspend fun trimOldData(retentionDays: Int) {
            if (retentionDays <= 0) {
                return
            }
            val threshold = diagnosticsHistoryRetentionThreshold(clock.now(), retentionDays)
            dao.deleteProbeResultsOlderThan(threshold)
            dao.deleteScanSessionsOlderThan(threshold)
            dao.deleteSnapshotsOlderThan(threshold)
            dao.deleteContextOlderThan(threshold)
            dao.deleteTelemetryOlderThan(threshold)
            dao.deleteNativeEventsOlderThan(threshold)
            dao.deleteExportRecordsOlderThan(threshold)
            dao.deleteBypassUsageSessionsOlderThan(threshold)
            dao.deleteNetworkDnsPathPreferencesOlderThan(threshold)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsHistoryStoresModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsProfileCatalog(store: RoomDiagnosticsProfileCatalog): DiagnosticsProfileCatalog

    @Binds
    @Singleton
    abstract fun bindDiagnosticsScanRecordStore(store: RoomDiagnosticsScanRecordStore): DiagnosticsScanRecordStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsArtifactReadStore(store: RoomDiagnosticsArtifactStore): DiagnosticsArtifactReadStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsArtifactWriteStore(store: RoomDiagnosticsArtifactStore): DiagnosticsArtifactWriteStore

    @Binds
    @Singleton
    abstract fun bindBypassUsageHistoryStore(store: RoomBypassUsageHistoryStore): BypassUsageHistoryStore

    @Binds
    @Singleton
    abstract fun bindRememberedNetworkPolicyRecordStore(
        store: RoomRememberedNetworkPolicyRecordStore,
    ): RememberedNetworkPolicyRecordStore

    @Binds
    @Singleton
    abstract fun bindNetworkDnsPathPreferenceRecordStore(
        store: RoomNetworkDnsPathPreferenceRecordStore,
    ): NetworkDnsPathPreferenceRecordStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsHistoryRetentionStore(
        store: RoomDiagnosticsHistoryRetentionStore,
    ): DiagnosticsHistoryRetentionStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsHistoryClock(clock: SystemDiagnosticsHistoryClock): DiagnosticsHistoryClock
}
