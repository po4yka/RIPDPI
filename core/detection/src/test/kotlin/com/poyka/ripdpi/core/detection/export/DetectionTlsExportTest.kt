package com.poyka.ripdpi.core.detection.export

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionReportFormatter
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.debug.DetectionDebugFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTlsExportTest {
    @Test
    fun `reports retain completed TLS findings`() {
        val finding = "Enabled protocols: TLSv1.3"
        val result =
            detectionExportFixture().copy(
                tlsFingerprint =
                    CategoryResult(
                        "Local TLS settings",
                        detected = false,
                        findings = listOf(Finding(finding)),
                    ),
            )

        assertTlsStatus(result, CheckExecutionStatus.COMPLETED)
        listOf(
            DetectionReportFormatter.format(result),
            DetectionDebugFormatter.format(result),
            DetectionMarkdownExportFormatter.format(result, metadata),
            DetectionJsonExportFormatter.format(result, metadata),
        ).forEach { output -> assertTrue(output.contains(finding)) }
    }

    @Test
    fun `reports distinguish skipped failed and incomplete TLS checks`() {
        val result = detectionExportFixture()
        assertTlsStatus(result.copy(tlsFingerprint = null), CheckExecutionStatus.SKIPPED)
        val statuses =
            listOf(
                CheckExecutionStatus.SKIPPED,
                CheckExecutionStatus.ERROR,
                CheckExecutionStatus.INCONCLUSIVE,
            )
        statuses.forEach { status ->
            val tls =
                CategoryResult("Local TLS settings", detected = false, findings = emptyList(), executionStatus = status)
            assertTlsStatus(result.copy(tlsFingerprint = tls), status)
        }
    }

    private fun assertTlsStatus(
        result: DetectionCheckResult,
        status: CheckExecutionStatus,
    ) {
        val label = if (status == CheckExecutionStatus.COMPLETED) "OK" else status.name
        assertTrue(DetectionReportFormatter.format(result).contains("--- Local TLS settings ---\nStatus: $label"))
        assertTrue(
            DetectionDebugFormatter.format(result).contains(
                "[tlsFingerprint]\nname=Local TLS settings\nexecutionStatus=${status.name}",
            ),
        )
        val markdown = DetectionMarkdownExportFormatter.format(result, metadata)
        assertTrue(markdown.contains("## Local TLS settings\n\n- status: [$label]"))
        assertTrue(markdown.contains("| Local TLS settings | [$label] |"))
        val json = Json.parseToJsonElement(DetectionJsonExportFormatter.format(result, metadata)).jsonObject
        val tls =
            json
                .getValue("results")
                .jsonObject
                .getValue("tlsFingerprint")
                .jsonObject
        assertEquals(status.name, tls.getValue("executionStatus").jsonPrimitive.content)
        assertEquals((status == CheckExecutionStatus.ERROR).toString(), tls.getValue("hasError").jsonPrimitive.content)
    }

    private val metadata = DetectionExportMetadata("2026-11-15T00:00:00Z", "test", "debug", privacyMode = false)
}
