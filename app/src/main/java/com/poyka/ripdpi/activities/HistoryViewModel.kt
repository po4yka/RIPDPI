package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.ProbeDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class HistorySection {
    Connections,
    Diagnostics,
    Events,
}

data class HistoryConnectionFiltersUiModel(
    val mode: String? = null,
    val status: String? = null,
    val query: String = "",
)

data class HistoryConnectionRowUiModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val serviceMode: String,
    val connectionState: String,
    val networkType: String,
    val startedAtLabel: String,
    val summary: String,
    val metrics: List<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
)

data class HistoryConnectionDetailUiModel(
    val session: HistoryConnectionRowUiModel,
    val highlights: List<DiagnosticsMetricUiModel>,
    val contextGroups: List<DiagnosticsContextGroupUiModel>,
    val snapshots: List<DiagnosticsNetworkSnapshotUiModel>,
    val events: List<DiagnosticsEventUiModel>,
)

data class HistoryConnectionsUiModel(
    val filters: HistoryConnectionFiltersUiModel = HistoryConnectionFiltersUiModel(),
    val sessions: List<HistoryConnectionRowUiModel> = emptyList(),
    val modes: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val focusedSessionId: String? = null,
)

data class HistoryUiState(
    val selectedSection: HistorySection = HistorySection.Connections,
    val connections: HistoryConnectionsUiModel = HistoryConnectionsUiModel(),
    val diagnostics: DiagnosticsSessionsUiModel = DiagnosticsSessionsUiModel(),
    val events: DiagnosticsEventsUiModel = DiagnosticsEventsUiModel(),
    val selectedConnectionDetail: HistoryConnectionDetailUiModel? = null,
    val selectedDiagnosticsDetail: DiagnosticsSessionDetailUiModel? = null,
    val selectedEvent: DiagnosticsEventUiModel? = null,
)

