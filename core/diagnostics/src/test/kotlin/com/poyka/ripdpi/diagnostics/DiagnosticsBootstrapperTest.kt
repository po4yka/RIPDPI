package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticProfileWire
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticsCatalogWire
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticsPackWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileExecutionPolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsBootstrapperTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `initialize starts runtime history only once`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val runtimeHistoryStartup = RecordingRuntimeHistoryStartup()
            val archiveExporter = RecordingArchiveExporter()
            val bootstrapper =
                createBootstrapper(
                    stores = stores,
                    runtimeHistoryStartup = runtimeHistoryStartup,
                    archiveExporter = archiveExporter,
                    importBundledProfilesOnInitialize = false,
                    scope = backgroundScope,
                )

            bootstrapper.initialize()
            bootstrapper.initialize()

            assertEquals(1, runtimeHistoryStartup.startCalls)
            assertEquals(1, archiveExporter.cleanupCalls)
        }

    @Test
    fun `initialize registers only one automatic probe subscription`() =
        runTest {
            val stores =
                FakeDiagnosticsHistoryStores().also { history ->
                    history.telemetryState.value =
                        listOf(
                            telemetrySample(createdAt = System.currentTimeMillis()),
                        )
                }
            val runtimeHistoryStartup = RecordingRuntimeHistoryStartup()
            val archiveExporter = RecordingArchiveExporter()
            val handoverStore = FakePolicyHandoverEventStore()
            val launcher = BootstrapperRecordingAutomaticProbeLauncher()
            val bootstrapper =
                createBootstrapper(
                    stores = stores,
                    runtimeHistoryStartup = runtimeHistoryStartup,
                    archiveExporter = archiveExporter,
                    policyHandoverEventStore = handoverStore,
                    automaticProbeLauncher = launcher,
                    importBundledProfilesOnInitialize = false,
                    scope = backgroundScope,
                )

            bootstrapper.initialize()
            bootstrapper.initialize()
            handoverStore.publish(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, launcher.events.size)
            assertEquals("fingerprint-b", launcher.events.single().currentFingerprintHash)
        }

    @Test
    fun `runtime history startup failure does not abort diagnostics bootstrap`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val runtimeHistoryStartup =
                RecordingRuntimeHistoryStartup(
                    failure = IllegalStateException("boom"),
                )
            val archiveExporter = RecordingArchiveExporter()
            val handoverStore = FakePolicyHandoverEventStore()
            val launcher = BootstrapperRecordingAutomaticProbeLauncher()
            val bootstrapper =
                createBootstrapper(
                    stores = stores,
                    runtimeHistoryStartup = runtimeHistoryStartup,
                    archiveExporter = archiveExporter,
                    policyHandoverEventStore = handoverStore,
                    automaticProbeLauncher = launcher,
                    importBundledProfilesOnInitialize = true,
                    scope = backgroundScope,
                )

            bootstrapper.initialize()
            handoverStore.publish(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, runtimeHistoryStartup.startCalls)
            assertEquals(1, archiveExporter.cleanupCalls)
            assertFalse(stores.profilesState.value.isEmpty())
            assertEquals(1, launcher.events.size)
        }

    private fun createBootstrapper(
        stores: FakeDiagnosticsHistoryStores,
        runtimeHistoryStartup: RuntimeHistoryStartup,
        archiveExporter: RecordingArchiveExporter,
        policyHandoverEventStore: FakePolicyHandoverEventStore = FakePolicyHandoverEventStore(),
        automaticProbeLauncher: AutomaticProbeLauncher = NoopAutomaticProbeLauncher,
        importBundledProfilesOnInitialize: Boolean,
        scope: CoroutineScope,
    ): DefaultDiagnosticsBootstrapper =
        DefaultDiagnosticsBootstrapper(
            archiveExporter = archiveExporter,
            profileImporter =
                BundledDiagnosticsProfileImporter(
                    profileSource = BootstrapBundledDiagnosticsProfileSource(sampleBundledProfilesJson()),
                    overrideSource = EmptyBundledDiagnosticsCatalogOverrideSource,
                    profileCatalog = stores,
                    clock = TestDiagnosticsHistoryClock(currentTime = 10L),
                    json = json,
                ),
            runtimeHistoryStartup = runtimeHistoryStartup,
            policyHandoverEventStore = policyHandoverEventStore,
            automaticProbeScheduler =
                AutomaticProbeScheduler(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    rememberedNetworkPolicyStore =
                        DefaultRememberedNetworkPolicyStore(
                            stores,
                            TestDiagnosticsHistoryClock(),
                        ),
                    diagnosticsArtifactReadStore = stores,
                    launcherProvider = constantProvider(automaticProbeLauncher),
                    automaticHandoverProbeDelayMs = 100L,
                    automaticHandoverProbeCooldownMs = 0L,
                    automaticStrategyFailureProbeCooldownMs = 0L,
                    scope = scope,
                ),
            importBundledProfilesOnInitialize = importBundledProfilesOnInitialize,
            scope = scope,
        )

    private fun sampleBundledProfilesJson(): String =
        json.encodeToString(
            BundledDiagnosticsCatalogWire.serializer(),
            BundledDiagnosticsCatalogWire(
                schemaVersion = 1,
                generatedAt = "2026-03-22",
                packs = listOf(BundledDiagnosticsPackWire(id = "default-pack", version = 3)),
                profiles =
                    listOf(
                        BundledDiagnosticProfileWire(
                            id = "default",
                            name = "Default",
                            version = 3,
                            request =
                                ProfileSpecWire(
                                    profileId = "default",
                                    displayName = "Default",
                                    executionPolicy =
                                        ProfileExecutionPolicyWire(
                                            manualOnly = false,
                                            allowBackground = false,
                                            requiresRawPath = false,
                                            probePersistencePolicy =
                                                com.poyka.ripdpi.diagnostics.contract.profile
                                                    .ProbePersistencePolicyWire
                                                    .MANUAL_ONLY,
                                        ),
                                    packRefs = listOf("default-pack@3"),
                                    domainTargets = listOf(DomainTarget(host = "example.org")),
                                ),
                        ),
                    ),
            ),
        )
}

