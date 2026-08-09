package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Dao
interface DiagnosticsDao :
    DiagnosticsProfileDao,
    DiagnosticsScanDao,
    DiagnosticsSnapshotDao,
    DiagnosticsTelemetryDao,
    DiagnosticsNativeEventDao,
    DiagnosticsExportDao,
    DiagnosticsBypassUsageDao,
    DiagnosticsRememberedPolicyDao,
    DiagnosticsNetworkPreferenceDao,
    DiagnosticsDurableStateDao,
    DiagnosticsRetentionDao {
    @Transaction
    suspend fun persistFailureArtifacts(
        usageSession: BypassUsageSessionEntity,
        snapshot: NetworkSnapshotEntity?,
        context: DiagnosticContextEntity?,
        telemetry: TelemetrySampleEntity,
        event: NativeSessionEventEntity,
    ) {
        upsertBypassUsageSession(usageSession)
        snapshot?.let { upsertNetworkSnapshot(it) }
        context?.let { upsertContextSnapshot(it) }
        insertTelemetrySample(telemetry)
        insertNativeSessionEvent(event)
    }
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
        NetworkDnsBlockedPathEntity::class,
        NetworkEdgePreferenceEntity::class,
        DiagnosticsDurableStateEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class DiagnosticsDatabase : RoomDatabase() {
    abstract fun diagnosticsDao(): DiagnosticsDao
}
