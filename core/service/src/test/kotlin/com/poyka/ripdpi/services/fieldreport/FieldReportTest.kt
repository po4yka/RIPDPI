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
    private val hintSchema = ClientFieldReportSchema(contractJson("client-network-hint.schema.json"))
    private val network = FieldReportNetwork("server_hint", "cellular", asn = 64500, country = "ZZ")
    private val simOperator = FieldReportNetwork("sim", "cellular", mcc = "001", mnc = "01")
    private val member = FieldReportMember("group", "member")
    private val reality = "p0-reality-upcloud-prod"
    private val hysteria = "p2-hysteria2-upcloud-prod"

    @Test
    fun `validator accepts the vendored goldens and rejects every vendored negative report and hint`() {
        assertEquals(emptyList<String>(), schema.errors(contractJson("client-field-report.golden.json")))
        assertEquals(emptyList<String>(), schema.errors(contractJson("client-field-report.golden-sim.json")))
        assertEquals(emptyList<String>(), hintSchema.errors(contractJson("client-network-hint.golden.json")))
        val reports = negatives("report-")
        assertTrue(reports.size >= 34)
        reports.forEach { name -> assertTrue(name, schema.errors(contractJson("negative/$name")).isNotEmpty()) }
        val hints = negatives("hint-")
        assertTrue(hints.size >= 6)
        hints.forEach { name -> assertTrue(name, hintSchema.errors(contractJson("negative/$name")).isNotEmpty()) }
    }

    @Test
    fun `the hint parser accepts the vendored golden and discards every vendored negative hint`() {
        assertEquals(
            NetworkHint(64500, "ZZ", 21_600, epoch("2026-11-19T16:00:00Z")),
            parseNetworkHint(contractFile("client-network-hint.golden.json").readText(), epoch("2026-11-19T10:30:00Z")),
        )
        negatives("hint-").forEach { name ->
            assertNull(name, parseNetworkHint(contractFile("negative/$name").readText(), epoch("2026-11-19T10:30:00Z")))
        }
        assertNull(parseNetworkHint("not json", 0))
    }

    @Test
    fun `a hint observed in the future expires one ttl after it was received`() {
        // The golden says 10:00; the device clock reads 06:00, so the server clock is ahead.
        val received = epoch("2026-11-19T06:00:00Z")

        assertEquals(
            epoch("2026-11-19T12:00:00Z"),
            parseNetworkHint(contractFile("client-network-hint.golden.json").readText(), received)?.expiresAtMillis,
        )
    }

    @Test
    fun `a sim operator hour builds the vendored sim golden and validates`() {
        val report = requireNotNull(buildFieldReport(goldenHour(simOperator), "1.4", setOf(reality, hysteria)))

        assertEquals(emptyList<String>(), schema.errors(report))
        assertEquals(oneHourWindow(contractJson("client-field-report.golden-sim.json")), report)
    }

    @Test
    fun `an operator code splits into mcc and mnc with leading zeros kept`() {
        assertEquals(FieldReportNetwork("sim", "cellular", mcc = "250", mnc = "01"), simNetwork("25001"))
        assertEquals(FieldReportNetwork("sim", "cellular", mcc = "310", mnc = "260"), simNetwork("310260"))
        listOf(null, "", "2500", "2500123", "25a01").forEach { assertNull(it, simNetwork(it)) }
    }

    @Test
    fun `a network that breaks the source rules builds no report`() {
        listOf(
            simOperator.copy(networkType = "wifi"),
            simOperator.copy(asn = 64500),
            simOperator.copy(mnc = "0001"),
            network.copy(asn = null),
            network.copy(mcc = "001", mnc = "01"),
            FieldReportNetwork("none", "wifi", asn = 64500),
            FieldReportNetwork("none", "wifi", mcc = "001", mnc = "01"),
            FieldReportNetwork("lookup", "wifi"),
        ).forEach { invalid ->
            assertNull(invalid.toString(), buildFieldReport(goldenHour(invalid), "1.4", setOf(reality, hysteria)))
        }
        // An hour stored before the network was recorded is never sent.
        assertNull(buildFieldReport(goldenHour(network).copy(network = null), "1.4", setOf(reality, hysteria)))
        // No identity is still a report.
        val none =
            requireNotNull(buildFieldReport(goldenHour(FieldReportNetwork("none", "wifi")), "1.4", setOf(reality)))
        assertEquals(emptyList<String>(), schema.errors(none))
    }

    @Test
    fun `an hour of attempts builds the vendored golden buckets and validates`() {
        val hour = goldenHour(network)
        assertEquals(epoch("2026-11-19T10:00:00Z"), hour.startEpochMillis)
        val report = requireNotNull(buildFieldReport(hour, "1.4", setOf(reality, hysteria)))

        assertEquals(emptyList<String>(), schema.errors(report))
        assertEquals(oneHourWindow(contractJson("client-field-report.golden.json")), report)
    }

    @Test
    fun `attempts on another network go to a separate hour`() {
        val at = epoch("2026-11-19T10:15:00Z")
        val state =
            FieldReportState()
                .record(FieldReportAttempt(reality, "xhttp", null, 10, member), network, at)
                .record(FieldReportAttempt(reality, "xhttp", null, 10, member), simOperator, at)
                .record(FieldReportAttempt(reality, "xhttp", null, 10, member), network, at)

        assertEquals(listOf(network, simOperator), state.hours.map { it.network })
        assertEquals(listOf(2L, 1L), state.hours.map { it.buckets.single().attempts })
    }

    /** The vendored golden buckets, recorded on [measuredOn], plus a suppressed and a foreign bucket. */
    private fun goldenHour(measuredOn: FieldReportNetwork): FieldReportHour {
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
                state =
                    state.record(
                        FieldReportAttempt(serverRef, protocol, stage, slotLatencies[slot], member),
                        measuredOn,
                        lastMilli,
                    )
            }
        }
        add(reality, "vless-reality", null, listOf(0, 3, 18, 12, 6, 2, 1, 0))
        add(reality, "vless-reality", FieldFailureStage.TlsHandshake, listOf(0, 0, 0, 0, 1, 2, 1, 3))
        add(hysteria, "hysteria2", FieldFailureStage.QuicHandshake, listOf(0, 0, 0, 0, 0, 0, 0, 5))
        // Suppressed: four attempts. Filtered: an endpoint the target group does not own.
        add(hysteria, "hysteria2", FieldFailureStage.TcpReset, listOf(4, 0, 0, 0, 0, 0, 0, 0))
        add("other-subscription-node", "hysteria2", null, listOf(9, 0, 0, 0, 0, 0, 0, 0))
        return state.hours.single()
    }

    /** The vendored goldens cover a six-hour window; a client report always covers one hour. */
    private fun oneHourWindow(golden: JsonObject): JsonObject =
        JsonObject(
            golden +
                ("window" to JsonObject(golden.getValue("window").jsonObject + ("duration_hours" to JsonPrimitive(1)))),
        )

    private fun negatives(prefix: String): List<String> =
        contractFile("negative")
            .listFiles { file -> file.name.startsWith(prefix) && file.name.endsWith(".json") }
            .orEmpty()
            .map { it.name }
            .sorted()

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
            state = state.record(attempt, network, epoch("2026-11-19T10:30:00Z"))
        }

        val report = requireNotNull(buildFieldReport(state.hours.single(), "1.4", setOf(reality))).toString()

        assertEquals(emptyList<String>(), schema.errors(contractJsonOf(report)))
        listOf("198.51.100.7", "4b1c9e2a", "cdn.example.test", "member-id-7f3a", "group-id-91c2", "abcd").forEach {
            assertFalse(it, report.contains(it))
        }
        assertTrue(report.contains("\"latency_ms_histogram\":[0,0,5,0,0,0,0,0]"))
        // A tag that is a hostname is never a server_ref, and a local configuration failure is not an attempt.
        assertNull(fieldReportAttempt(profile.copy(displayName = "edge.example.test"), success, 10))
        assertNull(
            fieldReportAttempt(profile, CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Configuration), 10),
        )
    }

    @Test
    fun `a remote failure reports its typed stage and a pre-HTTP failure is an attempt`() {
        val profile =
            ProxyProfile.Hysteria2(
                id = "member",
                displayName = reality,
                groupId = "group",
                server = "198.51.100.7",
                serverPort = 443,
                password = "fixture",
            )

        fun stageOf(measured: CandidateRelayMeasurement) = fieldReportAttempt(profile, measured, 10)?.failureStage

        assertEquals(
            FieldFailureStage.QuicHandshake,
            stageOf(
                CandidateRelayMeasurement.TimedOut(CandidateMeasurementStage.Ready, FieldFailureStage.QuicHandshake),
            ),
        )
        assertEquals(
            FieldFailureStage.Unknown,
            stageOf(CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Ready)),
        )
        assertEquals(
            FieldFailureStage.Dns,
            stageOf(CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Http, FieldFailureStage.Dns)),
        )
        assertEquals(
            FieldFailureStage.Unknown,
            stageOf(CandidateRelayMeasurement.TimedOut(CandidateMeasurementStage.Http)),
        )
    }

    @Test
    fun `an hour whose buckets all hold fewer than five attempts builds no report`() {
        var state = FieldReportState()
        repeat(
            4,
        ) {
            state =
                state.record(
                    FieldReportAttempt(reality, "xhttp", null, 10, member),
                    network,
                    epoch("2026-11-19T10:00:00Z"),
                )
        }

        assertNull(buildFieldReport(state.hours.single(), "1.4", setOf(reality)))
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
            val at = epoch("2026-11-19T10:00:00Z")
            val ledger = FieldReportLedger(file, { consent }, { at }, { network })
            ledger.update { it.record(FieldReportAttempt(reality, "xhttp", null, 10, member), network, at) }
            assertTrue(file.exists())
            assertNotNull(ledger.snapshot().hours.singleOrNull())

            consent = false
            ledger.update { it.record(FieldReportAttempt(reality, "xhttp", null, 10, member), network, at) }

            assertFalse(file.exists())
            assertEquals(FieldReportState(), ledger.snapshot())
        }
}

internal fun epoch(instant: String): Long = Instant.parse(instant).toEpochMilli()

internal fun contractJsonOf(text: String): JsonObject =
    kotlinx.serialization.json.Json
        .parseToJsonElement(text)
        .jsonObject
