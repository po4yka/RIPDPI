package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnTunnelRuntimePolicyTest {
    @Test
    fun startUsesPersistedPackageRulesForOneAndroidAndNativePlan() =
        runTest {
            val rule = packageRule("com.example.subscription")
            val repository = TestProxyGroupRepository(listOf(proxyGroup(listOf(rule))))
            val settings = AppSettingsSerializer.defaultValue
            val host = policyAwareHost()
            val sessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession())
            var nativePlan: VpnAppRoutingPlan? = null
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(settings),
                    proxyGroupRepository = repository,
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge()),
                    vpnTunnelSessionProvider = sessionProvider,
                    nativeUidPolicyProvider = { plan ->
                        nativePlan = plan
                        NativeUidPolicy("allowlist", listOf(10_321))
                    },
                )

            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = localProxyEndpoint,
            )

            assertEquals(listOf(rule), host.lastPackageRoutingRules)
            assertTrue("com.example.subscription" in host.lastInstalledPackages)
            assertSame(sessionProvider.lastAppRoutingPlan, nativePlan)
            assertSame(settings, sessionProvider.lastInterfaceSettings)
            assertEquals(
                VpnAppRoutingPlan.AllowOnly(setOf("com.example.subscription")),
                sessionProvider.lastAppRoutingPlan,
            )
        }

    @Test
    fun desiredPolicyFlowIgnoresGroupMetadataAndEmitsEffectiveRouteChanges() =
        runTest {
            val initialRule = packageRule("com.example.a")
            val repository = TestProxyGroupRepository(listOf(proxyGroup(listOf(initialRule))))
            val host = policyAwareHost()
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = repository,
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(),
                )
            val signatures = mutableListOf<String>()
            backgroundScope.launch {
                runtime.desiredInterfacePolicySignatures().take(3).toList(signatures)
            }
            runCurrent()

            repository.update(proxyGroup(listOf(initialRule), name = "Renamed only"))
            runCurrent()
            assertEquals(1, signatures.size)

            repository.update(proxyGroup(listOf(packageRule("com.example.b")), name = "Renamed only"))
            runCurrent()

            assertEquals(2, signatures.size)
            assertTrue(signatures[0] != signatures[1])

            host.updateInstalledPackages(setOf("com.example.a"))
            runCurrent()

            assertEquals(3, signatures.size)
            assertTrue(signatures[1] != signatures[2])
        }

    private fun kotlinx.coroutines.test.TestScope.policyAwareHost(): TestVpnServiceHost =
        TestVpnServiceHost(backgroundScope).apply {
            updateInstalledPackages(
                setOf("com.example.subscription", "com.example.a", "com.example.b"),
            )
            appRoutingPlanResolver = { _, rules, installedPackages ->
                VpnAppRoutingPlan.AllowOnly(
                    rules.mapTo(linkedSetOf()) { it.packageName }.intersect(installedPackages),
                )
            }
        }

    private companion object {
        val localProxyEndpoint =
            LocalProxyEndpoint(
                host = "127.0.0.1",
                port = 18080,
                username = VpnLocalProxyUsername,
            )

        fun packageRule(packageName: String): PackageRoutingRule =
            PackageRoutingRule(
                packageName = packageName,
                action = PackageRoutingAction.VIA_TUN,
                origin = PackageRoutingRuleOrigin.Subscription("sub-1"),
            )

        fun proxyGroup(
            rules: List<PackageRoutingRule>,
            name: String = "Imported",
        ): ProxyGroup =
            ProxyGroup(
                id = "group-1",
                name = name,
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = true,
                packageRoutingRules = rules,
            )
    }
}
