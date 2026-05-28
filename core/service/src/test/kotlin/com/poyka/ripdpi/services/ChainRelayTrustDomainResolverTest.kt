package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
                                    realityPublicKey = "entry-public",
                                    realityShortId = "entry-short",
                                ),
                            )
                            save(
                                RelayProfileRecord(
                                    id = "exit-hop",
                                    kind = RelayKindVlessReality,
                                    server = "exit.example",
                                    serverPort = 8443,
                                    serverName = "exit-sni.example",
                                    realityPublicKey = "exit-public",
                                    realityShortId = "exit-short",
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
                                    realityPublicKey = "entry-public",
                                    realityShortId = "entry-short",
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
        )
}
