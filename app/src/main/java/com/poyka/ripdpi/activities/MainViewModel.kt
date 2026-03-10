package com.poyka.ripdpi.activities

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.platform.TrafficStatsReader
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceEvent
import com.poyka.ripdpi.services.ServiceStateStore
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Error,
}

sealed interface MainEffect {
    data class RequestVpnPermission(
        val prepareIntent: Intent,
    ) : MainEffect

    data class ShowError(
        val message: String,
    ) : MainEffect
}

data class MainUiState(
    val appStatus: AppStatus = AppStatus.Halted,
    val activeMode: Mode = Mode.VPN,
    val configuredMode: Mode = Mode.VPN,
    val proxyIp: String = "127.0.0.1",
    val proxyPort: String = "1080",
    val theme: String = "system",
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val connectionDuration: Duration = ZERO,
    val dataTransferred: Long = 0L,
    val errorMessage: String? = null,
) {
    val isConnected: Boolean
        get() = connectionState == ConnectionState.Connected

    val isConnecting: Boolean
        get() = connectionState == ConnectionState.Connecting
}

private data class ConnectionRuntimeState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val errorMessage: String? = null,
    val connectionStartedAtMs: Long? = null,
    val baselineTransferredBytes: Long = 0L,
    val dataTransferred: Long = 0L,
    val connectionDuration: Duration = ZERO,
)

internal fun calculateTransferredBytes(
    totalBytes: Long,
    baselineBytes: Long,
): Long = (totalBytes - baselineBytes).coerceAtLeast(0L)

internal fun shouldPollConnectionMetrics(connectionState: ConnectionState): Boolean =
    connectionState == ConnectionState.Connected

data class MainStartupState(
    val isReady: Boolean = false,
    val theme: String = "system",
    val startDestination: String = Route.Home.route,
)

