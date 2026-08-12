package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.ZipFile

internal class DiagnosticsArchiveDetectionEvidenceTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `createArchive preserves completed detection stage provenance without a scan session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val baseOutcome = buildSampleCompositeOutcome()
            val rawFinding = "Resolver 203.0.113.44 exposed a detection signal"
            val redactedFinding = "Resolver <ip-redacted> exposed a detection signal"
            val outcome =
                baseOutcome.copy(
                    runId = "home-detection-stage-provenance",
                    stageSummaries =
                        baseOutcome.stageSummaries +
                            DiagnosticsHomeCompositeStageSummary(
                                stageKey = "detection_signals",
                                stageLabel = "Detection signals",
                                profileId = DetectionStageProfileId,
                                pathMode = ScanPathMode.RAW_PATH,
                                status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                                headline = "Detection signals complete",
                                summary = "VPN likely detectable on this network. 1 detection signal observed.",
                                evidenceSource = DiagnosticsHomeCompositeStageEvidenceSource.DETECTION_RUNNER,
                                detectionVerdict = DiagnosticsHomeDetectionVerdict.DETECTED,
                                detectedSignalCount = 1,
                                detectionFindings = listOf(rawFinding),
                            ),
                    completedStageCount = baseOutcome.completedStageCount + 1,
                    detectionVerdict = DiagnosticsHomeDetectionVerdict.DETECTED,
                    detectionFindings = listOf(rawFinding),
                )
            compositeRunService.putCompletedRun(outcome)

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 28L,
                    ),
                )

            assertDetectionStageArchiveProvenance(archive.absolutePath, json, redactedFinding)
        }

    @Test
    fun `createArchive exports home detection verdict evidence as structured fields`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val finding = "Indirect signs: System resolver differs from the control resolver"
            val outcome =
                buildSampleCompositeOutcome().copy(
                    runId = "home-detection-evidence",
                    detectionVerdict = DiagnosticsHomeDetectionVerdict.DETECTED,
                    detectionFindings = listOf(finding),
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
                val payload =
                    json.decodeFromString<JsonObject>(
                        zip.getInputStream(zip.getEntry("home-analysis.json")).bufferedReader().readText(),
                    )
                assertEquals(
                    "DETECTED" to listOf(finding),
                    payload["detectionVerdict"]?.jsonPrimitive?.content to
                        payload["detectionFindings"]?.jsonArray?.map { it.jsonPrimitive.content },
                )
            }
        }
}

private fun assertDetectionStageArchiveProvenance(
    archivePath: String,
    json: Json,
    redactedFinding: String,
) = ZipFile(archivePath).use { zip ->
    val stageEntries =
        listOf("stage-summaries.json", "stage-index.json").map { fileName ->
            json
                .decodeFromString<JsonObject>(
                    zip.getInputStream(zip.getEntry(fileName)).bufferedReader().readText(),
                ).getValue("stages")
                .jsonArray
                .map { it as JsonObject }
                .single { it.getValue("stageKey").jsonPrimitive.content == "detection_signals" }
        }
    val completeness =
        json
            .decodeFromString<JsonObject>(
                zip.getInputStream(zip.getEntry("completeness.json")).bufferedReader().readText(),
            ).getValue("stageEvidence")
            .jsonArray
            .map { it as JsonObject }
            .single { it.getValue("stageKey").jsonPrimitive.content == "detection_signals" }
    val counts = completeness.getValue("counts") as JsonObject
    val notApplicableCounts =
        listOf(
            "snapshots",
            "contexts",
            "nativeEvents",
            "telemetrySamples",
            "plannedAttempts",
            "executedAttempts",
            "observations",
            "diagnoses",
        ).map { countName ->
            (counts.getValue(countName) as JsonObject)["absenceReason"]?.jsonPrimitive?.content
        }
    val verdictEvidenceRefs = counts.getValue("verdictEvidenceRefs") as JsonObject

    assertEquals(
        listOf(
            List(2) {
                listOf(
                    "completed",
                    "detection_runner",
                    "DETECTED",
                    "1",
                    listOf(redactedFinding),
                )
            },
            listOf(
                "completed",
                List(8) { "not_applicable_for_stage" },
                listOf("2", "2", null),
            ),
        ),
        listOf(
            stageEntries.map { stage ->
                listOf(
                    stage["status"]?.jsonPrimitive?.content,
                    stage["evidenceSource"]?.jsonPrimitive?.content,
                    stage["detectionVerdict"]?.jsonPrimitive?.content,
                    stage["detectedSignalCount"]?.jsonPrimitive?.content,
                    stage["detectionFindings"]?.jsonArray?.map { it.jsonPrimitive.content },
                )
            },
            listOf(
                completeness["stageStatus"]?.jsonPrimitive?.content,
                notApplicableCounts,
                listOf(
                    verdictEvidenceRefs["sourceCount"]?.jsonPrimitive?.content,
                    verdictEvidenceRefs["includedCount"]?.jsonPrimitive?.content,
                    verdictEvidenceRefs["absenceReason"]?.jsonPrimitive?.content,
                ),
            ),
        ),
    )
}
