package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ChainRelaySelectionValidationTest : ChainRelayTrustDomainResolverTestSupport() {
    @Test
    fun `chain resolution rejects empty middle selections instead of shortening the route`() =
        runTest {
            for (middle in listOf("", " \t")) {
                val failure =
                    runCatching {
                        resolveChainRelayConfigSupport(
                            chainProfileId = "chain",
                            config =
                                RipDpiRelayConfig(
                                    enabled = true,
                                    kind = RelayKindChainRelay,
                                    chainEntryProfileId = "entry-hop",
                                    chainMiddleProfileIds = listOf(middle),
                                    chainExitProfileId = "exit-hop",
                                ),
                            credentials = null,
                            relayProfileStore = threeHopChainProfileStore(),
                            relayCredentialStore = threeHopChainCredentialStore(),
                            fallbackTlsFingerprintProfile = "chrome_stable",
                        )
                    }.exceptionOrNull()

                assertTrue(failure is ServiceStartupRejectedException)
                val reason = (failure as? ServiceStartupRejectedException)?.reason as? FailureReason.RelayConfigRejected
                assertTrue(reason?.message.orEmpty().contains("intermediate"))
            }
        }
}
