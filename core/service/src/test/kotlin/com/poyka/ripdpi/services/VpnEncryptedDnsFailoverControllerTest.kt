package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.buildEncryptedDnsCandidatePlan
import com.poyka.ripdpi.data.toEncryptedDnsPathCandidate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnEncryptedDnsFailoverControllerTest {
    @Test
    fun `single dns failure event does not trigger failover`() =
        runTest {
            val env = newEnv()

            env.controller.evaluate(
                state = env.state,
                activeDns = cloudflareDohDns(),
                currentDnsSignature = dnsSignature(cloudflareDohDns(), overrideReason = null),
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 0, failures = 0),
            )

            val failoverTriggered =
                env.controller.evaluate(
                    state = env.state,
                    activeDns = cloudflareDohDns(),
                    currentDnsSignature = dnsSignature(cloudflareDohDns(), overrideReason = null),
                    networkScopeKey = env.fingerprint.scopeKey(),
                    telemetry = dnsTelemetry(queries = 1, failures = 1, lastDnsError = "resolver timeout"),
                )

            assertFalse(failoverTriggered)
            assertNull(env.overrides.override.value)
        }

    @Test
    fun `second consecutive dns failure event triggers failover to next candidate`() =
        runTest {
            val env = newEnv()
            val currentDns = cloudflareDohDns()

            env.controller.evaluate(
                state = env.state,
                activeDns = currentDns,
                currentDnsSignature = dnsSignature(currentDns, overrideReason = null),
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 0, failures = 0),
            )
            env.controller.evaluate(
                state = env.state,
                activeDns = currentDns,
                currentDnsSignature = dnsSignature(currentDns, overrideReason = null),
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 1, failures = 1, lastDnsError = "resolver timeout"),
            )

            val failoverTriggered =
                env.controller.evaluate(
                    state = env.state,
                    activeDns = currentDns,
                    currentDnsSignature = dnsSignature(currentDns, overrideReason = null),
                    networkScopeKey = env.fingerprint.scopeKey(),
                    telemetry = dnsTelemetry(queries = 2, failures = 2, lastDnsError = "resolver timeout"),
                )

            val override = env.overrides.override.value
            assertTrue(failoverTriggered)
            assertNotNull(override)
            assertEquals("vpn_encrypted_dns_auto_failover: resolver timeout", override?.reason)
            assertTrue(override?.toActiveDnsSettings() != currentDns)
            assertTrue(env.state.attemptedPathKeys.contains(currentDns.toEncryptedDnsPathCandidate()!!.pathKey()))
        }

    @Test
    fun `successful dns response resets failure counter`() =
        runTest {
            val env = newEnv()
            val currentDns = cloudflareDohDns()
            val signature = dnsSignature(currentDns, overrideReason = null)

            env.controller.evaluate(
                state = env.state,
                activeDns = currentDns,
                currentDnsSignature = signature,
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 0, failures = 0),
            )
            env.controller.evaluate(
                state = env.state,
                activeDns = currentDns,
                currentDnsSignature = signature,
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 1, failures = 1, lastDnsError = "resolver timeout"),
            )
            env.controller.evaluate(
                state = env.state,
                activeDns = currentDns,
                currentDnsSignature = signature,
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 2, failures = 1, lastDnsError = null),
            )

            val failoverTriggered =
                env.controller.evaluate(
                    state = env.state,
                    activeDns = currentDns,
                    currentDnsSignature = signature,
                    networkScopeKey = env.fingerprint.scopeKey(),
                    telemetry = dnsTelemetry(queries = 3, failures = 2, lastDnsError = "resolver timeout"),
                )

            assertFalse(failoverTriggered)
            assertNull(env.overrides.override.value)
        }

    @Test
    fun `controller persists preferred path after successful failover`() =
        runTest {
            val env = newEnv()
            val currentDns = cloudflareDohDns()
            val currentSignature = dnsSignature(currentDns, overrideReason = null)

            env.controller.evaluate(
                state = env.state,
                activeDns = currentDns,
                currentDnsSignature = currentSignature,
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 0, failures = 0),
            )
            env.controller.evaluate(
                state = env.state,
                activeDns = currentDns,
                currentDnsSignature = currentSignature,
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 1, failures = 1, lastDnsError = "resolver timeout"),
            )
            env.controller.evaluate(
                state = env.state,
                activeDns = currentDns,
                currentDnsSignature = currentSignature,
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 2, failures = 2, lastDnsError = "resolver timeout"),
            )

            val override = requireNotNull(env.overrides.override.value)
            val failedOverDns = override.toActiveDnsSettings()
            val failedOverSignature = dnsSignature(failedOverDns, override.reason)
            env.controller.evaluate(
                state = env.state,
                activeDns = failedOverDns,
                currentDnsSignature = failedOverSignature,
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 1, failures = 0),
            )
            env.controller.evaluate(
                state = env.state,
                activeDns = failedOverDns,
                currentDnsSignature = failedOverSignature,
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 2, failures = 0),
            )

            val preferredPath =
                env.preferredPaths.getPreferredPath(env.fingerprint.scopeKey())
            assertEquals(failedOverDns, preferredPath?.toActiveDnsSettings())
            assertTrue(env.state.currentPathPersisted)
        }

    @Test
    fun `controller stops retrying when all candidates are exhausted`() =
        runTest {
            val env = newEnv()
            val currentDns = cloudflareDohDns()
            val signature = dnsSignature(currentDns, overrideReason = null)

            env.controller.evaluate(
                state = env.state,
                activeDns = currentDns,
                currentDnsSignature = signature,
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 0, failures = 0),
            )
            buildEncryptedDnsCandidatePlan(currentDns).forEach { candidate ->
                env.state.attemptedPathKeys += candidate.pathKey()
            }

            env.controller.evaluate(
                state = env.state,
                activeDns = currentDns,
                currentDnsSignature = signature,
                networkScopeKey = env.fingerprint.scopeKey(),
                telemetry = dnsTelemetry(queries = 1, failures = 1, lastDnsError = "resolver timeout"),
            )
            val failoverTriggered =
                env.controller.evaluate(
                    state = env.state,
                    activeDns = currentDns,
                    currentDnsSignature = signature,
                    networkScopeKey = env.fingerprint.scopeKey(),
                    telemetry = dnsTelemetry(queries = 2, failures = 2, lastDnsError = "resolver timeout"),
                )

            assertFalse(failoverTriggered)
            assertTrue(env.state.exhausted)
            assertNull(env.overrides.override.value)
        }

    private fun newEnv(): Env {
        val overrides = TestResolverOverrideStore()
        val preferredPaths = TestNetworkDnsPathPreferenceStore()
        val fingerprint = sampleFingerprint()
        return Env(
            controller =
                VpnEncryptedDnsFailoverController(
                    resolverOverrideStore = overrides,
                    networkDnsPathPreferenceStore = preferredPaths,
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    clock = TestServiceClock(now = 123L),
                ),
            state = VpnEncryptedDnsFailoverState(),
            overrides = overrides,
            preferredPaths = preferredPaths,
            fingerprint = fingerprint,
        )
    }

    private fun cloudflareDohDns() =
        activeDnsSettings(
            dnsMode = "encrypted",
            dnsProviderId = DnsProviderCloudflare,
            dnsIp = "1.1.1.1",
            encryptedDnsProtocol = EncryptedDnsProtocolDoh,
            encryptedDnsHost = "cloudflare-dns.com",
            encryptedDnsPort = 443,
            encryptedDnsTlsServerName = "cloudflare-dns.com",
            encryptedDnsBootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
            encryptedDnsDohUrl = "https://cloudflare-dns.com/dns-query",
        )

    private fun dnsTelemetry(
        queries: Long,
        failures: Long,
        lastDnsError: String? = null,
    ) = NativeRuntimeSnapshot(
        source = "tunnel",
        state = "running",
        health = "healthy",
        dnsQueriesTotal = queries,
        dnsFailuresTotal = failures,
        lastDnsError = lastDnsError,
    )

    private data class Env(
        val controller: VpnEncryptedDnsFailoverController,
        val state: VpnEncryptedDnsFailoverState,
        val overrides: TestResolverOverrideStore,
        val preferredPaths: TestNetworkDnsPathPreferenceStore,
        val fingerprint: com.poyka.ripdpi.data.NetworkFingerprint,
    )
}
