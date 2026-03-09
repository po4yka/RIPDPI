package com.poyka.ripdpi.ui.screens.home

import android.net.VpnService
import android.text.format.Formatter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

@Composable
fun HomeRoute(
    modifier: Modifier = Modifier,
    onOpenVpnPermission: () -> Unit,
    viewModel: MainViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    HomeScreen(
        uiState = uiState,
        modifier = modifier,
        onToggleConnection = {
            if (shouldOpenVpnPermission(
                    uiState = uiState,
                    vpnPermissionRequired = VpnService.prepare(context) != null,
                )
            ) {
                onOpenVpnPermission()
            } else {
                viewModel.toggleService(context)
            }
        },
    )
}

@Composable
fun HomeScreen(
    uiState: MainUiState,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
    ) {
        RipDpiTopAppBar(
            title = stringResource(R.string.app_name),
            brandGlyph = stringResource(R.string.app_name).take(1),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = layout.horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
        ) {
            Spacer(modifier = Modifier.height(spacing.sm))

            if (uiState.connectionState == ConnectionState.Error && uiState.errorMessage != null) {
                WarningBanner(
                    title = stringResource(R.string.home_status_error_title),
                    message = uiState.errorMessage,
                    tone = WarningBannerTone.Error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HomeStatusCard(
                uiState = uiState,
                onToggleConnection = onToggleConnection,
            )

            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Text(
                    text = stringResource(R.string.home_overview_title),
                    style = type.sectionTitle,
                    color = colors.mutedForeground,
                )
                HomeStatsGrid(uiState = uiState)
            }

            Spacer(modifier = Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun HomeStatusCard(
    uiState: MainUiState,
    onToggleConnection: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        variant =
            if (uiState.connectionState == ConnectionState.Connected) {
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
        Text(
            text = homeSupportingCopy(uiState),
            style = type.body,
            color = colors.mutedForeground,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        HomeConnectionButton(
            state = uiState.connectionState,
            label = homePrimaryActionLabel(uiState),
            modeLabel = homeModeLabel(currentMode(uiState)),
            onClick = onToggleConnection,
        )
    }
}

internal fun shouldOpenVpnPermission(
    uiState: MainUiState,
    vpnPermissionRequired: Boolean,
): Boolean =
    vpnPermissionRequired &&
        uiState.configuredMode == Mode.VPN &&
        uiState.connectionState != ConnectionState.Connected &&
        uiState.connectionState != ConnectionState.Connecting

@Composable
private fun HomeConnectionButton(
    state: ConnectionState,
    label: String,
    modeLabel: String,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val scheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1_100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
    )

    val containerColor =
        when (state) {
            ConnectionState.Connected,
            ConnectionState.Connecting,
            -> colors.foreground

            ConnectionState.Disconnected,
            ConnectionState.Error,
            -> scheme.surface
        }
    val contentColor =
        when (state) {
            ConnectionState.Connected,
            ConnectionState.Connecting,
            -> colors.background

            ConnectionState.Disconnected,
            ConnectionState.Error,
            -> colors.foreground
        }
    val haloColor =
        when (state) {
            ConnectionState.Connected -> colors.foreground.copy(alpha = 0.08f)
            ConnectionState.Connecting -> colors.foreground.copy(alpha = 0.14f)
            ConnectionState.Disconnected -> colors.accent
            ConnectionState.Error -> colors.destructive.copy(alpha = 0.12f)
        }
    val borderColor =
        when (state) {
            ConnectionState.Connected,
            ConnectionState.Connecting,
            -> Color.Transparent

            ConnectionState.Disconnected -> colors.cardBorder

            ConnectionState.Error -> colors.destructive
        }
    val buttonScale = if (state == ConnectionState.Connecting) pulseScale else 1f
    val icon =
        when (state) {
            ConnectionState.Connected -> RipDpiIcons.Connected
            ConnectionState.Connecting -> RipDpiIcons.Vpn
            ConnectionState.Disconnected -> RipDpiIcons.Offline
            ConnectionState.Error -> RipDpiIcons.Warning
        }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(216.dp)
                    .scale(buttonScale)
                    .background(haloColor, CircleShape),
        )
        Column(
            modifier =
                Modifier
                    .size(172.dp)
                    .scale(buttonScale)
                    .background(containerColor, CircleShape)
                    .border(width = 1.dp, color = borderColor, shape = CircleShape)
                    .clickable(
                        enabled = state != ConnectionState.Connecting,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    ).padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = label,
                style = type.bodyEmphasis,
                color = contentColor,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = modeLabel,
                style = type.monoSmall,
                color = contentColor.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HomeStatsGrid(uiState: MainUiState) {
    val spacing = RipDpiThemeTokens.spacing
    val context = LocalContext.current
    val resolvedMode = currentMode(uiState)
    val routeValue =
        when (resolvedMode) {
            Mode.VPN -> stringResource(R.string.home_route_local)
            Mode.Proxy -> stringResource(R.string.proxy_address, uiState.proxyIp, uiState.proxyPort)
        }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            HomeStatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.home_stat_duration),
                value = formatConnectionDuration(uiState.connectionDuration),
            )
            HomeStatCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.home_stat_traffic),
                value = Formatter.formatShortFileSize(context, uiState.dataTransferred),
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
    }
}

@Composable
private fun HomeStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
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
                color = colors.foreground,
            )
        }
    }
}

