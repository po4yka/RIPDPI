package com.poyka.ripdpi.core.detection.export

import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

    @Test
    fun `json includes scoped verdict provenance and evidence scopes`() {
        val fixture = detectionExportFixture()
        val inventoryEvidence =
            EvidenceItem(
                source = EvidenceSource.INSTALLED_APP,
                detected = true,
                confidence = EvidenceConfidence.MEDIUM,
                description = "Targeted bypass app installed",
                family = "xray",
                packageName = "com.example.targeted",
            )
        val networkEvidence =
            EvidenceItem(
                source = EvidenceSource.IP_COMPARISON,
                detected = true,
                confidence = EvidenceConfidence.HIGH,
                description = "Regional paths report different public IPs",
            )
        val result =
            fixture.copy(
                directSigns = fixture.directSigns.copy(evidence = listOf(inventoryEvidence)),
                ipComparison =
                    fixture.ipComparison?.copy(
                        category = fixture.ipComparison.category.copy(evidence = listOf(networkEvidence)),
                    ),
                verdictExplanation =
                    fixture.verdictExplanation?.copy(
                        appliedScopes =
                            listOf(
                                DetectionScope.LOCAL_INVENTORY,
                                DetectionScope.NETWORK_OBSERVATION,
                            ),
                        uniqueSignalCount = 2,
                    ),
            )

        val root = parseJson(DetectionJsonExportFormatter.format(result, exportMetadata()))
        val verdict = root.getValue("verdict").jsonObject
        val results = root.getValue("results").jsonObject
        val directEvidence =
            results
                .getValue("directSigns")
                .jsonObject
                .getValue("evidence")
                .jsonArray
                .single()
                .jsonObject
        val networkEvidenceJson =
            results
                .getValue("ipComparison")
                .jsonObject
                .getValue("evidence")
                .jsonArray
                .single()
                .jsonObject

        assertEquals(
            listOf("LOCAL_INVENTORY,NETWORK_OBSERVATION", "2", "LOCAL_INVENTORY", "NETWORK_OBSERVATION"),
            listOf(
                verdict
                    .getValue("appliedScopes")
                    .jsonArray
                    .joinToString(",") { it.jsonPrimitive.content },
                verdict.getValue("uniqueSignalCount").jsonPrimitive.content,
                directEvidence.getValue("scope").jsonPrimitive.content,
                networkEvidenceJson.getValue("scope").jsonPrimitive.content,
            ),
        )
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
