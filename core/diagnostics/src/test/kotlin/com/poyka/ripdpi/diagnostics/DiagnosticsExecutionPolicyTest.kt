package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.diagnostics.contract.profile.ProbePersistencePolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileExecutionPolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiagnosticsExecutionPolicyTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `intent resolver uses explicit execution policy instead of family`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.upsertProfile(
                com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity(
                    id = "profile-1",
                    name = "Policy profile",
                    source = "test",
                    version = 1,
                    requestJson =
                        json.encodeToString(
                            ProfileSpecWire.serializer(),
                            ProfileSpecWire(
                                profileId = "profile-1",
                                displayName = "Policy profile",
                                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                                executionPolicy =
                                    ProfileExecutionPolicyWire(
                                        manualOnly = true,
                                        allowBackground = false,
                                        requiresRawPath = false,
                                        probePersistencePolicy = ProbePersistencePolicyWire.ALWAYS,
                                    ),
                            ),
                        ),
                    updatedAt = 1L,
                ),
            )

            val resolver = DefaultDiagnosticsIntentResolver(stores, FakeAppSettingsRepository(), json)
            val intent = resolver.resolve("profile-1", ScanPathMode.IN_PATH)

            assertEquals(DiagnosticProfileFamily.AUTOMATIC_AUDIT, intent.family)
            assertTrue(intent.executionPolicy.manualOnly)
            assertFalse(intent.executionPolicy.allowBackground)
            assertFalse(intent.executionPolicy.requiresRawPath)
            assertEquals(ProbePersistencePolicy.ALWAYS, intent.executionPolicy.probePersistencePolicy)
        }

    @Test
    fun `profile spec requires explicit execution policy`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ProfileSpecWire(
                    profileId = "automatic-probing",
                    displayName = "Automatic probing",
                    kind = ScanKind.STRATEGY_PROBE,
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                ).normalizedExecutionPolicy()
            }

        assertTrue(error.message.orEmpty().contains("executionPolicy"))
    }

    @Test
    fun `profile spec requires explicit probe persistence policy`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ProfileSpecWire(
                    profileId = "custom-probe",
                    displayName = "Custom strategy probe",
                    kind = ScanKind.STRATEGY_PROBE,
                    family = DiagnosticProfileFamily.GENERAL,
                    executionPolicy =
                        ProfileExecutionPolicyWire(
                            manualOnly = false,
                            allowBackground = true,
                            requiresRawPath = true,
                        ),
                ).normalizedExecutionPolicy()
            }

        assertTrue(error.message.orEmpty().contains("probePersistencePolicy"))
    }

    @Test
    fun `normalized execution policy preserves explicit probe persistence policy`() {
        val executionPolicy =
            ProfileSpecWire(
                profileId = "custom-probe",
                displayName = "Custom strategy probe",
                kind = ScanKind.STRATEGY_PROBE,
                family = DiagnosticProfileFamily.GENERAL,
                executionPolicy =
                    ProfileExecutionPolicyWire(
                        manualOnly = false,
                        allowBackground = true,
                        requiresRawPath = true,
                        probePersistencePolicy = ProbePersistencePolicyWire.MANUAL_ONLY,
                    ),
            ).normalizedExecutionPolicy()

        assertEquals(ProbePersistencePolicyWire.MANUAL_ONLY, executionPolicy.probePersistencePolicy)
    }

    @Test
    fun `profile projection carries explicit probe persistence policy`() {
        val projection =
            ProfileSpecWire(
                profileId = "custom-probe",
                displayName = "Custom strategy probe",
                kind = ScanKind.STRATEGY_PROBE,
                family = DiagnosticProfileFamily.GENERAL,
                executionPolicy =
                    ProfileExecutionPolicyWire(
                        manualOnly = false,
                        allowBackground = true,
                        requiresRawPath = true,
                        probePersistencePolicy = ProbePersistencePolicyWire.ALWAYS,
                    ),
            ).toProfileProjection()

        assertEquals(ProbePersistencePolicy.ALWAYS, projection.executionPolicy.probePersistencePolicy)
    }

    @Test
    fun `scan context collector forces raw path when execution policy requires it`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val collector =
                DefaultScanContextCollector(
                    profileCatalog = stores,
                    networkFingerprintProvider =
                        object : NetworkFingerprintProvider {
                            override fun capture() = null
                        },
                    nativeNetworkSnapshotProvider =
                        object : NativeNetworkSnapshotProvider {
                            override fun capture() =
                                com.poyka.ripdpi.data
                                    .NativeNetworkSnapshot()
                        },
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDnsPathPreferenceStore =
                        DefaultNetworkDnsPathPreferenceStore(stores, TestDiagnosticsHistoryClock()),
                    networkEdgePreferenceStore =
                        com.poyka.ripdpi.data.diagnostics.DefaultNetworkEdgePreferenceStore(
                            stores,
                            TestDiagnosticsHistoryClock(),
                        ),
                    serviceStateStore = FakeServiceStateStore(),
                    json = json,
                )

            val context =
                collector.collect(
                    DiagnosticsIntent(
                        profileId = "automatic-probing",
                        displayName = "Automatic probing",
                        settings = defaultDiagnosticsAppSettings(),
                        kind = ScanKind.STRATEGY_PROBE,
                        family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                        regionTag = null,
                        executionPolicy =
                            ExecutionPolicy(
                                manualOnly = false,
                                allowBackground = true,
                                requiresRawPath = true,
                                probePersistencePolicy = ProbePersistencePolicy.BACKGROUND_ONLY,
                            ),
                        packRefs = emptyList(),
                        domainTargets = emptyList(),
                        dnsTargets = emptyList(),
                        tcpTargets = emptyList(),
                        quicTargets = emptyList(),
                        serviceTargets = emptyList(),
                        circumventionTargets = emptyList(),
                        throughputTargets = emptyList(),
                        whitelistSni = emptyList(),
                        telegramTarget = null,
                        strategyProbe = null,
                        requestedPathMode = ScanPathMode.IN_PATH,
                    ),
                )

            assertEquals(ScanPathMode.RAW_PATH, context.pathMode)
        }

    @Test
    fun `automatic admission follows execution policy allowBackground`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.upsertProfile(
                com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity(
                    id = "automatic-probing",
                    name = "Automatic probing",
                    source = "test",
                    version = 1,
                    requestJson =
                        json.encodeToString(
                            ProfileSpecWire.serializer(),
                            ProfileSpecWire(
                                profileId = "automatic-probing",
                                displayName = "Automatic probing",
                                kind = ScanKind.STRATEGY_PROBE,
                                executionPolicy =
                                    ProfileExecutionPolicyWire(
                                        manualOnly = true,
                                        allowBackground = false,
                                        requiresRawPath = true,
                                        probePersistencePolicy = ProbePersistencePolicyWire.MANUAL_ONLY,
                                    ),
                            ),
                        ),
                    updatedAt = 1L,
                ),
            )

            val service =
                ScanAdmissionService(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    profileCatalog = stores,
                    activeScanRegistry =
                        ActiveScanRegistry(
                            DefaultDiagnosticsTimelineSource(
                                profileCatalog = stores,
                                scanRecordStore = stores,
                                artifactReadStore = stores,
                                bypassUsageHistoryStore = stores,
                                mapper = DiagnosticsBoundaryMapper(json),
                                scope = backgroundScope,
                                json = json,
                            ),
                        ),
                    json = json,
                )

            val admitted = service.admitAutomaticProbe(defaultDiagnosticsAppSettings())

            assertNull(admitted)
        }

    @Test
    fun `manual admission rejects hidden automatic probe explicitly`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.seedDefaultProfile(json)
            val registry = activeScanRegistry(stores, backgroundScope, json)
            val service = scanAdmissionService(stores, registry, json)

            registry.registerBridge(
                bridge = FakeNetworkDiagnosticsBridge(json),
                sessionId = "hidden-probe",
                registerActiveBridge = false,
            )

            val admission = service.admitManualStart()

            assertTrue(admission is ManualStartAdmission.HiddenAutomaticProbeConflict)
        }

    @Test
    fun `manual admission rejects visible scan as already active`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val registry = activeScanRegistry(stores, backgroundScope, json)
            val service = scanAdmissionService(stores, registry, json)

            registry.updateProgress(
                ScanProgress(
                    sessionId = "visible-scan",
                    phase = "running",
                    completedSteps = 0,
                    totalSteps = 1,
                    message = "running",
                ),
            )

            try {
                service.admitManualStart()
                fail("Expected manual admission to be rejected")
            } catch (error: DiagnosticsScanStartRejectedException) {
                assertEquals(DiagnosticsScanStartRejectionReason.ScanAlreadyActive, error.reason)
            }
        }
}

private fun activeScanRegistry(
    stores: FakeDiagnosticsHistoryStores,
    scope: kotlinx.coroutines.CoroutineScope,
    json: kotlinx.serialization.json.Json,
): ActiveScanRegistry =
    ActiveScanRegistry(
        DefaultDiagnosticsTimelineSource(
            profileCatalog = stores,
            scanRecordStore = stores,
            artifactReadStore = stores,
            bypassUsageHistoryStore = stores,
            mapper = DiagnosticsBoundaryMapper(json),
            scope = scope,
            json = json,
        ),
    )

private fun scanAdmissionService(
    stores: FakeDiagnosticsHistoryStores,
    registry: ActiveScanRegistry,
    json: kotlinx.serialization.json.Json,
): ScanAdmissionService =
    ScanAdmissionService(
        appSettingsRepository = FakeAppSettingsRepository(),
        profileCatalog = stores,
        activeScanRegistry = registry,
        json = json,
    )
