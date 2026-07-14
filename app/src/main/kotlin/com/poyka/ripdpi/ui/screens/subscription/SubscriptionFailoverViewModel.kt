package com.poyka.ripdpi.ui.screens.subscription

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.SubscriptionClientSignal
import com.poyka.ripdpi.data.actionableSubscriptionSignals
import com.poyka.ripdpi.platform.StringResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
    val summary: String = "",
    val lastCheck: String = "",
    val activeServerLabel: String = "",
    val subscriptionAlert: SubscriptionAlertUiState? = null,
    val servers: ImmutableList<SubscriptionServerUiState> = persistentListOf(),
    val events: ImmutableList<SubscriptionFailoverEventUiState> = persistentListOf(),
) {
    val hasServers: Boolean
        get() = servers.isNotEmpty()
}

data class SubscriptionAlertUiState(
    val title: String,
    val message: String,
    val tone: SubscriptionAlertTone,
)

enum class SubscriptionAlertTone {
    ERROR,
    WARNING,
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
    @StringRes val labelRes: Int,
) {
    Up(R.string.subscription_failover_status_up),
    Checking(R.string.subscription_failover_status_checking),
    Down(R.string.subscription_failover_status_down),
    Unknown(R.string.subscription_failover_status_unknown),
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
        proxyGroupRepository: ProxyGroupRepository,
        private val relayProfileStore: RelayProfileStore,
        private val stringResolver: StringResolver,
    ) : ViewModel() {
        private val profiles = MutableStateFlow<List<RelayProfileRecord>>(emptyList())

        val uiState: StateFlow<SubscriptionFailoverUiState> =
            combine(
                profiles,
                serviceStateStore.telemetry,
                proxyGroupRepository.groups(),
            ) { relayProfiles, telemetry, groups ->
                SubscriptionFailoverMapper.toUiState(
                    profiles = relayProfiles,
                    groups = groups,
                    snapshot = telemetry.relayTelemetry,
                    nowMillis = System.currentTimeMillis(),
                    strings = stringResolver,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SubscriptionFailoverMapper.emptyUiState(stringResolver),
            )

        init {
            viewModelScope.launch {
                profiles.value = relayProfileStore.list().filter { it.server.isNotBlank() }
            }
        }
    }

internal object SubscriptionFailoverMapper {
    fun emptyUiState(strings: StringResolver): SubscriptionFailoverUiState =
        SubscriptionFailoverUiState(
            summary = strings.getString(R.string.subscription_failover_no_servers),
            lastCheck = strings.getString(R.string.subscription_failover_last_check_unknown),
            activeServerLabel = strings.getString(R.string.subscription_failover_no_active_server),
        )

    fun toUiState(
        profiles: List<RelayProfileRecord>,
        groups: List<ProxyGroup>,
        snapshot: NativeRuntimeSnapshot,
        nowMillis: Long,
        strings: StringResolver,
    ): SubscriptionFailoverUiState {
        val subscriptionAlert = subscriptionAlert(groups, nowMillis, strings)
        val orderedProfiles = profiles.sortedBy { it.id }
        if (orderedProfiles.isEmpty()) {
            return emptyUiState(strings).copy(subscriptionAlert = subscriptionAlert)
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
                strings = strings,
            )
        val events = failoverEvents(snapshot, activeProfile, activeIndex, strings)
        val recentEvent =
            events.firstOrNull()?.message
                ?: strings.getString(R.string.subscription_failover_no_switchovers)
        val activePosition = activeIndex + 1
        val summary =
            strings.getString(
                R.string.subscription_failover_summary_format,
                activePosition,
                orderedProfiles.size,
                strings.getString(activeStatus.labelRes),
                recentEvent,
                lastCheck,
            )
        return SubscriptionFailoverUiState(
            summary = summary,
            lastCheck = lastCheck,
            activeServerLabel =
                strings.getString(
                    R.string.subscription_failover_active_server_label,
                    activePosition,
                    activeProfile.displayLabel(),
                ),
            subscriptionAlert = subscriptionAlert,
            servers =
                orderedProfiles
                    .mapIndexed { index, profile ->
                        val isActive = index == activeIndex
                        profile.toServerUiState(
                            index = index,
                            total = orderedProfiles.size,
                            status = if (isActive) activeStatus else SubscriptionServerStatus.Unknown,
                            isActive = isActive,
                            strings = strings,
                        )
                    }.toImmutableList(),
            events = events.toImmutableList(),
        )
    }

    private fun RelayProfileRecord.toServerUiState(
        index: Int,
        total: Int,
        status: SubscriptionServerStatus,
        isActive: Boolean,
        strings: StringResolver,
    ): SubscriptionServerUiState =
        SubscriptionServerUiState(
            id = id,
            name = displayLabel(),
            endpoint = "$server:$serverPort",
            status = status,
            positionLabel =
                strings.getString(R.string.subscription_failover_position_label, index + 1, total),
            detail =
                if (isActive) {
                    strings.getString(R.string.subscription_failover_detail_current_server)
                } else {
                    strings.getString(R.string.subscription_failover_detail_available_on_switch)
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
        strings: StringResolver,
    ): List<SubscriptionFailoverEventUiState> =
        buildList {
            if (activeIndex > 0) {
                add(
                    SubscriptionFailoverEventUiState(
                        message = strings.getString(R.string.subscription_failover_event_switched_to_backup),
                        timeLabel = timeLabel(snapshot.capturedAt, strings),
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
                        message = fallbackMessage(snapshot, activeProfile, strings),
                        timeLabel = timeLabel(snapshot.capturedAt, strings),
                    ),
                )
            }
            snapshot.nativeEvents
                .filter(::isFailoverEvent)
                .sortedByDescending(NativeRuntimeEvent::createdAt)
                .take(MaxNativeEvents)
                .mapTo(this) { event ->
                    SubscriptionFailoverEventUiState(
                        message =
                            event.message.ifBlank {
                                strings.getString(R.string.subscription_failover_event_switch_recorded)
                            },
                        timeLabel = timeLabel(event.createdAt, strings),
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
        strings: StringResolver,
    ): String {
        val reason = snapshot.resolverFallbackReason ?: snapshot.lastRetryReason ?: snapshot.lastFallbackAction
        return if (reason.isNullOrBlank()) {
            strings.getString(
                R.string.subscription_failover_using_backup_path_for,
                activeProfile.displayLabel(),
            )
        } else {
            strings.getString(R.string.subscription_failover_using_backup_path_reason, reason)
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
        strings: StringResolver,
    ): String {
        if (capturedAt == null || capturedAt <= 0L) {
            return strings.getString(R.string.subscription_failover_last_check_unknown)
        }
        val deltaSeconds =
            ((nowMillis - capturedAt).coerceAtLeast(0L) / MillisPerSecond)
                .coerceAtLeast(0L)
        return when {
            deltaSeconds < SecondsPerMinute -> {
                strings.getString(R.string.subscription_failover_last_check_seconds_ago, deltaSeconds)
            }

            deltaSeconds < SecondsPerHour -> {
                strings.getString(
                    R.string.subscription_failover_last_check_minutes_ago,
                    deltaSeconds / SecondsPerMinute,
                )
            }

            else -> {
                strings.getString(
                    R.string.subscription_failover_last_check_at,
                    timeLabel(capturedAt, strings),
                )
            }
        }
    }

    private fun timeLabel(
        epochMillis: Long,
        strings: StringResolver,
    ): String =
        if (epochMillis <= 0L) {
            strings.getString(R.string.subscription_failover_time_unknown)
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

private fun subscriptionAlert(
    groups: List<ProxyGroup>,
    nowMillis: Long,
    strings: StringResolver,
): SubscriptionAlertUiState? {
    val signals = actionableSubscriptionSignals(groups, nowMillis)
    return if (signals.isEmpty()) {
        null
    } else {
        val highestSignal = signals.first().second
        val tone =
            if (highestSignal == SubscriptionClientSignal.STALE) {
                SubscriptionAlertTone.WARNING
            } else {
                SubscriptionAlertTone.ERROR
            }
        if (signals.size > 1) {
            SubscriptionAlertUiState(
                title = strings.getString(highestSignal.titleResource),
                message = strings.getString(R.string.subscription_signal_banner_multiple_message, signals.size),
                tone = tone,
            )
        } else {
            singleSubscriptionAlert(signals.single(), tone, strings)
        }
    }
}

private fun singleSubscriptionAlert(
    affected: Pair<ProxyGroup, SubscriptionClientSignal>,
    tone: SubscriptionAlertTone,
    strings: StringResolver,
): SubscriptionAlertUiState {
    val (group, signal) = affected
    val message =
        when (signal) {
            SubscriptionClientSignal.EXPIRED -> R.string.subscription_signal_notification_expired
            SubscriptionClientSignal.REVOKED -> R.string.subscription_signal_notification_revoked
            SubscriptionClientSignal.UNAVAILABLE -> R.string.subscription_signal_notification_unavailable
            SubscriptionClientSignal.STALE -> R.string.subscription_signal_notification_stale
        }
    return SubscriptionAlertUiState(
        title = strings.getString(signal.titleResource),
        message = strings.getString(message, group.name),
        tone = tone,
    )
}

private val SubscriptionClientSignal.titleResource: Int
    get() =
        when (this) {
            SubscriptionClientSignal.EXPIRED -> R.string.subscription_signal_banner_expired_title
            SubscriptionClientSignal.REVOKED -> R.string.subscription_signal_banner_revoked_title
            SubscriptionClientSignal.UNAVAILABLE -> R.string.subscription_signal_banner_unavailable_title
            SubscriptionClientSignal.STALE -> R.string.subscription_signal_banner_stale_title
        }
