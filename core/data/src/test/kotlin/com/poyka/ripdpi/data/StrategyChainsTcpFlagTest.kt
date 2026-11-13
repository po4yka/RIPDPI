package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyChainsTcpFlagTest {
    @Test
    fun `tcp flag masks parse numeric input and format canonically`() {
        val dsl =
            """
            [tcp]
            fake host+1 tcp_flags=18 tcp_flags_unset=0x40 tcp_flags_orig=urg|psh tcp_flags_orig_unset=0x100
            """.trimIndent()

        val parsed = parseStrategyChainDsl(dsl).getOrThrow()

        assertEquals(
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.Fake,
                    marker = "host+1",
                    tcpFlagsSet = "syn|ack",
                    tcpFlagsUnset = "ece",
                    tcpFlagsOrigSet = "psh|urg",
                    tcpFlagsOrigUnset = "ae",
                ),
            ),
            parsed.tcpSteps,
        )
        assertEquals(
            """
            [tcp]
            fake host+1 tcp_flags=syn|ack tcp_flags_unset=ece tcp_flags_orig=psh|urg tcp_flags_orig_unset=ae
            """.trimIndent(),
            formatStrategyChainDsl(parsed.tcpSteps, parsed.udpSteps),
        )
        assertEquals(
            "tcp: fake(host+1 fake+=syn|ack fake-=ece orig+=psh|urg orig-=ae)",
            formatChainSummary(parsed.tcpSteps, parsed.udpSteps),
        )
    }

    @Test
    fun `tcp flag validation rejects unsupported placement and overlapping masks`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppSettings.newBuilder().setStrategyChains(
                tcpSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.Oob,
                            marker = "host+1",
                            tcpFlagsSet = "syn",
                        ),
                    ),
                udpSteps = emptyList(),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            AppSettings.newBuilder().setStrategyChains(
                tcpSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.Fake,
                            marker = "host+1",
                            tcpFlagsSet = "syn|ack",
                            tcpFlagsUnset = "ack",
                        ),
                    ),
                udpSteps = emptyList(),
            )
        }
    }

    @Test
    fun `fakerst rejects only an unset mask that clears rst`() {
        fun chainWith(
            kind: TcpChainStepKind,
            unset: String,
        ) = runCatching {
            AppSettings.newBuilder().setStrategyChains(
                tcpSteps = listOf(TcpChainStepModel(kind = kind, marker = "host+2", tcpFlagsUnset = unset)),
                udpSteps = emptyList(),
            )
        }

        assertTrue(chainWith(TcpChainStepKind.FakeRst, "rst").isFailure)
        assertTrue(chainWith(TcpChainStepKind.FakeRst, "psh|rst").isFailure)
        assertTrue(chainWith(TcpChainStepKind.FakeRst, "psh").isSuccess)
        assertTrue(chainWith(TcpChainStepKind.Fake, "rst").isSuccess)
    }
}
