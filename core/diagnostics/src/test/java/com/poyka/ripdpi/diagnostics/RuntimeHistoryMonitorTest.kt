@file:Suppress("LongMethod")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicyProofDurationMs
import com.poyka.ripdpi.data.RememberedNetworkPolicyProofTransferBytes
import com.poyka.ripdpi.data.RememberedNetworkPolicySourceManualSession
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import com.poyka.ripdpi.data.RttBand
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHistoryMonitorTest {
    @Test
    fun `failure without active session creates failed connection history`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val monitor =
                createRuntimeHistoryMonitor(
                    appSettingsRepository = RecorderFakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = RecorderFakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = RecorderFakeDiagnosticsContextProvider(),
                    serviceStateStore = serviceStateStore,
                )

            monitor.start()
            Thread.sleep(100)
            serviceStateStore.emitFailed(Sender.Proxy, FailureReason.NativeError("boom"))

            waitUntil {
                stores.usageSessionsState.value.isNotEmpty() &&
                    stores.nativeEventsState.value.isNotEmpty() &&
                    stores.telemetryState.value.isNotEmpty()
            }

            val session = stores.usageSessionsState.value.single()
            val event = stores.nativeEventsState.value.single()
            val telemetrySample = stores.telemetryState.value.single()

            assertEquals("Failed", session.connectionState)
            assertEquals("boom", session.failureMessage)
            assertEquals("native_io", session.failureClass)
            assertNotNull(session.finishedAt)
            assertEquals(session.id, event.connectionSessionId)
            assertEquals("proxy", event.source)
            assertEquals("error", event.level)
            assertEquals("Failed", telemetrySample.connectionState)
            assertEquals("native_io", telemetrySample.failureClass)
        }

    @Test
    fun `running session records sampled telemetry and deduplicated runtime events`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val monitor =
                createRuntimeHistoryMonitor(
                    appSettingsRepository =
                        RecorderFakeAppSettingsRepository(
                            defaultAppSettings()
                                .toBuilder()
                                .setDiagnosticsSampleIntervalSeconds(5)
                                .build(),
                        ),
                    stores = stores,
                    networkMetadataProvider = RecorderFakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = RecorderFakeDiagnosticsContextProvider(),
                    serviceStateStore = serviceStateStore,
                )

            monitor.start()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Running,
                    tunnelStats = TunnelStats(txPackets = 4, txBytes = 1_024, rxPackets = 5, rxBytes = 2_048),
                    proxyTelemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            state = "running",
                            health = "healthy",
                            routeChanges = 2,
                            lastFailureClass = "http_blockpage",
                            lastFallbackAction = "retry_with_matching_group",
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "proxy",
                                        level = "info",
                                        message = "accepted",
                                        createdAt = 100L,
                                    ),
                                ),
                        ),
                    tunnelTelemetry =
                        NativeRuntimeSnapshot(
                            source = "tunnel",
                            state = "running",
                            health = "healthy",
                            resolverId = "cloudflare",
                            resolverProtocol = "doh",
                            resolverEndpoint = "https://cloudflare-dns.com/dns-query",
                            resolverLatencyMs = 38,
                            dnsFailuresTotal = 2,
                            resolverFallbackActive = true,
                            resolverFallbackReason = "UDP DNS showed dns_substitution",
                            networkHandoverClass = "transport_switch",
                        ),
                    runtimeFieldTelemetry =
                        RuntimeFieldTelemetry(
                            failureClass = FailureClass.DnsInterference,
                            telemetryNetworkFingerprintHash = "v1:abc123",
                            winningTcpStrategyFamily = "hostfake",
                            winningQuicStrategyFamily = "quic_burst",
                            proxyRttBand = RttBand.Between50And99,
                            resolverRttBand = RttBand.Lt50,
                            proxyRouteRetryCount = 2,
                            tunnelRecoveryRetryCount = 1,
                        ),
                    serviceStartedAt = System.currentTimeMillis(),
                    restartCount = 1,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            serviceStateStore.updateTelemetry(
                serviceStateStore.telemetry.value.copy(
                    proxyTelemetry =
                        serviceStateStore.telemetry.value.proxyTelemetry.copy(
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "proxy",
                                        level = "info",
                                        message = "accepted",
                                        createdAt = 100L,
                                    ),
                                ),
                        ),
                    updatedAt = System.currentTimeMillis(),
                ),
            )

            waitUntil(timeoutMillis = 8_000) {
                stores.telemetryState.value.isNotEmpty() &&
                    stores.snapshotsState.value.isNotEmpty() &&
                    stores.contextsState.value.isNotEmpty()
            }

            val session = stores.usageSessionsState.value.single()
            val telemetrySample = stores.telemetryState.value.single()
            assertEquals("Running", session.connectionState)
            assertEquals("VPN", session.serviceMode)
            assertEquals(1_024L, session.txBytes)
            assertEquals(2_048L, session.rxBytes)
            assertEquals("dns_interference", session.failureClass)
            assertEquals("v1:abc123", session.telemetryNetworkFingerprintHash)
            assertEquals("hostfake", session.winningTcpStrategyFamily)
            assertEquals("quic_burst", session.winningQuicStrategyFamily)
            assertEquals("50_99", session.proxyRttBand)
            assertEquals("lt50", session.resolverRttBand)
            assertEquals(2L, session.proxyRouteRetryCount)
            assertEquals(1L, session.tunnelRecoveryRetryCount)
            assertEquals("cloudflare", telemetrySample.resolverId)
            assertEquals("doh", telemetrySample.resolverProtocol)
            assertEquals("https://cloudflare-dns.com/dns-query", telemetrySample.resolverEndpoint)
            assertEquals(38L, telemetrySample.resolverLatencyMs)
            assertEquals(2, telemetrySample.dnsFailuresTotal)
            assertTrue(telemetrySample.resolverFallbackActive)
            assertEquals("UDP DNS showed dns_substitution", telemetrySample.resolverFallbackReason)
            assertEquals("transport_switch", telemetrySample.networkHandoverClass)
            assertEquals("dns_interference", telemetrySample.failureClass)
            assertEquals("http_blockpage", telemetrySample.lastFailureClass)
            assertEquals("retry_with_matching_group", telemetrySample.lastFallbackAction)
            assertEquals("v1:abc123", telemetrySample.telemetryNetworkFingerprintHash)
            assertEquals("hostfake", telemetrySample.winningTcpStrategyFamily)
            assertEquals("quic_burst", telemetrySample.winningQuicStrategyFamily)
            assertEquals("50_99", telemetrySample.proxyRttBand)
            assertEquals("lt50", telemetrySample.resolverRttBand)
            assertEquals(2L, telemetrySample.proxyRouteRetryCount)
            assertEquals(1L, telemetrySample.tunnelRecoveryRetryCount)
            assertEquals(1, stores.nativeEventsState.value.size)
            assertTrue(stores.snapshotsState.value.all { it.connectionSessionId == session.id })
            assertTrue(stores.contextsState.value.all { it.connectionSessionId == session.id })
            assertTrue(stores.telemetryState.value.all { it.connectionSessionId == session.id })

            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            waitUntil {
                stores.usageSessionsState.value
                    .single()
                    .finishedAt != null
            }
            assertFalse(
                stores.usageSessionsState.value
                    .single()
                    .finishedAt == null,
            )
        }

    @Test
    fun `handover policy rotation keeps previous remembered policy neutral`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock(currentTime = 1_000L)
            val serviceStateStore = DefaultServiceStateStore()
            val activePolicyStore = FakeActiveConnectionPolicyStore()
            val rememberedPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock)
            val firstPolicy =
                rememberedPolicyStore.rememberValidatedPolicy(
                    policy = rememberedPolicyJson("fingerprint-a", Mode.VPN),
                    source = RememberedNetworkPolicySourceManualSession,
                    validatedAt = 100L,
                )
            val secondPolicy =
                rememberedPolicyStore.rememberValidatedPolicy(
                    policy = rememberedPolicyJson("fingerprint-b", Mode.VPN),
                    source = RememberedNetworkPolicySourceManualSession,
                    validatedAt = 200L,
                )
            val monitor =
                createRuntimeHistoryMonitor(
                    appSettingsRepository = RecorderFakeAppSettingsRepository(),
                    stores = stores,
                    rememberedNetworkPolicyStore = rememberedPolicyStore,
                    networkMetadataProvider = RecorderFakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = RecorderFakeDiagnosticsContextProvider(),
                    serviceStateStore = serviceStateStore,
                    activeConnectionPolicyStore = activePolicyStore,
                )

            monitor.start()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            waitUntil { stores.usageSessionsState.value.isNotEmpty() }

            activePolicyStore.set(
                ActiveConnectionPolicy(
                    mode = Mode.VPN,
                    policy = rememberedPolicyJson("fingerprint-a", Mode.VPN),
                    matchedPolicy = firstPolicy,
                    usedRememberedPolicy = true,
                    fingerprintHash = "fingerprint-a",
                    policySignature = "policy-signature-a",
                    appliedAt = 1_000L,
                ),
            )
            waitUntil {
                stores.rememberedPoliciesState.value.any {
                    it.fingerprintHash == "fingerprint-a" &&
                        it.mode == Mode.VPN.preferenceValue &&
                        it.lastAppliedAt == 1_000L
                }
            }

            activePolicyStore.set(
                ActiveConnectionPolicy(
                    mode = Mode.VPN,
                    policy = rememberedPolicyJson("fingerprint-b", Mode.VPN),
                    matchedPolicy = secondPolicy,
                    usedRememberedPolicy = true,
                    fingerprintHash = "fingerprint-b",
                    policySignature = "policy-signature-b",
                    appliedAt = 2_000L,
                    restartReason = "network_handover",
                    handoverClassification = "transport_switch",
                ),
            )
            waitUntil {
                stores.rememberedPoliciesState.value.any {
                    it.fingerprintHash == "fingerprint-b" &&
                        it.mode == Mode.VPN.preferenceValue &&
                        it.lastAppliedAt == 2_000L
                }
            }

            val firstPersisted =
                requireNotNull(stores.getRememberedNetworkPolicy("fingerprint-a", Mode.VPN.preferenceValue))
            val secondPersisted =
                requireNotNull(stores.getRememberedNetworkPolicy("fingerprint-b", Mode.VPN.preferenceValue))

            assertEquals(0, firstPersisted.failureCount)
            assertEquals(0, firstPersisted.consecutiveFailureCount)
            assertEquals(1_000L, firstPersisted.lastAppliedAt)
            assertEquals(2_000L, secondPersisted.lastAppliedAt)
        }

    @Test
    fun `starting monitor twice does not duplicate failure handling`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val monitor =
                createRuntimeHistoryMonitor(
                    appSettingsRepository = RecorderFakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = RecorderFakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = RecorderFakeDiagnosticsContextProvider(),
                    serviceStateStore = serviceStateStore,
                )

            monitor.start()
            monitor.start()
            Thread.sleep(100)
            serviceStateStore.emitFailed(Sender.Proxy, FailureReason.NativeError("boom"))

            waitUntil {
                stores.usageSessionsState.value.isNotEmpty() &&
                    stores.nativeEventsState.value.isNotEmpty() &&
                    stores.telemetryState.value.isNotEmpty()
            }

            assertEquals(1, stores.usageSessionsState.value.size)
            assertEquals(1, stores.nativeEventsState.value.size)
            assertEquals(1, stores.telemetryState.value.size)
        }

    @Test
    fun `used remembered policy records failure when session ends before proof`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock(currentTime = 1_000L)
            val serviceStateStore = DefaultServiceStateStore()
            val activePolicyStore = FakeActiveConnectionPolicyStore()
            val rememberedPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock)
            val policy =
                rememberedPolicyStore.rememberValidatedPolicy(
                    policy = rememberedPolicyJson("fingerprint-fail", Mode.VPN),
                    source = RememberedNetworkPolicySourceManualSession,
                    validatedAt = 100L,
                )
            val monitor =
                createRuntimeHistoryMonitor(
                    appSettingsRepository = RecorderFakeAppSettingsRepository(),
                    stores = stores,
                    rememberedNetworkPolicyStore = rememberedPolicyStore,
                    networkMetadataProvider = RecorderFakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = RecorderFakeDiagnosticsContextProvider(),
                    serviceStateStore = serviceStateStore,
                    activeConnectionPolicyStore = activePolicyStore,
                )

            monitor.start()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            waitUntil { stores.usageSessionsState.value.isNotEmpty() }

            activePolicyStore.set(
                ActiveConnectionPolicy(
                    mode = Mode.VPN,
                    policy = rememberedPolicyJson("fingerprint-fail", Mode.VPN),
                    matchedPolicy = policy,
                    usedRememberedPolicy = true,
                    fingerprintHash = "fingerprint-fail",
                    policySignature = "policy-signature-fail",
                    appliedAt = System.currentTimeMillis() - 1_000L,
                ),
            )
            waitUntil {
                stores.rememberedPoliciesState.value.any {
                    it.fingerprintHash == "fingerprint-fail" &&
                        it.lastAppliedAt != null
                }
            }

            serviceStateStore.emitFailed(Sender.Proxy, FailureReason.NativeError("boom"))
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)

            waitUntil {
                stores.rememberedPoliciesState.value.any {
                    it.fingerprintHash == "fingerprint-fail" &&
                        it.mode == Mode.VPN.preferenceValue &&
                        it.failureCount == 1
                }
            }

            val persisted =
                requireNotNull(stores.getRememberedNetworkPolicy("fingerprint-fail", Mode.VPN.preferenceValue))
            assertEquals(1, persisted.failureCount)
            assertEquals(1, persisted.consecutiveFailureCount)
        }

    @Test
    fun `observed remembered policy is validated after proved successful session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock(currentTime = 1_000L)
            val serviceStateStore = DefaultServiceStateStore()
            val activePolicyStore = FakeActiveConnectionPolicyStore()
            val rememberedPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock)
            val monitor =
                createRuntimeHistoryMonitor(
                    appSettingsRepository = RecorderFakeAppSettingsRepository(),
                    stores = stores,
                    rememberedNetworkPolicyStore = rememberedPolicyStore,
                    networkMetadataProvider = RecorderFakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = RecorderFakeDiagnosticsContextProvider(),
                    serviceStateStore = serviceStateStore,
                    activeConnectionPolicyStore = activePolicyStore,
                )

            monitor.start()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            waitUntil { stores.usageSessionsState.value.isNotEmpty() }

            activePolicyStore.set(
                ActiveConnectionPolicy(
                    mode = Mode.VPN,
                    policy = rememberedPolicyJson("fingerprint-success", Mode.VPN),
                    usedRememberedPolicy = false,
                    fingerprintHash = "fingerprint-success",
                    policySignature = "policy-signature-success",
                    appliedAt = System.currentTimeMillis() - RememberedNetworkPolicyProofDurationMs - 1_000L,
                ),
            )
            waitUntil {
                stores.rememberedPoliciesState.value.any {
                    it.fingerprintHash == "fingerprint-success" &&
                        it.mode == Mode.VPN.preferenceValue
                }
            }

            serviceStateStore.updateTelemetry(
                runningTelemetry(
                    txBytes = RememberedNetworkPolicyProofTransferBytes / 2,
                    rxBytes = RememberedNetworkPolicyProofTransferBytes / 2,
                ),
            )
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)

            waitUntil {
                stores.rememberedPoliciesState.value.any {
                    it.fingerprintHash == "fingerprint-success" &&
                        it.mode == Mode.VPN.preferenceValue &&
                        it.status == RememberedNetworkPolicyStatusValidated
                }
            }

            val persisted =
                requireNotNull(stores.getRememberedNetworkPolicy("fingerprint-success", Mode.VPN.preferenceValue))
            assertEquals(RememberedNetworkPolicyStatusValidated, persisted.status)
            assertEquals(1, persisted.successCount)
            assertNotNull(persisted.lastValidatedAt)
        }
}

private fun rememberedPolicyJson(
    fingerprintHash: String,
    mode: Mode,
): RememberedNetworkPolicyJson =
    RememberedNetworkPolicyJson(
        fingerprintHash = fingerprintHash,
        mode = mode.preferenceValue,
        summary =
            NetworkFingerprintSummary(
                transport = "wifi",
                networkState = "validated",
                identityKind = "wifi",
                privateDnsMode = "system",
                dnsServerCount = 1,
            ),
        proxyConfigJson = """{"kind":"ui","listen_port":1080}""",
        winningTcpStrategyFamily = "hostfake",
        winningQuicStrategyFamily = "quic_burst",
        winningDnsStrategyFamily = "dns_doh",
    )

private class RecorderFakeAppSettingsRepository(
    initialSettings: AppSettings = defaultAppSettings(),
) : AppSettingsRepository {
    private val state = MutableStateFlow(initialSettings)

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private fun defaultAppSettings(): AppSettings =
    AppSettings
        .newBuilder()
        .setRipdpiMode("vpn")
        .setProxyIp("127.0.0.1")
        .setProxyPort(1080)
        .setDiagnosticsMonitorEnabled(true)
        .setDiagnosticsSampleIntervalSeconds(15)
        .setDiagnosticsActiveProfileId("default")
        .setDiagnosticsHistoryRetentionDays(14)
        .setDiagnosticsExportIncludeHistory(true)
        .build()

private class RecorderFakeNetworkMetadataProvider : NetworkMetadataProvider {
    override suspend fun captureSnapshot(includePublicIp: Boolean): NetworkSnapshotModel =
        NetworkSnapshotModel(
            transport = "wifi",
            capabilities = listOf("validated"),
            dnsServers = listOf("1.1.1.1"),
            privateDnsMode = "system",
            mtu = 1_500,
            localAddresses = listOf("192.0.2.10"),
            publicIp = "198.51.100.8",
            publicAsn = "AS64500",
            captivePortalDetected = false,
            networkValidated = true,
            capturedAt = System.currentTimeMillis(),
        )
}

private class RecorderFakeDiagnosticsContextProvider : DiagnosticsContextProvider {
    override suspend fun captureContext(): DiagnosticContextModel =
        DiagnosticContextModel(
            service =
                ServiceContextModel(
                    serviceStatus = "Running",
                    configuredMode = "VPN",
                    activeMode = "VPN",
                    selectedProfileId = "default",
                    selectedProfileName = "Default",
                    configSource = "ui",
                    proxyEndpoint = "127.0.0.1:1080",
                    desyncMethod = "split",
                    chainSummary = "tcp: split",
                    routeGroup = "1",
                    sessionUptimeMs = 1_000L,
                    lastNativeErrorHeadline = "none",
                    restartCount = 1,
                    hostAutolearnEnabled = "disabled",
                    learnedHostCount = 0,
                    penalizedHostCount = 0,
                    lastAutolearnHost = "",
                    lastAutolearnGroup = "",
                    lastAutolearnAction = "",
                ),
            permissions =
                PermissionContextModel(
                    vpnPermissionState = "enabled",
                    notificationPermissionState = "enabled",
                    batteryOptimizationState = "disabled",
                    dataSaverState = "disabled",
                ),
            device =
                DeviceContextModel(
                    appVersionName = "0.0.1",
                    appVersionCode = 1,
                    buildType = "debug",
                    androidVersion = "16",
                    apiLevel = 36,
                    manufacturer = "Google",
                    model = "Pixel",
                    primaryAbi = "arm64-v8a",
                    locale = "en-US",
                    timezone = "UTC",
                ),
            environment =
                EnvironmentContextModel(
                    batterySaverState = "disabled",
                    powerSaveModeState = "disabled",
                    networkMeteredState = "disabled",
                    roamingState = "disabled",
                ),
        )
}

private fun waitUntil(
    timeoutMillis: Long = 2_000,
    predicate: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) {
            return
        }
        Thread.sleep(25)
    }
    assertTrue("Timed out waiting for condition", predicate())
}

private class FakeActiveConnectionPolicyStore : ActiveConnectionPolicyStore {
    private val state = MutableStateFlow<Map<Mode, ActiveConnectionPolicy>>(emptyMap())

    override val activePolicies = state

    fun set(policy: ActiveConnectionPolicy) {
        state.value = state.value + (policy.mode to policy)
    }

    fun clear(mode: Mode) {
        state.value = state.value - mode
    }
}

private fun runningTelemetry(
    txBytes: Long,
    rxBytes: Long,
): ServiceTelemetrySnapshot {
    val now = System.currentTimeMillis()
    return ServiceTelemetrySnapshot(
        mode = Mode.VPN,
        status = AppStatus.Running,
        tunnelStats = TunnelStats(txPackets = 4, txBytes = txBytes, rxPackets = 5, rxBytes = rxBytes),
        proxyTelemetry =
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                health = "healthy",
            ),
        tunnelTelemetry =
            NativeRuntimeSnapshot(
                source = "tunnel",
                state = "running",
                health = "healthy",
            ),
        serviceStartedAt = now,
        restartCount = 1,
        updatedAt = now,
    )
}
