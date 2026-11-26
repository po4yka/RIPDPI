package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetClassFreezeReportTest {
    private fun matrix(
        outcome: String,
        targetClass: String,
        asn: Int,
        bodyReason: String? = null,
    ) = ProbeResult(
        "selective_availability",
        "t",
        outcome,
        listOfNotNull(
            ProbeDetail("targetClass", targetClass),
            ProbeDetail("destinationAsn", asn.toString()),
            bodyReason?.let { ProbeDetail("bodyReason", it) },
        ),
    )

    private fun volume(
        outcome: String,
        leg: String,
        status: String = "200",
    ) = ProbeResult(
        "throughput_window",
        "Short response",
        outcome,
        listOf(ProbeDetail("transferVolumeLeg", leg), ProbeDetail("httpStatusCodeReadings", status)),
    )

    @Test
    fun `stall is a freeze while a wrong status or short body is a server response`() {
        assertEquals(
            ResponseClass.Freeze,
            matrix("matrix_target_body_incomplete", "cdn", 1, "read_error").responseClass(),
        )
        assertEquals(
            ResponseClass.ServerResponse,
            matrix("matrix_target_body_incomplete", "cdn", 1, "early_eof").responseClass(),
        )
        assertEquals(
            ResponseClass.ServerResponse,
            matrix("matrix_target_http_error", "cdn", 1, "http_status").responseClass(),
        )
        assertEquals(ResponseClass.Freeze, volume("verified_download_timeout", "long").responseClass())
        assertEquals(ResponseClass.ServerResponse, volume("verified_download_failed", "long", "403").responseClass())
        assertEquals(ResponseClass.ServerResponse, volume("verified_download_size_mismatch", "long").responseClass())
        assertEquals(ResponseClass.ServerResponse, volume("verified_download_hash_mismatch", "long").responseClass())
        assertEquals(ResponseClass.Other, volume("verified_download_failed", "long", "none").responseClass())
    }

    @Test
    fun `freeze and volume results group by class and destination AS in one report`() {
        val ovh =
            SelectiveMatrixTarget(
                "ovh",
                "OVH",
                "https://proof.ovh.net/robots.txt",
                "global",
                "ovhcloud",
                "",
                "",
                targetClass = "foreign_datacenter",
                destinationAsn = 16276,
            )
        val rows =
            listOf(
                matrix("matrix_target_body_incomplete", "foreign_datacenter", 16276, "read_error"),
                matrix("matrix_target_available", "foreign_datacenter", 16276),
                matrix("matrix_target_available", "domestic_hosting", 50340),
                volume("verified_download_timeout", "long"),
                volume("verified_download_complete", "short"),
            ).groupByTargetClassAndAs(volumeTarget = ovh)
        val foreign = rows.single { it.group == TargetGroup("foreign_datacenter", 16276) }
        assertEquals(2, foreign.counts[ResponseClass.Freeze])
        assertEquals(2, foreign.counts[ResponseClass.Complete])
        assertEquals(
            1,
            rows.single { it.group == TargetGroup("domestic_hosting", 50340) }.counts[ResponseClass.Complete],
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun `report lines carry class and AS but no address or response text`() {
        val lines =
            listOf(
                matrix("matrix_target_body_incomplete", "cdn", 13335, "read_error"),
                volume("verified_download_timeout", "long"),
            ).targetGroupSummaryLines()
        assertTrue(lines.contains("targetGroup class=cdn asn=13335 freeze=1 serverresponse=0 complete=0 other=0"))
        assertTrue(
            lines.contains(
                "targetGroup class=unattributed asn=unattributed freeze=1 serverresponse=0 complete=0 other=0",
            ),
        )
        assertFalse(lines.any { Regex("""\d+\.\d+\.\d+\.\d+""").containsMatchIn(it) })
    }
}
