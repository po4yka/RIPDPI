package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineProgressWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProgressContractTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `legacy progress stays absent on wire and stale phase progress is cleared`() {
        val legacy =
            EngineProgressWire(
                sessionId = "test",
                phase = "dns",
                completedSteps = 0,
                totalSteps = 1,
                message = "DNS",
            )
        assertNull(legacy.toScanProgress().transferProgress)
        assertFalse(
            json
                .parseToJsonElement(json.encodeToString(EngineProgressWire.serializer(), legacy))
                .jsonObject
                .containsKey("transferProgress"),
        )
        val transfer = TransferProgress("Control", TransferMeasurement(1, 1, 0, 0))
        assertNull(legacy.copy(transferProgress = transfer).toScanProgress().transferProgress)
        assertNull(
            legacy
                .copy(
                    phase = "throughput",
                    isFinished = true,
                    transferProgress = transfer,
                ).toScanProgress()
                .transferProgress,
        )
    }

    @Test
    fun `live body progress survives the native to domain roundtrip`() {
        val input =
            """
            {"schemaVersion":9,"sessionId":"transfer","phase":"throughput","completedSteps":0,
             "totalSteps":1,"message":"Reading","transferProgress":{"target":"Control","measurement":{
             "runIndex":1,"runCount":1,"receivedBodyByteCount":1024,"expectedBodyByteCount":2048,
             "elapsedMs":250,"firstBodyByteMs":50,"lastBodyProgressMs":200,
             "responseComplete":false,"windowComplete":false,
             "samples":[{"elapsedMs":0,"bodyByteCount":0},{"elapsedMs":200,"bodyByteCount":1024}]}}}
            """.trimIndent()
        val wire = json.decodeFromString(EngineProgressWire.serializer(), input)
        val output = json.encodeToString(EngineProgressWire.serializer(), wire.toScanProgress().toEngineProgressWire())

        assertTrue("Body progress was lost", json.parseToJsonElement(output).jsonObject.containsKey("transferProgress"))
    }
}
