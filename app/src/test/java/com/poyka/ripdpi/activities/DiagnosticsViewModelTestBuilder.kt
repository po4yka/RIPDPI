package com.poyka.ripdpi.activities

import android.content.Context
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
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
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore = EmptyRememberedNetworkPolicyStore(),
    activeConnectionPolicyStore: ActiveConnectionPolicyStore = EmptyActiveConnectionPolicyStore(),
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
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            activeConnectionPolicyStore = activeConnectionPolicyStore,
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
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore = EmptyRememberedNetworkPolicyStore(),
    activeConnectionPolicyStore: ActiveConnectionPolicyStore = EmptyActiveConnectionPolicyStore(),
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
        rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
        activeConnectionPolicyStore = activeConnectionPolicyStore,
        serviceStateStore = serviceStateStore,
        initialize = initialize,
    )

private class EmptyRememberedNetworkPolicyStore : RememberedNetworkPolicyStore {
    private val policies = MutableStateFlow<List<RememberedNetworkPolicyEntity>>(emptyList())

    override fun observePolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> = policies

    override suspend fun findValidatedMatch(
        fingerprintHash: String,
        mode: Mode,
    ): RememberedNetworkPolicyEntity? = null

    override suspend fun upsertObservedPolicy(
        policy: RememberedNetworkPolicyJson,
        source: String,
        observedAt: Long?,
    ): RememberedNetworkPolicyEntity = error("EmptyRememberedNetworkPolicyStore does not support persistence")

    override suspend fun rememberValidatedPolicy(
        policy: RememberedNetworkPolicyJson,
        source: String,
        validatedAt: Long?,
    ): RememberedNetworkPolicyEntity = error("EmptyRememberedNetworkPolicyStore does not support persistence")

    override suspend fun recordApplied(
        policy: RememberedNetworkPolicyEntity,
        appliedAt: Long?,
    ): RememberedNetworkPolicyEntity = policy

    override suspend fun recordSuccess(
        policy: RememberedNetworkPolicyEntity,
        validated: Boolean,
        strategySignatureJson: String?,
        completedAt: Long?,
    ): RememberedNetworkPolicyEntity = policy

    override suspend fun recordFailure(
        policy: RememberedNetworkPolicyEntity,
        failedAt: Long?,
        allowSuppression: Boolean,
    ): RememberedNetworkPolicyEntity = policy

    override suspend fun clearAll() {
        policies.value = emptyList()
    }
}

private class EmptyActiveConnectionPolicyStore : ActiveConnectionPolicyStore {
    override val activePolicies: StateFlow<Map<com.poyka.ripdpi.data.Mode, ActiveConnectionPolicy>> =
        MutableStateFlow(emptyMap())
}
