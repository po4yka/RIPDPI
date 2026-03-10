package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.poyka.ripdpi.data.Mode
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Entity(tableName = "diagnostic_profiles")
@Serializable
data class DiagnosticProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val source: String,
    val version: Int,
    val requestJson: String,
    val updatedAt: Long,
)

@Entity(tableName = "target_pack_versions")
@Serializable
data class TargetPackVersionEntity(
    @PrimaryKey val packId: String,
    val version: Int,
    val importedAt: Long,
)

@Entity(tableName = "scan_sessions")
@Serializable
data class ScanSessionEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val approachProfileId: String? = null,
    val approachProfileName: String? = null,
    val strategyId: String? = null,
    val strategyLabel: String? = null,
    val strategyJson: String? = null,
    val pathMode: String,
    val serviceMode: String?,
    val status: String,
    val summary: String,
    val reportJson: String?,
    val startedAt: Long,
    val finishedAt: Long?,
)

@Entity(tableName = "probe_results")
@Serializable
data class ProbeResultEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val probeType: String,
    val target: String,
    val outcome: String,
    val detailJson: String,
    val createdAt: Long,
)

@Entity(tableName = "network_snapshots")
@Serializable
data class NetworkSnapshotEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val snapshotKind: String,
    val payloadJson: String,
    val capturedAt: Long,
)

@Entity(tableName = "diagnostic_context_snapshots")
@Serializable
data class DiagnosticContextEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val contextKind: String,
    val payloadJson: String,
    val capturedAt: Long,
)

@Entity(tableName = "telemetry_samples")
@Serializable
data class TelemetrySampleEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val activeMode: String?,
    val connectionState: String,
    val networkType: String,
    val publicIp: String?,
    val txPackets: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val rxBytes: Long,
    val createdAt: Long,
)

@Entity(tableName = "native_session_events")
@Serializable
data class NativeSessionEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val source: String,
    val level: String,
    val message: String,
    val createdAt: Long,
)

@Entity(tableName = "export_records")
@Serializable
data class ExportRecordEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val uri: String,
    val fileName: String,
    val createdAt: Long,
)

@Entity(tableName = "bypass_usage_sessions")
@Serializable
data class BypassUsageSessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val serviceMode: String,
    val approachProfileId: String?,
    val approachProfileName: String?,
    val strategyId: String,
    val strategyLabel: String,
    val strategyJson: String,
    val networkType: String,
    val txBytes: Long,
    val rxBytes: Long,
    val totalErrors: Long,
    val routeChanges: Long,
    val restartCount: Int,
    val endedReason: String?,
)

@Dao
interface DiagnosticsDao {
    @Query("SELECT * FROM diagnostic_profiles ORDER BY updatedAt DESC")
    fun observeProfiles(): Flow<List<DiagnosticProfileEntity>>

