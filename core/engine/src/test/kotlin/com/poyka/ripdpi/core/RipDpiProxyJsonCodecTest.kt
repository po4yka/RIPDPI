package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private const val TestLocalProxyAuth = "alpha-123"

class RipDpiProxyJsonCodecTest {
    @Test
    fun `ui preferences encode session local proxy overrides without mutating persisted listen config`() {
        val preferences =
            RipDpiProxyUIPreferences(
                listen = RipDpiListenConfig(port = 1080),
            )

        val payload =
            RipDpiProxyJsonCodec.encodeUiPreferences(
                preferences = preferences,
                localListenPortOverride = 0,
                localAuthToken = TestLocalProxyAuth,
            )

        val objectValue =
            kotlinx.serialization.json.Json
                .parseToJsonElement(payload)
                .jsonObject
        val sessionOverrides = objectValue["sessionOverrides"]?.jsonObject
        val listen = objectValue["listen"]?.jsonObject

        assertNotNull(sessionOverrides)
        assertEquals(
            0,
            sessionOverrides
                ?.get("listenPortOverride")
                ?.jsonPrimitive
                ?.content
                ?.toInt(),
        )
        assertEquals(TestLocalProxyAuth, sessionOverrides?.get("authToken")?.jsonPrimitive?.content)
        assertEquals(
            1080,
            listen
                ?.get("port")
                ?.jsonPrimitive
                ?.content
                ?.toInt(),
        )
        assertNull(listen?.get("authToken")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `command line preferences encode session local proxy overrides`() {
        val payload =
            RipDpiProxyJsonCodec.encodeCommandLinePreferences(
                args = listOf("ripdpi", "--port", "1081"),
                hostAutolearnStorePath = null,
                runtimeContext = null,
                logContext = null,
                localListenPortOverride = 0,
                localAuthToken = TestLocalProxyAuth,
            )

        val objectValue =
            kotlinx.serialization.json.Json
                .parseToJsonElement(payload)
                .jsonObject
        val sessionOverrides = objectValue["sessionOverrides"]?.jsonObject

        assertNotNull(sessionOverrides)
        assertEquals(
            0,
            sessionOverrides
                ?.get("listenPortOverride")
                ?.jsonPrimitive
                ?.content
                ?.toInt(),
        )
        assertEquals(TestLocalProxyAuth, sessionOverrides?.get("authToken")?.jsonPrimitive?.content)
    }

    @Test
    fun `ui preferences round trip tcp state activation filters`() {
        val preferences =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.Fake,
                                    marker = "host",
                                    activationFilter =
                                        ActivationFilterModel(
                                            round = NumericRangeModel(1, 2),
                                            tcpHasTimestamp = true,
                                            tcpHasEch = false,
                                            tcpWindowBelow = 4096,
                                            tcpMssBelow = 1400,
                                        ),
                                ),
                            ),
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())

