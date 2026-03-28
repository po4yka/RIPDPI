package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
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

    fun notificationsPermissionGranted(): Boolean

    fun vpnPermissionRequired(): Boolean
}

internal class QuickTileController(
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
                            Unit
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
                    val settings = appSettingsRepository.snapshot()
                    val mode = Mode.fromString(settings.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue })

                    if (needsPermissionResolution(mode, host)) {
                        updateStatus(host)
                        host.launchStartResolution()
                        return@launch
                    }

                    serviceController.start(mode)
                }
            }

            AppStatus.Running -> {
                serviceController.stop()
            }
        }
    }

    private fun needsPermissionResolution(
        mode: Mode,
        host: QuickTileHost,
    ): Boolean = !host.notificationsPermissionGranted() || (mode == Mode.VPN && host.vpnPermissionRequired())

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
}
