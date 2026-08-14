package com.poyka.ripdpi.failover

import android.app.Application
import android.content.Context
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.InitialTransportSelectionException
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
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
import com.poyka.ripdpi.services.InitialRelayRaceResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SimpleInitialRelayRacePolicyTest {
    private lateinit var application: Application
    private lateinit var clock: MutableFailoverClock
    private lateinit var healthCache: SimpleEgressHealthCache
    private lateinit var failoverBridge: RecordingInitialRaceFailoverCoordinator
    private lateinit var policy: SimpleInitialRelayRacePolicy

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application
            .getSharedPreferences("simple_initial_relay_race", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        clock = MutableFailoverClock(1_000L)
        healthCache = SimpleEgressHealthCache(application, clock)
        failoverBridge = RecordingInitialRaceFailoverCoordinator()
        policy =
            SimpleInitialRelayRacePolicy(
                bundleSource = SimpleRelayBundleSource { validBundle() },
                relayProfileStore = seededProfileStore(),
                relayCredentialStore = seededCredentialStore(),
                serviceStateStore = DefaultServiceStateStore(),
                failoverCoordinator = failoverBridge,
                egressHealthCache = healthCache,
            )
    }

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
    fun `startup readiness keeps runtime UDP requirement but probes web egress only`() =
        runTest {
            val readinessPolicy =
                SimpleRelayEgressReadinessPolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle() },
                    relayProfileStore = seededProfileStore(realityUdpEnabled = true),
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

            assertEquals(requirements, plan?.requirements)
            assertEquals(
                EgressRequirements(tcpConnect = true, udpAssociate = false),
                plan?.readinessProbeRequirements,
            )
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
                plan?.readinessProbeRequirements,
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
                plan?.readinessProbeRequirements,
            )
        }

    @Test
    fun `seeded AWG readiness rejects missing active probe`() =
        runTest {
            val readinessPolicy =
                SimpleRelayEgressReadinessPolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle("file:///not-http") },
                    relayProfileStore = seededProfileStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                )

            val error =
                runCatching {
                    readinessPolicy.plan(SIMPLE_SEED_AWG_PROFILE_ID, AmneziaWgEgressKind, "network-a")
                }.exceptionOrNull()

            assertTrue(error is InitialTransportSelectionException)
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
    fun `seeded relay startup rejects missing active probe`() =
        runTest {
            val readinessPolicy =
                SimpleRelayEgressReadinessPolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle("file:///not-http") },
                    relayProfileStore = seededProfileStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                )

            val error =
                runCatching {
                    readinessPolicy.plan(RealityProfileId, RelayKindVlessReality, "network-a")
                }.exceptionOrNull()

            assertTrue(error is InitialTransportSelectionException)
        }

    @Test
    fun `winner cache is network scoped and expires without cached fallback refresh`() =
        runTest {
            val first = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a")!!
            assertNull(first.cachedFallbackProfileId)
            policy.onSelected(
                InitialRelayRaceResult(
                    selectedCandidate = first.candidates[1],
                    usedCachedFallback = false,
                    latencyMs = 40L,
                ),
            )

            assertEquals(
                HysteriaProfileId,
                policy.plan(RealityProfileId, RelayKindVlessReality, "network-a")?.cachedFallbackProfileId,
            )
            assertNull(policy.plan(RealityProfileId, RelayKindVlessReality, "network-b")?.cachedFallbackProfileId)

            policy =
                SimpleInitialRelayRacePolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle("https://probe.example/changed") },
                    relayProfileStore = seededProfileStore(),
                    relayCredentialStore = seededCredentialStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                    failoverCoordinator = failoverBridge,
                    egressHealthCache = healthCache,
                )
            assertNull(policy.plan(RealityProfileId, RelayKindVlessReality, "network-a")?.cachedFallbackProfileId)

            clock.now = 23L * HourMillis
            policy =
                SimpleInitialRelayRacePolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle() },
                    relayProfileStore = seededProfileStore(),
                    relayCredentialStore = seededCredentialStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                    failoverCoordinator = failoverBridge,
                    egressHealthCache = healthCache,
                )
            val cached = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a")!!
            policy.onSelected(
                InitialRelayRaceResult(
                    selectedCandidate = cached.candidates[1],
                    usedCachedFallback = true,
                    latencyMs = null,
                ),
            )
            clock.now = 25L * HourMillis
            assertNull(policy.plan(RealityProfileId, RelayKindVlessReality, "network-a")?.cachedFallbackProfileId)
        }

    @Test
    fun `missing network scope still races but cannot use a cache`() =
        runTest {
            val plan = policy.plan(RealityProfileId, RelayKindVlessReality, null)

            assertEquals(2, plan?.candidates?.size)
            assertNull(plan?.cachedFallbackProfileId)
        }

    @Test
    fun `default UDP requirements preflight only udp-enabled hysteria`() =
        runTest {
            val requirements = EgressRequirements(tcpConnect = true, udpAssociate = true)

            val plan = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a", requirements)

            assertEquals(requirements, plan?.requirements)
            assertEquals(listOf(RelayKindHysteria2), plan?.candidates?.map { it.relayKind })
            assertNull(plan?.cachedFallbackProfileId)
        }

    @Test
    fun `xudp-enabled reality precedes hysteria for UDP requirements`() =
        runTest {
            policy =
                SimpleInitialRelayRacePolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle() },
                    relayProfileStore = seededProfileStore(realityUdpEnabled = true),
                    relayCredentialStore = seededCredentialStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                    failoverCoordinator = failoverBridge,
                    egressHealthCache = healthCache,
                )

            val plan =
                policy.plan(
                    RealityProfileId,
                    RelayKindVlessReality,
                    "network-a",
                    EgressRequirements(tcpConnect = true, udpAssociate = true),
                )

            assertEquals(listOf(RelayKindVlessReality, RelayKindHysteria2), plan?.candidates?.map { it.relayKind })
        }

    @Test
    fun `confirmed XUDP failure invalidates Reality and cooldown is network scoped`() =
        runTest {
            val requirements = EgressRequirements(tcpConnect = true, udpAssociate = true)
            policy = udpEnabledRealityPolicy()
            val initial = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a", requirements)!!
            policy.onSelected(InitialRelayRaceResult(initial.candidates.first(), false, 20L))

            healthCache.recordConfirmedFailure(
                networkScopeKey = "network-a",
                proof = EgressProof.TcpUdp,
                relayKind = RelayKindVlessReality,
                profileId = RealityProfileId,
            )

            val cooling = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a", requirements)
            val otherNetwork = policy.plan(RealityProfileId, RelayKindVlessReality, "network-b", requirements)
            assertEquals(listOf(RelayKindHysteria2), cooling?.candidates?.map { it.relayKind })
            assertNull(cooling?.cachedFallbackProfileId)
            assertEquals(
                listOf(RelayKindVlessReality, RelayKindHysteria2),
                otherNetwork?.candidates?.map { it.relayKind },
            )
        }

    @Test
    fun `Reality returns first after XUDP cooldown without affecting TCP proof`() =
        runTest {
            val udpRequirements = EgressRequirements(tcpConnect = true, udpAssociate = true)
            policy = udpEnabledRealityPolicy()
            healthCache.recordConfirmedFailure(
                networkScopeKey = "network-a",
                proof = EgressProof.TcpUdp,
                relayKind = RelayKindVlessReality,
                profileId = RealityProfileId,
            )

            val tcpPlan = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a")
            assertEquals(RelayKindVlessReality, tcpPlan?.candidates?.first()?.relayKind)

            clock.now += SimpleEgressHealthCache.NegativeCooldownMillis
            val retried = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a", udpRequirements)
            assertEquals(RelayKindVlessReality, retried?.candidates?.first()?.relayKind)
            assertNull(retried?.cachedFallbackProfileId)
        }

    @Test
    fun `xhttp and flowless reality stay out of UDP race`() =
        runTest {
            suspend fun candidateKinds(
                transport: String,
                flow: String,
            ): List<String>? {
                policy =
                    SimpleInitialRelayRacePolicy(
                        bundleSource = SimpleRelayBundleSource { validBundle() },
                        relayProfileStore =
                            seededProfileStore(
                                realityUdpEnabled = true,
                                realityTransport = transport,
                                realityFlow = flow,
                            ),
                        relayCredentialStore = seededCredentialStore(),
                        serviceStateStore = DefaultServiceStateStore(),
                        failoverCoordinator = failoverBridge,
                        egressHealthCache = healthCache,
                    )
                return policy
                    .plan(
                        RealityProfileId,
                        RelayKindVlessReality,
                        "network-a",
                        EgressRequirements(tcpConnect = true, udpAssociate = true),
                    )?.candidates
                    ?.map { it.relayKind }
            }

            assertEquals(
                listOf(RelayKindHysteria2),
                candidateKinds(RelayVlessTransportXhttp, RelayVlessFlowVision),
            )
            assertEquals(
                listOf(RelayKindHysteria2),
                candidateKinds(RelayVlessTransportRealityTcp, ""),
            )
        }

    @Test
    fun `reality cache is ignored when UDP becomes required`() =
        runTest {
            val tcpPlan = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a")!!
            policy.onSelected(InitialRelayRaceResult(tcpPlan.candidates.first(), false, 20L))

            val udpPlan =
                policy.plan(
                    RealityProfileId,
                    RelayKindVlessReality,
                    "network-a",
                    EgressRequirements(tcpConnect = true, udpAssociate = true),
                )

            assertEquals(listOf(RelayKindHysteria2), udpPlan?.candidates?.map { it.relayKind })
            assertNull(udpPlan?.cachedFallbackProfileId)
        }

    @Test
    fun `cached hysteria does not replace newly compatible Reality candidate set`() =
        runTest {
            val requirements = EgressRequirements(tcpConnect = true, udpAssociate = true)
            val hysteriaOnly = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a", requirements)!!
            policy.onSelected(InitialRelayRaceResult(hysteriaOnly.candidates.single(), false, 30L))

            policy =
                SimpleInitialRelayRacePolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle() },
                    relayProfileStore = seededProfileStore(realityUdpEnabled = true),
                    relayCredentialStore = seededCredentialStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                    failoverCoordinator = failoverBridge,
                    egressHealthCache = healthCache,
                )
            val xudpPlan = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a", requirements)

            assertEquals(listOf(RelayKindVlessReality, RelayKindHysteria2), xudpPlan?.candidates?.map { it.relayKind })
            assertNull(xudpPlan?.cachedFallbackProfileId)
        }

    @Test
    fun `hysteria without salamander obfuscation remains eligible for the race`() =
        runTest {
            policy =
                SimpleInitialRelayRacePolicy(
                    bundleSource = SimpleRelayBundleSource { validBundleWithoutObfs() },
                    relayProfileStore = seededProfileStore(),
                    relayCredentialStore =
                        InMemoryRelayCredentialStore(
                            RelayCredentialRecord(
                                profileId = HysteriaProfileId,
                                hysteriaPassword = "fixture-value",
                            ),
                        ),
                    serviceStateStore = DefaultServiceStateStore(),
                    failoverCoordinator = failoverBridge,
                    egressHealthCache = healthCache,
                )

            val plan = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a")

            assertEquals(2, plan?.candidates?.size)
            assertEquals(HysteriaProfileId, plan?.candidates?.last()?.profileId)
        }

    @Test
    fun `advertised salamander obfuscation requires its stored key`() =
        runTest {
            policy =
                SimpleInitialRelayRacePolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle() },
                    relayProfileStore = seededProfileStore(),
                    relayCredentialStore =
                        InMemoryRelayCredentialStore(
                            RelayCredentialRecord(
                                profileId = HysteriaProfileId,
                                hysteriaPassword = "fixture-value",
                            ),
                        ),
                    serviceStateStore = DefaultServiceStateStore(),
                    failoverCoordinator = failoverBridge,
                    egressHealthCache = healthCache,
                )

            assertNull(policy.plan(RealityProfileId, RelayKindVlessReality, "network-a"))
        }

    @Test
    fun `multiple reality endpoints race the first declared reality against hysteria`() =
        runTest {
            policy =
                SimpleInitialRelayRacePolicy(
                    bundleSource = SimpleRelayBundleSource { multiRealityBundle() },
                    relayProfileStore = seededProfileStore(),
                    relayCredentialStore = seededCredentialStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                    failoverCoordinator = failoverBridge,
                    egressHealthCache = healthCache,
                )

            val plan = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a")

            assertEquals(2, plan?.candidates?.size)
            assertEquals(RealityProfileId, plan?.candidates?.first()?.profileId)
            assertEquals(HysteriaProfileId, plan?.candidates?.last()?.profileId)
        }

    @Test
    fun `selected fallback reality races its exact endpoint against hysteria`() =
        runTest {
            policy =
                SimpleInitialRelayRacePolicy(
                    bundleSource = SimpleRelayBundleSource { multiRealityBundle() },
                    relayProfileStore = seededProfileStore(),
                    relayCredentialStore = seededCredentialStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                    failoverCoordinator = failoverBridge,
                    egressHealthCache = healthCache,
                )

            val plan = policy.plan(FallbackRealityProfileId, RelayKindVlessReality, "network-a")

            assertEquals(FallbackRealityProfileId, plan?.candidates?.first()?.profileId)
            assertEquals(HysteriaProfileId, plan?.candidates?.last()?.profileId)
        }

    @Test
    fun `unknown seeded endpoint disables race instead of substituting first member`() =
        runTest {
            policy =
                SimpleInitialRelayRacePolicy(
                    bundleSource = SimpleRelayBundleSource { multiRealityBundle() },
                    relayProfileStore = seededProfileStore(),
                    relayCredentialStore = seededCredentialStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                    failoverCoordinator = failoverBridge,
                    egressHealthCache = healthCache,
                )

            assertNull(
                policy.plan(
                    "${SEED_RELAY_PROFILE_ID_PREFIX}VlessReality-3",
                    RelayKindVlessReality,
                    "network-a",
                ),
            )
        }

    @Test
    fun `failover induced restart and malformed url disable the race`() =
        runTest {
            failoverBridge.skip = true
            assertNull(policy.plan(RealityProfileId, RelayKindVlessReality, "network-a"))

            failoverBridge.skip = false
            policy =
                SimpleInitialRelayRacePolicy(
                    bundleSource = SimpleRelayBundleSource { validBundle(probeUrl = "file:///not-http") },
                    relayProfileStore = seededProfileStore(),
                    relayCredentialStore = seededCredentialStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                    failoverCoordinator = failoverBridge,
                    egressHealthCache = healthCache,
                )
            assertNull(policy.plan(RealityProfileId, RelayKindVlessReality, "network-a"))
        }

    @Test
    fun `manual profile and incomplete seeded pair preserve legacy startup`() =
        runTest {
            assertNull(policy.plan("manual-profile", RelayKindVlessReality, "network-a"))

            policy =
                SimpleInitialRelayRacePolicy(
                    bundleSource =
                        SimpleRelayBundleSource {
                            validBundle().replace(
                                "\"type\": \"hysteria2\"",
                                "\"type\": \"trojan\"",
                            )
                        },
                    relayProfileStore = seededProfileStore(),
                    relayCredentialStore = seededCredentialStore(),
                    serviceStateStore = DefaultServiceStateStore(),
                    failoverCoordinator = failoverBridge,
                    egressHealthCache = healthCache,
                )
            assertNull(policy.plan(RealityProfileId, RelayKindVlessReality, "network-a"))
        }

    @Test
    fun `successful selection notifies failover coordinator before running`() =
        runTest {
            val plan = policy.plan(RealityProfileId, RelayKindVlessReality, "network-a")!!

            policy.onSelected(InitialRelayRaceResult(plan.candidates[1], false, 50L))

            assertEquals(HysteriaProfileId, failoverBridge.profileId)
            assertEquals(RelayKindHysteria2, failoverBridge.relayKind)
            assertFalse(failoverBridge.skip)
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

    private fun udpEnabledRealityPolicy(): SimpleInitialRelayRacePolicy =
        SimpleInitialRelayRacePolicy(
            bundleSource = SimpleRelayBundleSource { validBundle() },
            relayProfileStore = seededProfileStore(realityUdpEnabled = true),
            relayCredentialStore = seededCredentialStore(),
            serviceStateStore = DefaultServiceStateStore(),
            failoverCoordinator = failoverBridge,
            egressHealthCache = healthCache,
        )

    private fun seededCredentialStore(): RelayCredentialStore =
        InMemoryRelayCredentialStore(
            RelayCredentialRecord(
                profileId = HysteriaProfileId,
                hysteriaPassword = "fixture-value",
                hysteriaSalamanderKey = "fixture-obfs-value",
            ),
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

    private fun validBundleWithoutObfs(): String =
        validBundle().replace(
            ", \"obfs\": { \"type\": \"salamander\", \"password\": \"fixture-obfs-value\" }",
            "",
        )

    private fun multiRealityBundle(): String =
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
              "type": "vless", "tag": "reality-fallback", "server": "192.0.2.10", "server_port": 2053,
              "uuid": "00000000-0000-0000-0000-000000000001", "flow": "xtls-rprx-vision",
              "tls": { "enabled": true, "server_name": "fallback.example.test",
                "reality": { "enabled": true,
                  "public_key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "short_id": "deadbeef" } }
            },
            {
              "type": "hysteria2", "tag": "hysteria", "server": "192.0.2.20", "server_port": 8443,
              "password": "fixture-value", "obfs": { "type": "salamander", "password": "fixture-obfs-value" }
            },
            { "type": "selector", "tag": "select",
              "outbounds": ["reality", "reality-fallback", "hysteria", "auto"] },
            { "type": "urltest", "tag": "auto", "outbounds": ["reality", "reality-fallback", "hysteria"],
              "url": "https://probe.example/generate_204" }
          ]
        }
        """.trimIndent()

    private companion object {
        const val RealityProfileId = "${SEED_RELAY_PROFILE_ID_PREFIX}VlessReality"
        const val FallbackRealityProfileId = "${SEED_RELAY_PROFILE_ID_PREFIX}VlessReality-2"
        const val XhttpProfileId = "${SEED_RELAY_PROFILE_ID_PREFIX}Vless"
        const val HysteriaProfileId = "${SEED_RELAY_PROFILE_ID_PREFIX}Hysteria2"
        const val HourMillis = 60L * 60L * 1_000L
    }
}

private class MutableFailoverClock(
    var now: Long,
) : FailoverClock {
    override fun nowMillis(): Long = now
}

private class RecordingInitialRaceFailoverCoordinator : InitialRaceFailoverCoordinator {
    var skip = false
    var profileId: String? = null
    var relayKind: String? = null

    override fun shouldSkipInitialRelayRace(): Boolean = skip

    override fun recordInitialRelaySelection(
        profileId: String,
        relayKind: String,
    ) {
        this.profileId = profileId
        this.relayKind = relayKind
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

private class InMemoryRelayCredentialStore(
    vararg credentials: RelayCredentialRecord,
) : RelayCredentialStore {
    private val values = credentials.associateByTo(linkedMapOf(), RelayCredentialRecord::profileId)

    override suspend fun load(profileId: String): RelayCredentialRecord? = values[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        values[credentials.profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }
}
