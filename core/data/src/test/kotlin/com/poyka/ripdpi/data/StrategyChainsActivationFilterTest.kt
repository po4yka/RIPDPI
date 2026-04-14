package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyChainsActivationFilterTest {
    @Test
    fun `dsl round trip preserves activation filters on tcp and udp steps`() {
        val dsl =
            """
            [tcp]
            tlsrandrec sniext+4 count=5 min=24 max=48 when_stream=0-1199
            fake host when_round=1-2 when_size=64-512

            [udp]
            fake_burst 3 when_round=1-3 when_stream=0-1199
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertEquals(
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.TlsRandRec,
                    marker = "sniext+4",
                    fragmentCount = 5,
                    minFragmentSize = 24,
                    maxFragmentSize = 48,
                    activationFilter =
                        ActivationFilterModel(
                            streamBytes = NumericRangeModel(start = 0, end = 1199),
                        ),
                ),
                TcpChainStepModel(
                    kind = TcpChainStepKind.Fake,
                    marker = "host",
                    activationFilter =
                        ActivationFilterModel(
                            round = NumericRangeModel(start = 1, end = 2),
                            payloadSize = NumericRangeModel(start = 64, end = 512),
                        ),
                ),
            ),
            parsed.tcpSteps,
        )
        assertEquals(
            listOf(
                UdpChainStepModel(
                    count = 3,
                    activationFilter =
                        ActivationFilterModel(
                            round = NumericRangeModel(start = 1, end = 3),
                            streamBytes = NumericRangeModel(start = 0, end = 1199),
                        ),
                ),
            ),
            parsed.udpSteps,
        )
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertEquals(
            "tcp: tlsrandrec(sniext+4 count=5 min=24 max=48 stream=0-1199) -> fake(host round=1-2 size=64-512) | " +
                "udp: fake_burst(3 round=1-3 stream=0-1199)",
            formatChainSummary(parsed.tcpSteps, parsed.udpSteps),
        )
    }

    @Test
    fun `dsl round trip preserves tcp state activation filters on tcp steps`() {
        val dsl =
            """
            [tcp]
            fake host tcp_has_ts=true tcp_has_ech=false tcp_window_lt=4096 tcp_mss_lt=1400
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertEquals(
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.Fake,
                    marker = "host",
                    activationFilter =
                        ActivationFilterModel(
                            tcpHasTimestamp = true,
                            tcpHasEch = false,
                            tcpWindowBelow = 4096,
                            tcpMssBelow = 1400,
                        ),
                ),
            ),
            parsed.tcpSteps,
        )
        assertEquals(dsl, formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps))
        assertEquals(
            "tcp: fake(host ts=yes ech=no win<4096 mss<1400)",
            formatChainSummary(parsed.tcpSteps, parsed.udpSteps),
        )
    }

    @Test
    fun `dsl rejects tcp state activation filters on udp steps`() {
        val parsed = parseStrategyChainDsl("[udp]\nfake_burst 3 tcp_has_ts=true")

        assertTrue(parsed.isFailure)
        assertTrue(parsed.exceptionOrNull()?.message?.contains("tcp_has_ts is only supported for tcp steps") == true)
    }

    @Test
    fun `group activation filter rejects tcp state predicates`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppSettings.newBuilder().setGroupActivationFilterCompat(ActivationFilterModel(tcpHasTimestamp = true))
        }
    }
}
