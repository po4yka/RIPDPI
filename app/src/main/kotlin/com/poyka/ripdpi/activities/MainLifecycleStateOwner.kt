package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.crash.CrashReport
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal class MainLifecycleStateOwner(
    private val scope: CoroutineScope,
    settings: Flow<AppSettings>,
    private val settingsState: StateFlow<AppSettings>,
    private val permissionState: StateFlow<PermissionRuntimeState>,
    private val dependencies: MainLifecycleDependencies,
    private val effects: MutableSharedFlow<MainEffect>,
) {
    private val pendingCrashReportState = MutableStateFlow<CrashReport?>(null)

    val pendingCrashReport: StateFlow<CrashReport?> = pendingCrashReportState.asStateFlow()

    val crashReports =
        MainCrashReportActions(
            coordinator = dependencies.crashReportCoordinator,
            scope = scope,
            pendingCrashReport = pendingCrashReportState,
        )

    val appLock =
        MainAppLockActions(
            coordinator = dependencies.appLockLifecycleCoordinator,
        )

    val startupState: StateFlow<MainStartupState> =
        settings
            .map { settings ->
                MainStartupState(
                    isReady = true,
                    theme = settings.appTheme.ifEmpty { "system" },
                    startDestination = resolveStartupDestination(settings),
                )
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = MainStartupState(),
            )

    fun initialize() {
        dependencies.appLockLifecycleCoordinator.start(
            isBiometricEnabled = { settingsState.value.biometricEnabled },
        ) {
            effects.tryEmit(MainEffect.RelockRequested)
        }
        dependencies.startupSideEffectsCoordinator.start(
            scope = scope,
            batteryOptimizationStatus = permissionState.map { it.snapshot.batteryOptimization },
            isBatteryBannerDismissed = { settingsState.value.batteryBannerDismissed },
        ) { report ->
            pendingCrashReportState.value = report
        }
    }
}
