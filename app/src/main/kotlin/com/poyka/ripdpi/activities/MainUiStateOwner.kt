package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
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
internal class MainUiStateOwner(
    scope: CoroutineScope,
    settingsState: StateFlow<AppSettings>,
    serviceDependencies: MainServiceDependencies,
    diagnosticsDependencies: MainDiagnosticsDependencies,
    controlPlaneDependencies: MainControlPlaneDependencies,
    runtimeState: MutableStateFlow<ConnectionRuntimeState>,
    permissionState: MutableStateFlow<PermissionRuntimeState>,
    stringResolver: StringResolver,
    buildApproachSummary: (AppSettings, Mode, List<BypassApproachSummary>) -> HomeApproachSummaryUiState?,
) {
    val uiState: StateFlow<MainUiState> =
        combine(
            settingsState,
            serviceDependencies.serviceStateStore.status,
            runtimeState,
            permissionState,
            diagnosticsDependencies.diagnosticsTimelineSource.approachStats,
        ) { settings, statusAndMode, runtime, permissions, approachStats ->
            MainUiInputsBase(
                settings = settings,
                statusAndMode = statusAndMode,
                runtime = runtime,
                telemetry = serviceDependencies.serviceStateStore.telemetry.value,
                permissions = permissions,
                approachStats = approachStats,
            )
        }.combine(
            serviceDependencies.hardKillSwitchStateStore.snapshot,
        ) { base, hardKillSwitch ->
            base to hardKillSwitch
        }.combine(
            controlPlaneDependencies.hostPackCatalogUiStateStore.state,
        ) { (base, hardKillSwitch), hostPackCatalog ->
            Triple(base, hardKillSwitch, hostPackCatalog)
        }.combine(
            serviceDependencies.serviceStateStore.telemetry,
        ) { (base, hardKillSwitch, hostPackCatalog), telemetry ->
            Triple(base.copy(telemetry = telemetry), hardKillSwitch, hostPackCatalog)
        }.combine(
            controlPlaneDependencies.strategyPackStateStore.state,
        ) { (base, hardKillSwitch, hostPackCatalog), strategyPackRuntimeState ->
            MainUiInputs(
                settings = base.settings,
                statusAndMode = base.statusAndMode,
                runtime = base.runtime,
                telemetry = base.telemetry,
                permissions = base.permissions,
                hardKillSwitch = hardKillSwitch,
                approachStats = base.approachStats,
                hostPackCatalog = hostPackCatalog,
                strategyPackRuntimeState = strategyPackRuntimeState,
            )
        }.map { inputs ->
            val settings = inputs.settings
            val (status, activeMode) = inputs.statusAndMode
            val configuredMode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" })
            buildMainUiState(
                inputs = inputs,
                stringResolver = stringResolver,
                approachSummary =
                    buildApproachSummary(
                        settings,
                        if (status == AppStatus.Running) activeMode else configuredMode,
                        inputs.approachStats,
                    ),
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState(),
        )
}
