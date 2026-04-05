package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderAdGuard
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.activeDnsSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnResolverRefreshPlannerTest {
    @Test
    fun unchangedSignatureDoesNotRequestTunnelRebuild() =
        runTest {
            val settings = AppSettingsSerializer.defaultValue
            val resolver = TestConnectionPolicyResolver(sampleResolution(mode = Mode.VPN, settings = settings))
            val planner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver = resolver,
                    resolverOverrideStore = TestResolverOverrideStore(),
                )

            val plan =
                planner.plan(
                    currentSignature = dnsSignature(settings.activeDnsSettings(), overrideReason = null),
                    tunnelRunning = true,
                )

            assertFalse(plan.requiresTunnelRebuild)
            assertNotNull(plan.connectionPolicy)
        }

    @Test
    fun changedSignatureRequestsTunnelRebuild() =
        runTest {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsIp("8.8.8.8")
                    .build()
            val resolver =
                TestConnectionPolicyResolver(
                    sampleResolution(
                        mode = Mode.VPN,
                        settings = settings,
                        activeDns = settings.activeDnsSettings(),
                    ),
                )
            val planner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver = resolver,
                    resolverOverrideStore = TestResolverOverrideStore(),
                )

            val plan =
                planner.plan(
                    currentSignature =
                        dnsSignature(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null),
                    tunnelRunning = true,
                )

            assertTrue(plan.requiresTunnelRebuild)
            assertEquals("8.8.8.8", plan.connectionPolicy?.activeDns?.dnsIp)
        }

    @Test
    fun resolvedPreferredDnsPathDoesNotTriggerRebuildLoop() =
        runTest {
            val persistedSettings = AppSettingsSerializer.defaultValue
            val preferredSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModeEncrypted)
                    .setDnsProviderId(DnsProviderGoogle)
                    .setDnsIp("8.8.8.8")
                    .setEncryptedDnsProtocol(EncryptedDnsProtocolDoh)
                    .setEncryptedDnsHost("dns.google")
                    .setEncryptedDnsPort(443)
                    .setEncryptedDnsTlsServerName("dns.google")
                    .addAllEncryptedDnsBootstrapIps(listOf("8.8.8.8", "8.8.4.4"))
                    .setEncryptedDnsDohUrl("https://dns.google/dns-query")
                    .build()
            val planner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver =
                        TestConnectionPolicyResolver(
                            sampleResolution(
                                mode = Mode.VPN,
                                settings = persistedSettings,
                                activeDns = preferredSettings.activeDnsSettings(),
                            ),
                        ),
                    resolverOverrideStore = TestResolverOverrideStore(),
                )

            val plan =
                planner.plan(
                    currentSignature =
                        dnsSignature(
                            activeDns = preferredSettings.activeDnsSettings(),
                            overrideReason = null,
                        ),
                    tunnelRunning = true,
                )

            assertFalse(plan.requiresTunnelRebuild)
            assertEquals(DnsProviderGoogle, plan.connectionPolicy?.activeDns?.providerId)
            assertEquals(DnsProviderAdGuard, plan.resolution.activeDns.providerId)
        }

    @Test
    fun overrideIsClearedOnlyWhenPersistedDnsAlreadyMatchesEffectiveDns() =
        runTest {
            val matchingSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModeEncrypted)
                    .setDnsProviderId(DnsProviderCloudflare)
                    .setDnsIp("1.1.1.1")
                    .setEncryptedDnsProtocol(EncryptedDnsProtocolDoh)
                    .setEncryptedDnsHost("cloudflare-dns.com")
                    .setEncryptedDnsPort(443)
                    .setEncryptedDnsTlsServerName("cloudflare-dns.com")
                    .addAllEncryptedDnsBootstrapIps(listOf("1.1.1.1", "1.0.0.1"))
                    .setEncryptedDnsDohUrl("https://cloudflare-dns.com/dns-query")
                    .build()
            val matchingOverride =
                TemporaryResolverOverride(
                    resolverId = DnsProviderCloudflare,
                    protocol = EncryptedDnsProtocolDoh,
                    host = "cloudflare-dns.com",
                    port = 443,
                    tlsServerName = "cloudflare-dns.com",
                    bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                    dohUrl = "https://cloudflare-dns.com/dns-query",
                    dnscryptProviderName = "",
                    dnscryptPublicKey = "",
                    reason = "temporary override",
                    appliedAt = 10L,
                )
            val matchingStore = TestResolverOverrideStore(matchingOverride)
            val matchingPlanner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver =
                        TestConnectionPolicyResolver(sampleResolution(mode = Mode.VPN, settings = matchingSettings)),
                    resolverOverrideStore = matchingStore,
                )

            matchingPlanner.plan(currentSignature = null, tunnelRunning = true)

            assertNull(matchingStore.override.value)

            val nonMatchingSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsIp("9.9.9.9")
                    .build()
            val nonMatchingStore = TestResolverOverrideStore(matchingOverride)
            val nonMatchingPlanner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver =
                        TestConnectionPolicyResolver(sampleResolution(mode = Mode.VPN, settings = nonMatchingSettings)),
                    resolverOverrideStore = nonMatchingStore,
                )

            nonMatchingPlanner.plan(currentSignature = null, tunnelRunning = true)

            assertNotNull(nonMatchingStore.override.value)
        }
}
