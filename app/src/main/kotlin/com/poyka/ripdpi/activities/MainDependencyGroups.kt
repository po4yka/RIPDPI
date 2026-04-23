package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.hosts.HostPackCatalogUiStateCoordinator
import com.poyka.ripdpi.hosts.HostPackCatalogUiStateStore
import com.poyka.ripdpi.permissions.PermissionCoordinator
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.TrafficStatsReader
import com.poyka.ripdpi.services.ServiceController
import javax.inject.Inject

class MainServiceDependencies
    @Inject
    constructor(
        val serviceStateStore: ServiceStateStore,
        val serviceController: ServiceController,
        val trafficStatsReader: TrafficStatsReader,
    )

class MainPermissionDependencies
    @Inject
    constructor(
        val permissionPlatformBridge: PermissionPlatformBridge,
        val permissionStatusProvider: PermissionStatusProvider,
        val permissionCoordinator: PermissionCoordinator,
    )

class MainDiagnosticsDependencies
    @Inject
    constructor(
        val diagnosticsTimelineSource: DiagnosticsTimelineSource,
        val diagnosticsScanController: DiagnosticsScanController,
        val diagnosticsShareService: DiagnosticsShareService,
        val homeDiagnosticsServices: HomeDiagnosticsServices,
    )

class MainControlPlaneDependencies
    @Inject
    constructor(
        val hostPackCatalogUiStateStore: HostPackCatalogUiStateStore,
        val hostPackCatalogUiStateCoordinator: HostPackCatalogUiStateCoordinator,
        val strategyPackStateStore: StrategyPackStateStore,
    )

class MainLifecycleDependencies
    @Inject
    constructor(
        val appLockLifecycleCoordinator: MainAppLockLifecycleCoordinator,
        val startupSideEffectsCoordinator: MainStartupSideEffectsCoordinator,
        val settingsDismissCoordinator: MainSettingsDismissCoordinator,
        val crashReportCoordinator: MainCrashReportCoordinator,
    )
