package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticsArchiveRawWireRedactorTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val redactor = DiagnosticsArchiveRedactor(json)

    @Test
    fun `redactor removes numeric raw wire arrays while preserving byte counters`() {
        val hostnameBytes = listOf(115, 101, 110, 115, 105, 116, 105, 118, 101, 46, 101, 120, 97, 109, 112, 108, 101)
        val entity = rawWireProbe("""{"rawBytes":$hostnameBytes,"bytesSent":17,"rxBytes":42}""")

        val redacted = redactor.redact(entity)
        val projected = json.parseToJsonElement(redacted.detailJson).jsonObject

        assertFalse(redacted.detailJson.contains("115, 101, 110"))
        assertEquals("redacted", projected.getValue("rawBytes").jsonPrimitive.content)
        assertEquals("17", projected.getValue("bytesSent").jsonPrimitive.content)
        assertEquals("42", projected.getValue("rxBytes").jsonPrimitive.content)
    }

    @Test
    fun `redactor fails closed for encoded raw wire strings and objects`() {
        val entity =
            rawWireProbe(
                """{"socksRequestBytes":"c2Vuc2l0aXZlLmV4YW1wbGU=",""" +
                    """"payload":{"hex":"73656e7369746976652e6578616d706c65"}}""",
            )

        val redacted = redactor.redact(entity)
        val projected = json.parseToJsonElement(redacted.detailJson).jsonObject

        assertFalse(redacted.detailJson.contains("c2Vuc2l0aXZlLmV4YW1wbGU="))
        assertFalse(redacted.detailJson.contains("73656e7369746976652e6578616d706c65"))
        assertEquals("redacted", projected.getValue("socksRequestBytes").jsonPrimitive.content)
        assertEquals("redacted", projected.getValue("payload").jsonPrimitive.content)
    }

    @Test
    fun `redactor hides encoded raw wire name variants while preserving scalar counters`() {
        val entity =
            rawWireProbe(
                """{"rawPacketHex":"73656e7369746976652e6578616d706c65",""" +
                    """"packetBase64":"c2Vuc2l0aXZlLmV4YW1wbGU=",""" +
                    """"wireHex":"73656e7369746976652e6578616d706c65",""" +
                    """"payloadB64":"c2Vuc2l0aXZlLmV4YW1wbGU=","bytesSent":17,"txBytes":42}""",
            )

        val projected = json.parseToJsonElement(redactor.redact(entity).detailJson).jsonObject

        listOf("rawPacketHex", "packetBase64", "wireHex", "payloadB64").forEach { field ->
            assertEquals("redacted", projected.getValue(field).jsonPrimitive.content)
        }
        assertEquals("17", projected.getValue("bytesSent").jsonPrimitive.content)
        assertEquals("42", projected.getValue("txBytes").jsonPrimitive.content)
    }
}

private fun rawWireProbe(detailJson: String) =
    ProbeResultEntity(
        id = "probe-raw-wire",
        sessionId = "session-redact",
        probeType = "custom",
        target = "opaque-internal-target",
        outcome = "failed",
        detailJson = detailJson,
        createdAt = 4L,
    )
