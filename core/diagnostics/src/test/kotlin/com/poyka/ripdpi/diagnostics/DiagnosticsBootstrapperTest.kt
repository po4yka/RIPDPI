package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticProfileWire
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticsCatalogWire
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticsPackWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileExecutionPolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.inject.Provider

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
    fun `runtime history startup failure does not abort diagnostics bootstrap`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val runtimeHistoryStartup =
                RecordingRuntimeHistoryStartup(
                    failure = IllegalStateException("boom"),
                )
            val archiveExporter = RecordingArchiveExporter()
            val bootstrapper =
                createBootstrapper(
                    stores = stores,
                    runtimeHistoryStartup = runtimeHistoryStartup,
                    archiveExporter = archiveExporter,
                    importBundledProfilesOnInitialize = true,
                    scope = backgroundScope,
                )

            bootstrapper.initialize()

            assertEquals(1, runtimeHistoryStartup.startCalls)
            assertEquals(1, archiveExporter.cleanupCalls)
            assertFalse(stores.profilesState.value.isEmpty())
        }

    private fun createBootstrapper(
        stores: FakeDiagnosticsHistoryStores,
        runtimeHistoryStartup: RuntimeHistoryStartup,
        archiveExporter: RecordingArchiveExporter,
        importBundledProfilesOnInitialize: Boolean,
        scope: CoroutineScope,
    ): DefaultDiagnosticsBootstrapper =
        DefaultDiagnosticsBootstrapper(
            archiveExporter = archiveExporter,
            profileImporter =
                BundledDiagnosticsProfileImporter(
                    profileSource = BootstrapBundledDiagnosticsProfileSource(sampleBundledProfilesJson()),
                    profileCatalog = stores,
                    clock = TestDiagnosticsHistoryClock(currentTime = 10L),
                    json = json,
                ),
            runtimeHistoryStartup = runtimeHistoryStartup,
            policyHandoverEventStore = FakePolicyHandoverEventStore(),
            automaticProbeScheduler =
                AutomaticProbeScheduler(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    launcherProvider = constantProvider(NoopAutomaticProbeLauncher),
                    automaticHandoverProbeDelayMs = 100L,
                    automaticHandoverProbeCooldownMs = 0L,
                    scope = scope,
                ),
            importBundledProfilesOnInitialize = importBundledProfilesOnInitialize,
            scope = scope,
        )

    private fun sampleBundledProfilesJson(): String =
        json.encodeToString(
            BundledDiagnosticsCatalogWire.serializer(),
            BundledDiagnosticsCatalogWire(
                schemaVersion = 2,
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

    override suspend fun createArchive(sessionId: String?): DiagnosticsArchive {
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
