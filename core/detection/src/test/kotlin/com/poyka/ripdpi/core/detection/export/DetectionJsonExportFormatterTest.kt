package com.poyka.ripdpi.core.detection.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionJsonExportFormatterTest {
    @Test
    fun `json contains required top level keys`() {
        val root = parseJson(DetectionJsonExportFormatter.format(detectionExportFixture(), exportMetadata()))

        assertNotNull(root["meta"])
        assertNotNull(root["verdict"])
        assertNotNull(root["results"])
        assertNotNull(root["ipConsensus"])
    }

    @Test
    fun `format version is 1`() {
        val root = parseJson(DetectionJsonExportFormatter.format(detectionExportFixture(), exportMetadata()))

        assertEquals(
            "1",
            root
                .getValue("meta")
                .jsonObject
                .getValue("formatVersion")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `xray public key not exposed`() {
        val output = DetectionJsonExportFormatter.format(detectionExportFixture(), exportMetadata())

        assertFalse(output.contains(FixturePublicKey))
        assertTrue(output.contains("\"publicKeyPresent\": true"))
        assertFalse(output.contains(FixtureUuid))
        assertTrue(output.contains("\"uuidPresent\": true"))
    }

    @Test
    fun `privacy mode masks ips in json string fields`() {
        val output =
            DetectionJsonExportFormatter.format(
                result = detectionExportFixture(),
                metadata = exportMetadata(privacyMode = true),
            )

        assertFalse(output.contains(FixturePublicIp))
        assertTrue(output.contains("5.6.*.*"))
        assertTrue(output.contains("127.0.0.1"))
    }

    private fun parseJson(output: String) = Json.parseToJsonElement(output).jsonObject

    private fun exportMetadata(privacyMode: Boolean = false): DetectionExportMetadata =
        DetectionExportMetadata(
            timestamp = "2026-05-10T10:15:30Z",
            appVersion = "0.0.7-test",
            buildType = "debug",
            privacyMode = privacyMode,
        )
}
