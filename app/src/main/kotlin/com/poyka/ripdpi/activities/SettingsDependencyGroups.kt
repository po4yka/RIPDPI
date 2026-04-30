package com.poyka.ripdpi.activities

import android.app.Application
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.data.WarpPayloadGenCatalog
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import com.poyka.ripdpi.hosts.HostPackCatalogUiStateCoordinator
import com.poyka.ripdpi.hosts.HostPackCatalogUiStateStore
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.security.PinLockoutManager
import com.poyka.ripdpi.security.PinVerifier
import com.poyka.ripdpi.services.EnginePlatformCapabilities
import com.poyka.ripdpi.services.HostAutolearnStoreController
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import com.poyka.ripdpi.services.TelemetryInstallSaltStore
import com.poyka.ripdpi.strategy.StrategyPackService
import javax.inject.Inject

class SettingsUiDependencies
    @Inject
    constructor(
        val appSettingsRepository: AppSettingsRepository,
        val rememberedPolicySource: DiagnosticsRememberedPolicySource,
        val serviceStateStore: ServiceStateStore,
        val hostAutolearnStoreController: HostAutolearnStoreController,
        val routingProtectionCatalogService: RoutingProtectionCatalogService,
        val warpPayloadGenCatalog: WarpPayloadGenCatalog,
        val networkSnapshotProvider: NativeNetworkSnapshotProvider,
        val enginePlatformCapabilities: EnginePlatformCapabilities,
        val application: Application,
    )

class SettingsActionDependencies
    @Inject
    constructor(
        val hostPackCatalogUiStateCoordinator: HostPackCatalogUiStateCoordinator,
        val hostPackCatalogUiStateStore: HostPackCatalogUiStateStore,
        val strategyPackStateStore: StrategyPackStateStore,
        val launcherIconController: LauncherIconController,
        val stringResolver: StringResolver,
        val hostAutolearnStoreController: HostAutolearnStoreController,
        val telemetrySaltStore: TelemetryInstallSaltStore,
        val strategyPackService: StrategyPackService,
        val pinVerifier: PinVerifier,
        val pinLockoutManager: PinLockoutManager,
        val rememberedPolicySource: DiagnosticsRememberedPolicySource,
        val serviceStateStore: ServiceStateStore,
    )
