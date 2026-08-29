package com.poyka.ripdpi.diagnostics

import android.app.Application
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.AndroidLocalNetworkAccess
import com.poyka.ripdpi.data.LocalNetworkPermission
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskFamily
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.contract.engine.ScanCompletionKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@LooperMode(LooperMode.Mode.PAUSED)
class DiagnosticsLocalNetworkPreflightTest {
    @Test
    fun `raw probe families defer local endpoints without removing same-id public TCP task`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            shadowOf(context.getSystemService(ConnectivityManager::class.java)).clearAllNetworks()
            val localIp = "192.168.50.2"
            val base =
                EngineScanRequestWire(
                    profileId = "mixed-families",
                    displayName = "Mixed probe families",
                    pathMode = ScanPathMode.RAW_PATH,
                )
            val cases =
                listOf(
                    EngineProbeTaskFamily.QUIC to base.copy(quicTargets = listOf(QuicTarget(host = localIp))),
                    EngineProbeTaskFamily.DNS to
                        base.copy(
                            dnsTargets = listOf(DnsTarget(domain = localIp, udpServer = localIp)),
                        ),
                    EngineProbeTaskFamily.SERVICE to
                        base.copy(
                            serviceTargets = listOf(ServiceTarget(localIp, "service", tcpEndpointIp = localIp)),
                        ),
                    EngineProbeTaskFamily.CIRCUMVENTION to
                        base.copy(
                            circumventionTargets = listOf(CircumventionTarget(localIp, "tool", handshakeIp = localIp)),
                        ),
                    EngineProbeTaskFamily.THROUGHPUT to
                        base.copy(
                            throughputTargets = listOf(ThroughputTarget(localIp, "download", "https://$localIp/data")),
                        ),
                    EngineProbeTaskFamily.TELEGRAM to
                        base.copy(
                            telegramTarget = TelegramTarget(mediaUrl = "https://8.8.4.4/media", uploadIp = localIp),
                        ),
                )

