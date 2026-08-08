package com.poyka.ripdpi.services

import com.poyka.ripdpi.AppStartupReadiness
import com.poyka.ripdpi.AppStartupReadinessState
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.ServiceStartRejectionReason.ForegroundServiceBlocked
import com.poyka.ripdpi.services.ServiceStartRejectionReason.NotificationsPermissionMissing
import com.poyka.ripdpi.services.ServiceStartRejectionReason.VpnConsentMissing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class QuickTileVisualState {
    Active,
    Inactive,
    Unavailable,
}

internal interface QuickTileHost {
    fun renderTileState(state: QuickTileVisualState)

    fun showStartFailure(senderName: String)

    fun launchStartResolution()

    fun vpnPermissionRequired(): Boolean
}

internal class QuickTileController(
    private val appStartupReadiness: AppStartupReadiness,
    private val appSettingsRepository: AppSettingsRepository,
    private val serviceController: ServiceController,
    private val serviceStateStore: ServiceStateStore,
) {
    private var listeningScope: CoroutineScope? = null
    private var statusJob: Job? = null
    private var eventsJob: Job? = null
    private var currentTileState: QuickTileVisualState? = null

    fun onStartListening(
        host: QuickTileHost,
        scope: CoroutineScope,
    ) {
        clearSubscriptions()
        listeningScope = scope
        updateStatus(host)

        statusJob =
            scope.launch {
                serviceStateStore.status.collect {
                    updateStatus(host)
                }
            }

        eventsJob =
            scope.launch {
                serviceStateStore.events.collect { event ->
                    when (event) {
                        is ServiceEvent.Failed -> {
                            host.showStartFailure(event.sender.senderName)
                            updateStatus(host)
                        }

                        is ServiceEvent.PermissionRevoked -> {
                        }
                    }
                }
            }
    }

    fun onStopListening() {
        clearSubscriptions()
        listeningScope = null
    }

    fun onClick(host: QuickTileHost) {
        if (currentTileState == QuickTileVisualState.Unavailable) {
            return
        }

        render(host, QuickTileVisualState.Active)
        render(host, QuickTileVisualState.Unavailable)

        when (serviceStateStore.status.value.first) {
            AppStatus.Halted -> {
                val scope = listeningScope ?: return
                scope.launch {
                    if (appStartupReadiness.readiness.value != AppStartupReadinessState.Ready) {
                        updateStatus(host)
                        host.launchStartResolution()
                        return@launch
                    }

                    val settings = appSettingsRepository.snapshot()
                    val mode = Mode.fromString(settings.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue })

                    if (needsPermissionResolution(mode, host)) {
                        updateStatus(host)
                        host.launchStartResolution()
                        return@launch
                    }

                    when (val result = serviceController.start(mode)) {
                        is ServiceStartResult.Accepted -> Unit
                        is ServiceStartResult.Rejected -> handleStartRejected(host, result)
                    }
                }
            }

            // Reconnecting is an in-flight resume: a tile tap stops it, same as Running.
            AppStatus.Reconnecting,
            AppStatus.Running,
            -> {
                serviceController.stop()
            }
        }
    }

    private fun needsPermissionResolution(
        mode: Mode,
        host: QuickTileHost,
    ): Boolean = mode == Mode.VPN && host.vpnPermissionRequired()

    private fun handleStartRejected(
        host: QuickTileHost,
        result: ServiceStartResult.Rejected,
    ) {
        updateStatus(host)
        when (result.reason) {
            NotificationsPermissionMissing,
            VpnConsentMissing,
            -> host.launchStartResolution()

            is ForegroundServiceBlocked -> host.showStartFailure(result.mode.senderName)
        }
    }

    private fun updateStatus(host: QuickTileHost) {
        val state =
            if (serviceStateStore.status.value.first == AppStatus.Halted) {
                QuickTileVisualState.Inactive
            } else {
                QuickTileVisualState.Active
            }
        render(host, state)
    }

    private fun render(
        host: QuickTileHost,
        state: QuickTileVisualState,
    ) {
        if (currentTileState == state) {
            return
        }
        currentTileState = state
        host.renderTileState(state)
    }

    private fun clearSubscriptions() {
        statusJob?.cancel()
        statusJob = null
        eventsJob?.cancel()
        eventsJob = null
    }

    private val Mode.senderName: String
        get() =
            when (this) {
                Mode.VPN -> Sender.VPN.senderName
                Mode.Proxy -> Sender.Proxy.senderName
            }
}