@Composable
private fun homeStatusLabel(state: ConnectionState): String =
    when (state) {
        ConnectionState.Disconnected -> stringResource(R.string.vpn_disconnected)
        ConnectionState.Connecting -> stringResource(R.string.home_status_connecting)
        ConnectionState.Connected -> stringResource(R.string.vpn_connected)
        ConnectionState.Error -> stringResource(R.string.home_status_attention)
    }

private fun homeIndicatorTone(state: ConnectionState): StatusIndicatorTone =
    when (state) {
        ConnectionState.Disconnected -> StatusIndicatorTone.Idle
        ConnectionState.Connecting -> StatusIndicatorTone.Warning
        ConnectionState.Connected -> StatusIndicatorTone.Active
        ConnectionState.Error -> StatusIndicatorTone.Error
    }

@Composable
private fun homeHeadline(state: ConnectionState): String =
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
private fun homePrimaryActionLabel(uiState: MainUiState): String =
    when (uiState.connectionState) {
        ConnectionState.Connecting -> {
            stringResource(R.string.home_connection_button_connecting)
        }

        ConnectionState.Connected -> {
            when (uiState.activeMode) {
                Mode.VPN -> stringResource(R.string.vpn_disconnect)
                Mode.Proxy -> stringResource(R.string.proxy_stop)
            }
        }

        ConnectionState.Disconnected,
        ConnectionState.Error,
        -> {
            when (uiState.configuredMode) {
                Mode.VPN -> stringResource(R.string.vpn_connect)
                Mode.Proxy -> stringResource(R.string.proxy_start)
            }
        }
    }

@Composable
private fun homeModeLabel(mode: Mode): String =
    when (mode) {
        Mode.VPN -> stringResource(R.string.home_mode_vpn)
        Mode.Proxy -> stringResource(R.string.home_mode_proxy)
    }

private fun currentMode(uiState: MainUiState): Mode =
    if (uiState.connectionState == ConnectionState.Connected) {
        uiState.activeMode
    } else {
        uiState.configuredMode
    }

private fun formatConnectionDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenDisconnectedPreview() {
    RipDpiTheme(themePreference = "light") {
        HomeScreen(
            uiState = MainUiState(),
            onToggleConnection = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenConnectedPreview() {
    RipDpiTheme(themePreference = "dark") {
        HomeScreen(
            uiState =
                MainUiState(
                    connectionState = ConnectionState.Connected,
                    connectionDuration = Duration.parse("PT18M42S"),
                    dataTransferred = 18_242_560L,
                ),
            onToggleConnection = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenErrorPreview() {
    RipDpiTheme(themePreference = "light") {
        HomeScreen(
            uiState =
                MainUiState(
                    connectionState = ConnectionState.Error,
                    errorMessage = "Failed to start VPN",
                    configuredMode = Mode.Proxy,
                    proxyIp = "127.0.0.1",
                    proxyPort = "1080",
                    connectionDuration = ZERO,
                ),
            onToggleConnection = {},
        )
    }
}
