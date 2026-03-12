package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Entity(
    tableName = "network_snapshots",
    indices = [
        Index(
            name = "index_network_snapshots_sessionId_capturedAt",
            value = ["sessionId", "capturedAt"],
        ),
        Index(
            name = "index_network_snapshots_connectionSessionId_capturedAt",
            value = ["connectionSessionId", "capturedAt"],
        ),
        Index(
            name = "index_network_snapshots_capturedAt",
            value = ["capturedAt"],
        ),
    ],
)
@Serializable
data class NetworkSnapshotEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val connectionSessionId: String? = null,
    val snapshotKind: String,
    val payloadJson: String,
    val capturedAt: Long,
)

@Entity(
    tableName = "diagnostic_context_snapshots",
    indices = [
        Index(
            name = "index_diagnostic_context_snapshots_sessionId_capturedAt",
            value = ["sessionId", "capturedAt"],
        ),
        Index(
            name = "index_diagnostic_context_snapshots_connectionSessionId_capturedAt",
            value = ["connectionSessionId", "capturedAt"],
        ),
        Index(
            name = "index_diagnostic_context_snapshots_capturedAt",
            value = ["capturedAt"],
        ),
    ],
)
@Serializable
data class DiagnosticContextEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val connectionSessionId: String? = null,
    val contextKind: String,
    val payloadJson: String,
    val capturedAt: Long,
)

