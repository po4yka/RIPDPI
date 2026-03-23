package com.poyka.ripdpi.activities

import android.content.Context
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticsActiveConnectionPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.robolectric.RuntimeEnvironment

internal fun createDiagnosticsViewModel(
    appContext: Context = RuntimeEnvironment.getApplication(),
    diagnosticsTimelineSource: DiagnosticsTimelineSource,
    appSettingsRepository: AppSettingsRepository,
    diagnosticsBootstrapper: DiagnosticsBootstrapper = StubDiagnosticsBootstrapper(),
    diagnosticsScanController: DiagnosticsScanController = StubDiagnosticsScanController(),
    diagnosticsDetailLoader: DiagnosticsDetailLoader = StubDiagnosticsDetailLoader(),
    diagnosticsShareService: DiagnosticsShareService = StubDiagnosticsShareService(),
    diagnosticsResolverActions: DiagnosticsResolverActions = StubDiagnosticsResolverActions(),
    rememberedPolicySource: DiagnosticsRememberedPolicySource = EmptyRememberedNetworkPolicySource(),
    activeConnectionPolicySource: DiagnosticsActiveConnectionPolicySource = EmptyActiveConnectionPolicySource(),
    serviceStateStore: ServiceStateStore = DefaultServiceStateStore(),
    initialize: Boolean = true,
): DiagnosticsViewModel =
    DiagnosticsUiFactorySupport(appContext).let { support ->
        DiagnosticsViewModel(
            diagnosticsBootstrapper = diagnosticsBootstrapper,
            diagnosticsTimelineSource = diagnosticsTimelineSource,
            diagnosticsScanController = diagnosticsScanController,
            diagnosticsDetailLoader = diagnosticsDetailLoader,
            diagnosticsShareService = diagnosticsShareService,
            diagnosticsResolverActions = diagnosticsResolverActions,
            appSettingsRepository = appSettingsRepository,
            rememberedPolicySource = rememberedPolicySource,
            activeConnectionPolicySource = activeConnectionPolicySource,
            serviceStateStore = serviceStateStore,
            uiStateFactory =
                DiagnosticsUiStateFactory(
                    support = support,
                    sessionDetailUiMapper = DiagnosticsSessionDetailUiFactory(support),
                ),
        ).also { viewModel ->
            if (initialize) {
                viewModel.initialize()
            }
        }
    }

internal fun createDiagnosticsViewModel(
    appContext: Context = RuntimeEnvironment.getApplication(),
    diagnosticsManager: FakeDiagnosticsManager,
    appSettingsRepository: AppSettingsRepository,
    rememberedPolicySource: DiagnosticsRememberedPolicySource = EmptyRememberedNetworkPolicySource(),
    activeConnectionPolicySource: DiagnosticsActiveConnectionPolicySource = EmptyActiveConnectionPolicySource(),
    serviceStateStore: ServiceStateStore = DefaultServiceStateStore(),
    initialize: Boolean = true,
): DiagnosticsViewModel =
    createDiagnosticsViewModel(
        appContext = appContext,
        diagnosticsBootstrapper = diagnosticsManager.bootstrapper,
        diagnosticsTimelineSource = diagnosticsManager.timelineSource,
        diagnosticsScanController = diagnosticsManager.scanController,
        diagnosticsDetailLoader = diagnosticsManager.detailLoader,
        diagnosticsShareService = diagnosticsManager.shareService,
        diagnosticsResolverActions = diagnosticsManager.resolverActions,
        appSettingsRepository = appSettingsRepository,
        rememberedPolicySource = rememberedPolicySource,
        activeConnectionPolicySource = activeConnectionPolicySource,
        serviceStateStore = serviceStateStore,
        initialize = initialize,
    )

private class EmptyRememberedNetworkPolicySource : DiagnosticsRememberedPolicySource {
    private val policies = MutableStateFlow<List<DiagnosticsRememberedPolicy>>(emptyList())

    override fun observePolicies(limit: Int): Flow<List<DiagnosticsRememberedPolicy>> = policies

    override suspend fun clearAll() {
        policies.value = emptyList()
    }
}

private class EmptyActiveConnectionPolicySource : DiagnosticsActiveConnectionPolicySource {
    override val activePolicies: StateFlow<Map<Mode, DiagnosticActiveConnectionPolicy>> =
        MutableStateFlow(emptyMap())
}
