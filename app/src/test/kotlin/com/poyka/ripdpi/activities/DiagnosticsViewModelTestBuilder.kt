package com.poyka.ripdpi.activities

import android.content.Context
import androidx.lifecycle.SavedStateHandle
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
import com.poyka.ripdpi.diagnostics.dpi.DnsAvailabilitySurvey
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityChecker
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityScanner
import com.poyka.ripdpi.diagnostics.dpich.HttpCompressionProber
import com.poyka.ripdpi.diagnostics.rkn.RknLayeredProbePipeline
import com.poyka.ripdpi.diagnostics.rkn.SelfInfoFetcher
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
    dnsIntegrityChecker: DnsIntegrityChecker = DnsIntegrityChecker(),
    domainReachabilityScanner: DomainReachabilityScanner = DomainReachabilityScanner(),
    dnsAvailabilitySurvey: DnsAvailabilitySurvey = DnsAvailabilitySurvey(),
    rknLayeredProbePipeline: RknLayeredProbePipeline = RknLayeredProbePipeline(),
    selfInfoFetcher: SelfInfoFetcher = SelfInfoFetcher(),
    httpCompressionProber: HttpCompressionProber = HttpCompressionProber(),
    autoStartScan: Boolean = false,
    initialize: Boolean = true,
): DiagnosticsViewModel =
    DiagnosticsUiFactorySupport(appContext).let { support ->
        val uiStateFactory =
            DiagnosticsUiStateFactory(
                support = support,
                sessionDetailUiMapper = DiagnosticsSessionDetailUiFactory(support),
                resolver = DiagnosticsUiInputResolver(support),
                overviewFactory = DiagnosticsOverviewUiStateFactory(support),
                scanFactory = DiagnosticsScanUiStateFactory(support),
                liveFactory = DiagnosticsLiveUiStateFactory(support),
                sessionsFactory = DiagnosticsSessionsUiStateFactory(support),
                approachesFactory = DiagnosticsApproachesUiStateFactory(support),
                eventsFactory = DiagnosticsEventsUiStateFactory(support),
                shareFactory = DiagnosticsShareUiStateFactory(support),
                performanceFactory = DiagnosticsPerformanceUiStateFactory(),
            )
        DiagnosticsViewModel(
            savedStateHandle =
                SavedStateHandle(
                    mapOf("auto_start_scan" to autoStartScan),
                ),
            diagnosticsInteractionDependencies =
                DiagnosticsInteractionDependencies(
                    diagnosticsTimelineSource = diagnosticsTimelineSource,
                    diagnosticsScanController = diagnosticsScanController,
                    diagnosticsDetailLoader = diagnosticsDetailLoader,
                    diagnosticsShareService = diagnosticsShareService,
                    diagnosticsResolverActions = diagnosticsResolverActions,
                ),
            diagnosticsContextDependencies =
                DiagnosticsContextDependencies(
                    appSettingsRepository = appSettingsRepository,
                    appContext = appContext,
                    rememberedPolicySource = rememberedPolicySource,
                    activeConnectionPolicySource = activeConnectionPolicySource,
                    serviceStateStore = serviceStateStore,
                ),
            diagnosticsViewModelBootstrapper = DiagnosticsViewModelBootstrapper(diagnosticsBootstrapper),
            appSettingsRepository = appSettingsRepository,
            dnsIntegrityChecker = dnsIntegrityChecker,
            domainReachabilityScanner = domainReachabilityScanner,
            dnsAvailabilitySurvey = dnsAvailabilitySurvey,
            rknLayeredProbePipeline = rknLayeredProbePipeline,
            selfInfoFetcher = selfInfoFetcher,
            httpCompressionProber = httpCompressionProber,
            diagnosticsUiStateAssembler =
                DiagnosticsUiStateAssembler(
                    uiStateFactory = uiStateFactory,
                ),
            uiStateFactory = uiStateFactory,
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
    autoStartScan: Boolean = false,
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
        autoStartScan = autoStartScan,
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
