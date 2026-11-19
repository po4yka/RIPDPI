package com.poyka.ripdpi.services.fieldreport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.diagnostics.FieldFailureStage
import com.poyka.ripdpi.services.CandidateConfigurationProofs
import com.poyka.ripdpi.services.CandidateMeasurementStage
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import com.poyka.ripdpi.services.sampleResolvedRelayConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class FieldReportTest {
    private val schema = ClientFieldReportSchema()
    private val network = FieldReportNetwork(asn = 64500, country = "ZZ", networkType = "cellular")
    private val reality = "p0-reality-upcloud-prod"
    private val hysteria = "p2-hysteria2-upcloud-prod"

    @Test
    fun `validator accepts the vendored golden and rejects every vendored negative report`() {
        assertEquals(emptyList<String>(), schema.errors(contractJson("client-field-report.golden.json")))
        val negatives = contractFile("negative").listFiles { file -> file.name.startsWith("report-") }.orEmpty()
        assertTrue(negatives.size >= 19)
        negatives.forEach { file ->
            assertTrue(file.name, schema.errors(contractJson("negative/${file.name}")).isNotEmpty())
        }
    }

    @Test
    fun `an hour of attempts builds the vendored golden buckets and validates`() {
        // Each latency is an inclusive slot bound, so the slot counts below are the golden histograms.
        val slotLatencies = longArrayOf(50, 100, 250, 500, 1_000, 2_500, 5_000, 5_001)
        var state = FieldReportState()
        val lastMilli = epoch("2026-11-19T10:59:59.999Z")

        fun add(
            serverRef: String,
            protocol: String,
            stage: FieldFailureStage?,
            slots: List<Int>,
        ) = slots.forEachIndexed { slot, count ->
            repeat(count) {
                state = state.record(FieldReportAttempt(serverRef, protocol, stage, slotLatencies[slot]), lastMilli)
            }
        }
        add(reality, "vless-reality", null, listOf(0, 3, 18, 12, 6, 2, 1, 0))
        add(reality, "vless-reality", FieldFailureStage.TlsHandshake, listOf(0, 0, 0, 0, 1, 2, 1, 3))
        add(hysteria, "hysteria2", FieldFailureStage.QuicHandshake, listOf(0, 0, 0, 0, 0, 0, 0, 5))
        // Suppressed: four attempts. Filtered: an endpoint the target group does not own.
        add(hysteria, "hysteria2", FieldFailureStage.TcpReset, listOf(4, 0, 0, 0, 0, 0, 0, 0))
        add("other-subscription-node", "hysteria2", null, listOf(9, 0, 0, 0, 0, 0, 0, 0))

        val hour = state.hours.single()
        assertEquals(epoch("2026-11-19T10:00:00Z"), hour.startEpochMillis)
        val report = requireNotNull(buildFieldReport(hour, "1.4", network, setOf(reality, hysteria)))

        assertEquals(emptyList<String>(), schema.errors(report))
        // The vendored golden covers a six-hour window; a client report always covers one hour.
        val golden = contractJson("client-field-report.golden.json")
        val expected =
            JsonObject(
                golden +
                    (
                        "window" to
                            JsonObject(golden.getValue("window").jsonObject + ("duration_hours" to JsonPrimitive(1)))
                    ),
            )
        assertEquals(expected, report)
    }

    @Test
    fun `report holds only allow-listed values of the probe input`() {
        val profile =
            ProxyProfile.VlessReality(
                id = "member-id-7f3a",
                displayName = reality,
                groupId = "group-id-91c2",
                server = "198.51.100.7",
                serverPort = 443,
                uuid = "4b1c9e2a-0000-4000-8000-1234567890ab",
                realityPublicKey = "A".repeat(43),
                realityShortId = "abcd",
                serverName = "cdn.example.test",
            )
        val success =
            CandidateRelayMeasurement.Succeeded(
                120,
                CandidateConfigurationProofs.relay(sampleResolvedRelayConfig()),
            )
        var state = FieldReportState()
        repeat(5) {
            val attempt = requireNotNull(fieldReportAttempt(profile, success, elapsedMillis = 9_000))
            state = state.record(attempt, epoch("2026-11-19T10:30:00Z"))
        }

        val report = requireNotNull(buildFieldReport(state.hours.single(), "1.4", network, setOf(reality))).toString()

        assertEquals(emptyList<String>(), schema.errors(contractJsonOf(report)))
        listOf("198.51.100.7", "4b1c9e2a", "cdn.example.test", "member-id-7f3a", "group-id-91c2", "abcd").forEach {
            assertFalse(it, report.contains(it))
        }
        assertTrue(report.contains("\"latency_ms_histogram\":[0,0,5,0,0,0,0,0]"))
        // A tag that is a hostname is never a server_ref, and a local failure before the relay is not an attempt.
        assertNull(fieldReportAttempt(profile.copy(displayName = "edge.example.test"), success, 10))
        assertNull(fieldReportAttempt(profile, CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Ready), 10))
        assertEquals(
            FieldFailureStage.Unknown,
            fieldReportAttempt(
                profile,
                CandidateRelayMeasurement.TimedOut(CandidateMeasurementStage.Http),
                10,
            )?.failureStage,
        )
    }

    @Test
    fun `an hour whose buckets all hold fewer than five attempts builds no report`() {
        var state = FieldReportState()
        repeat(
            4,
        ) { state = state.record(FieldReportAttempt(reality, "xhttp", null, 10), epoch("2026-11-19T10:00:00Z")) }

        assertNull(buildFieldReport(state.hours.single(), "1.4", network, setOf(reality)))
    }

    @Test
    fun `app version keeps major and minor only`() {
        assertEquals("1.4", fieldReportAppVersion("1.4.2-github"))
        assertEquals("12.0", fieldReportAppVersion("12.0"))
        assertNull(fieldReportAppVersion("1"))
        assertNull(fieldReportAppVersion("1.40000"))
        assertNull(fieldReportAppVersion(null))
    }

    @Test
    fun `revoked consent makes the next write delete the store`() =
        kotlinx.coroutines.test.runTest {
            var consent = true
            val file = File.createTempFile("field-report", ".json")
            val ledger = FieldReportLedger(file, { consent }, { epoch("2026-11-19T10:00:00Z") })
            ledger.update { it.record(FieldReportAttempt(reality, "xhttp", null, 10), epoch("2026-11-19T10:00:00Z")) }
            assertTrue(file.exists())
            assertNotNull(ledger.snapshot().hours.singleOrNull())

            consent = false
            ledger.update { it.record(FieldReportAttempt(reality, "xhttp", null, 10), epoch("2026-11-19T10:00:00Z")) }

            assertFalse(file.exists())
            assertEquals(FieldReportState(), ledger.snapshot())
        }
}

internal fun epoch(instant: String): Long = Instant.parse(instant).toEpochMilli()

internal fun contractJsonOf(text: String): JsonObject =
    kotlinx.serialization.json.Json
        .parseToJsonElement(text)
        .jsonObject
