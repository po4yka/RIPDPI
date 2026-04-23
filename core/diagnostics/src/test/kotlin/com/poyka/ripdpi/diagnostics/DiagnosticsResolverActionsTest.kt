package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsResolverActionsTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `keep installs temporary resolver override for recommended session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val session = diagnosticsSessionWithResolverRecommendation(sessionId = "session-1")
            stores.sessionsState.value = listOf(session)
            val resolverOverrideStore = FakeResolverOverrideStore()
            val actions =
                DefaultDiagnosticsResolverActions(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    recommendationStore = DiagnosticsRecommendationStore(stores, json),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    networkDnsPathPreferenceStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    resolverOverrideStore = resolverOverrideStore,
                )

            actions.keepResolverRecommendationForSession(session.id)

            val override = resolverOverrideStore.override.value
            assertNotNull(override)
            assertEquals("cloudflare", override?.resolverId)
            assertEquals("doh", override?.protocol)
            assertEquals("cloudflare-dns.com", override?.host)
            assertEquals("Use encrypted DNS", override?.reason)
        }

    @Test
    fun `save applies recommendation to settings and persists preferred path`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val session = diagnosticsSessionWithResolverRecommendation(sessionId = "session-1")
            stores.sessionsState.value = listOf(session)
            val appSettingsRepository = FakeAppSettingsRepository()
            val resolverOverrideStore =
                FakeResolverOverrideStore().apply {
                    setTemporaryOverride(
                        TemporaryResolverOverride(
                            resolverId = "temp",
                            protocol = "dot",
                            host = "temp.example",
                            port = 853,
                            tlsServerName = "temp.example",
                            bootstrapIps = listOf("9.9.9.9"),
                            dohUrl = "",
                            dnscryptProviderName = "",
                            dnscryptPublicKey = "",
                            reason = "temporary",
                            appliedAt = 1L,
                        ),
                    )
                }
            val actions =
                DefaultDiagnosticsResolverActions(
                    appSettingsRepository = appSettingsRepository,
                    recommendationStore = DiagnosticsRecommendationStore(stores, json),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    networkDnsPathPreferenceStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    resolverOverrideStore = resolverOverrideStore,
                )

            actions.saveResolverRecommendation(session.id)

            val savedSettings = appSettingsRepository.snapshot()
            val preferredPath =
                stores.getNetworkDnsPathPreference(FakeNetworkFingerprintProvider().capture().scopeKey())

            assertEquals(DnsModeEncrypted, savedSettings.dnsMode)
            assertEquals("cloudflare", savedSettings.dnsProviderId)
            assertEquals("1.1.1.1", savedSettings.dnsIp)
            assertEquals("doh", savedSettings.encryptedDnsProtocol)
            assertEquals("cloudflare-dns.com", savedSettings.encryptedDnsHost)
            assertEquals(443, savedSettings.encryptedDnsPort)
            assertEquals("https://cloudflare-dns.com/dns-query", savedSettings.encryptedDnsDohUrl)
            assertNull(resolverOverrideStore.override.value)
            assertNotNull(preferredPath)
            assertTrue(preferredPath?.pathJson?.contains("cloudflare") == true)
        }

    @Test
    fun `keep and save are no-ops when session has no recommendation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "session-1",
                        profileId = "default",
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "done",
                    ),
                )
            val appSettingsRepository = FakeAppSettingsRepository()
            val resolverOverrideStore = FakeResolverOverrideStore()
            val actions =
                DefaultDiagnosticsResolverActions(
                    appSettingsRepository = appSettingsRepository,
                    recommendationStore = DiagnosticsRecommendationStore(stores, json),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    networkDnsPathPreferenceStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    resolverOverrideStore = resolverOverrideStore,
                )

            actions.keepResolverRecommendationForSession("session-1")
            actions.saveResolverRecommendation("session-1")

            assertNull(resolverOverrideStore.override.value)
            assertNull(
                stores.getNetworkDnsPathPreference(FakeNetworkFingerprintProvider().capture().scopeKey()),
            )
            assertEquals("", appSettingsRepository.snapshot().dnsProviderId)
        }
}

private fun diagnosticsSessionWithResolverRecommendation(
    sessionId: String,
): com.poyka.ripdpi.data.diagnostics.ScanSessionEntity {
    val json = diagnosticsTestJson()
    return diagnosticsSession(
        id = sessionId,
        profileId = "default",
        pathMode = ScanPathMode.RAW_PATH.name,
        summary = "resolver recommendation",
        reportJson =
            json.encodeToString(
                com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                    .serializer(),
                ScanReport(
                    sessionId = sessionId,
                    profileId = "default",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 10L,
                    finishedAt = 20L,
                    summary = "resolver recommendation",
                    results =
                        listOf(
                            ProbeResult(
                                probeType = "dns",
                                target = "blocked.example",
                                outcome = "dns_blocked",
                            ),
                        ),
                    resolverRecommendation =
                        ResolverRecommendation(
                            triggerOutcome = "dns_blocked",
                            selectedResolverId = "cloudflare",
                            selectedProtocol = "doh",
                            selectedEndpoint = "https://cloudflare-dns.com/dns-query",
                            selectedBootstrapIps = listOf("1.1.1.1"),
                            selectedHost = "cloudflare-dns.com",
                            selectedPort = 443,
                            selectedTlsServerName = "cloudflare-dns.com",
                            selectedDohUrl = "https://cloudflare-dns.com/dns-query",
                            rationale = "Use encrypted DNS",
                            persistable = true,
                        ),
                ).toEngineScanReportWire(),
            ),
    )
}
