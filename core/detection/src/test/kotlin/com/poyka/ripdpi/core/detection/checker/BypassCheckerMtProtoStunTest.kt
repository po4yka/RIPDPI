package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.ProxyType
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassCheckerMtProtoStunTest {
    private val dispatcher = StandardTestDispatcher()
    private val dispatchers =
        AppCoroutineDispatchers(
            io = dispatcher,
            default = dispatcher,
            main = dispatcher,
        )

    @Test
    fun mtprotoSuccessViaProxyRecordedAsObservation() =
        runTest(dispatcher) {
            val result =
                BypassChecker.check(
                    dispatchers = dispatchers,
                    proxyEndpointProvider = { socks5Proxy },
                    xrayApiScanProvider = { null },
                    directIpProvider = { "1.1.1.1" },
                    proxyIpProvider = { "2.2.2.2" },
                    mtProtoProber = MtProtoProber { true },
                    stunClient = Socks5StunClient { null },
                )

            assertTrue(result.mtProtoReachable)
            assertTrue(
                result.findings.any { finding ->
                    finding.description.contains("MTProto") && !finding.detected
                },
            )
            assertFalse(result.evidence.any { it.source == EvidenceSource.SPLIT_TUNNEL_BYPASS })
        }

    @Test
    fun stunReflexiveAddressCapturedViaSocks5UdpAssociate() =
        runTest(dispatcher) {
            val result =
                BypassChecker.check(
                    dispatchers = dispatchers,
                    proxyEndpointProvider = { socks5Proxy },
                    xrayApiScanProvider = { null },
                    directIpProvider = { "1.1.1.1" },
                    proxyIpProvider = { "2.2.2.2" },
                    mtProtoProber = MtProtoProber { false },
                    stunClient = Socks5StunClient { "5.6.7.8" },
                )

            assertEquals(listOf("5.6.7.8"), result.stunReflexiveAddresses)
            assertTrue(result.findings.any { it.description == "STUN reflexive address via SOCKS5: 5.6.7.8" })
        }

    @Test
    fun mtprotoFailureDoesNotAbortScan() =
        runTest(dispatcher) {
            val result =
                BypassChecker.check(
                    dispatchers = dispatchers,
                    proxyEndpointProvider = { socks5Proxy },
                    xrayApiScanProvider = { null },
                    directIpProvider = { "1.1.1.1" },
                    proxyIpProvider = { "2.2.2.2" },
                    mtProtoProber = MtProtoProber { error("boom") },
                    stunClient = Socks5StunClient { null },
                )

            assertFalse(result.mtProtoReachable)
            assertFalse(result.detected)
            assertTrue(result.needsReview)
            assertFalse(result.evidence.any { it.source == EvidenceSource.SPLIT_TUNNEL_BYPASS })
            assertTrue(result.findings.any { it.description == "Direct and proxy IPs differ" })
            val empty = CategoryResult(name = "empty", detected = false, findings = emptyList())
            assertEquals(
                Verdict.NEEDS_REVIEW,
                VerdictEngine.evaluate(empty, empty, empty, empty, result),
            )
        }

    private companion object {
        val socks5Proxy = ProxyEndpoint("127.0.0.1", 1080, ProxyType.SOCKS5)
    }
}
