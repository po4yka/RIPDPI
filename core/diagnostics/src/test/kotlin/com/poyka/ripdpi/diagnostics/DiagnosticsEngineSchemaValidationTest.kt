package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.DiagnosticsEngineSchemaVersion
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DiagnosticsEngineSchemaValidationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `report decoder accepts current and missing schema versions`() {
        val body =
            """{"sessionId":"s","profileId":"p","pathMode":"RAW_PATH","startedAt":1,"finishedAt":2,"summary":"ok"}"""

        assertEquals(DiagnosticsEngineSchemaVersion, json.decodeEngineScanReportWire(body).schemaVersion)
        assertEquals(
            DiagnosticsEngineSchemaVersion,
            json.decodeEngineScanReportWire(body.replaceFirst("{", "{\"schemaVersion\":2,")).schemaVersion,
        )
    }

    @Test
    fun `report decoder rejects old and future schema versions`() {
        val body =
            """{"schemaVersion":1,"sessionId":"s","profileId":"p","pathMode":"RAW_PATH","startedAt":1,"finishedAt":2,"summary":"old"}"""

        assertThrows(IllegalArgumentException::class.java) { json.decodeEngineScanReportWire(body) }
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeEngineScanReportWire(body.replace("\"schemaVersion\":1", "\"schemaVersion\":3"))
        }
    }

    @Test
    fun `progress decoder rejects unsupported schema versions`() {
        val body =
            """{"schemaVersion":3,"sessionId":"s","phase":"RUNNING","completedSteps":0,"totalSteps":1,"message":"wait"}"""

        assertThrows(IllegalArgumentException::class.java) { json.decodeEngineProgressWire(body) }
    }
}
