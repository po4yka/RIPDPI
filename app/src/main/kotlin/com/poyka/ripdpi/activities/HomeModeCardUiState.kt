package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Duration

enum class HomeMode {
    LocalDpiBypass,
    RemoteVpn,
    Diagnostic,
}

@Immutable
data class HomeModeCardUiState(
    val mode: HomeMode = HomeMode.LocalDpiBypass,
    val title: String = "",
    val primaryLabel: String = "",
    val secondaryLabel: String? = null,
    val isActive: Boolean = false,
    val isLoading: Boolean = false,
    val statusLine: String = secondaryLabel ?: "",
    val primaryActionLabel: String = "",
    val configureLabel: String = "",
    val primaryActionEnabled: Boolean = !isLoading,
)

internal val DefaultHomeModeCards: ImmutableList<HomeModeCardUiState> =
    HomeMode.entries
        .map { mode -> HomeModeCardUiState(mode = mode) }
        .toImmutableList()

@Suppress("LongParameterList")
internal fun buildHomeModeCards(
    settings: AppSettings,
    activeMode: Mode,
    configuredMode: Mode,
    connectionState: ConnectionState,
    connectionDuration: Duration,
    homeDiagnostics: HomeDiagnosticsUiState,
    stringResolver: StringResolver,
): ImmutableList<HomeModeCardUiState> {
    val draft = settings.toConfigDraft()
    return persistentListOf(
        buildLocalBypassCard(
            draft = draft,
            activeMode = activeMode,
            configuredMode = configuredMode,
            connectionState = connectionState,
            connectionDuration = connectionDuration,
            stringResolver = stringResolver,
        ),
        buildRemoteVpnCard(
            draft = draft,
            activeMode = activeMode,
            configuredMode = configuredMode,
            connectionState = connectionState,
            connectionDuration = connectionDuration,
            stringResolver = stringResolver,
        ),
        buildDiagnosticCard(
            homeDiagnostics = homeDiagnostics,
            stringResolver = stringResolver,
        ),
    )
}

private fun buildLocalBypassCard(
    draft: ConfigDraft,
    activeMode: Mode,
    configuredMode: Mode,
    connectionState: ConnectionState,
    connectionDuration: Duration,
    stringResolver: StringResolver,
): HomeModeCardUiState =
    HomeModeCardUiState(
        mode = HomeMode.LocalDpiBypass,
        title = stringResolver.getString(R.string.home_mode_local_dpi_bypass),
        primaryLabel = "${draft.chainSummary} - ${draft.dnsSummary}",
        secondaryLabel =
            modeStatusLabel(
                connectionState = connectionState,
                isActiveMode = isLocalBypassMode(mode = activeMode, relayEnabled = draft.relayEnabled),
                isConfiguredMode = isLocalBypassMode(mode = configuredMode, relayEnabled = draft.relayEnabled),
                connectionDuration = connectionDuration,
                stringResolver = stringResolver,
            ),
        statusLine =
            modeStatusLine(
                connectionState = connectionState,
                isActiveMode = isLocalBypassMode(mode = activeMode, relayEnabled = draft.relayEnabled),
                isConfiguredMode = isLocalBypassMode(mode = configuredMode, relayEnabled = draft.relayEnabled),
                connectionDuration = connectionDuration,
                stringResolver = stringResolver,
            ),
        primaryActionLabel =
            connectionActionLabel(
                isActive =
                    connectionState == ConnectionState.Connected &&
                        isLocalBypassMode(mode = activeMode, relayEnabled = draft.relayEnabled),
                stringResolver = stringResolver,
            ),
        configureLabel = stringResolver.getString(R.string.home_mode_card_configure),
        isActive =
            connectionState == ConnectionState.Connected &&
                isLocalBypassMode(mode = activeMode, relayEnabled = draft.relayEnabled),
        isLoading =
            connectionState == ConnectionState.Connecting &&
                isLocalBypassMode(mode = configuredMode, relayEnabled = draft.relayEnabled),
    )

private fun buildRemoteVpnCard(
    draft: ConfigDraft,
    activeMode: Mode,
    configuredMode: Mode,
    connectionState: ConnectionState,
    connectionDuration: Duration,
    stringResolver: StringResolver,
): HomeModeCardUiState {
    val serverLabel = draft.relayServer.ifBlank { draft.relayServerName }.ifBlank { null }
    val relaySummary = draft.relaySummary
    return HomeModeCardUiState(
        mode = HomeMode.RemoteVpn,
        title = stringResolver.getString(R.string.home_mode_remote_vpn),
        primaryLabel =
            when {
                !draft.relayEnabled -> stringResolver.getString(R.string.home_mode_card_remote_relay_disabled)
                serverLabel != null -> serverLabel
                else -> stringResolver.getString(R.string.home_mode_card_remote_server_unknown)
            },
        secondaryLabel =
            modeStatusLabel(
                connectionState = connectionState,
                isActiveMode = isRemoteVpnMode(mode = activeMode, relayEnabled = draft.relayEnabled),
                isConfiguredMode = isRemoteVpnMode(mode = configuredMode, relayEnabled = draft.relayEnabled),
                connectionDuration = connectionDuration,
                stringResolver = stringResolver,
            ) ?: relaySummary.takeIf { draft.relayEnabled },
        statusLine =
            modeStatusLine(
                connectionState = connectionState,
                isActiveMode = isRemoteVpnMode(mode = activeMode, relayEnabled = draft.relayEnabled),
                isConfiguredMode = isRemoteVpnMode(mode = configuredMode, relayEnabled = draft.relayEnabled),
                connectionDuration = connectionDuration,
                stringResolver = stringResolver,
            ),
        primaryActionLabel =
            connectionActionLabel(
                isActive =
                    connectionState == ConnectionState.Connected &&
                        isRemoteVpnMode(mode = activeMode, relayEnabled = draft.relayEnabled),
                stringResolver = stringResolver,
            ),
        configureLabel = stringResolver.getString(R.string.home_mode_card_configure),
        isActive =
            connectionState == ConnectionState.Connected &&
                isRemoteVpnMode(mode = activeMode, relayEnabled = draft.relayEnabled),
        isLoading =
            connectionState == ConnectionState.Connecting &&
                isRemoteVpnMode(mode = configuredMode, relayEnabled = draft.relayEnabled),
        primaryActionEnabled = draft.relayEnabled,
    )
}

