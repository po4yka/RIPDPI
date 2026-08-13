package com.poyka.ripdpi.activities

import android.content.Intent
import com.poyka.ripdpi.ui.navigation.Route
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
        val supportCode: String? = null,
        val supportPayload: String? = null,
    ) : MainActivityUiEvent {
        val supportText: String?
            get() = supportPayload ?: supportCode
    }
}

internal class MainActivityShellController(
    initialIntent: Intent? = null,
) {
    private val _state =
        MutableStateFlow(
            MainActivityShellState(
                launchHomeRequested = requestsHomeTab(initialIntent),
                launchRouteRequested = navigationRouteFrom(initialIntent)?.stableRoute,
                sharedDiagnosticFragmentRequested = diagnosticShareFragment(initialIntent),
                importRouteRequested = importRouteFrom(initialIntent),
                startConfiguredModeRequested = requestsConfiguredStart(initialIntent),
                stopConfiguredModeRequested = requestsConfiguredStop(initialIntent),
                selectorSelectionRequested = selectorRequestFrom(initialIntent),
            ),
        )
    private val _uiEvents = Channel<MainActivityUiEvent>(capacity = Channel.BUFFERED)

    // Queue commands while collection is stopped and consume each exactly once.
    private val _hostCommands = Channel<MainActivityHostCommand>(capacity = Channel.BUFFERED)

    val state: StateFlow<MainActivityShellState> = _state.asStateFlow()
    val uiEvents: Flow<MainActivityUiEvent> = _uiEvents.receiveAsFlow()
    val hostCommands: Flow<MainActivityHostCommand> = _hostCommands.receiveAsFlow()

    fun onNewIntent(intent: Intent?) {
        _state.update { current ->
            current.copy(
                launchHomeRequested = current.launchHomeRequested || requestsHomeTab(intent),
                launchRouteRequested =
                    navigationRouteFrom(intent)?.stableRoute ?: current.launchRouteRequested,
                sharedDiagnosticFragmentRequested =
                    diagnosticShareFragment(intent) ?: current.sharedDiagnosticFragmentRequested,
                importRouteRequested =
                    importRouteFrom(intent) ?: current.importRouteRequested,
                startConfiguredModeRequested =
                    current.startConfiguredModeRequested || requestsConfiguredStart(intent),
                stopConfiguredModeRequested =
                    current.stopConfiguredModeRequested || requestsConfiguredStop(intent),
                selectorSelectionRequested =
                    selectorRequestFrom(intent) ?: current.selectorSelectionRequested,
            )
        }
    }

    private fun selectorRequestFrom(intent: Intent?): SelectorSelectionRequest? =
        selectorGroupIdFrom(intent)?.let { groupId ->
            selectorProfileIdFrom(intent)?.let { profileId ->
                SelectorSelectionRequest(groupId = groupId, profileId = profileId)
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

                    com.poyka.ripdpi.permissions.PermissionKind.AlwaysOnVpn,
                    com.poyka.ripdpi.permissions.PermissionKind.VpnLockdown,
                    -> {
                        effect.payload?.let { intent ->
                            _hostCommands.trySend(MainActivityHostCommand.OpenIntent(intent))
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
                _uiEvents.trySend(
                    MainActivityUiEvent.ShowErrorSnackbar(
                        message = effect.message,
                        supportCode = effect.supportCode,
                        supportPayload = effect.supportPayload,
                    ),
                )
            }

            is MainEffect.ShareDiagnosticsArchive -> {
                requestShareDiagnosticsArchive(
                    filePath = effect.absolutePath,
                    fileName = effect.fileName,
                )
            }

            is MainEffect.SaveDiagnosticsArchive -> {
                _hostCommands.trySend(MainActivityHostCommand.SaveDiagnosticsArchiveRequest(effect.request))
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

    internal fun emitHostCommand(command: MainActivityHostCommand) {
        _hostCommands.trySend(command)
    }
}

internal fun MainActivityShellController.requestSaveLogs() {
    emitHostCommand(MainActivityHostCommand.SaveLogs)
}

internal fun MainActivityShellController.requestShareDebugBundle() {
    emitHostCommand(MainActivityHostCommand.ShareDebugBundle)
}

internal fun MainActivityShellController.requestSaveDiagnosticsArchive(
    filePath: String,
    fileName: String,
) {
    emitHostCommand(
        MainActivityHostCommand.SaveDiagnosticsArchive(
            filePath = filePath,
            fileName = fileName,
        ),
    )
}

internal fun MainActivityShellController.requestShareDiagnosticsArchive(
    filePath: String,
    fileName: String,
) {
    emitHostCommand(
        MainActivityHostCommand.ShareDiagnosticsArchive(
            filePath = filePath,
            fileName = fileName,
        ),
    )
}

internal fun MainActivityShellController.requestShareDiagnosticsSummary(
    title: String,
    body: String,
) {
    emitHostCommand(
        MainActivityHostCommand.ShareDiagnosticsSummary(
            title = title,
            body = body,
        ),
    )
}
