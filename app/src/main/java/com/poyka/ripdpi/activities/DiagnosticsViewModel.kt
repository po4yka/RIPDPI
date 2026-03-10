package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val activeProfile: DiagnosticProfileEntity? = null,
    val activeScanMessage: String? = null,
    val activeScanRunning: Boolean = false,
    val sessions: List<ScanSessionEntity> = emptyList(),
    val snapshots: List<NetworkSnapshotEntity> = emptyList(),
    val telemetry: List<TelemetrySampleEntity> = emptyList(),
    val events: List<NativeSessionEventEntity> = emptyList(),
    val exports: List<ExportRecordEntity> = emptyList(),
)

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        private val diagnosticsManager: DiagnosticsManager,
    ) : ViewModel() {
    val uiState: StateFlow<DiagnosticsUiState> =
        combine(
            diagnosticsManager.profiles,
            diagnosticsManager.activeScanProgress,
            diagnosticsManager.sessions,
            diagnosticsManager.snapshots,
            diagnosticsManager.telemetry,
            diagnosticsManager.nativeEvents,
            diagnosticsManager.exports,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val profiles = values[0] as List<DiagnosticProfileEntity>
            val progress = values[1] as ScanProgress?
            val sessions = values[2] as List<ScanSessionEntity>
            val snapshots = values[3] as List<NetworkSnapshotEntity>
            val telemetry = values[4] as List<TelemetrySampleEntity>
            val events = values[5] as List<NativeSessionEventEntity>
            val exports = values[6] as List<ExportRecordEntity>
            DiagnosticsUiState(
                activeProfile = profiles.firstOrNull(),
                activeScanMessage = progress?.message,
                activeScanRunning = progress != null,
                sessions = sessions,
                snapshots = snapshots,
                telemetry = telemetry,
                events = events,
                exports = exports,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DiagnosticsUiState(),
        )

    init {
        viewModelScope.launch {
            diagnosticsManager.initialize()
        }
    }

    fun startRawScan() {
        viewModelScope.launch {
            diagnosticsManager.startScan(ScanPathMode.RAW_PATH)
        }
    }

    fun startInPathScan() {
        viewModelScope.launch {
            diagnosticsManager.startScan(ScanPathMode.IN_PATH)
        }
    }

    fun cancelScan() {
        viewModelScope.launch {
            diagnosticsManager.cancelActiveScan()
        }
    }

    fun exportLatest(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val export = diagnosticsManager.exportBundle(uiState.value.sessions.firstOrNull()?.id)
            onReady(export.absolutePath)
        }
    }
}
