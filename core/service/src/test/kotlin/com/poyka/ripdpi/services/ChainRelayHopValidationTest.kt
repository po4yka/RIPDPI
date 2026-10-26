package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.StrategyFeatureMasqueCloudflareDirect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A chain hop gets the same per-kind checks and feature gates as a standalone profile. */
internal class ChainRelayHopValidationTest : ChainRelayTrustDomainResolverTestSupport() {
    @Test
    fun `cloudflare mtls masque entry hop is feature gated like a standalone profile`() =
        runTest {
            val entry = masqueEntry()
            val credentials = masqueMtlsCredentials()

            val gated = resolveChainError(entry, credentials, featureFlags = emptyMap())
            assertTrue(gated.reasonText(), gated.reasonText().contains("feature-gated"))

            val resolved =
                resolveChain(
                    entry,
                    credentials,
                    featureFlags = mapOf(StrategyFeatureMasqueCloudflareDirect to true),
                )
            assertEquals("entry-hop", resolved.chainEntryProfileId)
        }

    @Test
    fun `privacy pass masque hop is rejected because hops carry no provider`() =
        runTest {
            val error =
                resolveChainError(
                    masqueEntry(),
                    RelayCredentialRecord(profileId = "entry-hop", masqueAuthMode = RelayMasqueAuthModePrivacyPass),
                )
            assertTrue(error.reasonText(), error.reasonText().contains("privacy_pass"))
        }

    @Test
    fun `masque hop needs an https url`() =
        runTest {
            val error =
                resolveChainError(
                    masqueEntry().copy(masqueUrl = "http://masque.example/"),
                    RelayCredentialRecord(profileId = "entry-hop", masqueAuthToken = credentialFixture("bearer")),
                )
            assertTrue(error.reasonText(), error.reasonText().contains("HTTPS"))
        }

    @Test
    fun `trojan hop needs a tls server name`() =
        runTest {
            val error =
                resolveChainError(
                    RelayProfileRecord(
                        id = "entry-hop",
                        kind = RelayKindTrojan,
                        server = "trojan.example",
                        serverPort = 443,
                        serverName = "",
                    ),
                    RelayCredentialRecord(profileId = "entry-hop", trojanPassword = credentialFixture("trojan")),
                )
            assertTrue(error.reasonText(), error.reasonText().contains("TLS server name"))
        }

    @Test
    fun `shadowsocks hop needs a cipher method`() =
        runTest {
            val error =
                resolveChainError(
                    RelayProfileRecord(
                        id = "entry-hop",
                        kind = RelayKindShadowsocks,
                        server = "ss.example",
                        serverPort = 8388,
                    ),
                    RelayCredentialRecord(profileId = "entry-hop", shadowsocksPassword = credentialFixture("ss")),
                )
            assertNotNull(error)
            assertTrue(error.reasonText(), error.reasonText().contains("credentials missing"))
        }

    @Test
    fun `hop finalmask is rejected before the native chain builder sees it`() =
        runTest {
            val error =
                resolveChainError(
                    RelayProfileRecord(
                        id = "entry-hop",
                        kind = RelayKindTrojan,
                        server = "trojan.example",
                        serverPort = 443,
                        serverName = "trojan.example",
                        finalmaskType = "noise",
                    ),
                    RelayCredentialRecord(profileId = "entry-hop", trojanPassword = credentialFixture("trojan")),
                )
            assertTrue(error.reasonText(), error.reasonText().contains("finalmask"))
        }

    private fun masqueEntry(): RelayProfileRecord =
        RelayProfileRecord(
            id = "entry-hop",
            kind = RelayKindMasque,
            masqueUrl = "https://masque.example/",
            masqueTcpProtocol = "http2",
        )

    private fun masqueMtlsCredentials(): RelayCredentialRecord =
        RelayCredentialRecord(
            profileId = "entry-hop",
            masqueAuthMode = RelayMasqueAuthModeCloudflareMtls,
            masqueClientCertificateChainPem = credentialFixture("cert"),
            masqueClientPrivateKeyPem = credentialFixture("key"),
        )

    private suspend fun resolveChain(
        entry: RelayProfileRecord,
        entryCredentials: RelayCredentialRecord,
        featureFlags: Map<String, Boolean> = emptyMap(),
    ) = resolver(
        relayProfileStore =
            TestRelayProfileStore().apply {
                save(
                    RelayProfileRecord(
                        id = "chain",
                        kind = RelayKindChainRelay,
                        chainEntryProfileId = entry.id,
                        chainExitProfileId = "exit-hop",
                    ),
                )
                save(entry)
                save(vlessRealityHopRecord("exit-hop", "exit.example", 443, "exit"))
            },
        relayCredentialStore =
            TestRelayCredentialStore().apply {
                save(entryCredentials)
                save(vlessCredential("exit-hop", "22222222-2222-2222-2222-222222222222"))
            },
    ).resolve(
        config = chainRelayConfig(),
        inputs = testRelayResolutionInputs(quic = OwnedRelayQuicMigrationConfig(), featureFlags = featureFlags),
        expectedSelection =
            com.poyka.ripdpi.data
                .RuntimeConfigurationSelection("native"),
    )

    private suspend fun resolveChainError(
        entry: RelayProfileRecord,
        entryCredentials: RelayCredentialRecord,
        featureFlags: Map<String, Boolean> = emptyMap(),
    ): Throwable? = runCatching { resolveChain(entry, entryCredentials, featureFlags) }.exceptionOrNull()

    private fun Throwable?.reasonText(): String =
        ((this as? ServiceStartupRejectedException)?.reason as? FailureReason.RelayConfigRejected)?.message
            ?: this?.message.orEmpty()
}
