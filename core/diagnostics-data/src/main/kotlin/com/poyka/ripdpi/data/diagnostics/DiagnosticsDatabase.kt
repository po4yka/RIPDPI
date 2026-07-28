package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Database
import androidx.room.RoomDatabase

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
    DiagnosticsRetentionDao

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
    version = 8,
    exportSchema = true,
)
abstract class DiagnosticsDatabase : RoomDatabase() {
    abstract fun diagnosticsDao(): DiagnosticsDao
}
