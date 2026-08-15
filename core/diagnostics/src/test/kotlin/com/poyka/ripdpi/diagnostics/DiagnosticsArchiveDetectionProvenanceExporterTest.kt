package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.DetectionScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

internal class DiagnosticsArchiveDetectionProvenanceExporterTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `createArchive exports redacted detection provenance in home analysis`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val outcome =
                buildSampleCompositeOutcome().copy(
                    detectionVerdict = DiagnosticsHomeDetectionVerdict.DETECTED,
                    detectionFindings = listOf("com.example.vpnwatch", "network signal"),
                    detectionRuleApplied = "R3",
                    detectionEvidenceScopes =
                        listOf(
                            DetectionScope.LOCAL_OBSERVER_EXPOSURE,
                            DetectionScope.NETWORK_OBSERVATION,
                        ),
                    detectionSignalCount = 3,
                    detectionLocalFindings = listOf("com.example.vpnwatch", "local observer"),
                    detectionNetworkFindings = listOf("network signal"),
                )
            compositeRunService.putCompletedRun(outcome)

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 27L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val homeAnalysisRaw =
                    zip.getInputStream(zip.getEntry("home-analysis.json")).bufferedReader().readText()
                val provenance =
                    requireNotNull(
                        json.parseToJsonElement(homeAnalysisRaw).jsonObject["detectionProvenance"]?.jsonObject,
                    )

                assertEquals("detection_signals", provenance["stageKey"]?.jsonPrimitive?.content)
                assertEquals("DETECTED", provenance["verdict"]?.jsonPrimitive?.content)
                assertEquals("R3", provenance["ruleApplied"]?.jsonPrimitive?.content)
                assertEquals("available", provenance["evidenceStatus"]?.jsonPrimitive?.content)
                assertEquals(
                    listOf("LOCAL_OBSERVER_EXPOSURE", "NETWORK_OBSERVATION"),
                    provenance["evidenceScopes"]?.jsonArray?.map { it.jsonPrimitive.content },
                )
                assertEquals("3", provenance["uniqueSignalCount"]?.jsonPrimitive?.content)
                assertEquals("2", provenance["localFindingCount"]?.jsonPrimitive?.content)
                assertEquals("1", provenance["networkFindingCount"]?.jsonPrimitive?.content)
                assertFalse(homeAnalysisRaw.contains("com.example.vpnwatch"))
            }
        }

    @Test
    fun `createArchive marks incomplete detection provenance unavailable`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val outcome =
                buildSampleCompositeOutcome().copy(
                    detectionVerdict = DiagnosticsHomeDetectionVerdict.DETECTED,
                    detectionRuleApplied = "R3",
                    detectionSignalCount = null,
                    detectionEvidenceScopes = emptyList(),
                    detectionFindings = emptyList(),
                    detectionLocalFindings = emptyList(),
                    detectionNetworkFindings = emptyList(),
                )
            compositeRunService.putCompletedRun(outcome)

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 27L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val homeAnalysis =
                    json
                        .parseToJsonElement(
                            zip
                                .getInputStream(zip.getEntry("home-analysis.json"))
                                .bufferedReader()
                                .readText(),
                        ).jsonObject
                val provenance = requireNotNull(homeAnalysis["detectionProvenance"]?.jsonObject)

                assertEquals("unavailable", provenance["evidenceStatus"]?.jsonPrimitive?.content)
                assertTrue(provenance["uniqueSignalCount"] is JsonNull)
                assertTrue(provenance["localFindingCount"] is JsonNull)
                assertTrue(provenance["networkFindingCount"] is JsonNull)
            }
        }
}
