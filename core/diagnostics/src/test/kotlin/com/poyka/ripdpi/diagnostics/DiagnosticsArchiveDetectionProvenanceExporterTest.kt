package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.DetectionScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
                    detectionDecisionSignals =
                        listOf(
                            localObserverSignal(source = "LOCAL_PROXY"),
                            localObserverSignal(source = "ACTIVE_VPN"),
                            networkSignal(),
                        ),
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
                assertEquals(
                    listOf("detection_signal_1", "detection_signal_2", "detection_signal_3"),
                    provenance["findingDetails"]?.jsonArray?.map {
                        it.jsonObject["alias"]?.jsonPrimitive?.content
                    },
                )
                assertEquals(
                    listOf("local_detection_signal", "local_detection_signal", "network_detection_signal"),
                    provenance["findingDetails"]?.jsonArray?.map {
                        it.jsonObject["kind"]?.jsonPrimitive?.content
                    },
                )
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

    @Test
    fun `createArchive exports process-recreated persisted local inventory detection signals`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val sampleOutcome = buildSampleCompositeOutcome()
            val persistedOutcome =
                sampleOutcome.copy(
                    stageSummaries =
                        sampleOutcome.stageSummaries +
                            DiagnosticsHomeCompositeStageSummary(
                                stageKey = "detection_signals",
                                stageLabel = "Detection signals",
                                profileId = DetectionStageProfileId,
                                pathMode = ScanPathMode.RAW_PATH,
                                status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                                headline = "Detection signals complete",
                                summary = "Five privacy-safe local signals recorded.",
                            ),
                    completedStageCount = sampleOutcome.completedStageCount + 1,
                    detectionVerdict = DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW,
                    detectionRuleApplied = "R6",
                    detectionSignalCount = 5,
                    detectionEvidenceScopes = listOf(DetectionScope.LOCAL_INVENTORY),
                    detectionDecisionSignals = List(5) { localInventorySignal(source = "INSTALLED_APP") },
                )
            val persistence =
                HomeDiagnosticsRunPersistence(
                    store = stores,
                    clock = TestDiagnosticsHistoryClock(currentTime = 123L),
                    json = json,
                )
            persistence.persistCompletedRun(persistedOutcome)

            val archive =
                createArchiveExporterForTest(
                    stores = stores,
                    context = TestContext(),
                    rootModeEnabled = false,
                    compositeRunService = persistenceBackedRunService(persistence),
                    json = json,
                ).createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = persistedOutcome.bundleSessionIds,
                        homeRunId = persistedOutcome.runId,
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
                val details = requireNotNull(provenance["findingDetails"]?.jsonArray)

                assertEquals("available", provenance["evidenceStatus"]?.jsonPrimitive?.content)
                assertEquals("5", provenance["uniqueSignalCount"]?.jsonPrimitive?.content)
                assertEquals(5, details.size)
                assertTrue(
                    details.all {
                        it.jsonObject["semantics"]?.jsonPrimitive?.content ==
                            "LOCAL_INVENTORY_MATCH_REQUIRES_REVIEW"
                    },
                )
                assertFalse(homeAnalysisRaw.contains("com.example"))
                assertFalse(homeAnalysisRaw.contains("xray"))
                assertFalse(homeAnalysisRaw.contains("Targeted bypass"))
            }
        }

    @Test
    fun `createArchive fails unavailable when persisted home run is missing`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val persistence =
                HomeDiagnosticsRunPersistence(stores, TestDiagnosticsHistoryClock(currentTime = 123L), json)

            val failure =
                runCatching {
                    createArchiveExporterForTest(
                        stores = stores,
                        context = TestContext(),
                        rootModeEnabled = false,
                        compositeRunService = persistenceBackedRunService(persistence),
                        json = json,
                    ).createArchive(
                        DiagnosticsArchiveRequest(
                            sessionIds = buildSampleCompositeOutcome().bundleSessionIds,
                            homeRunId = "missing-home-run",
                            reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                            requestedAt = 27L,
                        ),
                    )
                }.exceptionOrNull()

            assertNotNull(failure)
        }

    @Test
    fun `createArchive fails unavailable when persisted home run is corrupt`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            stores.homeRunsState.value =
                listOf(
                    com.poyka.ripdpi.data.diagnostics.HomeDiagnosticsRunEntity(
                        runId = "home-run-corrupt",
                        origin = "home_composite",
                        status = "completed",
                        payloadVersion = 1,
                        payloadJson = "{not-json",
                        createdAt = 1L,
                        updatedAt = 1L,
                    ),
                )
            val persistence =
                HomeDiagnosticsRunPersistence(stores, TestDiagnosticsHistoryClock(currentTime = 123L), json)

            val failure =
                runCatching {
                    createArchiveExporterForTest(
                        stores = stores,
                        context = TestContext(),
                        rootModeEnabled = false,
                        compositeRunService = persistenceBackedRunService(persistence),
                        json = json,
                    ).createArchive(
                        DiagnosticsArchiveRequest(
                            sessionIds = buildSampleCompositeOutcome().bundleSessionIds,
                            homeRunId = "home-run-corrupt",
                            reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                            requestedAt = 27L,
                        ),
                    )
                }.exceptionOrNull()

            assertNotNull(failure)
        }

    @Test
    fun `createArchive marks detection evidence unavailable when count parity fails`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val outcome =
                buildSampleCompositeOutcome().copy(
                    detectionVerdict = DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW,
                    detectionRuleApplied = "R6",
                    detectionSignalCount = 2,
                    detectionEvidenceScopes = listOf(DetectionScope.LOCAL_INVENTORY),
                    detectionDecisionSignals = listOf(localInventorySignal()),
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
                            zip.getInputStream(zip.getEntry("home-analysis.json")).bufferedReader().readText(),
                        ).jsonObject
                val provenance = requireNotNull(homeAnalysis["detectionProvenance"]?.jsonObject)

                assertEquals("unavailable", provenance["evidenceStatus"]?.jsonPrimitive?.content)
                assertEquals(0, provenance["findingDetails"]?.jsonArray?.size)
            }
        }

    private fun localInventorySignal(source: String = "INSTALLED_APP"): HomeDetectionDecisionSignal =
        HomeDetectionDecisionSignal(
            category = HomeDetectionSignalCategory.LOCAL_INVENTORY_REVIEW,
            semantics = HomeDetectionSignalSemantics.LOCAL_INVENTORY_MATCH_REQUIRES_REVIEW,
            source = source,
            confidence = "MEDIUM",
            scope = "LOCAL_INVENTORY",
        )

    private fun localObserverSignal(source: String): HomeDetectionDecisionSignal =
        HomeDetectionDecisionSignal(
            category = HomeDetectionSignalCategory.LOCAL_OBSERVER_EXPOSURE,
            semantics = HomeDetectionSignalSemantics.LOCAL_OBSERVER_EXPOSURE_PRESENT,
            source = source,
            confidence = "MEDIUM",
            scope = "LOCAL_OBSERVER_EXPOSURE",
        )

    private fun networkSignal(): HomeDetectionDecisionSignal =
        HomeDetectionDecisionSignal(
            category = HomeDetectionSignalCategory.NETWORK_OBSERVATION,
            semantics = HomeDetectionSignalSemantics.NETWORK_OBSERVATION_PRESENT,
            source = "DNS",
            confidence = "LOW",
            scope = "NETWORK_OBSERVATION",
        )

    private fun persistenceBackedRunService(
        persistence: HomeDiagnosticsRunPersistence,
    ): DiagnosticsHomeCompositeRunService =
        object : DiagnosticsHomeCompositeRunService {
            override suspend fun startHomeAnalysis(
                options: DiagnosticsHomeRunOptions,
            ): DiagnosticsHomeCompositeRunStarted = error("unused")

            override suspend fun startQuickAnalysis(
                options: DiagnosticsHomeRunOptions,
            ): DiagnosticsHomeCompositeRunStarted = error("unused")

            override fun observeHomeRun(runId: String) = error("unused")

            override suspend fun cancelHomeRun(runId: String) = error("unused")

            override suspend fun finalizeHomeRun(runId: String): DiagnosticsHomeCompositeOutcome =
                requireNotNull(getCompletedRun(runId)) { "Missing completed run $runId" }

            override suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome? =
                persistence.getCompletedRun(runId)

            override suspend fun lookupCachedOutcome(fingerprintHash: String): CachedProbeOutcome? = null

            override suspend fun evictCachedOutcome(fingerprintHash: String) = Unit
        }
}