private fun isLocalBypassMode(
    mode: Mode,
    relayEnabled: Boolean,
): Boolean = mode == Mode.Proxy || (mode == Mode.VPN && !relayEnabled)

private fun isRemoteVpnMode(
    mode: Mode,
    relayEnabled: Boolean,
): Boolean = mode == Mode.VPN && relayEnabled

internal fun buildDiagnosticCard(
    homeDiagnostics: HomeDiagnosticsUiState,
    stringResolver: StringResolver,
): HomeModeCardUiState {
    val latestAudit = homeDiagnostics.latestAudit
    return HomeModeCardUiState(
        mode = HomeMode.Diagnostic,
        title = stringResolver.getString(R.string.home_mode_diagnostic_scan),
        primaryLabel =
            when {
                homeDiagnostics.analysisAction.busy && homeDiagnostics.analysisAction.supportingText.isNotBlank() -> {
                    homeDiagnostics.analysisAction.supportingText
                }

                latestAudit != null -> {
                    latestAudit.headline
                }

                else -> {
                    stringResolver.getString(R.string.home_mode_card_diagnostic_empty)
                }
            },
        secondaryLabel = latestAudit?.confidenceLabel(),
        statusLine =
            when {
                homeDiagnostics.analysisAction.busy -> {
                    stringResolver.getString(R.string.home_mode_card_status_busy)
                }

                latestAudit != null -> {
                    latestAudit.diagnosticStatusLine(stringResolver)
                }

                else -> {
                    stringResolver.getString(R.string.home_mode_card_status_inactive)
                }
            },
        primaryActionLabel = stringResolver.getString(R.string.home_mode_card_run_scan),
        configureLabel = stringResolver.getString(R.string.home_mode_card_configure),
        primaryActionEnabled = homeDiagnostics.analysisAction.enabled && !homeDiagnostics.analysisAction.busy,
        isActive = false,
        isLoading = homeDiagnostics.analysisAction.busy,
    )
}

private fun HomeDiagnosticsLatestAuditUiState.confidenceLabel(): String? =
    recommendationSummary
        ?: summary.takeIf { it.isNotBlank() }

private fun HomeDiagnosticsLatestAuditUiState.diagnosticStatusLine(stringResolver: StringResolver): String =
    when {
        stale -> {
            stringResolver.getString(R.string.home_mode_card_diagnostic_status_stale)
        }

        actionable -> {
            stringResolver.getString(R.string.home_mode_card_diagnostic_status_actionable)
        }

        failedStageCount > 0 && totalStageCount > 0 -> {
            stringResolver.getString(
                R.string.home_mode_card_diagnostic_status_review_format,
                failedStageCount,
                totalStageCount,
            )
        }

        totalStageCount > 0 -> {
            stringResolver.getString(
                R.string.home_mode_card_diagnostic_status_complete_format,
                completedStageCount,
                totalStageCount,
            )
        }

        else -> {
            summary.ifBlank {
                stringResolver.getString(R.string.home_mode_card_diagnostic_status_review)
            }
        }
    }

private fun modeStatusLabel(
    connectionState: ConnectionState,
    isActiveMode: Boolean,
    isConfiguredMode: Boolean,
    connectionDuration: Duration,
    stringResolver: StringResolver,
): String? =
    when {
        connectionState == ConnectionState.Connected && isActiveMode -> {
            stringResolver.getString(
                R.string.home_mode_card_connected_format,
                formatHomeModeConnectionDuration(connectionDuration),
            )
        }

        connectionState == ConnectionState.Connecting && isConfiguredMode -> {
            stringResolver.getString(R.string.home_mode_card_connecting)
        }

        else -> {
            null
        }
    }

private fun modeStatusLine(
    connectionState: ConnectionState,
    isActiveMode: Boolean,
    isConfiguredMode: Boolean,
    connectionDuration: Duration,
    stringResolver: StringResolver,
): String =
    modeStatusLabel(
        connectionState = connectionState,
        isActiveMode = isActiveMode,
        isConfiguredMode = isConfiguredMode,
        connectionDuration = connectionDuration,
        stringResolver = stringResolver,
    ) ?: stringResolver.getString(R.string.home_mode_card_status_inactive)

private fun connectionActionLabel(
    isActive: Boolean,
    stringResolver: StringResolver,
): String =
    stringResolver.getString(
        if (isActive) {
            R.string.home_mode_card_disable
        } else {
            R.string.home_mode_card_enable
        },
    )

private fun formatHomeModeConnectionDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0L)
    val hours = totalSeconds / SecondsPerHour
    val minutes = (totalSeconds % SecondsPerHour) / SecondsPerMinute
    val seconds = totalSeconds % SecondsPerMinute
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private const val SecondsPerMinute = 60L
private const val SecondsPerHour = 60L * SecondsPerMinute
