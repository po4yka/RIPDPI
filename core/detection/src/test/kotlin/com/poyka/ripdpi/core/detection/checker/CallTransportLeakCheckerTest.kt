package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CallTransportPath
import com.poyka.ripdpi.core.detection.CallTransportStunRequest
import com.poyka.ripdpi.core.detection.CallTransportStunServer
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.consensus.IpConsensusBuilder
import com.poyka.ripdpi.core.detection.consensus.IpConsensusChannel
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.ProxyType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTransportLeakCheckerTest {
    @Test
    fun noCallsWhenDisabled() =
        runTest {
            val stunClient = RecordingCallTransportStunClient()
            val mtProtoProber = RecordingCallTransportMtProtoProber()

            val result =
                CallTransportLeakChecker.check(
                    enabled = false,
                    stunClient = stunClient,
                    mtProtoProber = mtProtoProber,
                    proxyEndpoint = socks5Proxy,
                    socks5StunClient = Socks5StunClient { error("SOCKS5 STUN should not run") },
                    socks5MtProtoProber = MtProtoProber { error("SOCKS5 MTProto should not run") },
                )

            assertFalse(result.category.detected)
            assertEquals(emptyList<CallTransportStunRequest>(), stunClient.requests)
            assertEquals(emptyList<CallTransportPath>(), mtProtoProber.paths)
        }

    @Test
    fun reflexiveAddressMismatchBetweenVpnAndUnderlyingPathIsHighConfidence() =
        runTest {
            val result =
                CallTransportLeakChecker.check(
                    enabled = true,
                    stunClient =
                        RecordingCallTransportStunClient(
                            mapOf(
                                CallTransportPath.VPN to "1.2.3.4",
                                CallTransportPath.UNDERLYING to "5.6.7.8",
                            ),
                        ),
                    mtProtoProber = RecordingCallTransportMtProtoProber(),
                )

            assertTrue(result.category.detected)
            assertTrue(
                result.category.findings.any { finding ->
                    finding.detected &&
                        finding.confidence == EvidenceConfidence.HIGH &&
                        finding.source == EvidenceSource.CALL_TRANSPORT
                },
            )
        }

    @Test
    fun noLeakFindingWhenBothPathsReturnSameReflexiveAddress() =
        runTest {
            val result =
                CallTransportLeakChecker.check(
                    enabled = true,
                    stunClient =
                        RecordingCallTransportStunClient(
                            mapOf(
                                CallTransportPath.VPN to "1.2.3.4",
                                CallTransportPath.UNDERLYING to "1.2.3.4",
                            ),
                        ),
                    mtProtoProber = RecordingCallTransportMtProtoProber(),
                )

            assertFalse(result.category.detected)
            assertFalse(result.category.findings.any { it.detected })
        }

    @Test
    fun mtprotoReachabilityRecordedAsMediumConfidence() =
        runTest {
            val result =
                CallTransportLeakChecker.check(
                    enabled = true,
                    stunClient = RecordingCallTransportStunClient(),
                    mtProtoProber = RecordingCallTransportMtProtoProber(reachable = true),
                )

            assertTrue(result.mtProtoReachable)
            assertTrue(
                result.category.findings.any { finding ->
                    finding.description.contains("MTProto") &&
                        finding.confidence == EvidenceConfidence.MEDIUM &&
                        finding.source == EvidenceSource.CALL_TRANSPORT
                },
            )
        }

    @Test
    fun socks5UdpAssociateUsedWhenLocalProxyDetected() =
        runTest {
            val calls = mutableListOf<ProxyEndpoint>()
            val result =
                CallTransportLeakChecker.check(
                    enabled = true,
                    stunClient = RecordingCallTransportStunClient(),
                    mtProtoProber = RecordingCallTransportMtProtoProber(),
                    proxyEndpoint = socks5Proxy,
                    socks5StunClient =
                        Socks5StunClient { endpoint ->
                            calls += endpoint
                            "9.9.9.9"
                        },
                    socks5MtProtoProber = MtProtoProber { false },
                )

            assertEquals(listOf(socks5Proxy), calls)
            assertEquals(listOf("9.9.9.9"), result.proxyStunReflexiveAddresses)
        }

    @Test
    fun stunReflexiveAddressAddedToIpConsensusChannel() =
        runTest {
            val callTransport =
                CallTransportLeakChecker.check(
                    enabled = true,
                    stunClient =
                        RecordingCallTransportStunClient(
                            mapOf(CallTransportPath.VPN to "1.2.3.4"),
                        ),
                    mtProtoProber = RecordingCallTransportMtProtoProber(),
                )

            val result =
                IpConsensusBuilder.build(
                    observations = IpConsensusBuilder.observationsFrom(callTransport),
                )

            assertEquals(
                listOf("1.2.3.4"),
                result.observedIps[IpConsensusChannel.CALL_TRANSPORT],
            )
        }

    private class RecordingCallTransportStunClient(
        private val addresses: Map<CallTransportPath, String?> = emptyMap(),
    ) : CallTransportStunClient {
        val requests = mutableListOf<CallTransportStunRequest>()

        override suspend fun reflexiveAddress(
            path: CallTransportPath,
            server: CallTransportStunServer,
        ): String? {
            requests += CallTransportStunRequest(path, server)
            return addresses[path]
        }
    }

    private class RecordingCallTransportMtProtoProber(
        private val reachable: Boolean = false,
    ) : CallTransportMtProtoProber {
        val paths = mutableListOf<CallTransportPath>()

        override suspend fun canReach(path: CallTransportPath): Boolean {
            paths += path
            return reachable
        }
    }

    private companion object {
        val socks5Proxy = ProxyEndpoint("127.0.0.1", 1080, ProxyType.SOCKS5)
    }
}
