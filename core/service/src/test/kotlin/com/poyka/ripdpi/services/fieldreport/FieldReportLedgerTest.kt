package com.poyka.ripdpi.services.fieldreport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.diagnostics.FieldFailureStage
import com.poyka.ripdpi.services.CandidateMeasurementStage
import com.poyka.ripdpi.services.CandidatePhysicalNetworkObserver
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class FieldReportLedgerTest {
    private val member =
        ProxyProfile.Hysteria2(
            id = "member",
            displayName = "p2-hysteria2-upcloud-prod",
            groupId = "group",
            server = "198.51.100.7",
            serverPort = 443,
            password = "fixture",
        )
    private val failed = CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Http, FieldFailureStage.Dns)
    private val network = FieldReportNetwork("none", "wifi")
    private val physical = CandidatePhysicalNetworkObserver<String>(requireBlockedObservation = false)
    private val registration = physical.beginRegistration()

    init {
        selectNetwork("wifi")
    }

    private fun selectNetwork(name: String) {
        physical.available(registration, name, preferred = true)
        physical.capabilities(registration, name, "internet", usable = true)
        physical.links(registration, name, "route", usable = true)
    }

    private fun tempFile() = File.createTempFile("field-report", ".json").apply { delete() }

    private suspend fun FieldReportLedger.attempts() =
        snapshot().hours.sumOf { hour -> hour.buckets.sumOf { it.attempts } }

    private suspend fun FieldReportLedger.measureAndCount() {
        captureProbe()?.let { recordProbe(it, member, failed, 10) }
    }

    @Test
    fun `without consent a probe reads no network and writes nothing`() =
        runTest {
            val file = tempFile()
            var lookups = 0
            val ledger =
                FieldReportLedger(file, { false }, { 0L }, { network.also { lookups++ } }, {
                    physical.capture().also { lookups++ }
                })

            ledger.measureAndCount()

            assertEquals(0, lookups)
            assertFalse(file.exists())
        }

    @Test
    fun `a revocation between the consent check and the lock discards the count`() =
        runTest {
            val file = tempFile()
            var checks = 0
            lateinit var ledger: FieldReportLedger
            // Revoked and granted again while the probe is between its first check and the lock.
            ledger =
                FieldReportLedger(file, {
                    if (checks++ == 0) ledger.clear()
                    true
                }, { 0L }, { network }, physical::capture)
            ledger.measureAndCount()
            assertEquals(0L, ledger.attempts())

            // Revoked after the first check and still off when the lock is taken, inside a flush interval.
            val answers = ArrayDeque(listOf(true, true, true, true, false))
            val revoked =
                FieldReportLedger(
                    tempFile(),
                    { answers.removeFirstOrNull() ?: false },
                    { 0L },
                    { network },
                    physical::capture,
                )
            revoked.measureAndCount()
            revoked.measureAndCount()
            assertEquals(1L, revoked.attempts())
        }

    @Test
    fun `probes are counted in memory and written at most once per flush interval`() =
        runTest {
            val file = tempFile()
            var now = 1_800_000_000_000L
            val ledger = FieldReportLedger(file, { true }, { now }, { network }, physical::capture)

            fun persisted() = FieldReportLedger(file, { true }, { now }, { network }, physical::capture)

            ledger.measureAndCount()
            now += 60_000
            ledger.measureAndCount()

            assertEquals(2L, ledger.attempts())
            assertEquals(1L, persisted().attempts())

            now += FlushIntervalMillis
            ledger.measureAndCount()

            assertEquals(3L, persisted().attempts())
        }

    @Test
    fun `an unchanged epoch counts the captured label once`() =
        runTest {
            var label = network
            val ledger = FieldReportLedger(tempFile(), { true }, { 0L }, { label }, physical::capture)
            val start = requireNotNull(ledger.captureProbe())
            // The same Wi-Fi can gain a server hint while I/O runs.
            label = FieldReportNetwork("server_hint", "wifi", asn = 64500, country = "ZZ")

            repeat(2) { ledger.recordProbe(start, member, failed, 10) }

            assertEquals(1L, ledger.attempts())
            assertEquals(
                network,
                ledger
                    .snapshot()
                    .hours
                    .single()
                    .network,
            )
        }

    @Test
    fun `handover away and back during the commit checks discards the probe`() =
        runTest {
            var checks = 0
            val file = tempFile()
            val ledger =
                FieldReportLedger(file, {
                    if (++checks == 2) {
                        selectNetwork("lte")
                        selectNetwork("wifi")
                    }
                    true
                }, { 0L }, { network }, physical::capture)
            val start = requireNotNull(ledger.captureProbe())

            ledger.recordProbe(start, member, failed, 10)

            assertEquals(0L, ledger.attempts())
            assertFalse(file.exists())
        }

    @Test
    fun `an unavailable epoch skips the label and the aggregate`() =
        runTest {
            val file = tempFile()
            val ledger = FieldReportLedger(file, { true }, { 0L }, { error("unexpected label lookup") }, { null })

            ledger.measureAndCount()

            assertFalse(file.exists())
        }
}
