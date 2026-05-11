package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluggableTransportReachabilityProbeTest {
    @Test
    fun allThreePtsReachableReturnsAllOk() =
        runTest {
            val result = probe().run()

            assertTrue(result.obfs4 is PtVerdict.PtOk)
            assertTrue(result.snowflake is PtVerdict.PtOk)
            assertTrue(result.meek is PtVerdict.PtOk)
            assertEquals(4, result.traces.size)
        }

    @Test
    fun obfs4BridgeBlockedIsolated() =
        runTest {
            val result =
                probe(
                    obfs4 = { PtProbeTrace("obfs4", "bridge", false, 10, "rst") },
                ).run()

            assertTrue(result.obfs4 is PtVerdict.PtBridgeBlocked)
            assertTrue(result.snowflake is PtVerdict.PtOk)
            assertTrue(result.meek is PtVerdict.PtOk)
        }

    @Test
    fun snowflakeBrokerUnreachableReturnsBrokerBlocked() =
        runTest {
            val result =
                probe(
                    snowflakeBroker = { PtProbeTrace("snowflake_broker", "broker", false, 10, "503") },
                ).run()

            assertTrue(result.snowflake is PtVerdict.PtBrokerBlocked)
        }

    @Test
    fun snowflakeBrokerOkButStunBlockedReturnsStunBlocked() =
        runTest {
            val result =
                probe(
                    snowflakeStun = { PtProbeTrace("snowflake_stun", "stun", false, 10, "timeout") },
                ).run()

            assertTrue(result.snowflake is PtVerdict.PtStunBlocked)
        }

    @Test
    fun meekAllFrontsBlocked() =
        runTest {
            val result =
                probe(
                    meek = { PtProbeTrace("meek", "front", false, 10, "timeout") },
                ).run()

            assertTrue(result.meek is PtVerdict.PtFrontBlocked)
        }

    @Test
    fun onePtFailureDoesNotCancelOthers() =
        runTest {
            val result =
                probe(
                    obfs4 = { throw IllegalStateException("boom") },
                ).run()

            assertTrue(result.obfs4 is PtVerdict.PtError)
            assertTrue(result.snowflake is PtVerdict.PtOk)
            assertTrue(result.meek is PtVerdict.PtOk)
        }

    @Test
    fun ptSubprobesRunInParallel() =
        runTest {
            val result =
                probe(
                    obfs4 = {
                        delay(50)
                        PtProbeTrace("obfs4", "bridge", true, 50)
                    },
                    snowflakeBroker = {
                        delay(50)
                        PtProbeTrace("snowflake_broker", "broker", true, 50)
                    },
                    meek = {
                        delay(50)
                        PtProbeTrace("meek", "front", true, 50)
                    },
                ).run(timeoutMs = 1_000)

            assertTrue(result.obfs4 is PtVerdict.PtOk)
            assertTrue(result.snowflake is PtVerdict.PtOk)
            assertTrue(result.meek is PtVerdict.PtOk)
        }

    private fun probe(
        obfs4: suspend () -> PtProbeTrace = { PtProbeTrace("obfs4", "bridge", true, 10) },
        snowflakeBroker: suspend () -> PtProbeTrace = { PtProbeTrace("snowflake_broker", "broker", true, 10) },
        snowflakeStun: suspend () -> PtProbeTrace = { PtProbeTrace("snowflake_stun", "stun", true, 10) },
        meek: suspend () -> PtProbeTrace = { PtProbeTrace("meek", "front", true, 10) },
    ): PluggableTransportReachabilityProbe =
        PluggableTransportReachabilityProbe(
            obfs4Probe = FakePtSubprobe(obfs4),
            snowflakeBrokerProbe = FakePtSubprobe(snowflakeBroker),
            snowflakeStunProbe = FakePtSubprobe(snowflakeStun),
            meekProbe = FakePtSubprobe(meek),
        )

    private class FakePtSubprobe(
        private val result: suspend () -> PtProbeTrace,
    ) : PtSubprobe {
        override suspend fun run(timeoutMs: Long): PtProbeTrace = result()
    }
}
