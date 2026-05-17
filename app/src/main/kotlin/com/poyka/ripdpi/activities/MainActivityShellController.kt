package com.poyka.ripdpi.activities

import android.content.Intent
import com.poyka.ripdpi.ui.navigation.Route
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class MainActivityShellState(
    val launchHomeRequested: Boolean = false,
    val launchRouteRequested: String? = null,
    val sharedDiagnosticFragmentRequested: String? = null,
    val importRouteRequested: Route? = null,
    val startConfiguredModeRequested: Boolean = false,
    val stopConfiguredModeRequested: Boolean = false,
    val selectorSelectionRequested: SelectorSelectionRequest? = null,
    val vpnPermissionDialogVisible: Boolean = false,
    val relockRequested: Boolean = false,
)

internal data class SelectorSelectionRequest(
    val groupId: String,
    val profileId: String,
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
                sharedDiagnosticFragmentRequested = MainActivity.diagnosticShareFragment(initialIntent),
                importRouteRequested = MainActivity.importRouteFrom(initialIntent),
                startConfiguredModeRequested = MainActivity.requestsConfiguredStart(initialIntent),
                stopConfiguredModeRequested = MainActivity.requestsConfiguredStop(initialIntent),
                selectorSelectionRequested = selectorRequestFrom(initialIntent),
            ),
        )
    private val _uiEvents =
        MutableSharedFlow<MainActivityUiEvent>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val _hostCommands =
        MutableSharedFlow<MainActivityHostCommand>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val state: StateFlow<MainActivityShellState> = _state.asStateFlow()
    val uiEvents: SharedFlow<MainActivityUiEvent> = _uiEvents.asSharedFlow()
    val hostCommands: SharedFlow<MainActivityHostCommand> = _hostCommands.asSharedFlow()

    fun onNewIntent(intent: Intent?) {
        _state.update { current ->
            current.copy(
                launchHomeRequested = current.launchHomeRequested || MainActivity.requestsHomeTab(intent),
                sharedDiagnosticFragmentRequested =
                    MainActivity.diagnosticShareFragment(intent) ?: current.sharedDiagnosticFragmentRequested,
                importRouteRequested =
                    MainActivity.importRouteFrom(intent) ?: current.importRouteRequested,
                startConfiguredModeRequested =
                    current.startConfiguredModeRequested || MainActivity.requestsConfiguredStart(intent),
                stopConfiguredModeRequested =
                    current.stopConfiguredModeRequested || MainActivity.requestsConfiguredStop(intent),
                selectorSelectionRequested =
                    selectorRequestFrom(intent) ?: current.selectorSelectionRequested,
            )
        }
    }

    private fun selectorRequestFrom(intent: Intent?): SelectorSelectionRequest? {
        val groupId = MainActivity.selectorGroupIdFrom(intent) ?: return null
        val profileId = MainActivity.selectorProfileIdFrom(intent) ?: return null
        return SelectorSelectionRequest(groupId = groupId, profileId = profileId)
    }

    fun setLaunchRouteRequest(route: String?) {
        _state.update { it.copy(launchRouteRequested = route) }
    }

    fun onEffect(effect: MainEffect) {
        when (effect) {
            is MainEffect.RequestPermission -> {
                when (effect.kind) {
                    com.poyka.ripdpi.permissions.PermissionKind.Notifications -> {
                        _hostCommands.tryEmit(MainActivityHostCommand.RequestNotificationsPermission)
                    }

                    com.poyka.ripdpi.permissions.PermissionKind.VpnConsent -> {
                        effect.payload?.let { intent ->
                            _hostCommands.tryEmit(MainActivityHostCommand.RequestVpnConsent(intent))
                        }
                    }

                    com.poyka.ripdpi.permissions.PermissionKind.BatteryOptimization -> {
                        effect.payload?.let { intent ->
                            _hostCommands.tryEmit(MainActivityHostCommand.RequestBatteryOptimization(intent))
                        }
                    }
                }
            }

            is MainEffect.OpenAppSettings -> {
                _hostCommands.tryEmit(MainActivityHostCommand.OpenIntent(effect.intent))
            }

            MainEffect.ShowVpnPermissionDialog -> {
                showVpnPermissionDialog()
            }

            is MainEffect.ShowError -> {
                _uiEvents.tryEmit(MainActivityUiEvent.ShowErrorSnackbar(effect.message))
            }

            is MainEffect.ShareDiagnosticsArchive -> {
                requestShareDiagnosticsArchive(
                    filePath = effect.absolutePath,
                    fileName = effect.fileName,
                )
            }

            MainEffect.RelockRequested -> {
                _state.update { it.copy(relockRequested = true) }
            }
        }
    }

    fun consumeLaunchHomeRequest() {
        _state.update { it.copy(launchHomeRequested = false) }
    }

    fun consumeLaunchRouteRequest() {
        _state.update { it.copy(launchRouteRequested = null) }
    }

    fun consumeDiagnosticShareFragmentRequest() {
        _state.update { it.copy(sharedDiagnosticFragmentRequested = null) }
    }

    fun consumeImportRouteRequest() {
        _state.update { it.copy(importRouteRequested = null) }
    }

    fun consumeStartConfiguredModeRequest() {
        _state.update { it.copy(startConfiguredModeRequested = false) }
    }

    fun consumeStopConfiguredModeRequest() {
        _state.update { it.copy(stopConfiguredModeRequested = false) }
    }

    fun consumeSelectorSelectionRequest() {
        _state.update { it.copy(selectorSelectionRequested = null) }
    }

    fun showVpnPermissionDialog() {
        _state.update { it.copy(vpnPermissionDialogVisible = true) }
    }

    fun dismissVpnPermissionDialog() {
        _state.update { it.copy(vpnPermissionDialogVisible = false) }
    }

    fun consumeRelockRequest() {
        _state.update { it.copy(relockRequested = false) }
    }

    fun onConnectionStateChanged(connectionState: ConnectionState) {
        if (connectionState == ConnectionState.Connecting || connectionState == ConnectionState.Connected) {
            dismissVpnPermissionDialog()
        }
    }

    fun requestSaveLogs() {
        _hostCommands.tryEmit(MainActivityHostCommand.SaveLogs)
    }

    fun requestShareDebugBundle() {
        _hostCommands.tryEmit(MainActivityHostCommand.ShareDebugBundle)
    }

    fun requestSaveDiagnosticsArchive(
        filePath: String,
        fileName: String,
    ) {
        _hostCommands.tryEmit(
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
        _hostCommands.tryEmit(
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
        _hostCommands.tryEmit(
            MainActivityHostCommand.ShareDiagnosticsSummary(
                title = title,
                body = body,
            ),
        )
    }
}