internal fun resolveStartupDestination(settings: AppSettings): String =
    when {
        !settings.onboardingComplete -> Route.Onboarding.route
        settings.biometricEnabled -> Route.BiometricPrompt.route
        else -> Route.Home.route
    }

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        appSettingsRepository: AppSettingsRepository,
        private val serviceStateStore: ServiceStateStore,
        private val serviceController: ServiceController,
        private val stringResolver: StringResolver,
        private val trafficStatsReader: TrafficStatsReader,
    ) : ViewModel() {
    private val runtimeState = MutableStateFlow(ConnectionRuntimeState())
    private var connectionMetricsJob: Job? = null
    private val settingsState: StateFlow<AppSettings> =
        appSettingsRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettingsSerializer.defaultValue,
        )

    private val _effects = Channel<MainEffect>(Channel.BUFFERED)
    val effects: Flow<MainEffect> = _effects.receiveAsFlow()

    val startupState: StateFlow<MainStartupState> =
        settingsState
            .map { settings ->
                MainStartupState(
                    isReady = true,
                    theme = settings.appTheme.ifEmpty { "system" },
                    startDestination = resolveStartupDestination(settings),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = MainStartupState(),
            )

    val uiState: StateFlow<MainUiState> =
        combine(
            settingsState,
            serviceStateStore.status,
            runtimeState,
        ) { settings, (status, activeMode), runtime ->
            MainUiState(
                appStatus = status,
                activeMode = activeMode,
                configuredMode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" }),
                proxyIp = settings.proxyIp.ifEmpty { "127.0.0.1" },
                proxyPort = if (settings.proxyPort > 0) settings.proxyPort.toString() else "1080",
                theme = settings.appTheme.ifEmpty { "system" },
                connectionState = runtime.connectionState,
                connectionDuration = runtime.connectionDuration,
                dataTransferred = runtime.dataTransferred,
                errorMessage = runtime.errorMessage,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState(),
        )

    init {
        observeStatus()
        observeServiceEvents()
    }

    fun toggleService(context: Context) {
        when (uiState.value.connectionState) {
            ConnectionState.Connecting -> {
                return
            }

            ConnectionState.Connected -> {
                stop()
            }

            ConnectionState.Disconnected, ConnectionState.Error -> {
                when (uiState.value.appStatus) {
                    AppStatus.Halted -> start(context)
                    AppStatus.Running -> stop()
                }
            }
        }
    }

    fun dismissError() {
        runtimeState.update { current ->
            if (current.connectionState != ConnectionState.Error) {
                current
            } else {
                current.copy(
                    connectionState = ConnectionState.Disconnected,
                    errorMessage = null,
                )
            }
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        if (granted) {
            setConnectingState()
            serviceController.start(Mode.VPN)
            return
        }

        showError(stringResolver.getString(R.string.vpn_permission_denied))
    }

    fun onNotificationPermissionDenied() {
        showError(stringResolver.getString(R.string.permissions_notifications_denied))
    }

    fun requestVpnPermission(context: Context) {
        when (uiState.value.connectionState) {
            ConnectionState.Connecting,
            ConnectionState.Connected,
            -> return

            ConnectionState.Disconnected,
            ConnectionState.Error,
            -> Unit
        }

        dismissError()
        val intent = VpnService.prepare(context)
        if (intent != null) {
            _effects.trySend(MainEffect.RequestVpnPermission(intent))
        } else {
            setConnectingState()
            serviceController.start(Mode.VPN)
        }
    }

    private fun start(context: Context) {
        val configuredMode = uiState.value.configuredMode
        setConnectingState()

        when (configuredMode) {
            Mode.VPN -> {
                val intent = VpnService.prepare(context)
                if (intent != null) {
                    _effects.trySend(MainEffect.RequestVpnPermission(intent))
                } else {
                    serviceController.start(Mode.VPN)
                }
            }

            Mode.Proxy -> {
                serviceController.start(Mode.Proxy)
            }
        }
    }

    private fun stop() {
        serviceController.stop()
    }

    private fun observeStatus() {
        viewModelScope.launch {
            serviceStateStore.status.collect { (status, _) ->
                when (status) {
                    AppStatus.Running -> onConnected()
                    AppStatus.Halted -> onHalted()
                }
            }
        }
    }

    private fun observeServiceEvents() {
        viewModelScope.launch {
            serviceStateStore.events.collect { event ->
                when (event) {
                    is ServiceEvent.Failed -> onServiceFailed(event.sender)
                }
            }
        }
    }

    private fun onConnected() {
        val baselineBytes = currentTransferredBytes()
        val connectedAtMs = SystemClock.elapsedRealtime()

        runtimeState.update { current ->
            if (current.connectionState == ConnectionState.Connected && current.connectionStartedAtMs != null) {
                current
            } else {
                current.copy(
                    connectionState = ConnectionState.Connected,
                    errorMessage = null,
                    connectionStartedAtMs = connectedAtMs,
                    baselineTransferredBytes = baselineBytes,
                    dataTransferred = 0L,
                    connectionDuration = ZERO,
                )
            }
        }
        startConnectionMetricsPolling()
    }

    private fun onHalted() {
        stopConnectionMetricsPolling()
        runtimeState.update { current ->
            if (current.connectionState == ConnectionState.Error) {
                current.copy(
                    connectionStartedAtMs = null,
                    baselineTransferredBytes = 0L,
                    dataTransferred = 0L,
                    connectionDuration = ZERO,
                )
            } else {
                ConnectionRuntimeState()
            }
        }
    }

    private fun onServiceFailed(sender: Sender) {
        val message = stringResolver.getString(R.string.failed_to_start, sender.senderName)
        showError(message)
    }

    private fun showError(message: String) {
        stopConnectionMetricsPolling()
        runtimeState.update {
            it.copy(
                connectionState = ConnectionState.Error,
                errorMessage = message,
                connectionStartedAtMs = null,
                baselineTransferredBytes = 0L,
                dataTransferred = 0L,
                connectionDuration = ZERO,
            )
        }
        _effects.trySend(MainEffect.ShowError(message))
    }

    private fun setConnectingState() {
        stopConnectionMetricsPolling()
        runtimeState.update {
            it.copy(
                connectionState = ConnectionState.Connecting,
                errorMessage = null,
                connectionStartedAtMs = null,
                baselineTransferredBytes = 0L,
                dataTransferred = 0L,
                connectionDuration = ZERO,
            )
        }
    }

    private fun startConnectionMetricsPolling() {
        if (!shouldPollConnectionMetrics(runtimeState.value.connectionState)) {
            stopConnectionMetricsPolling()
            return
        }
        if (connectionMetricsJob?.isActive == true) {
            return
        }

        refreshConnectionMetrics()
        connectionMetricsJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(1.seconds)
                    refreshConnectionMetrics()
                }
            }
    }

    private fun stopConnectionMetricsPolling() {
        connectionMetricsJob?.cancel()
        connectionMetricsJob = null
    }

    private fun refreshConnectionMetrics() {
        val state = runtimeState.value
        val startedAtMs = state.connectionStartedAtMs ?: return
        if (!shouldPollConnectionMetrics(state.connectionState)) {
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        val totalBytes = currentTransferredBytes()
        runtimeState.update { current ->
            val currentStartedAtMs = current.connectionStartedAtMs ?: return@update current
            if (current.connectionState != ConnectionState.Connected) {
                current
            } else {
                current.copy(
                    connectionDuration = (nowMs - currentStartedAtMs).coerceAtLeast(0L).milliseconds,
                    dataTransferred =
                        calculateTransferredBytes(
                            totalBytes = totalBytes,
                            baselineBytes = current.baselineTransferredBytes,
                        ),
                )
            }
        }
    }

    private fun currentTransferredBytes(): Long {
        return trafficStatsReader.currentTransferredBytes()
    }
}
