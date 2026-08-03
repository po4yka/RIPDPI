package com.poyka.ripdpi.activities

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
internal class HomeDiagnosticsStateOwner(
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

    val uiState: StateFlow<HomeDiagnosticsUiState> =
        combine(
            settingsState,
            serviceStateStore.status,
            connectionRuntimeState,
            runtimeState,
        ) { settings, statusAndMode, connectionRuntime, homeDiagnosticsRuntime ->
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
            initialValue = HomeDiagnosticsUiState(),
        )

    val diagnosticCard: StateFlow<HomeModeCardUiState> =
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
                        homeDiagnostics = HomeDiagnosticsUiState(),
                        stringResolver = stringResolver,
                    ),
            )

    val actions: MainHomeDiagnosticsActions =
        MainHomeDiagnosticsActions(
            mutations = mutations,
            diagnosticsTimelineSource = diagnosticsDependencies.diagnosticsTimelineSource,
            diagnosticsScanController = diagnosticsDependencies.diagnosticsScanController,
            diagnosticsShareService = diagnosticsDependencies.diagnosticsShareService,
            diagnosticsHomeWorkflowService = diagnosticsDependencies.homeDiagnosticsServices.workflowService,
            diagnosticsHomeCompositeRunService = diagnosticsDependencies.homeDiagnosticsServices.compositeRunService,
            serviceStateStore = serviceStateStore,
            latestDirectModeOutcomeStore = diagnosticsDependencies.latestDirectModeOutcomeStore,
            runtimeState = connectionRuntimeState,
            permissionState = permissionState,
            homeDiagnosticsState = this.runtimeState,
            stringResolver = stringResolver,
            requestVpnStart = requestVpnStart,
            pcapCaptureRuntimeController = pcapCaptureRuntimeController,
        )

    fun initialize() {
        actions.initialize()
    }
}
