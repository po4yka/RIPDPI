package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.services.CandidateRelayProbeEnvironment
import com.poyka.ripdpi.services.ProfileUtilityProbeFixtures
import com.poyka.ripdpi.services.fieldreport.FieldReportLedger
import com.poyka.ripdpi.services.fieldreport.FieldReportNetwork
import com.poyka.ripdpi.services.reachableCandidateHttpProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RelayPayloadMemberLatencyProbeTest {
    @Test
    fun `REALITY TCP without Vision measures its native relay route`() =
        runTest {
            val url = "https://probe.example/payload"
            val probes =
                ProfileUtilityProbeFixtures(
                    reachableCandidateHttpProbe(url),
                    { CandidateRelayProbeEnvironment(false, "chrome_stable", emptyMap(), false, false) },
                    { false },
                    {},
                )
            val member =
                ProxyProfile.VlessReality(
                    "node",
                    "Node",
                    "group",
                    "edge.example",
                    443,
                    "user",
                    "A".repeat(43),
                    "",
                    "edge.example",
                    flow = "",
                )

            val result =
                withContext(Dispatchers.IO) {
                    RelayPayloadMemberLatencyProbe(probes.relay, noConsentFieldReports())
                        .measure(member, MemberProbeTarget.Reachability(url))
                }

            assertEquals(MemberProbeResult(0L, reason = ProfileQualityReason.Verified), result)

            // A failing field-report count leaves the probe result intact.
            var networkRead = false
            val failingCount =
                FieldReportLedger(
                    File.createTempFile("field-report", ".json").apply { delete() },
                    { true },
                    { 0L },
                    {
                        networkRead = true
                        error("network context unavailable")
                    },
                )
            val counted =
                withContext(Dispatchers.IO) {
                    RelayPayloadMemberLatencyProbe(probes.relay, failingCount)
                        .measure(member, MemberProbeTarget.Reachability(url))
                }
            assertEquals(MemberProbeResult(0L, reason = ProfileQualityReason.Verified), counted)
            assertFalse(networkRead)

            val participating =
                withContext(Dispatchers.IO) {
                    RelayPayloadMemberLatencyProbe(probes.relay, failingCount)
                        .measure(member, MemberProbeTarget.Reachability(url, member.groupId))
                }
            assertEquals(MemberProbeResult(0L, reason = ProfileQualityReason.Verified), participating)
            assertTrue(networkRead)
        }
}

private fun noConsentFieldReports(): FieldReportLedger =
    FieldReportLedger(
        File.createTempFile("field-report", ".json").apply { delete() },
        { false },
        { 0L },
        { FieldReportNetwork("none", "other") },
    )
