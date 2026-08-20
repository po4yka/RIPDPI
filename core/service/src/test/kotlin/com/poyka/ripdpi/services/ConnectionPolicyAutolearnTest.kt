package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiHostAutolearnConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AsnRoutingMapCatalog
import com.poyka.ripdpi.data.AsnRoutingMapEntry
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionPolicyAutolearnTest {
    @Test
    fun `remembered policy replay preserves current host autolearn controls`() =
        runTest {
            val rememberedStore =
                TestRememberedNetworkPolicyStore().apply {
                    validatedMatch =
                        sampleRememberedPolicyEntity(mode = Mode.VPN).copy(
                            proxyConfigJson =
                                RipDpiProxyUIPreferences(
                                    hostAutolearn =
                                        RipDpiHostAutolearnConfig(
                                            enabled = false,
                                            penaltyTtlHours = 6,
                                            maxHosts = 512,
                                            storePath = "/remembered/host-autolearn.json",
                                            networkScopeKey = "remembered-network",
                                        ),
                                ).toNativeConfigJson(),
                        )
                }
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository =
                        TestAppSettingsRepository(
                            AppSettingsSerializer.defaultValue
                                .toBuilder()
                                .setNetworkStrategyMemoryEnabled(true)
                                .setHostAutolearnEnabled(true)
                                .setHostAutolearnPenaltyTtlHours(12)
                                .setHostAutolearnMaxHosts(2048)
                                .build(),
                        ),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = rememberedStore,
                    rootHelperManager = RootHelperManager(),
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                    destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                )

            val hostAutolearn =
                decodeRipDpiProxyUiPreferences(resolver.resolve(mode = Mode.VPN).proxyPreferences.toNativeConfigJson())
                    ?.hostAutolearn

            assertEquals(
                Triple(true, 12, 2048),
                hostAutolearn?.let { Triple(it.enabled, it.penaltyTtlHours, it.maxHosts) },
            )
            assertTrue(hostAutolearn?.storePath?.endsWith("/host-autolearn-v2.json") == true)
            assertEquals(sampleFingerprint().scopeKey(), hostAutolearn?.networkScopeKey)
        }

    private fun antiCorrelationRoutingPolicy(): AntiCorrelationRoutingPolicy =
        DefaultAntiCorrelationRoutingPolicy(
            asnRoutingCatalogProvider =
                object : AsnRoutingCatalogProvider {
                    override fun load(): AsnRoutingMapCatalog =
                        AsnRoutingMapCatalog(
                            entries =
                                listOf(
                                    AsnRoutingMapEntry(
                                        asn = 13238,
                                        label = "Yandex",
                                        country = "RU",
                                        cdn = true,
                                    ),
                                ),
                        )
                },
        )
}
