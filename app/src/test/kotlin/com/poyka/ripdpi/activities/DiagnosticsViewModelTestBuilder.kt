package com.poyka.ripdpi.activities

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.poyka.ripdpi.data.AppSettingsRepository
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
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpi.EchTlsHandshake
import com.poyka.ripdpi.diagnostics.dpi.EchTlsHandshakeResult
import com.poyka.ripdpi.diagnostics.dpi.Tcp16FatHeaderProbe
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistDetector
import com.poyka.ripdpi.diagnostics.dpich.HttpCompressionProber
import com.poyka.ripdpi.diagnostics.dpich.Ipv4WhitelistedSubnetDiscoverer
import com.poyka.ripdpi.diagnostics.dpich.RipeStatPrefixSource
import com.poyka.ripdpi.diagnostics.dpich.SubnetAliveProbe
import com.poyka.ripdpi.diagnostics.dpich.SubnetAliveProbeResult
import com.poyka.ripdpi.diagnostics.dpich.SubnetsCache
import com.poyka.ripdpi.diagnostics.dpich.TlsKeylogRunFinalizer
import com.poyka.ripdpi.diagnostics.rkn.RknLayeredProbePipeline
import com.poyka.ripdpi.diagnostics.rkn.SelfInfoFetcher
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceGate
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceReport
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.robolectric.RuntimeEnvironment
import java.io.File

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
    serviceStateStore: ServiceStateStore = FakeServiceStateStore(),
    probeDependencies: DiagnosticsProbeDependencies = defaultDiagnosticsProbeDependencies(appContext),
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
                    rememberedPolicySource = rememberedPolicySource,
                    activeConnectionPolicySource = activeConnectionPolicySource,
                    serviceStateStore = serviceStateStore,
                ),
            diagnosticsViewModelBootstrapper = DiagnosticsViewModelBootstrapper(diagnosticsBootstrapper),
            appSettingsRepository = appSettingsRepository,
            serviceStateStore = serviceStateStore,
            xrayProviderProbeCoordinator =
                com.poyka.ripdpi.data.xray
                    .DefaultXrayProviderProbeCoordinator(),
            probeDependencies = probeDependencies,
            diagnosticsFiles = DiagnosticsFiles(appContext),
            stringResolver = AndroidStringResolver(appContext),
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
    serviceStateStore: ServiceStateStore = FakeServiceStateStore(),
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

private fun defaultDiagnosticsProbeDependencies(appContext: Context): DiagnosticsProbeDependencies =
    DiagnosticsProbeDependencies(
        dnsIntegrityChecker = DnsIntegrityChecker(),
        domainReachabilityScanner = DomainReachabilityScanner(),
        dnsAvailabilitySurvey = DnsAvailabilitySurvey(),
        rknLayeredProbePipeline = RknLayeredProbePipeline(),
        selfInfoFetcher = SelfInfoFetcher(),
        httpCompressionProber = HttpCompressionProber(),
        cidrWhitelistDetector = CidrWhitelistDetector(emptyList(), emptyList()),
        ipv4WhitelistedSubnetDiscoverer = emptyIpv4WhitelistedSubnetDiscoverer(),
        tcp16FatHeaderProbe = Tcp16FatHeaderProbe(),
        assetLoader = DpiAssetLoader(appContext),
        tlsKeylogRunFinalizer = TlsKeylogRunFinalizer(),
        echTlsHandshake = StubEchTlsHandshake,
        remoteDeviceAcceptance =
            DiagnosticsRemoteDeviceAcceptance(
                gate = StubRemoteDeviceAcceptanceGate,
                stringResolver = AndroidStringResolver(appContext),
            ),
    )

private object StubRemoteDeviceAcceptanceGate : RemoteDeviceAcceptanceGate {
    override val report = MutableStateFlow(RemoteDeviceAcceptanceReport())

    override fun start(scope: kotlinx.coroutines.CoroutineScope) = Unit

    override fun renderRedactedReport(): String = "{}"
}

private object StubEchTlsHandshake : EchTlsHandshake {
    override suspend fun connect(
        target: String,
        echConfig: ByteArray,
    ): EchTlsHandshakeResult = EchTlsHandshakeResult(negotiatedEch = false, latencyMs = null)
}

private class EmptyRememberedNetworkPolicySource : DiagnosticsRememberedPolicySource {
    private val policies = MutableStateFlow<List<DiagnosticsRememberedPolicy>>(emptyList())

    override fun observePolicies(limit: Int): Flow<List<DiagnosticsRememberedPolicy>> = policies

    override suspend fun deletePolicy(policy: DiagnosticsRememberedPolicy) {
        policies.value = policies.value.filterNot { it.id == policy.id }
    }

    override suspend fun clearAll() {
        policies.value = emptyList()
    }
}

private class EmptyActiveConnectionPolicySource : DiagnosticsActiveConnectionPolicySource {
    override val activePolicies: StateFlow<Map<Mode, DiagnosticActiveConnectionPolicy>> =
        MutableStateFlow(emptyMap())
}

private fun emptyIpv4WhitelistedSubnetDiscoverer(): Ipv4WhitelistedSubnetDiscoverer =
    Ipv4WhitelistedSubnetDiscoverer(
        asns = emptyList(),
        ripeStat = RipeStatPrefixSource { emptyList() },
        cache =
            SubnetsCache(
                File
                    .createTempFile("ipv4-whitelist", ".json")
                    .also(File::delete),
            ),
        aliveProbe =
            object : SubnetAliveProbe {
                override suspend fun probe(
                    ip: String,
                    timeoutMs: Long,
                ): SubnetAliveProbeResult = SubnetAliveProbeResult.Unreachable
            },
    )
