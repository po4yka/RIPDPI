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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import javax.inject.Singleton

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
    val failureClass: String? = null,
    val telemetryNetworkFingerprintHash: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val proxyRttBand: String = "unknown",
    val resolverRttBand: String = "unknown",
    val proxyRouteRetryCount: Long = 0,
    val tunnelRecoveryRetryCount: Long = 0,
    val resolverId: String? = null,
    val resolverProtocol: String? = null,
    val resolverEndpoint: String? = null,
    val resolverLatencyMs: Long? = null,
    val dnsFailuresTotal: Long = 0,
    val resolverFallbackActive: Boolean = false,
    val resolverFallbackReason: String? = null,
    val networkHandoverClass: String? = null,
    val lastFailureClass: String? = null,
    val lastFallbackAction: String? = null,
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
    val failureClass: String? = null,
    val telemetryNetworkFingerprintHash: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val proxyRttBand: String = "unknown",
    val resolverRttBand: String = "unknown",
    val proxyRouteRetryCount: Long = 0,
    val tunnelRecoveryRetryCount: Long = 0,
    val txBytes: Long,
    val rxBytes: Long,
    val totalErrors: Long,
    val routeChanges: Long,
    val restartCount: Int,
    val endedReason: String?,
    val failureMessage: String? = null,
)

@Entity(
    tableName = "remembered_network_policies",
    indices = [
        Index(
            name = "index_remembered_network_policies_fingerprintHash_mode",
            value = ["fingerprintHash", "mode"],
            unique = true,
        ),
        Index(
            name = "index_remembered_network_policies_updatedAt",
            value = ["updatedAt"],
        ),
        Index(
            name = "index_remembered_network_policies_mode_status_suppressedUntil",
            value = ["mode", "status", "suppressedUntil"],
        ),
    ],
)
@Serializable
data class RememberedNetworkPolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fingerprintHash: String,
    val mode: String,
    val summaryJson: String,
    val proxyConfigJson: String,
    val vpnDnsPolicyJson: String? = null,
    val strategySignatureJson: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val source: String,
    val status: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailureCount: Int = 0,
    val firstObservedAt: Long,
    val lastValidatedAt: Long? = null,
    val lastAppliedAt: Long? = null,
    val suppressedUntil: Long? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "network_dns_path_preferences",
    indices = [
        Index(
            name = "index_network_dns_path_preferences_fingerprintHash",
            value = ["fingerprintHash"],
            unique = true,
        ),
        Index(
            name = "index_network_dns_path_preferences_updatedAt",
            value = ["updatedAt"],
        ),
    ],
)
@Serializable
data class NetworkDnsPathPreferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fingerprintHash: String,
    val summaryJson: String,
    val pathJson: String,
    val updatedAt: Long,
)

@Suppress("TooManyFunctions")
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

    @Query("SELECT * FROM remembered_network_policies ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRememberedNetworkPolicies(limit: Int = 64): Flow<List<RememberedNetworkPolicyEntity>>

    @Query(
        """
        SELECT * FROM remembered_network_policies
        WHERE fingerprintHash = :fingerprintHash AND mode = :mode
        LIMIT 1
        """,
    )
    suspend fun getRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ): RememberedNetworkPolicyEntity?

    @Query(
        """
        SELECT * FROM remembered_network_policies
        WHERE fingerprintHash = :fingerprintHash
            AND mode = :mode
            AND status = :validatedStatus
            AND (suppressedUntil IS NULL OR suppressedUntil <= :now)
        ORDER BY COALESCE(lastValidatedAt, 0) DESC, updatedAt DESC
        LIMIT 1
        """,
    )
    suspend fun findValidatedRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
        validatedStatus: String,
        now: Long,
    ): RememberedNetworkPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRememberedNetworkPolicy(policy: RememberedNetworkPolicyEntity): Long

    @Query("DELETE FROM remembered_network_policies")
    suspend fun clearRememberedNetworkPolicies()

    @Query("DELETE FROM remembered_network_policies WHERE updatedAt < :threshold")
    suspend fun deleteRememberedNetworkPoliciesOlderThan(threshold: Long)

    @Query(
        """
        DELETE FROM remembered_network_policies
        WHERE id NOT IN (
            SELECT id FROM remembered_network_policies
            ORDER BY updatedAt DESC
            LIMIT :retainCount
        )
        """,
    )
    suspend fun trimRememberedNetworkPoliciesToCount(retainCount: Int)

    @Query(
        """
        SELECT * FROM network_dns_path_preferences
        WHERE fingerprintHash = :fingerprintHash
        LIMIT 1
        """,
    )
    suspend fun getNetworkDnsPathPreference(fingerprintHash: String): NetworkDnsPathPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetworkDnsPathPreference(preference: NetworkDnsPathPreferenceEntity): Long

    @Query("DELETE FROM network_dns_path_preferences WHERE updatedAt < :threshold")
    suspend fun deleteNetworkDnsPathPreferencesOlderThan(threshold: Long)

    @Query(
        """
        DELETE FROM network_dns_path_preferences
        WHERE id NOT IN (
            SELECT id FROM network_dns_path_preferences
            ORDER BY updatedAt DESC
            LIMIT :retainCount
        )
        """,
    )
    suspend fun trimNetworkDnsPathPreferencesToCount(retainCount: Int)

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
        RememberedNetworkPolicyEntity::class,
        NetworkDnsPathPreferenceEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class DiagnosticsDatabase : RoomDatabase() {
    abstract fun diagnosticsDao(): DiagnosticsDao
}

val DiagnosticsMigration1To2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM remembered_network_policies")
        }
    }

@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsDatabaseModule {
    @Provides
    @Singleton
    fun provideDiagnosticsDatabase(
        @ApplicationContext context: Context,
    ): DiagnosticsDatabase =
        Room
            .databaseBuilder(
                context,
                DiagnosticsDatabase::class.java,
                "diagnostics.db",
            ).addMigrations(DiagnosticsMigration1To2)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()

    @Provides
    @Singleton
    fun provideDiagnosticsDao(database: DiagnosticsDatabase): DiagnosticsDao = database.diagnosticsDao()
}