            for ((family, localRequest) in cases) {
                val targetId = if (family == EngineProbeTaskFamily.TELEGRAM) "telegram" else localIp
                val publicTarget = TcpTarget(id = targetId, provider = "public", ip = "8.8.8.8")
                val publicTask = EngineProbeTaskWire(EngineProbeTaskFamily.TCP, targetId, "Public TCP")
                val localTask = EngineProbeTaskWire(family, targetId, "Local target")
                val expected = base.copy(tcpTargets = listOf(publicTarget), probeTasks = listOf(publicTask))
                val request =
                    localRequest.copy(
                        tcpTargets = listOf(publicTarget),
                        probeTasks = listOf(publicTask, localTask),
                    )

                val admission = AndroidLocalNetworkAccess(context).prepareScanEndpoints(request)

                assertEquals("$family must preserve only public TCP", expected, admission.request)
                assertEquals("$family must defer once per target", 1, admission.deferred.size)
                val deferred = admission.deferred.single()
                assertEquals(targetId, deferred.target)
                assertEquals("capability_skipped", deferred.outcome)
                val details = deferred.details.associate { it.key to it.value }
                assertEquals(LocalNetworkPermission, details["permission"])
                assertEquals("local_network_permission_required", details["reason"])
            }
        }

    @Test
    fun `raw web scan defers LAN connect IP despite public edges and removes only its task`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val publicTarget = DomainTarget(host = "8.8.8.8")
            val localTarget =
                DomainTarget(
                    host = "1.1.1.1",
                    connectIp = "192.168.50.2",
                    connectIps = listOf("8.8.4.4"),
                )
            val publicTask = EngineProbeTaskWire(EngineProbeTaskFamily.WEB, publicTarget.host, "Public WEB")
            val localTask = EngineProbeTaskWire(EngineProbeTaskFamily.WEB, localTarget.host, "LAN WEB")
            val request =
                EngineScanRequestWire(
                    profileId = "mixed-web",
                    displayName = "Mixed WEB targets",
                    pathMode = ScanPathMode.RAW_PATH,
                    domainTargets = listOf(publicTarget, localTarget),
                    probeTasks = listOf(publicTask, localTask),
                )

            val admission = AndroidLocalNetworkAccess(context).prepareScanEndpoints(request)

            assertEquals(
                request.copy(domainTargets = listOf(publicTarget), probeTasks = listOf(publicTask)),
                admission.request,
            )
            val deferred = admission.deferred.single()
            assertEquals(localTarget.host, deferred.target)
            assertEquals("capability_skipped", deferred.outcome)
            val details = deferred.details.associate { it.key to it.value }
            assertEquals(LocalNetworkPermission, details["permission"])
            assertEquals("local_network_permission_required", details["reason"])
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `controller executes public raw target and persists LAN permission deferral`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val json = diagnosticsTestJson()
            val publicTarget = TcpTarget(id = "8.8.8.8", provider = "public", ip = "8.8.8.8")
            val localTarget = TcpTarget(id = "192.168.50.2", provider = "local", ip = "192.168.50.2")
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            stores.profilesState.value =
                listOf(
                    stores.profilesState.value.single().copy(
                        requestJson =
                            diagnosticsProfileRequestJson(
                                json = json,
                                profileId = "default",
                                displayName = "Mixed TCP targets",
                                targets = DiagnosticsProfileTargets(tcpTargets = listOf(publicTarget, localTarget)),
                            ),
                    ),
                )
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val bridge = bridgeFactory.bridge
            bridge.afterStartScan = {
                bridge.enqueueReport(
                    ScanReport(
                        sessionId = requireNotNull(bridge.startedSessionId),
                        profileId = "default",
                        pathMode = ScanPathMode.RAW_PATH,
                        startedAt = 10L,
                        finishedAt = 20L,
                        summary = "Public target succeeded",
                        results = listOf(ProbeResult("tcp_connect", publicTarget.id, "success")),
                    ),
                )
            }
            val services =
                createDiagnosticsServices(
                    context = context,
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val sessionId = services.scanController.startScan(ScanPathMode.RAW_PATH).startedSessionId()
            advanceUntilIdle()

            val forwarded =
                json.decodeFromString(
                    EngineScanRequestWire.serializer(),
                    requireNotNull(bridge.startedRequestJson),
                )
            assertEquals(listOf(publicTarget), forwarded.tcpTargets)
            val results = stores.getProbeResults(sessionId)
            assertEquals("success", results.single { it.target == publicTarget.id }.outcome)
            val deferred = results.single { it.target == localTarget.id }
            assertEquals("capability_skipped", deferred.outcome)
            val details =
                json
                    .decodeFromString(ListSerializer(ProbeDetail.serializer()), deferred.detailJson)
                    .associate { it.key to it.value }
            assertEquals(LocalNetworkPermission, details["permission"])
            assertEquals("local_network_permission_required", details["reason"])
            assertEquals("completed", stores.getScanSession(sessionId)?.status)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation persists LAN permission deferral in the partial report`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val json = diagnosticsTestJson()
            val publicTarget = TcpTarget(id = "8.8.8.8", provider = "public", ip = "8.8.8.8")
            val localTarget = TcpTarget(id = "192.168.50.2", provider = "local", ip = "192.168.50.2")
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            stores.profilesState.value =
                listOf(
                    stores.profilesState.value.single().copy(
                        requestJson =
                            diagnosticsProfileRequestJson(
                                json = json,
                                profileId = "default",
                                displayName = "Mixed TCP targets",
                                targets = DiagnosticsProfileTargets(tcpTargets = listOf(publicTarget, localTarget)),
                            ),
                    ),
                )
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services =
                createDiagnosticsServices(
                    context = context,
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val sessionId = services.scanController.startScan(ScanPathMode.RAW_PATH).startedSessionId()
            bridgeFactory.bridge.enqueueReport(
                ScanReport(
                    sessionId = sessionId,
                    profileId = "default",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 10L,
                    finishedAt = 20L,
                    summary = "Public target partial result",
                    results = listOf(ProbeResult("tcp_connect", publicTarget.id, "success")),
                ),
            )

            services.scanController.cancelActiveScan()

            val report =
                json.decodeEngineScanReportWire(
                    requireNotNull(stores.getScanSession(sessionId)?.reportJson),
                )
            assertEquals(ScanCompletionKind.PARTIAL_RESULTS, report.completionKind)
            assertEquals("success", report.results.single { it.target == publicTarget.id }.outcome)
            assertEquals("capability_skipped", report.results.single { it.target == localTarget.id }.outcome)
        }

    @Test
    fun `partial report JSON adds deferred LAN result before persistence`() =
        runTest {
            val json = diagnosticsTestJson()
            val deferredTarget = "192.168.50.2"
            val prepared =
                preparedDiagnosticsScan("partial-json", defaultDiagnosticsAppSettings()).copy(
                    localNetworkDeferrals =
                        listOf(ProbeResult("tcp_connect", deferredTarget, "capability_skipped")),
                )
            val rawReport =
                json.encodeToString(
                    EngineScanReportWire.serializer(),
                    ScanReport(
                        sessionId = prepared.sessionId,
                        profileId = "default",
                        pathMode = ScanPathMode.RAW_PATH,
                        startedAt = 10L,
                        finishedAt = 20L,
                        summary = "Partial",
                    ).toEngineScanReportWire(),
                )

            val report = json.decodeEngineScanReportWire(rawReport.withLocalNetworkDeferrals(prepared, json))

            assertEquals(ScanCompletionKind.PARTIAL_RESULTS, report.completionKind)
            assertEquals("capability_skipped", report.results.single { it.target == deferredTarget }.outcome)
        }

    @Test
    fun `in path scan preserves inner LAN target when owned proxy uses loopback`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val request =
                EngineScanRequestWire(
                    profileId = "inner-lan",
                    displayName = "LAN target through owned proxy",
                    pathMode = ScanPathMode.IN_PATH,
                    proxyHost = "127.0.0.1",
                    proxyPort = 1080,
                    tcpTargets =
                        listOf(TcpTarget(id = "inner-lan", provider = "local", ip = "192.168.50.2")),
                )

            val admission = AndroidLocalNetworkAccess(context).prepareScanEndpoints(request)

            assertEquals(request, admission.request)
            assertEquals(emptyList<ProbeResult>(), admission.deferred)
        }

    @Test
    fun `raw scan defers denied LAN target while retaining public TCP target`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val publicTarget = TcpTarget(id = "8.8.8.8", provider = "public", ip = "8.8.8.8")
            val localTarget = TcpTarget(id = "192.168.50.2", provider = "local", ip = "192.168.50.2")
            val request =
                EngineScanRequestWire(
                    profileId = "mixed-tcp",
                    displayName = "Mixed TCP targets",
                    pathMode = ScanPathMode.RAW_PATH,
                    tcpTargets = listOf(publicTarget, localTarget),
                )

            val admission = AndroidLocalNetworkAccess(context).prepareScanEndpoints(request)

            assertEquals(request.copy(tcpTargets = listOf(publicTarget)), admission.request)
            val deferred = admission.deferred.single()
            assertEquals(localTarget.id, deferred.target)
            assertEquals("capability_skipped", deferred.outcome)
            val details = deferred.details.associate { it.key to it.value }
            assertEquals(LocalNetworkPermission, details["permission"])
            assertEquals("local_network_permission_required", details["reason"])
            assertEquals(
                DiagnosticsOutcomeBucket.Inconclusive,
                DiagnosticsOutcomeTaxonomy
                    .classifyProbeOutcome(
                        probeType = deferred.probeType,
                        pathMode = request.pathMode,
                        outcome = deferred.outcome,
                    ).bucket,
            )
        }
}
