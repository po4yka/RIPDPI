package com.poyka.ripdpi.ui.screens.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.ServiceStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class SubscriptionFailoverUiState(
    val summary: String = "No subscription servers observed yet",
    val lastCheck: String = "last check unknown",
    val activeServerLabel: String = "No active server",
    val servers: List<SubscriptionServerUiState> = emptyList(),
    val events: List<SubscriptionFailoverEventUiState> = emptyList(),
) {
    val hasServers: Boolean
        get() = servers.isNotEmpty()
}

data class SubscriptionServerUiState(
    val id: String,
    val name: String,
    val endpoint: String,
    val status: SubscriptionServerStatus,
    val positionLabel: String,
    val detail: String,
)

enum class SubscriptionServerStatus(
    val label: String,
) {
    Up("up"),
    Checking("checking"),
    Down("down"),
    Unknown("unknown"),
}

data class SubscriptionFailoverEventUiState(
    val message: String,
    val timeLabel: String,
)

@HiltViewModel
class SubscriptionFailoverViewModel
    @Inject
    constructor(
        serviceStateStore: ServiceStateStore,
        private val relayProfileStore: RelayProfileStore,
    ) : ViewModel() {
        private val profiles = MutableStateFlow<List<RelayProfileRecord>>(emptyList())

        val uiState: StateFlow<SubscriptionFailoverUiState> =
            combine(profiles, serviceStateStore.telemetry) { relayProfiles, telemetry ->
                SubscriptionFailoverMapper.toUiState(
                    profiles = relayProfiles,
                    snapshot = telemetry.relayTelemetry,
                    nowMillis = System.currentTimeMillis(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SubscriptionFailoverUiState(),
            )

        init {
            viewModelScope.launch {
                profiles.value = relayProfileStore.list().filter { it.server.isNotBlank() }
            }
        }
    }

internal object SubscriptionFailoverMapper {
    fun toUiState(
        profiles: List<RelayProfileRecord>,
        snapshot: NativeRuntimeSnapshot,
        nowMillis: Long,
    ): SubscriptionFailoverUiState {
        val orderedProfiles = profiles.sortedBy { it.id }
        if (orderedProfiles.isEmpty()) {
            return SubscriptionFailoverUiState()
        }
        val activeIndex =
            orderedProfiles
                .indexOfFirst { it.id == snapshot.profileId }
                .takeIf { it >= 0 } ?: 0
        val activeProfile = orderedProfiles[activeIndex]
        val activeStatus = snapshot.toServerStatus()
        val capturedAt =
            snapshot.capturedAt.takeIf { it > 0L }
                ?: snapshot.nativeEvents.maxOfOrNull { it.createdAt }
        val lastCheck =
            lastCheckLabel(
                capturedAt = capturedAt,
                nowMillis = nowMillis,
            )
        val events = failoverEvents(snapshot, activeProfile, activeIndex)
        val recentEvent = events.firstOrNull()?.message ?: "no switchovers observed"
        val activePosition = activeIndex + 1
        val summary =
            "server $activePosition/${orderedProfiles.size} ${activeStatus.label} · " +
                "$recentEvent · $lastCheck"
        return SubscriptionFailoverUiState(
            summary = summary,
            lastCheck = lastCheck,
            activeServerLabel = "Server $activePosition: ${activeProfile.displayLabel()}",
            servers =
                orderedProfiles.mapIndexed { index, profile ->
                    val isActive = index == activeIndex
                    profile.toServerUiState(
                        index = index,
                        total = orderedProfiles.size,
                        status = if (isActive) activeStatus else SubscriptionServerStatus.Unknown,
                        isActive = isActive,
                    )
                },
            events = events,
        )
    }

    private fun RelayProfileRecord.toServerUiState(
        index: Int,
        total: Int,
        status: SubscriptionServerStatus,
        isActive: Boolean,
    ): SubscriptionServerUiState =
        SubscriptionServerUiState(
            id = id,
            name = displayLabel(),
            endpoint = "$server:$serverPort",
            status = status,
            positionLabel = "server ${index + 1}/$total",
            detail =
                if (isActive) {
                    "current server"
                } else {
                    "available if the app switches"
                },
        )

    private fun NativeRuntimeSnapshot.toServerStatus(): SubscriptionServerStatus {
        val normalizedHealth = health.lowercase(Locale.US)
        val normalizedState = state.lowercase(Locale.US)
        return when {
            normalizedHealth in setOf("healthy", "ok", "running", "active") -> SubscriptionServerStatus.Up
            normalizedState in setOf("running", "active") && totalErrors == 0L -> SubscriptionServerStatus.Up
            normalizedHealth in setOf("starting", "checking", "idle") -> SubscriptionServerStatus.Checking
            normalizedHealth in setOf("degraded", "error", "failed", "unhealthy") -> SubscriptionServerStatus.Down
            lastHandshakeError != null || lastError != null || lastFailureClass != null -> SubscriptionServerStatus.Down
            else -> SubscriptionServerStatus.Unknown
        }
    }

    private fun failoverEvents(
        snapshot: NativeRuntimeSnapshot,
        activeProfile: RelayProfileRecord,
        activeIndex: Int,
    ): List<SubscriptionFailoverEventUiState> =
        buildList {
            if (activeIndex > 0) {
                add(
                    SubscriptionFailoverEventUiState(
                        message = "switched to backup",
                        timeLabel = timeLabel(snapshot.capturedAt),
                    ),
                )
            }
            val isUsingFallback =
                snapshot.resolverFallbackActive ||
                    !snapshot.fallbackMode.isNullOrBlank() ||
                    !snapshot.lastFallbackAction.isNullOrBlank()
            if (isUsingFallback) {
                add(
                    SubscriptionFailoverEventUiState(
                        message = fallbackMessage(snapshot, activeProfile),
                        timeLabel = timeLabel(snapshot.capturedAt),
                    ),
                )
            }
            snapshot.nativeEvents
                .filter(::isFailoverEvent)
                .sortedByDescending(NativeRuntimeEvent::createdAt)
                .take(MaxNativeEvents)
                .mapTo(this) { event ->
                    SubscriptionFailoverEventUiState(
                        message = event.message.ifBlank { "server switch recorded" },
                        timeLabel = timeLabel(event.createdAt),
                    )
                }
        }.distinctBy { it.message to it.timeLabel }

    private fun isFailoverEvent(event: NativeRuntimeEvent): Boolean {
        val text =
            listOfNotNull(event.kind, event.subsystem, event.message)
                .joinToString(" ")
                .lowercase(Locale.US)
        return FailoverEventMarkers.any(text::contains)
    }

    private fun fallbackMessage(
        snapshot: NativeRuntimeSnapshot,
        activeProfile: RelayProfileRecord,
    ): String {
        val reason = snapshot.resolverFallbackReason ?: snapshot.lastRetryReason ?: snapshot.lastFallbackAction
        return if (reason.isNullOrBlank()) {
            "using backup path for ${activeProfile.displayLabel()}"
        } else {
            "using backup path: $reason"
        }
    }

    private fun RelayProfileRecord.displayLabel(): String =
        serverName.takeIf(String::isNotBlank)
            ?: operatorName.takeIf(String::isNotBlank)
            ?: server.takeIf(String::isNotBlank)
            ?: id

    private fun lastCheckLabel(
        capturedAt: Long?,
        nowMillis: Long,
    ): String {
        if (capturedAt == null || capturedAt <= 0L) return "last check unknown"
        val deltaSeconds =
            ((nowMillis - capturedAt).coerceAtLeast(0L) / MillisPerSecond)
                .coerceAtLeast(0L)
        return when {
            deltaSeconds < SecondsPerMinute -> "last check ${deltaSeconds}s ago"
            deltaSeconds < SecondsPerHour -> "last check ${deltaSeconds / SecondsPerMinute}m ago"
            else -> "last check at ${timeLabel(capturedAt)}"
        }
    }

    private fun timeLabel(epochMillis: Long): String =
        if (epochMillis <= 0L) {
            "time unknown"
        } else {
            SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMillis))
        }

    private const val MaxNativeEvents = 4
    private const val MillisPerSecond = 1_000L
    private const val SecondsPerMinute = 60L
    private const val SecondsPerHour = 3_600L

    private val FailoverEventMarkers =
        listOf(
            "failover",
            "fallback",
            "backup",
            "switch",
            "switched",
            "route_change",
            "route change",
        )
}
