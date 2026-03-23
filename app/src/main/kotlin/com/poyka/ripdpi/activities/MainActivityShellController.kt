package com.poyka.ripdpi.activities

import android.content.Intent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

internal data class MainActivityShellState(
    val launchHomeRequested: Boolean = false,
    val launchRouteRequested: String? = null,
    val startConfiguredModeRequested: Boolean = false,
    val vpnPermissionDialogVisible: Boolean = false,
)

internal sealed interface MainActivityUiEvent {
    data class ShowErrorSnackbar(
        val message: String,
    ) : MainActivityUiEvent
}

internal class MainActivityShellController(
    initialIntent: Intent? = null,
) {
    private val _state =
        MutableStateFlow(
            MainActivityShellState(
                launchHomeRequested = MainActivity.requestsHomeTab(initialIntent),
                startConfiguredModeRequested = MainActivity.requestsConfiguredStart(initialIntent),
            ),
        )
    private val _uiEvents = Channel<MainActivityUiEvent>(Channel.BUFFERED)
    private val _hostCommands = Channel<MainActivityHostCommand>(Channel.BUFFERED)

    val state: StateFlow<MainActivityShellState> = _state.asStateFlow()
    val uiEvents: Flow<MainActivityUiEvent> = _uiEvents.receiveAsFlow()
    val hostCommands: Flow<MainActivityHostCommand> = _hostCommands.receiveAsFlow()

    fun onNewIntent(intent: Intent?) {
        _state.update { current ->
            current.copy(
                launchHomeRequested = current.launchHomeRequested || MainActivity.requestsHomeTab(intent),
                startConfiguredModeRequested =
                    current.startConfiguredModeRequested || MainActivity.requestsConfiguredStart(intent),
            )
        }
    }

    fun setLaunchRouteRequest(route: String?) {
        _state.update { it.copy(launchRouteRequested = route) }
    }

    fun onEffect(effect: MainEffect) {
        when (effect) {
            is MainEffect.RequestPermission -> {
                when (effect.kind) {
                    com.poyka.ripdpi.permissions.PermissionKind.Notifications -> {
                        _hostCommands.trySend(MainActivityHostCommand.RequestNotificationsPermission)
                    }

                    com.poyka.ripdpi.permissions.PermissionKind.VpnConsent -> {
                        effect.payload?.let { intent ->
                            _hostCommands.trySend(MainActivityHostCommand.RequestVpnConsent(intent))
                        }
                    }

                    com.poyka.ripdpi.permissions.PermissionKind.BatteryOptimization -> {
                        effect.payload?.let { intent ->
                            _hostCommands.trySend(MainActivityHostCommand.RequestBatteryOptimization(intent))
                        }
                    }
                }
            }

            is MainEffect.OpenAppSettings -> {
                _hostCommands.trySend(MainActivityHostCommand.OpenIntent(effect.intent))
            }

            MainEffect.ShowVpnPermissionDialog -> {
                showVpnPermissionDialog()
            }

            is MainEffect.ShowError -> {
                _uiEvents.trySend(MainActivityUiEvent.ShowErrorSnackbar(effect.message))
            }
        }
    }

    fun consumeLaunchHomeRequest() {
        _state.update { it.copy(launchHomeRequested = false) }
    }

    fun consumeLaunchRouteRequest() {
        _state.update { it.copy(launchRouteRequested = null) }
    }

    fun consumeStartConfiguredModeRequest() {
        _state.update { it.copy(startConfiguredModeRequested = false) }
    }

    fun showVpnPermissionDialog() {
        _state.update { it.copy(vpnPermissionDialogVisible = true) }
    }

    fun dismissVpnPermissionDialog() {
        _state.update { it.copy(vpnPermissionDialogVisible = false) }
    }

    fun onConnectionStateChanged(connectionState: ConnectionState) {
        if (connectionState == ConnectionState.Connecting || connectionState == ConnectionState.Connected) {
            dismissVpnPermissionDialog()
        }
    }

    fun requestSaveLogs() {
        _hostCommands.trySend(MainActivityHostCommand.SaveLogs)
    }

    fun requestShareDebugBundle() {
        _hostCommands.trySend(MainActivityHostCommand.ShareDebugBundle)
    }

    fun requestSaveDiagnosticsArchive(
        filePath: String,
        fileName: String,
    ) {
        _hostCommands.trySend(
            MainActivityHostCommand.SaveDiagnosticsArchive(
                filePath = filePath,
                fileName = fileName,
            ),
        )
    }

    fun requestShareDiagnosticsArchive(
        filePath: String,
        fileName: String,
    ) {
        _hostCommands.trySend(
            MainActivityHostCommand.ShareDiagnosticsArchive(
                filePath = filePath,
                fileName = fileName,
            ),
        )
    }

    fun requestShareDiagnosticsSummary(
        title: String,
        body: String,
    ) {
        _hostCommands.trySend(
            MainActivityHostCommand.ShareDiagnosticsSummary(
                title = title,
                body = body,
            ),
        )
    }
}
