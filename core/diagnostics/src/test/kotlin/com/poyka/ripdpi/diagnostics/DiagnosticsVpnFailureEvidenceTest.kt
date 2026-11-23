package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsVpnFailureEvidenceTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `offline default service state does not imply a previous VPN session`() =
        runTest {
            assertFalse(
                requireNotNull(
                    collectSnapshot(NativeNetworkSnapshot(transport = "none")).networkSnapshot,
                ).vpnServiceWasActive,
            )
        }

    @Test
    fun `explicit prior VPN evidence is preserved`() =
        runTest {
            val offline = NativeNetworkSnapshot(transport = "none", vpnServiceWasActive = true)
            assertTrue(requireNotNull(collectSnapshot(offline).networkSnapshot).vpnServiceWasActive)
        }

    @Test
    fun `unverified metadata does not enter the diagnostic plan`() =
        runTest {
            assertNull(collectSnapshot(NativeNetworkSnapshot(transport = "unknown")).networkSnapshot)
        }

    private suspend fun collectSnapshot(
        snapshot: NativeNetworkSnapshot,
    ): com.poyka.ripdpi.diagnostics.domain.ScanContext {
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
                        override fun capture() = snapshot
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

        return collector.collect(
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
    }
}
