package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetClassFreezeReportTest {
    private fun matrix(
        outcome: String,
        targetClass: String? = "cdn",
        asn: Long? = 13335,
        bodyReason: String? = null,
        cohort: String = "global",
    ) = ProbeResult(
        "selective_availability",
        "t",
        outcome,
        listOfNotNull(
            ProbeDetail("cohort", cohort),
            targetClass?.let { ProbeDetail("targetClass", it) },
            asn?.let { ProbeDetail("destinationAsn", it.toString()) },
            bodyReason?.let { ProbeDetail("bodyReason", it) },
        ),
    )

    private fun volume(
        outcome: String,
        leg: String,
        status: String = "200",
        targetClass: String? = null,
        asn: Long? = null,
    ) = ProbeResult(
        "throughput_window",
        "Short response",
        outcome,
        listOfNotNull(
            ProbeDetail("transferVolumeLeg", leg),
            ProbeDetail("httpStatusCodeReadings", status),
            targetClass?.let { ProbeDetail("targetClass", it) },
            asn?.let { ProbeDetail("destinationAsn", it.toString()) },
        ),
    )

    private fun incomplete(reason: String) =
        matrix("matrix_target_body_incomplete", bodyReason = reason).responseClass()

    @Test
    fun `only a stall is a freeze while reset, clean close and server answers have their own buckets`() {
        assertEquals(ResponseClass.Freeze, incomplete("read_timeout"))
        assertEquals(ResponseClass.ConnectionReset, incomplete("connection_reset"))
        assertEquals(ResponseClass.ConnectionClosed, incomplete("early_eof"))
        assertEquals(ResponseClass.Other, incomplete("read_error"))
        assertEquals(ResponseClass.ServerResponse, incomplete("response_limit"))
        assertEquals(
            ResponseClass.ServerResponse,
            matrix("matrix_target_http_error", bodyReason = "http_status").responseClass(),
        )
        assertEquals(ResponseClass.Freeze, volume("verified_download_timeout", "long").responseClass())
        assertEquals(ResponseClass.ConnectionClosed, volume("verified_download_early_eof", "long").responseClass())
        assertEquals(ResponseClass.ServerResponse, volume("verified_download_failed", "long", "403").responseClass())
        assertEquals(ResponseClass.ServerResponse, volume("verified_download_size_mismatch", "long").responseClass())
        assertEquals(ResponseClass.ServerResponse, volume("verified_download_hash_mismatch", "long").responseClass())
        assertEquals(ResponseClass.Other, volume("verified_download_failed", "long", "none").responseClass())
    }

    @Test
    fun `freeze and declared volume results group by class and destination AS in one report`() {
        val rows =
            listOf(
                matrix("matrix_target_body_incomplete", "foreign_datacenter", 16276, "read_timeout"),
                matrix("matrix_target_available", "foreign_datacenter", 16276),
                matrix("matrix_target_available", "domestic_hosting", 50340),
                volume("verified_download_timeout", "long", targetClass = "foreign_datacenter", asn = 16276),
                volume("verified_download_complete", "short", targetClass = "foreign_datacenter", asn = 16276),
            ).groupByTargetClassAndAs()
        val catalog = rows.single { it.group == TargetGroup("foreign_datacenter", 16276) }
        assertEquals(1, catalog.counts[ResponseClass.Freeze])
        assertEquals(1, catalog.counts[ResponseClass.Complete])
        val declared = rows.single { it.group == TargetGroup("foreign_datacenter", 16276, GroupSource.VolumeDeclared) }
        assertEquals(1, declared.counts[ResponseClass.Freeze])
        assertEquals(1, declared.counts[ResponseClass.Complete])
        assertEquals(
            1,
            rows.single { it.group == TargetGroup("domestic_hosting", 50340) }.counts[ResponseClass.Complete],
        )
        assertEquals(3, rows.size)
    }

    @Test
    fun `unattributed results never share a row`() {
        val lines =
            listOf(
                matrix("matrix_target_available", null, null, cohort = "user"),
                matrix("matrix_target_available", null, null, cohort = "global"),
                volume("verified_download_timeout", "long"),
            ).targetGroupSummaryLines()
        assertEquals(
            listOf(
                summaryLine("user_host", freeze = 0, complete = 1),
                summaryLine("legacy_no_class", freeze = 0, complete = 1),
                summaryLine("volume_unattributed", freeze = 1, complete = 0),
            ).sorted(),
            lines.sorted(),
        )
    }

    private fun summaryLine(
        source: String,
        freeze: Int,
        complete: Int,
    ): String =
        "targetGroup source=$source class=none asn=none freeze=$freeze serverresponse=0 " +
            "connectionreset=0 connectionclosed=0 complete=$complete other=0"
}
