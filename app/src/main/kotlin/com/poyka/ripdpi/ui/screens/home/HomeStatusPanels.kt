package com.poyka.ripdpi.ui.screens.home

import android.text.format.Formatter
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale
import kotlin.time.Duration

private const val secondsPerHour = 3_600
private const val secondsPerMinute = 60

@Composable
internal fun HomeStatusCard(
    uiState: MainUiState,
    onToggleConnection: () -> Unit,
) {
    TrackRecomposition("HomeStatusCard")
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        variant =
            if (uiState.connectionState == ConnectionState.Connected) {
                RipDpiCardVariant.Status
            } else if (uiState.connectionState == ConnectionState.Connecting) {
                RipDpiCardVariant.Elevated
            } else {
                RipDpiCardVariant.Outlined
            },
    ) {
        Text(
            text = stringResource(R.string.home_status_section),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        StatusIndicator(
            label = homeStatusLabel(uiState.connectionState),
            tone = homeIndicatorTone(uiState.connectionState),
        )
        Text(
            text = homeHeadline(uiState.connectionState),
            style = type.screenTitle,
            color = colors.foreground,
        )
        if (uiState.connectionState == ConnectionState.Disconnected && uiState.approachSummary != null) {
            Text(
                text = uiState.approachSummary.title,
                style = type.secondaryBody,
                color = colors.mutedForeground,
            )
        } else if (uiState.connectionState != ConnectionState.Disconnected) {
            Text(
                text = homeSupportingCopy(uiState),
                style = type.body,
                color = colors.mutedForeground,
            )
        }
        RipDpiConnectionActuator(
            state = uiState.connectionActuator,
            onActivate = onToggleConnection,
            onDeactivate = onToggleConnection,
            testTag = RipDpiTestTags.HomeConnectionButton,
        )
    }
}

@Composable
internal fun HomeConnectionButton(
    state: ConnectionState,
    label: String,
    modeLabel: String,
    onClick: () -> Unit,
) {
    TrackRecomposition("HomeConnectionButton")
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    HomeConnectionButtonLayout(
        state = state,
        label = label,
        modeLabel = modeLabel,
        stateDescription = "${homeStatusLabel(state)}, $modeLabel",
        onClick = onClick,
        interactionSource = interactionSource,
        visuals = rememberHomeConnectionButtonVisuals(state),
        motionState = rememberHomeConnectionButtonMotionState(state, isPressed),
    )
}

@Composable
internal fun HomeStatsGrid(uiState: MainUiState) {
    TrackRecomposition("HomeStatsGrid")
    val spacing = RipDpiThemeTokens.spacing
    val context = LocalContext.current
    val resolvedMode = currentMode(uiState)
    val formattedDuration =
        remember(uiState.connectionDuration) { formatConnectionDuration(uiState.connectionDuration) }
    val formattedTraffic =
        remember(uiState.dataTransferred) {
            Formatter.formatShortFileSize(context, uiState.dataTransferred)
        }
    val routeValue =
        when (resolvedMode) {
            Mode.VPN -> stringResource(R.string.home_route_local)
            Mode.Proxy -> stringResource(R.string.proxy_address, uiState.proxyIp, uiState.proxyPort)
        }

    Column(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeStatsGrid),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            HomeStatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.home_stat_duration),
                value = formattedDuration,
            )
            HomeStatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.home_stat_traffic),
                value = formattedTraffic,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            HomeStatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.home_stat_mode),
                value = homeModeLabel(resolvedMode),
            )
            HomeStatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.home_stat_route),
                value = routeValue,
            )
        }
        HomeStatCard(
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.home_stat_quality),
            value = connectionQualityLabel(uiState.connectionState),
            valueColor = connectionQualityColor(uiState.connectionState),
        )
    }
}

@Composable
private fun connectionQualityLabel(state: ConnectionState): String =
    when (state) {
        ConnectionState.Connected -> stringResource(R.string.home_quality_excellent)
        ConnectionState.Connecting -> stringResource(R.string.home_quality_connecting)
        ConnectionState.Disconnected -> stringResource(R.string.home_quality_offline)
        ConnectionState.Error -> stringResource(R.string.home_quality_offline)
    }

@Composable
private fun connectionQualityColor(state: ConnectionState): Color {
    val colors = RipDpiThemeTokens.colors
    return when (state) {
        ConnectionState.Connected -> colors.success
        ConnectionState.Connecting -> colors.warning
        ConnectionState.Disconnected -> colors.mutedForeground
        ConnectionState.Error -> colors.destructive
    }
}

@Composable
internal fun HomeStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = label,
                style = type.smallLabel,
                color = colors.mutedForeground,
            )
            Text(
                text = value,
                style = type.monoValue,
                color = valueColor ?: colors.foreground,
            )
        }
    }
}

@Composable
internal fun homeStatusLabel(state: ConnectionState): String =
    when (state) {
        ConnectionState.Disconnected -> stringResource(R.string.vpn_disconnected)
        ConnectionState.Connecting -> stringResource(R.string.home_status_connecting)
        ConnectionState.Connected -> stringResource(R.string.vpn_connected)
        ConnectionState.Error -> stringResource(R.string.home_status_attention)
    }

internal fun homeIndicatorTone(state: ConnectionState): StatusIndicatorTone =
    when (state) {
        ConnectionState.Disconnected -> StatusIndicatorTone.Idle
        ConnectionState.Connecting -> StatusIndicatorTone.Warning
        ConnectionState.Connected -> StatusIndicatorTone.Active
        ConnectionState.Error -> StatusIndicatorTone.Error
    }

@Composable
internal fun homeHeadline(state: ConnectionState): String =
    when (state) {
        ConnectionState.Disconnected -> stringResource(R.string.home_status_disconnected_title)
        ConnectionState.Connecting -> stringResource(R.string.home_status_connecting_title)
        ConnectionState.Connected -> stringResource(R.string.home_status_connected_title)
        ConnectionState.Error -> stringResource(R.string.home_status_error_title)
    }

@Composable
private fun homeSupportingCopy(uiState: MainUiState): String =
    when (uiState.connectionState) {
        ConnectionState.Disconnected -> stringResource(R.string.home_status_disconnected_body)
        ConnectionState.Connecting -> stringResource(R.string.home_status_connecting_body)
        ConnectionState.Connected -> stringResource(R.string.home_status_connected_body)
        ConnectionState.Error -> stringResource(R.string.home_status_error_body)
    }

@Composable
internal fun homeModeLabel(mode: Mode): String =
    when (mode) {
        Mode.VPN -> stringResource(R.string.home_mode_vpn)
        Mode.Proxy -> stringResource(R.string.home_mode_proxy)
    }

internal fun currentMode(uiState: MainUiState): Mode =
    if (uiState.connectionState == ConnectionState.Connected) {
        uiState.activeMode
    } else {
        uiState.configuredMode
    }

internal fun formatConnectionDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0L)
    val hours = totalSeconds / secondsPerHour
    val minutes = (totalSeconds % secondsPerHour) / secondsPerMinute
    val seconds = totalSeconds % secondsPerMinute
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