private class BootstrapBundledDiagnosticsProfileSource(
    private val payload: String,
) : BundledDiagnosticsProfileSource {
    override fun readProfilesJson(): String = payload
}

private class RecordingRuntimeHistoryStartup(
    private val failure: Throwable? = null,
) : RuntimeHistoryStartup {
    var startCalls: Int = 0
        private set

    override fun start() {
        startCalls += 1
        failure?.let { throw it }
    }
}

private class RecordingArchiveExporter : DiagnosticsArchiveExporter {
    var cleanupCalls: Int = 0
        private set

    override fun cleanupCache() {
        cleanupCalls += 1
    }

    override suspend fun createArchive(request: DiagnosticsArchiveRequest): DiagnosticsArchive {
        error("unused")
    }
}

private object NoopAutomaticProbeLauncher : AutomaticProbeLauncher {
    override fun hasActiveScan(): Boolean = false

    override suspend fun launchAutomaticProbe(
        settings: com.poyka.ripdpi.proto.AppSettings,
        event: com.poyka.ripdpi.data.PolicyHandoverEvent,
    ): Boolean = false
}

private fun constantProvider(launcher: AutomaticProbeLauncher): Provider<AutomaticProbeLauncher> =
    object : Provider<AutomaticProbeLauncher> {
        override fun get(): AutomaticProbeLauncher = launcher
    }

private class BootstrapperRecordingAutomaticProbeLauncher : AutomaticProbeLauncher {
    val events = mutableListOf<PolicyHandoverEvent>()

    override fun hasActiveScan(): Boolean = false

    override suspend fun launchAutomaticProbe(
        settings: com.poyka.ripdpi.proto.AppSettings,
        event: PolicyHandoverEvent,
    ): Boolean {
        events += event
        return true
    }
}

private fun handoverEvent() =
    PolicyHandoverEvent(
        mode = Mode.VPN,
        previousFingerprintHash = "fingerprint-a",
        currentFingerprintHash = "fingerprint-b",
        classification = "transport_switch",
        currentNetworkValidated = true,
        currentCaptivePortalDetected = false,
        usedRememberedPolicy = false,
        policySignature = "policy-1",
        occurredAt = 100L,
    )

private fun telemetrySample(createdAt: Long) =
    TelemetrySampleEntity(
        id = "telemetry-1",
        sessionId = null,
        connectionSessionId = "conn-1",
        activeMode = Mode.VPN.name,
        connectionState = "Running",
        networkType = "wifi",
        publicIp = null,
        failureClass = "dns_tampering",
        telemetryNetworkFingerprintHash = "fingerprint-b",
        lastFailureClass = null,
        txPackets = 0L,
        txBytes = 0L,
        rxPackets = 0L,
        rxBytes = 0L,
        createdAt = createdAt,
    )