@Entity(
    tableName = "telemetry_samples",
    indices = [
        Index(
            name = "index_telemetry_samples_sessionId_createdAt",
            value = ["sessionId", "createdAt"],
        ),
        Index(
            name = "index_telemetry_samples_connectionSessionId_createdAt",
            value = ["connectionSessionId", "createdAt"],
        ),
        Index(
            name = "index_telemetry_samples_createdAt",
            value = ["createdAt"],
        ),
    ],
)
@Serializable
data class TelemetrySampleEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val connectionSessionId: String? = null,
    val activeMode: String?,
    val connectionState: String,
    val networkType: String,
    val publicIp: String?,
    val resolverId: String? = null,
    val resolverProtocol: String? = null,
    val resolverEndpoint: String? = null,
    val resolverLatencyMs: Long? = null,
    val dnsFailuresTotal: Long = 0,
    val resolverFallbackActive: Boolean = false,
    val resolverFallbackReason: String? = null,
    val networkHandoverClass: String? = null,
    val txPackets: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val rxBytes: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "native_session_events",
    indices = [
        Index(
            name = "index_native_session_events_sessionId_createdAt",
            value = ["sessionId", "createdAt"],
        ),
        Index(
            name = "index_native_session_events_connectionSessionId_createdAt",
            value = ["connectionSessionId", "createdAt"],
        ),
        Index(
            name = "index_native_session_events_createdAt",
            value = ["createdAt"],
        ),
    ],
)
@Serializable
data class NativeSessionEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val connectionSessionId: String? = null,
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
    val updatedAt: Long = 0L,
    val serviceMode: String,
    val connectionState: String = "Running",
    val health: String = "idle",
    val approachProfileId: String?,
    val approachProfileName: String?,
    val strategyId: String,
    val strategyLabel: String,
    val strategyJson: String,
    val networkType: String,
    val publicIp: String? = null,
    val txBytes: Long,
    val rxBytes: Long,
    val totalErrors: Long,
    val routeChanges: Long,
    val restartCount: Int,
    val endedReason: String?,
    val failureMessage: String? = null,
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

    @Query(
        """
        SELECT * FROM network_snapshots
        WHERE connectionSessionId = :connectionSessionId
        ORDER BY capturedAt DESC
        LIMIT :limit
        """,
    )
    fun observeSnapshotsForConnectionSession(
        connectionSessionId: String,
        limit: Int = 100,
    ): Flow<List<NetworkSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetworkSnapshot(snapshot: NetworkSnapshotEntity)

    @Query("DELETE FROM network_snapshots WHERE capturedAt < :threshold")
    suspend fun deleteSnapshotsOlderThan(threshold: Long)

    @Query("SELECT * FROM diagnostic_context_snapshots ORDER BY capturedAt DESC LIMIT :limit")
    fun observeContextSnapshots(limit: Int = 100): Flow<List<DiagnosticContextEntity>>

    @Query(
        """
        SELECT * FROM diagnostic_context_snapshots
        WHERE connectionSessionId = :connectionSessionId
        ORDER BY capturedAt DESC
        LIMIT :limit
        """,
    )
    fun observeContextSnapshotsForConnectionSession(
        connectionSessionId: String,
        limit: Int = 100,
    ): Flow<List<DiagnosticContextEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity)

    @Query("DELETE FROM diagnostic_context_snapshots WHERE capturedAt < :threshold")
    suspend fun deleteContextOlderThan(threshold: Long)

    @Query("SELECT * FROM telemetry_samples ORDER BY createdAt DESC LIMIT :limit")
    fun observeTelemetry(limit: Int = 200): Flow<List<TelemetrySampleEntity>>

    @Query(
        """
        SELECT * FROM telemetry_samples
        WHERE connectionSessionId = :connectionSessionId
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    fun observeTelemetryForConnectionSession(
        connectionSessionId: String,
        limit: Int = 200,
    ): Flow<List<TelemetrySampleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetrySample(sample: TelemetrySampleEntity)

    @Query("DELETE FROM telemetry_samples WHERE createdAt < :threshold")
    suspend fun deleteTelemetryOlderThan(threshold: Long)

    @Query("SELECT * FROM native_session_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeNativeEvents(limit: Int = 250): Flow<List<NativeSessionEventEntity>>

    @Query(
        """
        SELECT * FROM native_session_events
        WHERE connectionSessionId = :connectionSessionId
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    fun observeNativeEventsForConnectionSession(
        connectionSessionId: String,
        limit: Int = 250,
    ): Flow<List<NativeSessionEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity)

    @Query("DELETE FROM native_session_events WHERE createdAt < :threshold")
    suspend fun deleteNativeEventsOlderThan(threshold: Long)

    @Query("SELECT * FROM export_records ORDER BY createdAt DESC LIMIT :limit")
    fun observeExportRecords(limit: Int = 50): Flow<List<ExportRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExportRecord(record: ExportRecordEntity)

    @Query("DELETE FROM export_records WHERE createdAt < :threshold")
    suspend fun deleteExportRecordsOlderThan(threshold: Long)

    @Query("SELECT * FROM bypass_usage_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeBypassUsageSessions(limit: Int = 100): Flow<List<BypassUsageSessionEntity>>

    @Query("SELECT * FROM bypass_usage_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getBypassUsageSession(sessionId: String): BypassUsageSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBypassUsageSession(session: BypassUsageSessionEntity)

    @Query("DELETE FROM bypass_usage_sessions WHERE finishedAt IS NOT NULL AND finishedAt < :threshold")
    suspend fun deleteBypassUsageSessionsOlderThan(threshold: Long)

    @Query("DELETE FROM probe_results WHERE createdAt < :threshold")
    suspend fun deleteProbeResultsOlderThan(threshold: Long)

    @Query("DELETE FROM scan_sessions WHERE finishedAt IS NOT NULL AND finishedAt < :threshold")
    suspend fun deleteScanSessionsOlderThan(threshold: Long)
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
    version = 5,
    exportSchema = true,
)
abstract class DiagnosticsDatabase : RoomDatabase() {
    abstract fun diagnosticsDao(): DiagnosticsDao
}

interface DiagnosticsHistoryRepository {
    fun observeProfiles(): Flow<List<DiagnosticProfileEntity>>

    fun observeRecentScanSessions(limit: Int = 50): Flow<List<ScanSessionEntity>>

    fun observeSnapshots(limit: Int = 100): Flow<List<NetworkSnapshotEntity>>

    fun observeConnectionSnapshots(
        connectionSessionId: String,
        limit: Int = 100,
    ): Flow<List<NetworkSnapshotEntity>>

    fun observeContexts(limit: Int = 100): Flow<List<DiagnosticContextEntity>>

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

    fun observeConnectionNativeEvents(
        connectionSessionId: String,
        limit: Int = 250,
    ): Flow<List<NativeSessionEventEntity>>

    fun observeExportRecords(limit: Int = 50): Flow<List<ExportRecordEntity>>

    fun observeBypassUsageSessions(limit: Int = 100): Flow<List<BypassUsageSessionEntity>>

    suspend fun getProfile(id: String): DiagnosticProfileEntity?

    suspend fun getPackVersion(packId: String): TargetPackVersionEntity?

    suspend fun getScanSession(sessionId: String): ScanSessionEntity?

    suspend fun getBypassUsageSession(sessionId: String): BypassUsageSessionEntity?

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

    override fun observeConnectionContexts(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<DiagnosticContextEntity>> =
        dao.observeContextSnapshotsForConnectionSession(
            connectionSessionId = connectionSessionId,
            limit = limit,
        )

    override fun observeTelemetry(limit: Int): Flow<List<TelemetrySampleEntity>> =
        dao.observeTelemetry(limit)

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

    override fun observeConnectionNativeEvents(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<NativeSessionEventEntity>> =
        dao.observeNativeEventsForConnectionSession(
            connectionSessionId = connectionSessionId,
            limit = limit,
        )

    override fun observeExportRecords(limit: Int): Flow<List<ExportRecordEntity>> =
        dao.observeExportRecords(limit)

    override fun observeBypassUsageSessions(limit: Int): Flow<List<BypassUsageSessionEntity>> =
        dao.observeBypassUsageSessions(limit)

    override suspend fun getProfile(id: String): DiagnosticProfileEntity? = dao.getProfile(id)

    override suspend fun getPackVersion(packId: String): TargetPackVersionEntity? =
        dao.getPackVersion(packId)

    override suspend fun getScanSession(sessionId: String): ScanSessionEntity? =
        dao.getScanSession(sessionId)

    override suspend fun getBypassUsageSession(sessionId: String): BypassUsageSessionEntity? =
        dao.getBypassUsageSession(sessionId)

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
        dao.deleteProbeResultsOlderThan(threshold)
        dao.deleteScanSessionsOlderThan(threshold)
        dao.deleteSnapshotsOlderThan(threshold)
        dao.deleteContextOlderThan(threshold)
        dao.deleteTelemetryOlderThan(threshold)
        dao.deleteNativeEventsOlderThan(threshold)
        dao.deleteExportRecordsOlderThan(threshold)
        dao.deleteBypassUsageSessionsOlderThan(threshold)
    }
}

internal val DiagnosticsDatabaseMigration3To4 =
    object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE bypass_usage_sessions ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE bypass_usage_sessions ADD COLUMN connectionState TEXT NOT NULL DEFAULT 'Running'")
            database.execSQL("ALTER TABLE bypass_usage_sessions ADD COLUMN health TEXT NOT NULL DEFAULT 'idle'")
            database.execSQL("ALTER TABLE bypass_usage_sessions ADD COLUMN publicIp TEXT")
            database.execSQL("ALTER TABLE bypass_usage_sessions ADD COLUMN failureMessage TEXT")
            database.execSQL(
                """
                UPDATE bypass_usage_sessions
                SET updatedAt = COALESCE(finishedAt, startedAt),
                    connectionState = CASE
                        WHEN finishedAt IS NULL THEN 'Running'
                        ELSE 'Stopped'
                    END,
                    health = 'idle'
                """.trimIndent(),
            )

            database.execSQL("ALTER TABLE network_snapshots ADD COLUMN connectionSessionId TEXT")
            database.execSQL("ALTER TABLE diagnostic_context_snapshots ADD COLUMN connectionSessionId TEXT")
            database.execSQL("ALTER TABLE telemetry_samples ADD COLUMN connectionSessionId TEXT")
            database.execSQL("ALTER TABLE native_session_events ADD COLUMN connectionSessionId TEXT")

            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_network_snapshots_sessionId_capturedAt
                ON network_snapshots(sessionId, capturedAt)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_network_snapshots_connectionSessionId_capturedAt
                ON network_snapshots(connectionSessionId, capturedAt)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_network_snapshots_capturedAt
                ON network_snapshots(capturedAt)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_diagnostic_context_snapshots_sessionId_capturedAt
                ON diagnostic_context_snapshots(sessionId, capturedAt)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_diagnostic_context_snapshots_connectionSessionId_capturedAt
                ON diagnostic_context_snapshots(connectionSessionId, capturedAt)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_diagnostic_context_snapshots_capturedAt
                ON diagnostic_context_snapshots(capturedAt)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_telemetry_samples_sessionId_createdAt
                ON telemetry_samples(sessionId, createdAt)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_telemetry_samples_connectionSessionId_createdAt
                ON telemetry_samples(connectionSessionId, createdAt)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_telemetry_samples_createdAt
                ON telemetry_samples(createdAt)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_native_session_events_sessionId_createdAt
                ON native_session_events(sessionId, createdAt)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_native_session_events_connectionSessionId_createdAt
                ON native_session_events(connectionSessionId, createdAt)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_native_session_events_createdAt
                ON native_session_events(createdAt)
                """.trimIndent(),
            )
        }
    }

internal val DiagnosticsDatabaseMigration4To5 =
    object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE telemetry_samples ADD COLUMN resolverId TEXT")
            database.execSQL("ALTER TABLE telemetry_samples ADD COLUMN resolverProtocol TEXT")
            database.execSQL("ALTER TABLE telemetry_samples ADD COLUMN resolverEndpoint TEXT")
            database.execSQL("ALTER TABLE telemetry_samples ADD COLUMN resolverLatencyMs INTEGER")
            database.execSQL("ALTER TABLE telemetry_samples ADD COLUMN dnsFailuresTotal INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE telemetry_samples ADD COLUMN resolverFallbackActive INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE telemetry_samples ADD COLUMN resolverFallbackReason TEXT")
            database.execSQL("ALTER TABLE telemetry_samples ADD COLUMN networkHandoverClass TEXT")
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
        ).addMigrations(DiagnosticsDatabaseMigration3To4, DiagnosticsDatabaseMigration4To5).build()

    @Provides
    @Singleton
    fun provideDiagnosticsDao(database: DiagnosticsDatabase): DiagnosticsDao = database.diagnosticsDao()
}
