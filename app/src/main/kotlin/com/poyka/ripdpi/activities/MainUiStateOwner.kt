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
            serviceDependencies.privacyThreatStateStore.snapshot,
        ) { (base, hardKillSwitch), privacyThreat ->
            Triple(base, hardKillSwitch, privacyThreat)
        }.combine(
            controlPlaneDependencies.hostPackCatalogUiStateStore.state,
        ) { (base, hardKillSwitch, privacyThreat), hostPackCatalog ->
            MainUiStateOwnerIntermediate(base, hardKillSwitch, privacyThreat, hostPackCatalog)
        }.combine(
            serviceDependencies.serviceStateStore.telemetry,
        ) { intermediate, telemetry ->
            intermediate.copy(base = intermediate.base.copy(telemetry = telemetry))
        }.combine(
            controlPlaneDependencies.strategyPackStateStore.state,
        ) { intermediate, strategyPackRuntimeState ->
            MainUiInputs(
                settings = intermediate.base.settings,
                statusAndMode = intermediate.base.statusAndMode,
                runtime = intermediate.base.runtime,
                telemetry = intermediate.base.telemetry,
                permissions = intermediate.base.permissions,
                hardKillSwitch = intermediate.hardKillSwitch,
                privacyThreat = intermediate.privacyThreat,
                approachStats = intermediate.base.approachStats,
                hostPackCatalog = intermediate.hostPackCatalog,
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

private data class MainUiStateOwnerIntermediate(
    val base: MainUiInputsBase,
    val hardKillSwitch: com.poyka.ripdpi.services.AndroidHardKillSwitchSnapshot,
    val privacyThreat: com.poyka.ripdpi.services.PrivacyThreatSnapshot,
    val hostPackCatalog: HostPackCatalogUiState,
)
