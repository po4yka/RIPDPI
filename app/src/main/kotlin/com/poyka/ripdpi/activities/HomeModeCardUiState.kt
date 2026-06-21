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

/**
 * A labeled facet of a mode card's configuration summary (e.g. "Strategy" -> "tcp: split(host+1)").
 * When a card carries [HomeModeCardUiState.summaryFacets], the card renders them as a structured
 * key/value panel instead of the dense single-line [HomeModeCardUiState.primaryLabel].
 */
@Immutable
data class HomeModeSummaryFacet(
    val label: String,
    val value: String,
)

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
    val primaryActionDisabledHint: String = "",
    val summaryFacets: ImmutableList<HomeModeSummaryFacet> = persistentListOf(),
)

internal val DefaultHomeModeCards: ImmutableList<HomeModeCardUiState> =
    HomeMode.entries
        .map { mode -> HomeModeCardUiState(mode = mode) }
        .toImmutableList()

internal data class HomeModeCardsInput(
    val settings: AppSettings,
    val activeMode: Mode,
    val configuredMode: Mode,
    val connectionState: ConnectionState,
    val connectionDuration: Duration,
    val homeDiagnostics: HomeDiagnosticsUiState,
    val stringResolver: StringResolver,
)

internal fun buildHomeModeCards(input: HomeModeCardsInput): ImmutableList<HomeModeCardUiState> {
    val draft = input.settings.toConfigDraft()
    return buildHomeModeCards(input, draft)
}

private fun buildHomeModeCards(
    input: HomeModeCardsInput,
    draft: ConfigDraft,
): ImmutableList<HomeModeCardUiState> =
    persistentListOf(
        buildLocalBypassCard(
            draft = draft,
            activeMode = input.activeMode,
            configuredMode = input.configuredMode,
            connectionState = input.connectionState,
            connectionDuration = input.connectionDuration,
            stringResolver = input.stringResolver,
        ),
        buildRemoteVpnCard(
            draft = draft,
            activeMode = input.activeMode,
            configuredMode = input.configuredMode,
            connectionState = input.connectionState,
            connectionDuration = input.connectionDuration,
            stringResolver = input.stringResolver,
        ),
        buildDiagnosticCard(
            homeDiagnostics = input.homeDiagnostics,
            stringResolver = input.stringResolver,
        ),
    )

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
        summaryFacets =
            persistentListOf(
                HomeModeSummaryFacet(
                    label = stringResolver.getString(R.string.diagnostics_metric_strategy),
                    value = draft.chainSummary,
                ),
                HomeModeSummaryFacet(
                    label = stringResolver.getString(R.string.home_connection_stage_dns),
                    value = draft.dnsSummary,
                ),
            ),
        secondaryLabel =
            modeStatusLabel(
                connectionState = connectionState,
                isActiveMode = isLocalBypassMode(activeMode),
                isConfiguredMode = isLocalBypassMode(configuredMode),
                connectionDuration = connectionDuration,
                stringResolver = stringResolver,
            ),
        statusLine =
            modeStatusLine(
                connectionState = connectionState,
                isActiveMode = isLocalBypassMode(activeMode),
                isConfiguredMode = isLocalBypassMode(configuredMode),
                connectionDuration = connectionDuration,
                stringResolver = stringResolver,
            ),
        primaryActionLabel =
            connectionActionLabel(
                isActive =
                    connectionState == ConnectionState.Connected &&
                        isLocalBypassMode(activeMode),
                stringResolver = stringResolver,
            ),
        configureLabel = stringResolver.getString(R.string.home_mode_card_configure),
        isActive =
            connectionState == ConnectionState.Connected &&
                isLocalBypassMode(activeMode),
        isLoading =
            connectionState == ConnectionState.Connecting &&
                isLocalBypassMode(configuredMode),
    )

private fun buildRemoteVpnCard(
    draft: ConfigDraft,
    activeMode: Mode,
    configuredMode: Mode,
    connectionState: ConnectionState,
    connectionDuration: Duration,
    stringResolver: StringResolver,
): HomeModeCardUiState {
    val relaySummary = draft.relaySummary
    return HomeModeCardUiState(
        mode = HomeMode.RemoteVpn,
        title = stringResolver.getString(R.string.home_mode_remote_vpn),
        primaryLabel = remoteVpnPrimaryLabel(draft, stringResolver),
        secondaryLabel =
            modeStatusLabel(
                connectionState = connectionState,
                isActiveMode = isRemoteVpnMode(activeMode),
                isConfiguredMode = isRemoteVpnMode(configuredMode),
                connectionDuration = connectionDuration,
                stringResolver = stringResolver,
            ) ?: relaySummary.takeIf { draft.relayEnabled },
        statusLine =
            modeStatusLine(
                connectionState = connectionState,
                isActiveMode = isRemoteVpnMode(activeMode),
                isConfiguredMode = isRemoteVpnMode(configuredMode),
                connectionDuration = connectionDuration,
                stringResolver = stringResolver,
            ),
        primaryActionLabel =
            connectionActionLabel(
                isActive =
                    connectionState == ConnectionState.Connected &&
                        isRemoteVpnMode(activeMode),
                stringResolver = stringResolver,
            ),
        configureLabel = stringResolver.getString(R.string.home_mode_card_configure),
        isActive =
            connectionState == ConnectionState.Connected &&
                isRemoteVpnMode(activeMode),
        isLoading =
            connectionState == ConnectionState.Connecting &&
                isRemoteVpnMode(configuredMode),
    )
}

private fun remoteVpnPrimaryLabel(
    draft: ConfigDraft,
    stringResolver: StringResolver,
): String {
    val serverLabel = draft.relayServer.ifBlank { draft.relayServerName }.ifBlank { null }
    return when {
        !draft.relayEnabled -> stringResolver.getString(R.string.home_mode_vpn)
        serverLabel != null -> serverLabel
        else -> stringResolver.getString(R.string.home_mode_card_remote_server_unknown)
    }
}

internal fun actuatorLabelForRemoteVpn(
    settings: AppSettings,
    stringResolver: StringResolver,
): String =
    if (settings.toConfigDraft().relayEnabled) {
        stringResolver.getString(R.string.home_mode_remote_vpn)
    } else {
        stringResolver.getString(R.string.home_mode_vpn)
    }

private fun isLocalBypassMode(mode: Mode): Boolean = mode == Mode.Proxy

private fun isRemoteVpnMode(mode: Mode): Boolean = mode == Mode.VPN

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
        configureLabel = stringResolver.getString(R.string.home_diagnostics_open_diagnostics_action),
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
