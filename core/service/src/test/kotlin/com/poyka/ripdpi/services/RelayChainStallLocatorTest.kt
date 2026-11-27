package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadProbeControl
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.fakes.FakeRelayCredentialStore
import com.poyka.ripdpi.data.fakes.FakeRelayProfileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayChainStallLocatorTest {
    private val profiles = FakeRelayProfileStore()
    private val credentials = FakeRelayCredentialStore()
    private val control = PayloadProbeControl("https://control.example/payload.bin", ControlBytes, "a".repeat(64))
    private val measured = mutableListOf<RelayProfileRecord>()
    private val proof = CandidateConfigurationProofs.relay(sampleResolvedRelayConfig())

    private suspend fun fourHopChain() {
        listOf("h1" to RelayKindVlessReality, "h2" to RelayKindTrojan, "h3" to RelayKindTrojan, "h4" to RelayKindTrojan)
            .forEach { (id, kind) -> profiles.save(RelayProfileRecord(id = id, kind = kind)) }
        profiles.save(
            RelayProfileRecord(
                id = "chain",
                kind = RelayKindChainRelay,
                chainEntryProfileId = "h1",
                chainMiddleProfileIds = listOf("h2", "h3"),
                chainExitProfileId = "h4",
            ),
        )
    }

    private fun locator(stopAtExit: String?) =
        RelayChainStallLocator(profiles, credentials) { profile, _, used, timeout ->
            assertEquals(control, used)
            assertEquals(TimeoutMillis, timeout)
            measured += profile
            val exit = if (profile.kind == RelayKindChainRelay) profile.chainExitProfileId else profile.id
            if (exit == stopAtExit) {
                RelayVolumeMeasurement(
                    CandidateRelayMeasurement.TimedOut(CandidateMeasurementStage.Http),
                    StalledBytes,
                )
            } else {
                RelayVolumeMeasurement(
                    CandidateRelayMeasurement.Succeeded(1L, proof),
                    ControlBytes.toLong(),
                )
            }
        }

    @Test
    fun `the first hop prefix that stops the transfer is reported with its byte count`() =
        runTest {
            fourHopChain()

            val stop = locator(stopAtExit = "h3").locate("chain", control, TimeoutMillis)

            assertEquals(RelayChainVolumeStop.Stopped(hop = 3, hopCount = 4, receivedBytes = StalledBytes), stop)
            assertEquals(listOf("h1", "chain", "chain"), measured.map { it.id })
            assertEquals(RelayKindVlessReality, measured[0].kind)
            assertEquals(listOf<String>() to "h2", measured[1].chainMiddleProfileIds to measured[1].chainExitProfileId)
            assertEquals(listOf("h2") to "h3", measured[2].chainMiddleProfileIds to measured[2].chainExitProfileId)
        }

    @Test
    fun `an entry hop that stops the transfer is located without probing later hops`() =
        runTest {
            fourHopChain()
            credentials.save(RelayCredentialRecord("h1", vlessUuid = "entry-uuid"))
            val seenCredentials = mutableListOf<RelayCredentialRecord>()
            val locator =
                RelayChainStallLocator(profiles, credentials) { profile, record, _, _ ->
                    seenCredentials += record
                    measured += profile
                    RelayVolumeMeasurement(CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Ready), null)
                }

            val stop = locator.locate("chain", control, TimeoutMillis)

            assertEquals(RelayChainVolumeStop.Stopped(hop = 1, hopCount = 4, receivedBytes = 0L), stop)
            assertEquals(listOf("h1"), measured.map { it.id })
            assertEquals("entry-uuid", seenCredentials.single().vlessUuid)
        }

    @Test
    fun `a chain that moves the payload through every prefix is delivered`() =
        runTest {
            fourHopChain()

            val stop = locator(stopAtExit = null).locate("chain", control, TimeoutMillis)

            assertEquals(RelayChainVolumeStop.Delivered, stop)
            assertEquals(4, measured.size)
            val last = measured[3]
            assertEquals(listOf("h2", "h3") to "h4", last.chainMiddleProfileIds to last.chainExitProfileId)
        }

    @Test
    fun `a single endpoint that cascades behind the server is not split into hops`() =
        runTest {
            profiles.save(RelayProfileRecord(id = "cascade", kind = RelayKindTrojan))

            val stop = locator(stopAtExit = "cascade").locate("cascade", control, TimeoutMillis)

            assertEquals(RelayChainVolumeStop.SingleEndpoint, stop)
            assertEquals(emptyList<RelayProfileRecord>(), measured)
        }

    @Test
    fun `a chain with an inline hop or a rejected prefix configuration is not measured per hop`() =
        runTest {
            profiles.save(
                RelayProfileRecord(
                    id = "inline",
                    kind = RelayKindChainRelay,
                    chainEntryServer = "entry.example",
                    chainExitProfileId = "h4",
                ),
            )
            assertEquals(
                RelayChainVolumeStop.Unmeasured,
                locator(stopAtExit = null).locate("inline", control, TimeoutMillis),
            )
            assertEquals(emptyList<RelayProfileRecord>(), measured)

            fourHopChain()
            val rejecting =
                RelayChainStallLocator(profiles, credentials) { _, _, _, _ ->
                    RelayVolumeMeasurement(
                        CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Configuration),
                        null,
                    )
                }
            assertEquals(RelayChainVolumeStop.Unmeasured, rejecting.locate("chain", control, TimeoutMillis))
        }

    @Test
    fun `a mid-chain hop that never comes up stops the volume with no bytes`() =
        runTest {
            fourHopChain()
            val locator =
                RelayChainStallLocator(profiles, credentials) { profile, _, _, _ ->
                    val outcome =
                        if (profile.kind == RelayKindChainRelay) {
                            CandidateRelayMeasurement.TimedOut(CandidateMeasurementStage.Ready)
                        } else {
                            CandidateRelayMeasurement.Succeeded(1L, proof)
                        }
                    RelayVolumeMeasurement(outcome, null)
                }

            val stop = locator.locate("chain", control, TimeoutMillis)

            assertEquals(RelayChainVolumeStop.Stopped(hop = 2, hopCount = 4, receivedBytes = 0L), stop)
        }

    @Test
    fun `a full body that fails verification or an unknown byte count blames no hop`() =
        runTest {
            fourHopChain()
            val rejected =
                RelayChainStallLocator(profiles, credentials) { _, _, _, _ ->
                    RelayVolumeMeasurement(
                        CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Http),
                        ControlBytes.toLong(),
                    )
                }
            val unknown =
                RelayChainStallLocator(profiles, credentials) { _, _, _, _ ->
                    RelayVolumeMeasurement(CandidateRelayMeasurement.TimedOut(CandidateMeasurementStage.Http), null)
                }

            assertEquals(RelayChainVolumeStop.Unmeasured, rejected.locate("chain", control, TimeoutMillis))
            assertEquals(RelayChainVolumeStop.Unmeasured, unknown.locate("chain", control, TimeoutMillis))
        }

    private companion object {
        const val ControlBytes = 65_536
        const val StalledBytes = 16_384L
        const val TimeoutMillis = 10_000L
    }
}
