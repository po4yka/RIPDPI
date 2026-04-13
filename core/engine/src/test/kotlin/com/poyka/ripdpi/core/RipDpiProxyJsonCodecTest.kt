package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RipDpiProxyJsonCodecTest {
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
}
