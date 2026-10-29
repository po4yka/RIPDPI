package com.poyka.ripdpi.failover

import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.InitialTransportSelectionException
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessFlowVision
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.seed.SEED_RELAY_PROFILE_ID_PREFIX
import com.poyka.ripdpi.seed.SIMPLE_SEED_AWG_PROFILE_ID
import com.poyka.ripdpi.services.AmneziaWgEgressKind
import com.poyka.ripdpi.services.EgressRequirements
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SimpleRelayEgressReadinessPolicyTest {
    @Test
    fun `startup readiness preflights only configured VLESS`() =
        runTest {
            val readinessPolicy =
                SimpleRelayEgressReadinessPolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle() },
                    relayProfileStore = seededProfileStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                )

            val plan = readinessPolicy.plan(RealityProfileId, RelayKindVlessReality, "network-a")

            assertEquals(listOf(RelayKindVlessReality), plan?.candidates?.map { it.relayKind })
            assertEquals(RealityProfileId, plan?.candidates?.single()?.profileId)
            assertNull(plan?.cachedFallbackProfileId)
        }

    @Test
    fun `startup readiness projects non UDP Reality into a TCP only relay session`() =
        runTest {
            val readinessPolicy =
                SimpleRelayEgressReadinessPolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle() },
                    relayProfileStore = seededProfileStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                )
            val requirements = EgressRequirements(tcpConnect = true, udpAssociate = true)

            val plan =
                readinessPolicy.plan(
                    RealityProfileId,
                    RelayKindVlessReality,
                    "network-a",
                    requirements,
                )

            assertEquals(
                EgressRequirements(tcpConnect = true, udpAssociate = false),
                plan?.requirements,
            )
            assertEquals(plan?.requirements, plan?.probePlan?.requirements)
        }

    @Test
    fun `fallback readiness preflights only configured Hysteria2`() =
        runTest {
            val readinessPolicy =
                SimpleRelayEgressReadinessPolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle() },
                    relayProfileStore = seededProfileStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                )

            val plan = readinessPolicy.plan(HysteriaProfileId, RelayKindHysteria2, "network-a")

            assertEquals(listOf(RelayKindHysteria2), plan?.candidates?.map { it.relayKind })
            assertEquals(HysteriaProfileId, plan?.candidates?.single()?.profileId)
        }

    @Test
    fun `fallback readiness preflights configured VLESS xHTTP`() =
        runTest {
            val readinessPolicy =
                SimpleRelayEgressReadinessPolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle() },
                    relayProfileStore = seededProfileStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                )

            val plan = readinessPolicy.plan(XhttpProfileId, RelayKindVless, "network-a")

            assertEquals(listOf(RelayKindVless), plan?.candidates?.map { it.relayKind })
            assertEquals(XhttpProfileId, plan?.candidates?.single()?.profileId)
            assertEquals(
                EgressRequirements(tcpConnect = true, udpAssociate = false),
                plan?.probePlan?.requirements,
            )
        }

    @Test
    fun `fallback readiness preflights seeded AWG internet egress`() =
        runTest {
            val readinessPolicy =
                SimpleRelayEgressReadinessPolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle() },
                    relayProfileStore = seededProfileStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                )

            val plan = readinessPolicy.plan(SIMPLE_SEED_AWG_PROFILE_ID, AmneziaWgEgressKind, "network-a")

            assertEquals(listOf(AmneziaWgEgressKind), plan?.candidates?.map { it.relayKind })
            assertEquals(SIMPLE_SEED_AWG_PROFILE_ID, plan?.candidates?.single()?.profileId)
            assertEquals(
                EgressRequirements(tcpConnect = true, udpAssociate = false),
                plan?.probePlan?.requirements,
            )
        }

    @Test
    fun `seeded AWG readiness reports missing active probe target without replacing it`() =
        runTest {
            val readinessPolicy =
                SimpleRelayEgressReadinessPolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle("file:///not-http") },
                    relayProfileStore = seededProfileStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                )

            val plan = readinessPolicy.plan(SIMPLE_SEED_AWG_PROFILE_ID, AmneziaWgEgressKind, "network-a")

            assertEquals(
                Triple(null, com.poyka.ripdpi.services.RelayTargetCategory.Unavailable, "network-a"),
                Triple(
                    plan?.probePlan?.targetUrl,
                    plan?.probePlan?.targetCategory,
                    plan?.healthScope?.persistentNetworkHash,
                ),
            )
        }

    @Test
    fun `fallback readiness rejects seeded VLESS without xHTTP transport`() =
        runTest {
            val readinessPolicy =
                SimpleRelayEgressReadinessPolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle() },
                    relayProfileStore =
                        InMemoryRelayProfileStore(
                            RelayProfileRecord(
                                id = XhttpProfileId,
                                kind = RelayKindVless,
                                vlessTransport = RelayVlessTransportRealityTcp,
                            ),
                        ),
                    serviceStateStore = DefaultServiceStateStore(),
                )

            val error =
                runCatching {
                    readinessPolicy.plan(XhttpProfileId, RelayKindVless, "network-a")
                }.exceptionOrNull()

            assertTrue(error is InitialTransportSelectionException)
        }

    @Test
    fun `seeded relay startup preserves missing probe as unavailable evidence`() =
        runTest {
            val readinessPolicy =
                SimpleRelayEgressReadinessPolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle("file:///not-http") },
                    relayProfileStore = seededProfileStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                )

            val plan = readinessPolicy.plan(RealityProfileId, RelayKindVlessReality, "network-a")

            assertEquals(
                null to com.poyka.ripdpi.services.RelayTargetCategory.Unavailable,
                plan?.probePlan?.let { it.targetUrl to it.targetCategory },
            )
        }

    private fun seededProfileStore(
        realityUdpEnabled: Boolean = false,
        realityTransport: String = RelayVlessTransportRealityTcp,
        realityFlow: String = RelayVlessFlowVision,
    ): RelayProfileStore =
        InMemoryRelayProfileStore(
            RelayProfileRecord(
                id = RealityProfileId,
                kind = RelayKindVlessReality,
                udpEnabled = realityUdpEnabled,
                vlessTransport = realityTransport,
                vlessFlow = realityFlow,
            ),
            RelayProfileRecord(id = FallbackRealityProfileId, kind = RelayKindVlessReality),
            RelayProfileRecord(
                id = XhttpProfileId,
                kind = RelayKindVless,
                vlessTransport = RelayVlessTransportXhttp,
            ),
            RelayProfileRecord(id = HysteriaProfileId, kind = RelayKindHysteria2, udpEnabled = true),
        )

    private fun validBundle(probeUrl: String = "https://probe.example/generate_204"): String =
        """
        {
          "outbounds": [
            {
              "type": "vless", "tag": "reality", "server": "192.0.2.10", "server_port": 443,
              "uuid": "00000000-0000-0000-0000-000000000001", "flow": "xtls-rprx-vision",
              "tls": { "enabled": true, "server_name": "example.test",
                "reality": { "enabled": true,
                  "public_key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "short_id": "deadbeef" } }
            },
            {
              "type": "hysteria2", "tag": "hysteria", "server": "192.0.2.20", "server_port": 8443,
              "password": "fixture-value", "obfs": { "type": "salamander", "password": "fixture-obfs-value" }
            },
            { "type": "selector", "tag": "select", "outbounds": ["reality", "hysteria", "auto"] },
            { "type": "urltest", "tag": "auto", "outbounds": ["reality", "hysteria"], "url": "$probeUrl" }
          ]
        }
        """.trimIndent()

    private companion object {
        const val RealityProfileId = "${SEED_RELAY_PROFILE_ID_PREFIX}VlessReality"
        const val FallbackRealityProfileId = "${SEED_RELAY_PROFILE_ID_PREFIX}VlessReality-2"
        const val XhttpProfileId = "${SEED_RELAY_PROFILE_ID_PREFIX}Vless"
        const val HysteriaProfileId = "${SEED_RELAY_PROFILE_ID_PREFIX}Hysteria2"
    }
}

private class InMemoryRelayProfileStore(
    vararg profiles: RelayProfileRecord,
) : RelayProfileStore {
    private val values = profiles.associateByTo(linkedMapOf(), RelayProfileRecord::id)

    override suspend fun load(profileId: String): RelayProfileRecord? = values[profileId]

    override suspend fun list(): List<RelayProfileRecord> = values.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        values[profile.id] = profile
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }
}
