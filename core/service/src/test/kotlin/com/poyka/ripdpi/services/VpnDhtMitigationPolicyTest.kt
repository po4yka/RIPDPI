package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DhtMitigationModeBypass
import com.poyka.ripdpi.data.DhtMitigationModeDropWarn
import com.poyka.ripdpi.data.DhtTriggerCidrsCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnDhtMitigationPolicyTest {
    @Test
    fun `bypass mode returns excluded routes when route exclusion is supported`() =
        runTest {
            val repository =
                TestAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setDhtMitigationMode(DhtMitigationModeBypass)
                        .build(),
                )
            val policy =
                DefaultVpnDhtMitigationPolicy(
                    appSettingsRepository = repository,
                    catalogProvider =
                        FakeDhtTriggerCidrsCatalogProvider(
                            DhtTriggerCidrsCatalog(
                                cidrs = listOf("134.195.196.0/22", "62.210.0.0/17"),
                            ),
                        ),
                )

            val plan = policy.buildPlan(supportsRouteExclusion = true)

            assertEquals(
                listOf(
                    VpnExcludedRoute("134.195.196.0", 22),
                    VpnExcludedRoute("62.210.0.0", 17),
                ),
                plan.excludedRoutes,
            )
            assertNull(plan.warningMessage)
        }

    @Test
    fun `drop warn mode emits warning without excluded routes`() =
        runTest {
            val repository =
                TestAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setDhtMitigationMode(DhtMitigationModeDropWarn)
                        .build(),
                )
            val policy =
                DefaultVpnDhtMitigationPolicy(
                    appSettingsRepository = repository,
                    catalogProvider = FakeDhtTriggerCidrsCatalogProvider(DhtTriggerCidrsCatalog(cidrs = listOf("a"))),
                )

            val plan = policy.buildPlan(supportsRouteExclusion = true)

            assertTrue(plan.excludedRoutes.isEmpty())
            assertTrue(plan.warningMessage?.contains("drop+warn") == true)
        }

    @Test
    fun `full tunnel mode suppresses dht mitigation`() =
        runTest {
            val repository =
                TestAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setFullTunnelMode(true)
                        .setDhtMitigationMode(DhtMitigationModeBypass)
                        .build(),
                )
            val policy =
                DefaultVpnDhtMitigationPolicy(
                    appSettingsRepository = repository,
                    catalogProvider =
                        FakeDhtTriggerCidrsCatalogProvider(
                            DhtTriggerCidrsCatalog(cidrs = listOf("134.195.196.0/22")),
                        ),
                )

            val plan = policy.buildPlan(supportsRouteExclusion = true)

            assertTrue(plan.excludedRoutes.isEmpty())
            assertNull(plan.warningMessage)
        }
}

private class FakeDhtTriggerCidrsCatalogProvider(
    private val catalog: DhtTriggerCidrsCatalog,
) : DhtTriggerCidrsCatalogProvider {
    override fun load(): DhtTriggerCidrsCatalog = catalog
}
