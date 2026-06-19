package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * The entire UI of the "simple" flavor: two actions over the compiled-in config.
 *
 * - Connect / Disconnect toggles the VPN using the embedded relay profile (seeded
 *   at first launch — see M2). Wires to the same [MainViewModel] entry points the
 *   full UI uses, so the connection/permission flow is identical.
 * - Run diagnostic report kicks off the full home analysis. The completed report is
 *   handed to the OS share sheet — that auto-share-on-complete hand-off, plus the
 *   active-protocol field, lands in M5; M1 wires the trigger and progress.
 */
@Composable
fun SimpleHomeScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val diagnostics by viewModel.homeDiagnosticsUiState.collectAsStateWithLifecycle()

    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    val connecting = uiState.connectionState == ConnectionState.Connecting
    val connected = uiState.connectionState == ConnectionState.Connected
    val active = connecting || connected
    val reportBusy = diagnostics.analysisAction.busy

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = spacing.xl, vertical = spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.simple_title),
                style = RipDpiThemeTokens.type.screenTitle,
                color = colors.foreground,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.padding(top = spacing.sm),
                text = stringResource(simpleStatusLabel(uiState.connectionState)),
                style = RipDpiThemeTokens.type.body,
                color = if (connected) colors.success else colors.mutedForeground,
                textAlign = TextAlign.Center,
            )

            RipDpiButton(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.xxl),
                text =
                    stringResource(
                        if (active) R.string.simple_disconnect else R.string.simple_connect,
                    ),
                onClick = {
                    if (active) viewModel.onStopRequested() else viewModel.onToggleVpn(enabled = true)
                },
                loading = connecting,
                variant = RipDpiButtonVariant.Primary,
            )

            RipDpiButton(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.md),
                text = stringResource(R.string.simple_run_report),
                onClick = { viewModel.onRunHomeFullAnalysis() },
                loading = reportBusy,
                enabled = !reportBusy,
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}

private fun simpleStatusLabel(state: ConnectionState): Int =
    when (state) {
        ConnectionState.Disconnected -> R.string.simple_status_disconnected
        ConnectionState.Connecting -> R.string.simple_status_connecting
        ConnectionState.Connected -> R.string.simple_status_connected
        ConnectionState.Error -> R.string.simple_status_error
    }
