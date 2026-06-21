package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayTrustDomain
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.detectRelayChainTrustWarning
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChainRelayTrustDomainResolverTest {
    @Test
    fun `resolve chain relay family resolves referenced hop profiles`() =
        runTest {
            val resolver =
                resolver(
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "chain",
                                    kind = RelayKindChainRelay,
                                    chainEntryProfileId = "entry-hop",
                                    chainExitProfileId = "exit-hop",
                                ),
                            )
                            save(
                                RelayProfileRecord(
                                    id = "entry-hop",
                                    kind = RelayKindVlessReality,
                                    server = "entry.example",
                                    serverPort = 443,
                                    serverName = "entry-sni.example",
                                    realityPublicKey = TestVlessRealityPublicKey,
                                    realityShortId = TestVlessRealityEntryShortId,
                                ),
                            )
                            save(
                                RelayProfileRecord(
                                    id = "exit-hop",
                                    kind = RelayKindVlessReality,
                                    server = "exit.example",
                                    serverPort = 8443,
                                    serverName = "exit-sni.example",
                                    realityPublicKey = TestVlessRealityPublicKey,
                                    realityShortId = TestVlessRealityExitShortId,
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "entry-hop",
                                    vlessUuid = "11111111-1111-1111-1111-111111111111",
                                ),
                            )
                            save(
                                RelayCredentialRecord(
                                    profileId = "exit-hop",
                                    vlessUuid = "22222222-2222-2222-2222-222222222222",
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindChainRelay,
                            profileId = "chain",
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals("entry-hop", resolved.chainEntryProfileId)
            assertEquals("entry.example", resolved.chainEntryServer)
            assertEquals("11111111-1111-1111-1111-111111111111", resolved.chainEntryUuid)
            assertEquals("exit-hop", resolved.chainExitProfileId)
            assertEquals("exit.example", resolved.chainExitServer)
            assertEquals("22222222-2222-2222-2222-222222222222", resolved.chainExitUuid)
        }

    @Test
    fun `resolve three-hop chain carries every ordered hop over the wire as chainHops`() =
        runTest {
            val resolved = resolveThreeHopChain()
            assertThreeHopChainHops(resolved)
        }

    @Test
    fun `resolve chain relay family preserves heterogeneous referenced hop configs`() =
        runTest {
            val resolver =
                resolver(
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "chain",
                                    kind = RelayKindChainRelay,
                                    chainEntryProfileId = "entry-hop",
                                    chainExitProfileId = "masque-exit",
                                ),
                            )
                            save(
                                RelayProfileRecord(
                                    id = "entry-hop",
                                    kind = RelayKindVlessReality,
                                    server = "entry.example",
                                    serverPort = 443,
                                    serverName = "entry-sni.example",
                                    realityPublicKey = TestVlessRealityPublicKey,
                                    realityShortId = TestVlessRealityEntryShortId,
                                ),
                            )
                            save(
                                RelayProfileRecord(
                                    id = "masque-exit",
                                    kind = RelayKindMasque,
                                    masqueUrl = "https://masque.example/.well-known/masque/tcp/",
                                    masqueUseHttp2Fallback = true,
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "entry-hop",
                                    vlessUuid = "11111111-1111-1111-1111-111111111111",
                                ),
                            )
                            save(
                                RelayCredentialRecord(
                                    profileId = "masque-exit",
                                    masqueAuthToken = credentialFixture("masque-exit"),
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindChainRelay,
                            profileId = "chain",
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertNotNull(resolved.chainEntry)
            assertNotNull(resolved.chainExit)
            assertEquals(RelayKindVlessReality, resolved.chainEntry?.kind)
            assertEquals("entry-hop", resolved.chainEntry?.profileId)
            assertEquals("11111111-1111-1111-1111-111111111111", resolved.chainEntry?.vlessUuid)
            assertEquals(RelayKindMasque, resolved.chainExit?.kind)
            assertEquals("masque-exit", resolved.chainExit?.profileId)
            assertEquals("https://masque.example/.well-known/masque/tcp/", resolved.chainExit?.masqueUrl)
            assertEquals(credentialFixture("masque-exit"), resolved.chainExit?.masqueAuthToken)
        }

    @Test
    fun `resolve chain relay config carries hop trust domains and warns on shared jurisdiction`() =
        runTest {
            val resolved =
                resolveChainRelayConfigSupport(
                    chainProfileId = "chain",
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindChainRelay,
                            profileId = "chain",
                            chainEntryProfileId = "entry-hop",
                            chainExitProfileId = "exit-hop",
                        ),
                    credentials = null,
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "entry-hop",
                                    kind = RelayKindVlessReality,
                                    jurisdiction = "RU",
                                    operatorName = "Entry Transit",
                                    server = "entry.example",
                                    serverName = "entry.example",
                                    realityPublicKey = TestVlessRealityPublicKey,
                                    realityShortId = TestVlessRealityEntryShortId,
                                ),
                            )
                            save(
                                RelayProfileRecord(
                                    id = "exit-hop",
                                    kind = RelayKindMasque,
                                    jurisdiction = "ru",
                                    operatorName = "Exit Transit",
                                    masqueUrl = "https://masque.example/.well-known/masque/tcp/",
                                ),
                            )
                        },
                    relayCredentialStore = entryCredentialStore(),
                )

            assertEquals("RU", resolved.entry.trustDomain.jurisdiction)
            assertEquals("Entry Transit", resolved.entry.trustDomain.operatorName)
            assertEquals("ru", resolved.exit.trustDomain.jurisdiction)
            assertEquals("Exit Transit", resolved.exit.trustDomain.operatorName)
            assertEquals("RU", resolved.trustWarning?.sharedJurisdiction)
            assertEquals(null, resolved.trustWarning?.sharedOperatorName)
        }

    @Test
    fun `resolve chain relay config warns on shared operator across different jurisdictions`() =
        runTest {
            val resolved =
                resolveChainRelayConfigSupport(
                    chainProfileId = "chain",
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindChainRelay,
                            profileId = "chain",
                            chainEntryProfileId = "entry-hop",
                            chainExitProfileId = "exit-hop",
                        ),
                    credentials = null,
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "entry-hop",
                                    kind = RelayKindVlessReality,
                                    jurisdiction = "US",
                                    operatorName = "Acme Relay",
                                    server = "entry.example",
                                    serverName = "entry.example",
                                    realityPublicKey = TestVlessRealityPublicKey,
                                    realityShortId = TestVlessRealityEntryShortId,
                                ),
                            )
                            save(
                                RelayProfileRecord(
                                    id = "exit-hop",
                                    kind = RelayKindMasque,
                                    jurisdiction = "NL",
                                    operatorName = " acme relay ",
                                    masqueUrl = "https://masque.example/.well-known/masque/tcp/",
                                ),
                            )
                        },
                    relayCredentialStore = entryCredentialStore(),
                )

            assertEquals(null, resolved.trustWarning?.sharedJurisdiction)
            assertEquals("Acme Relay", resolved.trustWarning?.sharedOperatorName)
        }

    @Test
    fun `resolve chain relay config rejects quic exit profile before native launch`() =
        runTest {
            try {
                resolveChainRelayConfigSupport(
                    chainProfileId = "chain",
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindChainRelay,
                            profileId = "chain",
                            chainEntryProfileId = "entry-hop",
                            chainExitProfileId = "quic-exit",
                        ),
                    credentials = null,
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "entry-hop",
                                    kind = RelayKindVlessReality,
                                    server = "entry.example",
                                    serverName = "entry.example",
                                    realityPublicKey = TestVlessRealityPublicKey,
                                    realityShortId = TestVlessRealityEntryShortId,
                                ),
                            )
                            save(
                                RelayProfileRecord(
                                    id = "quic-exit",
                                    kind = RelayKindHysteria2,
                                    server = "exit.example",
                                    serverName = "exit.example",
                                ),
                            )
                        },
                    relayCredentialStore = entryCredentialStore(),
                )
                fail("Expected QUIC exit profile to be rejected")
            } catch (error: ServiceStartupRejectedException) {
                val reason = error.reason as? FailureReason.RelayConfigRejected
                assertTrue(reason != null)
                assertTrue(
                    reason?.message?.contains("chain relay exit profile kind hysteria2 is not supported") == true,
                )
            }
        }

    @Test
    fun `detect chain trust warning scans every hop pair across an N-hop chain`() {
        val warning =
            detectRelayChainTrustWarning(
                listOf(
                    RelayTrustDomain(jurisdiction = "US", operatorName = "Acme Entry"),
                    RelayTrustDomain(jurisdiction = "NL", operatorName = "Transit Co"),
                    RelayTrustDomain(jurisdiction = "us", operatorName = "Final Exit"),
                ),
            )

        // hops[0] and hops[2] share the US jurisdiction even though they are not
        // adjacent, so the per-pair scan must surface it.
        assertNotNull(warning)
        assertEquals("US", warning?.sharedJurisdiction)
        assertEquals(null, warning?.sharedOperatorName)
        // Cumulative latency caveat is the hop count: three sequential hops.
        assertEquals(3, warning?.cumulativeLatencyHops)
    }

    @Test
    fun `detect chain trust warning is silent for a clean four-hop chain`() {
        val warning =
            detectRelayChainTrustWarning(
                listOf(
                    RelayTrustDomain(jurisdiction = "US", operatorName = "Op A"),
                    RelayTrustDomain(jurisdiction = "NL", operatorName = "Op B"),
                    RelayTrustDomain(jurisdiction = "DE", operatorName = "Op C"),
                    RelayTrustDomain(jurisdiction = "JP", operatorName = "Op D"),
                ),
            )

        assertEquals(null, warning)
    }

    @Test
    fun `detect chain trust warning flags a missing middle hop trust domain and carries hop count`() {
        val warning =
            detectRelayChainTrustWarning(
                listOf(
                    RelayTrustDomain(jurisdiction = "US", operatorName = "Op A"),
                    RelayTrustDomain(jurisdiction = "", operatorName = ""),
                    RelayTrustDomain(jurisdiction = "JP", operatorName = "Op C"),
                ),
            )

        assertNotNull(warning)
        // Entry (first) and exit (last) carry complete trust domains; only the
        // middle hop is incomplete, which still raises a warning with the caveat.
        assertEquals(false, warning?.missingEntryTrustDomain)
        assertEquals(false, warning?.missingExitTrustDomain)
        assertEquals(3, warning?.cumulativeLatencyHops)
    }

    private suspend fun entryCredentialStore(): TestRelayCredentialStore =
        TestRelayCredentialStore().apply {
            save(
                RelayCredentialRecord(
                    profileId = "entry-hop",
                    vlessUuid = "11111111-1111-1111-1111-111111111111",
                ),
            )
        }

    private fun credentialFixture(label: String): String = "relay-test-credential-$label"

    private suspend fun resolveThreeHopChain(): ResolvedRipDpiRelayConfig =
        threeHopChainResolver().resolve(
            config = chainRelayConfig(),
            quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
        )

    private suspend fun threeHopChainResolver(): DefaultUpstreamRelayRuntimeConfigResolver =
        resolver(
            relayProfileStore = threeHopChainProfileStore(),
            relayCredentialStore = threeHopChainCredentialStore(),
        )

    private suspend fun threeHopChainProfileStore(): TestRelayProfileStore =
        TestRelayProfileStore().apply {
            save(threeHopChainRecord())
            save(vlessRealityHopRecord(id = "entry-hop", server = "entry.example", port = 443, sni = "entry-sni"))
            save(vlessRealityHopRecord(id = "middle-hop", server = "middle.example", port = 8443, sni = "middle-sni"))
            save(vlessRealityHopRecord(id = "exit-hop", server = "exit.example", port = 9443, sni = "exit-sni"))
        }

    private fun threeHopChainRecord(): RelayProfileRecord =
        RelayProfileRecord(
            id = "chain",
            kind = RelayKindChainRelay,
            chainEntryProfileId = "entry-hop",
            chainMiddleProfileIds = listOf("middle-hop"),
            chainExitProfileId = "exit-hop",
        )

    private fun vlessRealityHopRecord(
        id: String,
        server: String,
        port: Int,
        sni: String,
    ): RelayProfileRecord =
        RelayProfileRecord(
            id = id,
            kind = RelayKindVlessReality,
            server = server,
            serverPort = port,
            serverName = "$sni.example",
            realityPublicKey = TestVlessRealityPublicKey,
            realityShortId = TestVlessRealityShortId,
        )

    private suspend fun threeHopChainCredentialStore(): TestRelayCredentialStore =
        TestRelayCredentialStore().apply {
            save(vlessCredential(profileId = "entry-hop", uuid = "11111111-1111-1111-1111-111111111111"))
            save(vlessCredential(profileId = "middle-hop", uuid = "44444444-4444-4444-4444-444444444444"))
            save(vlessCredential(profileId = "exit-hop", uuid = "22222222-2222-2222-2222-222222222222"))
        }

    private fun vlessCredential(
        profileId: String,
        uuid: String,
    ): RelayCredentialRecord =
        RelayCredentialRecord(
            profileId = profileId,
            vlessUuid = uuid,
        )

    private fun chainRelayConfig(): RipDpiRelayConfig =
        RipDpiRelayConfig(
            enabled = true,
            kind = RelayKindChainRelay,
            profileId = "chain",
        )

    private fun assertThreeHopChainHops(resolved: ResolvedRipDpiRelayConfig) {
        assertEquals(3, resolved.chainHops.size)
        assertEquals("entry-hop", resolved.chainHops[0].profileId)
        assertEquals("middle-hop", resolved.chainHops[1].profileId)
        assertEquals("middle.example", resolved.chainHops[1].server)
        assertEquals("44444444-4444-4444-4444-444444444444", resolved.chainHops[1].vlessUuid)
        assertEquals("exit-hop", resolved.chainHops[2].profileId)
        assertEquals("entry-hop", resolved.chainEntryProfileId)
        assertEquals("entry.example", resolved.chainEntryServer)
        assertEquals("exit-hop", resolved.chainExitProfileId)
        assertEquals("exit.example", resolved.chainExitServer)
    }

    private fun resolver(
        relayProfileStore: TestRelayProfileStore,
        relayCredentialStore: TestRelayCredentialStore,
    ): DefaultUpstreamRelayRuntimeConfigResolver =
        DefaultUpstreamRelayRuntimeConfigResolver(
            relayProfileStore = relayProfileStore,
            relayCredentialStore = relayCredentialStore,
            relayKindResolverRegistry =
                createDefaultRelayKindResolverRegistry(
                    relayProfileStore = relayProfileStore,
                    relayCredentialStore = relayCredentialStore,
                    cloudflareMasqueGeohashResolver =
                        object : CloudflareMasqueGeohashResolver {
                            override suspend fun resolveHeaderValue(): String? = null
                        },
                    masquePrivacyPassProvider = StaticMasquePrivacyPassProvider(),
                ),
            tlsFingerprintProfileProvider =
                object : OwnedTlsFingerprintProfileProvider {
                    override fun currentProfile(): String = TlsFingerprintProfileChromeStable
                },
            runtimeExperimentSelectionProvider =
                object : RuntimeExperimentSelectionProvider {
                    override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                },
            torRuntimePathProvider = StaticTorRuntimePathProvider(),
            torPluggableTransportProvider = StaticTorPluggableTransportProvider(),
        )
}