        assertEquals(
            preferences.chains.tcpSteps
                .first()
                .activationFilter,
            decoded
                ?.chains
                ?.tcpSteps
                ?.firstOrNull()
                ?.activationFilter,
        )
    }

    @Test
    fun `ui preferences round trip tcp rotation config`() {
        val preferences =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(kind = TcpChainStepKind.TlsRec, marker = "extlen"),
                                TcpChainStepModel(kind = TcpChainStepKind.Split, marker = "host+2"),
                            ),
                        tcpRotation =
                            RipDpiTcpRotationConfig(
                                candidates =
                                    listOf(
                                        RipDpiTcpRotationCandidateConfig(
                                            tcpSteps =
                                                listOf(
                                                    TcpChainStepModel(
                                                        kind = TcpChainStepKind.TlsRec,
                                                        marker = "extlen",
                                                    ),
                                                    TcpChainStepModel(kind = TcpChainStepKind.Split, marker = "midsld"),
                                                ),
                                        ),
                                    ),
                            ),
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())
        val rotation = decoded?.chains?.tcpRotation

        assertNotNull(rotation)
        assertEquals(3, rotation?.fails)
        assertEquals(3, rotation?.retrans)
        assertEquals(65536, rotation?.seq)
        assertEquals(1, rotation?.rst)
        assertEquals(60L, rotation?.timeSecs)
        assertEquals(true, rotation?.cancelOnFailure)
        assertEquals(1, rotation?.candidates?.size)
        assertEquals(
            TcpChainStepKind.TlsRec,
            rotation
                ?.candidates
                ?.firstOrNull()
                ?.tcpSteps
                ?.get(0)
                ?.kind,
        )
        assertEquals(
            "midsld",
            rotation
                ?.candidates
                ?.firstOrNull()
                ?.tcpSteps
                ?.get(1)
                ?.marker,
        )
    }

    @Test
    fun `ui preferences round trip direct path capabilities through runtime context`() {
        val preferences =
            RipDpiProxyUIPreferences(
                runtimeContext =
                    RipDpiRuntimeContext(
                        directPathCapabilities =
                            listOf(
                                RipDpiDirectPathCapability(
                                    authority = "Example.org:443",
                                    quicUsable = false,
                                    udpUsable = true,
                                    fallbackRequired = true,
                                    repeatedHandshakeFailureClass = " tcp_reset ",
                                    updatedAt = 321L,
                                ),
                            ),
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())
        val capability = decoded?.runtimeContext?.directPathCapabilities?.singleOrNull()

        assertNotNull(capability)
        assertEquals("example.org:443", capability?.authority)
        assertEquals(false, capability?.quicUsable)
        assertEquals(true, capability?.udpUsable)
        assertEquals(true, capability?.fallbackRequired)
        assertEquals("tcp_reset", capability?.repeatedHandshakeFailureClass)
        assertEquals(321L, capability?.updatedAt)
    }

    @Test
    fun `ui preferences round trip tcp flag masks`() {
        val preferences =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.Fake,
                                    marker = "host+1",
                                    tcpFlagsSet = "syn|fin",
                                    tcpFlagsUnset = "ack",
                                    tcpFlagsOrigSet = "psh|urg",
                                    tcpFlagsOrigUnset = "ece",
                                ),
                            ),
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())
        val step = decoded?.chains?.tcpSteps?.singleOrNull()

        assertNotNull(step)
        assertEquals("fin|syn", step?.tcpFlagsSet)
        assertEquals("ack", step?.tcpFlagsUnset)
        assertEquals("psh|urg", step?.tcpFlagsOrigSet)
        assertEquals("ece", step?.tcpFlagsOrigUnset)
    }

    @Test
    fun `ui preferences round trip fake ordering fields`() {
        val preferences =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(kind = TcpChainStepKind.TlsRec, marker = "extlen"),
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.FakeSplit,
                                    marker = "host+1",
                                    fakeOrder = "2",
                                    fakeSeqMode = "sequential",
                                ),
                            ),
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())
        val step = decoded?.chains?.tcpSteps?.lastOrNull()

        assertNotNull(step)
        assertEquals("2", step?.fakeOrder)
        assertEquals("sequential", step?.fakeSeqMode)
    }

    @Test
    fun `ui preferences round trip fake packet ip id mode`() {
        val preferences =
            RipDpiProxyUIPreferences(
                fakePackets =
                    RipDpiFakePacketConfig(
                        ipIdMode = "SeqGroup",
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())

        assertEquals("seqgroup", decoded?.fakePackets?.ipIdMode)
    }

    @Test
    fun `ui preferences round trip morph policy through runtime context`() {
        val preferences =
            RipDpiProxyUIPreferences(
                runtimeContext =
                    RipDpiRuntimeContext(
                        morphPolicy =
                            RipDpiMorphPolicy(
                                id = " balanced ",
                                firstFlightSizeMin = 320,
                                firstFlightSizeMax = 768,
                                paddingEnvelopeMin = 16,
                                paddingEnvelopeMax = 96,
                                entropyTargetPermil = 3400,
                                tcpBurstCadenceMs = listOf(0, 12, 24),
                                tlsBurstCadenceMs = listOf(0, 8),
                                quicBurstProfile = " Compat_Burst ",
                                fakePacketShapeProfile = " Compat_Default ",
                            ),
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())
        val morphPolicy = decoded?.runtimeContext?.morphPolicy

        assertNotNull(morphPolicy)
        assertEquals("balanced", morphPolicy?.id)
        assertEquals(320, morphPolicy?.firstFlightSizeMin)
        assertEquals(768, morphPolicy?.firstFlightSizeMax)
        assertEquals(16, morphPolicy?.paddingEnvelopeMin)
        assertEquals(96, morphPolicy?.paddingEnvelopeMax)
        assertEquals(3400, morphPolicy?.entropyTargetPermil)
        assertEquals(listOf(0, 12, 24), morphPolicy?.tcpBurstCadenceMs)
        assertEquals(listOf(0, 8), morphPolicy?.tlsBurstCadenceMs)
        assertEquals("compat_burst", morphPolicy?.quicBurstProfile)
        assertEquals("compat_default", morphPolicy?.fakePacketShapeProfile)
    }

    @Test
    fun `ui preferences round trip warp ws tunnel and log context sections`() {
        val preferences = warpWsTunnelAndLogContextPreferences()
        val decoded = decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())
        assertWarpWsTunnelAndLogContextRoundTrip(decoded)
    }

    private fun warpWsTunnelAndLogContextPreferences() =
        RipDpiProxyUIPreferences(
            warp =
                RipDpiWarpConfig(
                    enabled = true,
                    routeMode = "rules",
                    routeHosts = "example.org\nexample.net",
                    builtInRulesEnabled = false,
                    endpointSelectionMode = "manual",
                    manualEndpoint =
                        RipDpiWarpManualEndpointConfig(
                            host = "engage.cloudflareclient.com",
                            ipv4 = "162.159.193.10",
                            ipv6 = "2606:4700:d0::a29f:c10a",
                            port = 2409,
                        ),
                    scannerEnabled = false,
                    scannerParallelism = 4,
                    scannerMaxRttMs = 900,
                    amneziaPreset = "custom",
                    amnezia =
                        RipDpiWarpAmneziaConfig(
                            enabled = true,
                            jc = 5,
                            jmin = 2,
                            jmax = 7,
                            h1 = 11L,
                            h2 = 12L,
                            h3 = 13L,
                            h4 = 14L,
                            s1 = 21,
                            s2 = 22,
                            s3 = 23,
                            s4 = 24,
                        ),
                    localSocksHost = "127.0.0.9",
                    localSocksPort = 12090,
                ),
            wsTunnel = RipDpiWsTunnelConfig(enabled = true, mode = "telegram"),
            logContext =
                RipDpiLogContext(
                    runtimeId = " runtime-1 ",
                    mode = " Audit ",
                    policySignature = " policy-v1 ",
                    fingerprintHash = " fp-123 ",
                    diagnosticsSessionId = " diag-321 ",
                ),
        )

    private fun assertWarpWsTunnelAndLogContextRoundTrip(decoded: RipDpiProxyUIPreferences?) {
        val warp = decoded?.warp
        val wsTunnel = decoded?.wsTunnel
        val logContext = decoded?.logContext

        assertNotNull(warp)
        assertEquals(true, warp?.enabled)
        assertEquals("rules", warp?.routeMode)
        assertEquals("example.org\nexample.net", warp?.routeHosts)
        assertEquals(false, warp?.builtInRulesEnabled)
        assertEquals("manual", warp?.endpointSelectionMode)
        assertEquals("engage.cloudflareclient.com", warp?.manualEndpoint?.host)
        assertEquals("162.159.193.10", warp?.manualEndpoint?.ipv4)
        assertEquals("2606:4700:d0::a29f:c10a", warp?.manualEndpoint?.ipv6)
        assertEquals(2409, warp?.manualEndpoint?.port)
        assertEquals(false, warp?.scannerEnabled)
        assertEquals(4, warp?.scannerParallelism)
        assertEquals(900, warp?.scannerMaxRttMs)
        assertEquals("custom", warp?.amneziaPreset)
        assertEquals(true, warp?.amnezia?.enabled)
        assertEquals(5, warp?.amnezia?.jc)
        assertEquals(24, warp?.amnezia?.s4)
        assertEquals("127.0.0.9", warp?.localSocksHost)
        assertEquals(12090, warp?.localSocksPort)
        assertEquals(true, wsTunnel?.enabled)
        assertEquals("telegram", wsTunnel?.mode)
        assertEquals("runtime-1", logContext?.runtimeId)
        assertEquals("audit", logContext?.mode)
        assertEquals("policy-v1", logContext?.policySignature)
        assertEquals("fp-123", logContext?.fingerprintHash)
        assertEquals("diag-321", logContext?.diagnosticsSessionId)
    }
}
