package com.poyka.ripdpi.ui.screens.status

import com.poyka.ripdpi.activities.ConnectionRuntimeState
import com.poyka.ripdpi.activities.HomeDiagnosticsRuntimeState
import com.poyka.ripdpi.activities.MainDiagnosticsDependencies
import com.poyka.ripdpi.activities.MainHomeDiagnosticsActions
import com.poyka.ripdpi.activities.MainMutationRunner
import com.poyka.ripdpi.activities.PermissionRuntimeState
import com.poyka.ripdpi.activities.buildHomeDiagnosticsUiState
import com.poyka.ripdpi.activities.resolveEffectiveConnectionState
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeController
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Suppress("LongParameterList")
internal class StatusDiagnosticsStateOwner(
    scope: CoroutineScope,
    settingsState: StateFlow<AppSettings>,
    serviceStateStore: com.poyka.ripdpi.data.ServiceStateStore,
    connectionRuntimeState: MutableStateFlow<ConnectionRuntimeState>,
    permissionState: MutableStateFlow<PermissionRuntimeState>,
    mutations: MainMutationRunner,
    diagnosticsDependencies: MainDiagnosticsDependencies,
    stringResolver: StringResolver,
    requestVpnStart: () -> Unit,
    pcapCaptureRuntimeController: PcapCaptureRuntimeController? = null,
) {
    val runtimeState: MutableStateFlow<HomeDiagnosticsRuntimeState> =
        MutableStateFlow(HomeDiagnosticsRuntimeState())

    private val localeState = MutableStateFlow("")

    val uiState: StateFlow<StatusDiagnosticsUiState> =
        combine(
            settingsState,
            serviceStateStore.status,
            connectionRuntimeState,
            runtimeState,
            localeState,
        ) { settings, statusAndMode, connectionRuntime, homeDiagnosticsRuntime, _ ->
            buildHomeDiagnosticsUiState(
                settings = settings,
                appStatus = statusAndMode.first,
                connectionState =
                    resolveEffectiveConnectionState(
                        appStatus = statusAndMode.first,
                        runtimeConnectionState = connectionRuntime.connectionState,
                    ),
                runtime = homeDiagnosticsRuntime,
                stringResolver = stringResolver,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = StatusDiagnosticsUiState(),
        )

    val diagnosticCard: StateFlow<StatusModeCardUiState> =
        uiState
            .map { homeDiagnostics ->
                buildDiagnosticCard(
                    homeDiagnostics = homeDiagnostics,
                    stringResolver = stringResolver,
                )
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue =
                    buildDiagnosticCard(
                        homeDiagnostics = StatusDiagnosticsUiState(),
                        stringResolver = stringResolver,
                    ),
            )

    val actions: MainHomeDiagnosticsActions =
        MainHomeDiagnosticsActions(
            mutations = mutations,
            diagnosticsTimelineSource = diagnosticsDependencies.diagnosticsTimelineSource,
            diagnosticsScanController = diagnosticsDependencies.diagnosticsScanController,
            diagnosticsHomeWorkflowService = diagnosticsDependencies.homeDiagnosticsServices.workflowService,
            diagnosticsHomeCompositeRunService = diagnosticsDependencies.homeDiagnosticsServices.compositeRunService,
            serviceStateStore = serviceStateStore,
            latestDirectModeOutcomeStore = diagnosticsDependencies.latestDirectModeOutcomeStore,
            runtimeState = connectionRuntimeState,
            permissionState = permissionState,
            homeDiagnosticsState = this.runtimeState,
            stringResolver = stringResolver,
            requestVpnStart = requestVpnStart,
            vpnStartBlockedByDns = { hasUnsupportedVpnDoq(settingsState.value) },
            pcapCaptureRuntimeController = pcapCaptureRuntimeController,
        )

    fun onLocaleChanged(localeTags: String) {
        localeState.value = localeTags
    }

    fun initialize() {
        actions.initialize()
    }
}
