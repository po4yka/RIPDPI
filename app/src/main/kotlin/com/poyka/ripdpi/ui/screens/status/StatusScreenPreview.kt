package com.poyka.ripdpi.ui.screens.status

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Duration.Companion.ZERO

@Preview(showBackground = true)
@Composable
private fun StatusScreenAllInactivePreview() {
    RipDpiTheme(themePreference = "light") {
        StatusScreen(
            uiState = MainUiState(modeCards = previewStatusModeCards()),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusScreenBypassActivePreview() {
    RipDpiTheme(themePreference = "dark") {
        StatusScreen(
            uiState = MainUiState(modeCards = previewStatusModeCards(activeMode = StatusMode.LocalDpiBypass)),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusScreenVpnActivePreview() {
    RipDpiTheme(themePreference = "light") {
        StatusScreen(
            uiState = MainUiState(modeCards = previewStatusModeCards(activeMode = StatusMode.RemoteVpn)),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusScreenDiagnosticRunningPreview() {
    RipDpiTheme(themePreference = "dark") {
        StatusScreen(
            uiState = MainUiState(modeCards = previewStatusModeCards(loadingMode = StatusMode.Diagnostic)),
            onToggleConnection = {},
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusScreenErrorPreview() {
    RipDpiTheme(themePreference = "light") {
        StatusScreen(
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
            onOpenDiagnostics = {},
            onOpenHistory = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
        )
    }
}

private fun previewStatusModeCards(
    activeMode: StatusMode? = null,
    loadingMode: StatusMode? = null,
) = persistentListOf(
    previewStatusModeCard(
        mode = StatusMode.LocalDpiBypass,
        activeMode = activeMode,
        loadingMode = loadingMode,
    ),
    previewStatusModeCard(
        mode = StatusMode.RemoteVpn,
        activeMode = activeMode,
        loadingMode = loadingMode,
    ),
    previewStatusModeCard(
        mode = StatusMode.Diagnostic,
        activeMode = activeMode,
        loadingMode = loadingMode,
    ),
)

private fun previewStatusModeCard(
    mode: StatusMode,
    activeMode: StatusMode?,
    loadingMode: StatusMode?,
): StatusModeCardUiState {
    val active = mode == activeMode
    val loading = mode == loadingMode
    return StatusModeCardUiState(
        mode = mode,
        title =
            when (mode) {
                StatusMode.LocalDpiBypass -> "Proxy only"
                StatusMode.RemoteVpn -> "Whole device"
                StatusMode.Diagnostic -> "Network Diagnostic"
            },
        primaryLabel =
            when (mode) {
                StatusMode.LocalDpiBypass -> "tlsrec_split_host - AdGuard DoH"
                StatusMode.RemoteVpn -> "relay.example"
                StatusMode.Diagnostic -> if (loading) "Stage 2 of 4 - Testing TCP" else "No analysis yet"
            },
        secondaryLabel = if (active) "Connected 00:18:42" else null,
        statusLine =
            when {
                loading -> "Running"
                active -> "Connected 00:18:42"
                else -> "Inactive"
            },
        primaryActionLabel =
            when {
                mode == StatusMode.Diagnostic -> "Run Scan"
                active -> "Disable"
                else -> "Enable"
            },
        configureLabel = "Configure",
        primaryActionEnabled = !loading,
        isActive = active,
        isLoading = loading,
    )
}
