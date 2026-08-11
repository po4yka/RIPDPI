package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.ZipFile

internal class DiagnosticsArchiveDetectionEvidenceTest : DiagnosticsArchiveExporterTestBase() {
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
