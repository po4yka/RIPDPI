package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.DiagnosticsEngineSchemaVersion
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DiagnosticsEngineSchemaValidationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `report decoder accepts deployed strategy attempt schema`() {
        val body =
            """
            {
                "schemaVersion":7,"sessionId":"s","profileId":"p","pathMode":"RAW_PATH",
                "startedAt":1,"finishedAt":2,"summary":"ok"
            }
            """.trimIndent()
        val missing = body.replace("\"schemaVersion\":7,", "")

        assertEquals(DiagnosticsEngineSchemaVersion, json.decodeEngineScanReportWire(body).schemaVersion)
        assertThrows(SerializationException::class.java) { json.decodeEngineScanReportWire(missing) }
    }

    @Test
    fun `report decoder rejects old and future schema versions`() {
        val body =
            """
            {
                "schemaVersion":4,"sessionId":"s","profileId":"p","pathMode":"RAW_PATH",
                "startedAt":1,"finishedAt":2,"summary":"old"
            }
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { json.decodeEngineScanReportWire(body) }
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeEngineScanReportWire(body.replace("\"schemaVersion\":4", "\"schemaVersion\":8"))
        }
    }

    @Test
    fun `progress decoder requires current schema version`() {
        val body =
            """
            {
                "schemaVersion":7,"sessionId":"s","phase":"RUNNING",
                "completedSteps":0,"totalSteps":1,"message":"wait"
            }
            """.trimIndent()
        val missing = body.replace("\"schemaVersion\":7,", "")

        assertEquals(DiagnosticsEngineSchemaVersion, json.decodeEngineProgressWire(body).schemaVersion)
        assertThrows(SerializationException::class.java) { json.decodeEngineProgressWire(missing) }
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeEngineProgressWire(body.replace("\"schemaVersion\":7", "\"schemaVersion\":6"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeEngineProgressWire(body.replace("\"schemaVersion\":7", "\"schemaVersion\":8"))
        }
    }
}
