package com.poyka.ripdpi.services.fieldreport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.diagnostics.FieldFailureStage
import com.poyka.ripdpi.services.CandidateMeasurementStage
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

    private fun tempFile() = File.createTempFile("field-report", ".json").apply { delete() }

    private suspend fun FieldReportLedger.attempts() =
        snapshot().hours.sumOf { hour -> hour.buckets.sumOf { it.attempts } }

    @Test
    fun `without consent a probe reads no network and writes nothing`() =
        runTest {
            val file = tempFile()
            var lookups = 0
            val ledger = FieldReportLedger(file, { false }, { 0L }, { network.also { lookups++ } })

            ledger.recordProbe(member, failed, 10)

            assertEquals(0, lookups)
            assertFalse(file.exists())
        }

    @Test
    fun `probes are counted in memory and written at most once per flush interval`() =
        runTest {
            val file = tempFile()
            var now = 1_800_000_000_000L
            val ledger = FieldReportLedger(file, { true }, { now }, { network })

            fun persisted() = FieldReportLedger(file, { true }, { now }, { network })

            ledger.recordProbe(member, failed, 10)
            now += 60_000
            ledger.recordProbe(member, failed, 10)

            assertEquals(2L, ledger.attempts())
            assertEquals(1L, persisted().attempts())

            now += FlushIntervalMillis
            ledger.recordProbe(member, failed, 10)

            assertEquals(3L, persisted().attempts())
        }
}