sealed interface HistoryEffect

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        private val historyRepository: DiagnosticsHistoryRepository,
        private val diagnosticsManager: DiagnosticsManager,
    ) : ViewModel() {
        private val json = Json { ignoreUnknownKeys = true }
        private val timestampFormatter = SimpleDateFormat("MMM d, HH:mm", Locale.US)

        private val selectedSectionRequest = MutableStateFlow(HistorySection.Connections)
        private val connectionModeFilter = MutableStateFlow<String?>(null)
        private val connectionStatusFilter = MutableStateFlow<String?>(null)
        private val connectionSearch = MutableStateFlow("")
        private val diagnosticsPathModeFilter = MutableStateFlow<String?>(null)
        private val diagnosticsStatusFilter = MutableStateFlow<String?>(null)
        private val diagnosticsSearch = MutableStateFlow("")
        private val eventSourceFilter = MutableStateFlow<String?>(null)
        private val eventSeverityFilter = MutableStateFlow<String?>(null)
        private val eventSearch = MutableStateFlow("")
        private val eventAutoScroll = MutableStateFlow(true)
        private val selectedConnectionDetail = MutableStateFlow<HistoryConnectionDetailUiModel?>(null)
        private val selectedDiagnosticsDetail = MutableStateFlow<DiagnosticsSessionDetailUiModel?>(null)
        private val selectedEventId = MutableStateFlow<String?>(null)

        private val _effects = Channel<HistoryEffect>(Channel.BUFFERED)
        val effects: Flow<HistoryEffect> = _effects.receiveAsFlow()

        val uiState: StateFlow<HistoryUiState> =
            combine(
                historyRepository.observeBypassUsageSessions(limit = 120),
                historyRepository.observeRecentScanSessions(limit = 120),
                historyRepository.observeNativeEvents(limit = 250),
                selectedSectionRequest,
                connectionModeFilter,
                connectionStatusFilter,
                connectionSearch,
                diagnosticsPathModeFilter,
                diagnosticsStatusFilter,
                diagnosticsSearch,
                eventSourceFilter,
                eventSeverityFilter,
                eventSearch,
                eventAutoScroll,
                selectedConnectionDetail,
                selectedDiagnosticsDetail,
                selectedEventId,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val connectionSessions = values[0] as List<BypassUsageSessionEntity>
                val scanSessions = values[1] as List<ScanSessionEntity>
                val nativeEvents = values[2] as List<NativeSessionEventEntity>
                val selectedSection = values[3] as HistorySection
                val connectionMode = values[4] as String?
                val connectionStatus = values[5] as String?
                val connectionQuery = values[6] as String
                val diagnosticsPath = values[7] as String?
                val diagnosticsStatus = values[8] as String?
                val diagnosticsQuery = values[9] as String
                val eventSource = values[10] as String?
                val eventSeverity = values[11] as String?
                val eventQuery = values[12] as String
                val autoScroll = values[13] as Boolean
                val connectionDetail = values[14] as HistoryConnectionDetailUiModel?
                val diagnosticsDetail = values[15] as DiagnosticsSessionDetailUiModel?
                val selectedEventId = values[16] as String?

                buildUiState(
                    connectionSessions = connectionSessions,
                    scanSessions = scanSessions,
                    nativeEvents = nativeEvents,
                    selectedSection = selectedSection,
                    connectionMode = connectionMode,
                    connectionStatus = connectionStatus,
                    connectionQuery = connectionQuery,
                    diagnosticsPath = diagnosticsPath,
                    diagnosticsStatus = diagnosticsStatus,
                    diagnosticsQuery = diagnosticsQuery,
                    eventSource = eventSource,
                    eventSeverity = eventSeverity,
                    eventQuery = eventQuery,
                    autoScroll = autoScroll,
                    connectionDetail = connectionDetail,
                    diagnosticsDetail = diagnosticsDetail,
                    selectedEventId = selectedEventId,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HistoryUiState(),
            )

        init {
            viewModelScope.launch {
                diagnosticsManager.initialize()
            }
        }

        fun selectSection(section: HistorySection) {
            selectedSectionRequest.value = section
        }

        fun setConnectionModeFilter(mode: String?) {
            connectionModeFilter.value = toggleValue(connectionModeFilter.value, mode)
        }

        fun setConnectionStatusFilter(status: String?) {
            connectionStatusFilter.value = toggleValue(connectionStatusFilter.value, status)
        }

        fun setConnectionSearch(query: String) {
            connectionSearch.value = query
        }

        fun setDiagnosticsPathModeFilter(pathMode: String?) {
            diagnosticsPathModeFilter.value = toggleValue(diagnosticsPathModeFilter.value, pathMode)
        }

        fun setDiagnosticsStatusFilter(status: String?) {
            diagnosticsStatusFilter.value = toggleValue(diagnosticsStatusFilter.value, status)
        }

        fun setDiagnosticsSearch(query: String) {
            diagnosticsSearch.value = query
        }

        fun toggleEventFilter(
            source: String? = null,
            severity: String? = null,
        ) {
            if (source != null) {
                eventSourceFilter.value = toggleValue(eventSourceFilter.value, source)
            }
            if (severity != null) {
                eventSeverityFilter.value = toggleValue(eventSeverityFilter.value, severity)
            }
        }

        fun setEventSearch(query: String) {
            eventSearch.value = query
        }

        fun setEventAutoScroll(enabled: Boolean) {
            eventAutoScroll.value = enabled
        }

        fun selectConnection(sessionId: String) {
            viewModelScope.launch {
                val session = historyRepository.getBypassUsageSession(sessionId) ?: return@launch
                val snapshots = historyRepository.observeConnectionSnapshots(sessionId, limit = 40).first()
                val contexts = historyRepository.observeConnectionContexts(sessionId, limit = 20).first()
                val telemetry = historyRepository.observeConnectionTelemetry(sessionId, limit = 60).first()
                val events = historyRepository.observeConnectionNativeEvents(sessionId, limit = 80).first()
                selectedConnectionDetail.value =
                    session.toConnectionDetail(
                        snapshots = snapshots,
                        contexts = contexts,
                        telemetry = telemetry,
                        events = events,
                    )
            }
        }

        fun dismissConnectionDetail() {
            selectedConnectionDetail.value = null
        }

        fun selectDiagnosticsSession(sessionId: String) {
            viewModelScope.launch {
                selectedDiagnosticsDetail.value = diagnosticsManager.loadSessionDetail(sessionId).toUiModel()
            }
        }

        fun dismissDiagnosticsDetail() {
            selectedDiagnosticsDetail.value = null
        }

        fun selectEvent(eventId: String) {
            selectedEventId.value = eventId
        }

        fun dismissEventDetail() {
            selectedEventId.value = null
        }

    private fun buildUiState(
        connectionSessions: List<BypassUsageSessionEntity>,
        scanSessions: List<ScanSessionEntity>,
        nativeEvents: List<NativeSessionEventEntity>,
        selectedSection: HistorySection,
        connectionMode: String?,
        connectionStatus: String?,
        connectionQuery: String,
        diagnosticsPath: String?,
        diagnosticsStatus: String?,
        diagnosticsQuery: String,
        eventSource: String?,
        eventSeverity: String?,
        eventQuery: String,
        autoScroll: Boolean,
        connectionDetail: HistoryConnectionDetailUiModel?,
        diagnosticsDetail: DiagnosticsSessionDetailUiModel?,
        selectedEventId: String?,
    ): HistoryUiState {
        val connectionRows = connectionSessions.map(::toConnectionRowUiModel)
        val filteredConnections =
            connectionRows.filter { session ->
                (connectionMode == null || session.serviceMode == connectionMode) &&
                    (connectionStatus == null || session.connectionState.equals(connectionStatus, ignoreCase = true)) &&
                    session.matchesConnectionQuery(connectionQuery)
            }

        val diagnosticsRows = scanSessions.map(::toScanRowUiModel)
        val filteredDiagnostics =
            diagnosticsRows.filter { session ->
                (diagnosticsPath == null || session.pathMode == diagnosticsPath) &&
                    (diagnosticsStatus == null || session.status.equals(diagnosticsStatus, ignoreCase = true)) &&
                    session.matchesDiagnosticsQuery(diagnosticsQuery)
            }

        val eventModels = nativeEvents.map(::toEventUiModel)
        val filteredEvents =
            eventModels.filter { event ->
                (eventSource == null || event.source.equals(eventSource, ignoreCase = true)) &&
                    (eventSeverity == null || event.severity.equals(eventSeverity, ignoreCase = true)) &&
                    event.matchesEventQuery(eventQuery)
            }

        return HistoryUiState(
            selectedSection = selectedSection,
            connections =
                HistoryConnectionsUiModel(
                    filters =
                        HistoryConnectionFiltersUiModel(
                            mode = connectionMode,
                            status = connectionStatus,
                            query = connectionQuery,
                        ),
                    sessions = filteredConnections,
                    modes = connectionRows.map { it.serviceMode }.distinct(),
                    statuses = connectionRows.map { it.connectionState }.distinct(),
                    focusedSessionId = connectionDetail?.session?.id,
                ),
            diagnostics =
                DiagnosticsSessionsUiModel(
                    filters =
                        DiagnosticsSessionFiltersUiModel(
                            pathMode = diagnosticsPath,
                            status = diagnosticsStatus,
                            query = diagnosticsQuery,
                        ),
                    sessions = filteredDiagnostics,
                    pathModes = diagnosticsRows.map { it.pathMode }.distinct(),
                    statuses = diagnosticsRows.map { it.status }.distinct(),
                    focusedSessionId = diagnosticsDetail?.session?.id,
                ),
            events =
                DiagnosticsEventsUiModel(
                    filters =
                        DiagnosticsEventFiltersUiModel(
                            source = eventSource,
                            severity = eventSeverity,
                            search = eventQuery,
                            autoScroll = autoScroll,
                        ),
                    events = filteredEvents,
                    availableSources = eventModels.map { it.source }.distinct(),
                    availableSeverities = eventModels.map { it.severity }.distinct(),
                    focusedEventId = selectedEventId,
                ),
            selectedConnectionDetail = connectionDetail,
            selectedDiagnosticsDetail = diagnosticsDetail,
            selectedEvent = eventModels.firstOrNull { it.id == selectedEventId },
        )
    }

    private fun BypassUsageSessionEntity.toConnectionDetail(
        snapshots: List<NetworkSnapshotEntity>,
        contexts: List<DiagnosticContextEntity>,
        telemetry: List<TelemetrySampleEntity>,
        events: List<NativeSessionEventEntity>,
    ): HistoryConnectionDetailUiModel {
        val row = toConnectionRowUiModel(this)
        return HistoryConnectionDetailUiModel(
            session = row,
            highlights =
                listOf(
                    DiagnosticsMetricUiModel("Network", networkType, DiagnosticsTone.Info),
                    DiagnosticsMetricUiModel("Health", health.replaceFirstChar { it.uppercase() }, toneForConnection(this)),
                    DiagnosticsMetricUiModel("TX", formatBytes(txBytes), DiagnosticsTone.Info),
                    DiagnosticsMetricUiModel("RX", formatBytes(rxBytes), DiagnosticsTone.Positive),
                    DiagnosticsMetricUiModel("Errors", totalErrors.toString(), if (totalErrors > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral),
                    DiagnosticsMetricUiModel("Route changes", routeChanges.toString(), DiagnosticsTone.Info),
                ),
            contextGroups =
                contexts
                    .mapNotNull { context -> context.decodeContext()?.toContextGroups() }
                    .flatten()
                    .distinctBy { it.title + it.fields.joinToString { field -> "${field.label}:${field.value}" } },
            snapshots = snapshots.mapNotNull(::toSnapshotUiModel),
            events = events.map(::toEventUiModel),
        )
    }

    private fun DiagnosticSessionDetail.toUiModel(): DiagnosticsSessionDetailUiModel =
        DiagnosticsSessionDetailUiModel(
            session = toScanRowUiModel(session),
            probeGroups =
                results
                    .mapIndexed { index, result -> result.toProbeUiModel(index) }
                    .groupBy { it.probeType }
                    .map { (title, items) ->
                        DiagnosticsProbeGroupUiModel(
                            title = title,
                            items = items,
                        )
                    },
            snapshots = snapshots.mapNotNull(::toSnapshotUiModel),
            events = events.map(::toEventUiModel),
            contextGroups = context?.decodeContext()?.toContextGroups().orEmpty(),
            hasSensitiveDetails = false,
            sensitiveDetailsVisible = false,
        )

    private fun toConnectionRowUiModel(session: BypassUsageSessionEntity): HistoryConnectionRowUiModel {
        val durationMs = (session.finishedAt ?: session.updatedAt).coerceAtLeast(session.startedAt) - session.startedAt
        val summary =
            session.failureMessage
                ?: session.endedReason
                ?: "${session.serviceMode} on ${session.networkType}"
        return HistoryConnectionRowUiModel(
            id = session.id,
            title = "${session.serviceMode} ${session.connectionState.lowercase(Locale.US)}",
            subtitle = "${session.networkType} · ${formatTimestamp(session.startedAt)}",
            serviceMode = session.serviceMode,
            connectionState = session.connectionState,
            networkType = session.networkType,
            startedAtLabel = formatTimestamp(session.startedAt),
            summary = summary,
            metrics =
                listOf(
                    DiagnosticsMetricUiModel("Duration", formatDurationMs(durationMs)),
                    DiagnosticsMetricUiModel("TX", formatBytes(session.txBytes), DiagnosticsTone.Info),
                    DiagnosticsMetricUiModel("RX", formatBytes(session.rxBytes), DiagnosticsTone.Positive),
                    DiagnosticsMetricUiModel("Errors", session.totalErrors.toString(), if (session.totalErrors > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral),
                ),
            tone = toneForConnection(session),
        )
    }

    private fun toScanRowUiModel(session: ScanSessionEntity): DiagnosticsSessionRowUiModel {
        val report = decodeReport(session.reportJson)
        return DiagnosticsSessionRowUiModel(
            id = session.id,
            profileId = session.profileId,
            title = session.summary,
            subtitle = "${session.pathMode} · ${session.serviceMode ?: "Unknown"} · ${formatTimestamp(session.startedAt)}",
            pathMode = session.pathMode,
            serviceMode = session.serviceMode ?: "Unknown",
            status = session.status,
            startedAtLabel = formatTimestamp(session.startedAt),
            summary = session.summary,
            metrics =
                buildList {
                    add(DiagnosticsMetricUiModel("Path", session.pathMode))
                    add(DiagnosticsMetricUiModel("Mode", session.serviceMode ?: "Unknown"))
                    report?.results?.size?.let { add(DiagnosticsMetricUiModel("Probes", it.toString())) }
                },
            tone = toneForOutcome(session.status),
        )
    }

    private fun ProbeResultEntity.toProbeUiModel(index: Int): DiagnosticsProbeResultUiModel =
        DiagnosticsProbeResultUiModel(
            id = "$sessionId-$index-$probeType-$target",
            probeType = probeType,
            target = target,
            outcome = outcome,
            tone = toneForOutcome(outcome),
            details = decodeProbeDetails(detailJson).map { DiagnosticsFieldUiModel(it.key, it.value) },
        )

    private fun toEventUiModel(event: NativeSessionEventEntity): DiagnosticsEventUiModel =
        DiagnosticsEventUiModel(
            id = event.id,
            source = event.source.replaceFirstChar { it.uppercase() },
            severity = event.level.uppercase(Locale.US),
            message = event.message,
            createdAtLabel = formatTimestamp(event.createdAt),
            tone = toneForOutcome(event.level),
        )

    private fun toSnapshotUiModel(snapshotEntity: NetworkSnapshotEntity): DiagnosticsNetworkSnapshotUiModel? {
        val snapshot =
            runCatching {
                json.decodeFromString(NetworkSnapshotModel.serializer(), snapshotEntity.payloadJson)
            }.getOrNull() ?: return null
        return DiagnosticsNetworkSnapshotUiModel(
            title = snapshotEntity.snapshotKind.replace('_', ' ').replaceFirstChar { it.uppercase() },
            subtitle = "${snapshot.transport} · ${formatTimestamp(snapshot.capturedAt)}",
            fields =
                listOf(
                    DiagnosticsFieldUiModel("DNS", snapshot.dnsServers.joinToString().ifBlank { "Unknown" }),
                    DiagnosticsFieldUiModel("Private DNS", snapshot.privateDnsMode),
                    DiagnosticsFieldUiModel("Public IP", snapshot.publicIp ?: "Unknown"),
                    DiagnosticsFieldUiModel("Validated", snapshot.networkValidated.toString()),
                    DiagnosticsFieldUiModel("Captive portal", snapshot.captivePortalDetected.toString()),
                ),
        )
    }

    private fun DiagnosticContextEntity.decodeContext(): DiagnosticContextModel? =
        runCatching { json.decodeFromString(DiagnosticContextModel.serializer(), payloadJson) }.getOrNull()

    private fun DiagnosticContextModel.toContextGroups(): List<DiagnosticsContextGroupUiModel> =
        listOf(
            DiagnosticsContextGroupUiModel(
                title = "Service",
                fields =
                    listOf(
                        DiagnosticsFieldUiModel("Status", service.serviceStatus),
                        DiagnosticsFieldUiModel("Mode", service.activeMode),
                        DiagnosticsFieldUiModel("Profile", service.selectedProfileName),
                        DiagnosticsFieldUiModel("Config source", service.configSource),
                        DiagnosticsFieldUiModel("Proxy", service.proxyEndpoint),
                        DiagnosticsFieldUiModel("Chain", service.chainSummary),
                        DiagnosticsFieldUiModel("Last native error", service.lastNativeErrorHeadline),
                    ),
            ),
            DiagnosticsContextGroupUiModel(
                title = "Environment",
                fields =
                    listOf(
                        DiagnosticsFieldUiModel("VPN permission", permissions.vpnPermissionState),
                        DiagnosticsFieldUiModel("Notifications", permissions.notificationPermissionState),
                        DiagnosticsFieldUiModel("Battery optimization", permissions.batteryOptimizationState),
                        DiagnosticsFieldUiModel("Data saver", permissions.dataSaverState),
                        DiagnosticsFieldUiModel("Power save", environment.powerSaveModeState),
                        DiagnosticsFieldUiModel("Metered", environment.networkMeteredState),
                        DiagnosticsFieldUiModel("Roaming", environment.roamingState),
                    ),
            ),
            DiagnosticsContextGroupUiModel(
                title = "Device",
                fields =
                    listOf(
                        DiagnosticsFieldUiModel("App", device.appVersionName),
                        DiagnosticsFieldUiModel("Device", "${device.manufacturer} ${device.model}"),
                        DiagnosticsFieldUiModel("Android", "${device.androidVersion} (API ${device.apiLevel})"),
                        DiagnosticsFieldUiModel("Locale", device.locale),
                    ),
            ),
        )

    private fun toneForConnection(session: BypassUsageSessionEntity): DiagnosticsTone =
        when {
            session.connectionState.equals("failed", ignoreCase = true) -> DiagnosticsTone.Negative
            session.health.equals("degraded", ignoreCase = true) -> DiagnosticsTone.Warning
            session.finishedAt == null -> DiagnosticsTone.Positive
            else -> DiagnosticsTone.Neutral
        }

    private fun toneForOutcome(value: String): DiagnosticsTone {
        val normalized = value.lowercase(Locale.US)
        return when {
            normalized.contains("ok") || normalized.contains("success") || normalized.contains("completed") -> DiagnosticsTone.Positive
            normalized.contains("warn") || normalized.contains("timeout") || normalized.contains("partial") || normalized.contains("degraded") || normalized.contains("running") -> DiagnosticsTone.Warning
            normalized.contains("error") || normalized.contains("failed") || normalized.contains("blocked") || normalized.contains("stopped") -> DiagnosticsTone.Negative
            normalized.contains("info") -> DiagnosticsTone.Info
            else -> DiagnosticsTone.Neutral
        }
    }

    private fun decodeReport(reportJson: String?): com.poyka.ripdpi.diagnostics.ScanReport? =
        reportJson?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                json.decodeFromString(com.poyka.ripdpi.diagnostics.ScanReport.serializer(), it)
            }.getOrNull()
        }

    private fun decodeProbeDetails(detailJson: String): List<ProbeDetail> =
        runCatching {
            json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson)
        }.getOrElse { emptyList() }

    private fun HistoryConnectionRowUiModel.matchesConnectionQuery(query: String): Boolean {
        if (query.isBlank()) {
            return true
        }
        val normalized = query.lowercase(Locale.US)
        return listOf(title, subtitle, summary, serviceMode, connectionState, networkType).any {
            it.lowercase(Locale.US).contains(normalized)
        }
    }

    private fun DiagnosticsSessionRowUiModel.matchesDiagnosticsQuery(query: String): Boolean {
        if (query.isBlank()) {
            return true
        }
        val normalized = query.lowercase(Locale.US)
        return listOf(title, subtitle, summary, pathMode, serviceMode, status).any {
            it.lowercase(Locale.US).contains(normalized)
        }
    }

    private fun DiagnosticsEventUiModel.matchesEventQuery(query: String): Boolean {
        if (query.isBlank()) {
            return true
        }
        val normalized = query.lowercase(Locale.US)
        return listOf(source, severity, message).any { it.lowercase(Locale.US).contains(normalized) }
    }

    private fun toggleValue(
        current: String?,
        next: String?,
    ): String? = if (current == next) null else next

    private fun formatTimestamp(timestamp: Long): String = timestampFormatter.format(Date(timestamp))

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000f)
            bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000f)
            bytes >= 1_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000f)
            else -> "$bytes B"
        }

    private fun formatDurationMs(durationMs: Long): String {
        val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> "${hours}h ${minutes}m"
            minutes > 0L -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