    @Query("SELECT * FROM diagnostic_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfile(id: String): DiagnosticProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: DiagnosticProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPackVersion(version: TargetPackVersionEntity)

    @Query("SELECT * FROM target_pack_versions WHERE packId = :packId LIMIT 1")
    suspend fun getPackVersion(packId: String): TargetPackVersionEntity?

    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentScanSessions(limit: Int = 50): Flow<List<ScanSessionEntity>>

    @Query("SELECT * FROM scan_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getScanSession(sessionId: String): ScanSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScanSession(session: ScanSessionEntity)

    @Query("SELECT * FROM probe_results WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProbeResults(results: List<ProbeResultEntity>)

    @Query("DELETE FROM probe_results WHERE sessionId = :sessionId")
    suspend fun deleteProbeResultsForSession(sessionId: String)

    @Query("SELECT * FROM network_snapshots ORDER BY capturedAt DESC LIMIT :limit")
    fun observeSnapshots(limit: Int = 100): Flow<List<NetworkSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetworkSnapshot(snapshot: NetworkSnapshotEntity)

    @Query("SELECT * FROM diagnostic_context_snapshots ORDER BY capturedAt DESC LIMIT :limit")
    fun observeContextSnapshots(limit: Int = 100): Flow<List<DiagnosticContextEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity)

    @Query("DELETE FROM diagnostic_context_snapshots WHERE capturedAt < :threshold")
    suspend fun deleteContextOlderThan(threshold: Long)

    @Query("SELECT * FROM telemetry_samples ORDER BY createdAt DESC LIMIT :limit")
    fun observeTelemetry(limit: Int = 200): Flow<List<TelemetrySampleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetrySample(sample: TelemetrySampleEntity)

    @Query("DELETE FROM telemetry_samples WHERE createdAt < :threshold")
    suspend fun deleteTelemetryOlderThan(threshold: Long)

    @Query("SELECT * FROM native_session_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeNativeEvents(limit: Int = 250): Flow<List<NativeSessionEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity)

    @Query("DELETE FROM native_session_events WHERE createdAt < :threshold")
    suspend fun deleteNativeEventsOlderThan(threshold: Long)

    @Query("SELECT * FROM export_records ORDER BY createdAt DESC LIMIT :limit")
    fun observeExportRecords(limit: Int = 50): Flow<List<ExportRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExportRecord(record: ExportRecordEntity)

    @Query("SELECT * FROM bypass_usage_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeBypassUsageSessions(limit: Int = 100): Flow<List<BypassUsageSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBypassUsageSession(session: BypassUsageSessionEntity)

    @Query("DELETE FROM bypass_usage_sessions WHERE finishedAt IS NOT NULL AND finishedAt < :threshold")
    suspend fun deleteBypassUsageSessionsOlderThan(threshold: Long)
}

@Database(
    entities = [
        DiagnosticProfileEntity::class,
        TargetPackVersionEntity::class,
        ScanSessionEntity::class,
        ProbeResultEntity::class,
        NetworkSnapshotEntity::class,
        DiagnosticContextEntity::class,
        TelemetrySampleEntity::class,
        NativeSessionEventEntity::class,
        ExportRecordEntity::class,
        BypassUsageSessionEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class DiagnosticsDatabase : RoomDatabase() {
    abstract fun diagnosticsDao(): DiagnosticsDao
}

interface DiagnosticsHistoryRepository {
    fun observeProfiles(): Flow<List<DiagnosticProfileEntity>>

    fun observeRecentScanSessions(limit: Int = 50): Flow<List<ScanSessionEntity>>

    fun observeSnapshots(limit: Int = 100): Flow<List<NetworkSnapshotEntity>>

    fun observeContexts(limit: Int = 100): Flow<List<DiagnosticContextEntity>>

    fun observeTelemetry(limit: Int = 200): Flow<List<TelemetrySampleEntity>>

    fun observeNativeEvents(limit: Int = 250): Flow<List<NativeSessionEventEntity>>

    fun observeExportRecords(limit: Int = 50): Flow<List<ExportRecordEntity>>

    fun observeBypassUsageSessions(limit: Int = 100): Flow<List<BypassUsageSessionEntity>>

    suspend fun getProfile(id: String): DiagnosticProfileEntity?

    suspend fun getPackVersion(packId: String): TargetPackVersionEntity?

    suspend fun getScanSession(sessionId: String): ScanSessionEntity?

    suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity>

    suspend fun upsertProfile(profile: DiagnosticProfileEntity)

    suspend fun upsertPackVersion(version: TargetPackVersionEntity)

    suspend fun upsertScanSession(session: ScanSessionEntity)

    suspend fun replaceProbeResults(
        sessionId: String,
        results: List<ProbeResultEntity>,
    )

    suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity)

    suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity)

    suspend fun insertTelemetrySample(sample: TelemetrySampleEntity)

    suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity)

    suspend fun insertExportRecord(record: ExportRecordEntity)

    suspend fun upsertBypassUsageSession(session: BypassUsageSessionEntity)

    suspend fun trimOldData(retentionDays: Int)
}

@Singleton
class RoomDiagnosticsHistoryRepository
    @Inject
    constructor(
        private val dao: DiagnosticsDao,
    ) : DiagnosticsHistoryRepository {
    override fun observeProfiles(): Flow<List<DiagnosticProfileEntity>> = dao.observeProfiles()

    override fun observeRecentScanSessions(limit: Int): Flow<List<ScanSessionEntity>> =
        dao.observeRecentScanSessions(limit)

    override fun observeSnapshots(limit: Int): Flow<List<NetworkSnapshotEntity>> =
        dao.observeSnapshots(limit)

    override fun observeContexts(limit: Int): Flow<List<DiagnosticContextEntity>> =
        dao.observeContextSnapshots(limit)

    override fun observeTelemetry(limit: Int): Flow<List<TelemetrySampleEntity>> =
        dao.observeTelemetry(limit)

    override fun observeNativeEvents(limit: Int): Flow<List<NativeSessionEventEntity>> =
        dao.observeNativeEvents(limit)

    override fun observeExportRecords(limit: Int): Flow<List<ExportRecordEntity>> =
        dao.observeExportRecords(limit)

    override fun observeBypassUsageSessions(limit: Int): Flow<List<BypassUsageSessionEntity>> =
        dao.observeBypassUsageSessions(limit)

    override suspend fun getProfile(id: String): DiagnosticProfileEntity? = dao.getProfile(id)

    override suspend fun getPackVersion(packId: String): TargetPackVersionEntity? =
        dao.getPackVersion(packId)

    override suspend fun getScanSession(sessionId: String): ScanSessionEntity? =
        dao.getScanSession(sessionId)

    override suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity> =
        dao.getProbeResults(sessionId)

    override suspend fun upsertProfile(profile: DiagnosticProfileEntity) {
        dao.upsertProfile(profile)
    }

    override suspend fun upsertPackVersion(version: TargetPackVersionEntity) {
        dao.upsertPackVersion(version)
    }

    override suspend fun upsertScanSession(session: ScanSessionEntity) {
        dao.upsertScanSession(session)
    }

    override suspend fun replaceProbeResults(
        sessionId: String,
        results: List<ProbeResultEntity>,
    ) {
        dao.deleteProbeResultsForSession(sessionId)
        if (results.isNotEmpty()) {
            dao.insertProbeResults(results)
        }
    }

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

    override suspend fun upsertBypassUsageSession(session: BypassUsageSessionEntity) {
        dao.upsertBypassUsageSession(session)
    }

    override suspend fun trimOldData(retentionDays: Int) {
        if (retentionDays <= 0) {
            return
        }
        val threshold = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
        dao.deleteContextOlderThan(threshold)
        dao.deleteTelemetryOlderThan(threshold)
        dao.deleteNativeEventsOlderThan(threshold)
        dao.deleteBypassUsageSessionsOlderThan(threshold)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsHistoryRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsHistoryRepository(
        repository: RoomDiagnosticsHistoryRepository,
    ): DiagnosticsHistoryRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsDatabaseModule {
    @Provides
    @Singleton
    fun provideDiagnosticsDatabase(
        @ApplicationContext context: Context,
    ): DiagnosticsDatabase =
        Room.databaseBuilder(
            context,
            DiagnosticsDatabase::class.java,
            "diagnostics.db",
        ).fallbackToDestructiveMigration().build()

    @Provides
    @Singleton
    fun provideDiagnosticsDao(database: DiagnosticsDatabase): DiagnosticsDao = database.diagnosticsDao()
}
