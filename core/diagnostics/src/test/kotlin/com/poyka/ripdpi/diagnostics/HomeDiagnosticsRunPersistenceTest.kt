package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.data.diagnostics.HomeDiagnosticsRunEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDiagnosticsRunPersistenceTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `persisted completed run creates hidden local detection evidence session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persistence = persistence(stores)
            val outcome = completedOutcome()

            persistence.persistCompletedRun(outcome)

            val restored = requireNotNull(persistence.getCompletedRun(outcome.runId))
            val detectionSessionId =
                requireNotNull(
                    restored.stageSummaries.single { it.stageKey == "detection_signals" }.sessionId,
                )
            assertTrue(detectionSessionId in restored.bundleSessionIds)
            assertTrue(stores.observeRecentScanSessions().first().isEmpty())

            val detectionSession = requireNotNull(stores.getScanSession(detectionSessionId))
            assertEquals("detection-signals", detectionSession.profileId)
            assertEquals("home_detection_local", detectionSession.launchOrigin)
            assertNull(detectionSession.reportJson)

            val evidenceRows = stores.getProbeResults(detectionSessionId)
            assertEquals(1, evidenceRows.size)
            assertEquals("home_detection_signal", evidenceRows.single().probeType)
            assertEquals("LOCAL_INVENTORY_REVIEW", evidenceRows.single().target)
            assertEquals("LOCAL_INVENTORY_MATCH_REQUIRES_REVIEW", evidenceRows.single().outcome)
            assertTrue(!evidenceRows.single().detailJson.contains("com.example"))
            assertEquals(outcome.runId, restored.runId)
        }

    @Test
    fun `corrupt persisted home run fails closed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.homeRunsState.value =
                listOf(
                    HomeDiagnosticsRunEntity(
                        runId = "home-run-corrupt",
                        origin = "home_composite",
                        status = "completed",
                        payloadVersion = 1,
                        payloadJson = "{not-json",
                        createdAt = 1L,
                        updatedAt = 1L,
                    ),
                )

            assertNull(persistence(stores).getCompletedRun("home-run-corrupt"))
        }

    @Test
    fun `unsupported persisted payload version fails closed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val outcome = completedOutcome()
            persistence(stores).persistCompletedRun(outcome)
            stores.homeRunsState.value =
                stores.homeRunsState.value.map { row ->
                    row.copy(payloadVersion = row.payloadVersion + 1)
                }

            assertNull(persistence(stores).getCompletedRun(outcome.runId))
        }

    @Test
    fun `mismatched persisted origin fails closed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val outcome = completedOutcome()
            persistence(stores).persistCompletedRun(outcome)
            stores.homeRunsState.value =
                stores.homeRunsState.value.map { row ->
                    row.copy(origin = "ordinary_scan")
                }

            assertNull(persistence(stores).getCompletedRun(outcome.runId))
        }

    @Test
    fun `mismatched run id fails closed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            persistence(stores).persistCompletedRun(completedOutcome(runId = "home-run-real"))
            stores.homeRunsState.value =
                stores.homeRunsState.value.map { row ->
                    row.copy(runId = "home-run-requested")
                }

            assertNull(persistence(stores).getCompletedRun("home-run-requested"))
        }

    @Test
    fun `detection signal count mismatch fails closed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val outcome =
                completedOutcome().copy(
                    detectionSignalCount = 2,
                    detectionDecisionSignals = listOf(localInventorySignal()),
                )

            val failure = runCatching { persistence(stores).persistCompletedRun(outcome) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(stores.homeRunsState.value.isEmpty())
            assertNull(persistence(stores).getCompletedRun(outcome.runId))
        }

    @Test
    fun `detection signal source and scope mismatch fails closed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val outcome = completedOutcome()
            persistence(stores).persistCompletedRun(outcome)
            stores.homeRunsState.value =
                stores.homeRunsState.value.map { row ->
                    val corruptedPayload =
                        row.payloadJson.replace(
                            "\"scope\": \"LOCAL_INVENTORY\"",
                            "\"scope\": \"NETWORK_OBSERVATION\"",
                        )
                    assertTrue(corruptedPayload != row.payloadJson)
                    row.copy(
                        payloadJson = corruptedPayload,
                    )
                }

            assertNull(persistence(stores).getCompletedRun(outcome.runId))
        }

    @Test
    fun `explicit network scope overrides source default during persistence`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val networkSignal =
                HomeDetectionDecisionSignal(
                    category = HomeDetectionSignalCategory.NETWORK_OBSERVATION,
                    semantics = HomeDetectionSignalSemantics.NETWORK_OBSERVATION_PRESENT,
                    source = "NETWORK_CAPABILITIES",
                    confidence = "MEDIUM",
                    scope = "NETWORK_OBSERVATION",
                )
            val outcome =
                completedOutcome().copy(
                    detectionEvidenceScopes = listOf(DetectionScope.NETWORK_OBSERVATION),
                    detectionDecisionSignals = listOf(networkSignal),
                )

            persistence(stores).persistCompletedRun(outcome)

            val restored = requireNotNull(persistence(stores).getCompletedRun(outcome.runId))
            assertEquals(listOf(networkSignal), restored.detectionDecisionSignals)
            assertEquals(listOf(DetectionScope.NETWORK_OBSERVATION), restored.detectionEvidenceScopes)
        }

    @Test
    fun `packet capture disposition persists without local capture identity`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val outcome =
                completedOutcome().copy(
                    packetCaptureDisposition =
                        DiagnosticsHomePacketCaptureDisposition(
                            requested = true,
                            outcome = DiagnosticsHomePacketCaptureOutcome.CAPTURED_SEPARATE,
                            captureSetId = 73L,
                            totalDrops = 4L,
                        ),
                )

            persistence(stores).persistCompletedRun(outcome)

            val rawPayload = requireNotNull(stores.homeRunsState.value.singleOrNull()).payloadJson
            assertTrue(!rawPayload.contains("captureSetId"))
            assertTrue(!rawPayload.contains("73"))
            val restored = requireNotNull(persistence(stores).getCompletedRun(outcome.runId))
            assertEquals(true, restored.packetCaptureDisposition.requested)
            assertEquals(
                DiagnosticsHomePacketCaptureOutcome.CAPTURED_SEPARATE,
                restored.packetCaptureDisposition.outcome,
            )
            assertEquals(4L, restored.packetCaptureDisposition.totalDrops)
            assertNull(restored.packetCaptureDisposition.captureSetId)
            assertNull(restored.packetCaptureDisposition.failureCode)
        }

    @Test
    fun `pending packet capture cleanup persists without local capture identity`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val outcome =
                completedOutcome().copy(
                    packetCaptureDisposition =
                        DiagnosticsHomePacketCaptureDisposition(
                            requested = true,
                            outcome = DiagnosticsHomePacketCaptureOutcome.CLEANUP_PENDING,
                            captureSetId = 74L,
                            failureCode = "capture_cleanup_pending",
                        ),
                )

            persistence(stores).persistCompletedRun(outcome)

            val rawPayload = requireNotNull(stores.homeRunsState.value.singleOrNull()).payloadJson
            assertTrue(!rawPayload.contains("captureSetId"))
            assertTrue(!rawPayload.contains("74"))
            val restored = requireNotNull(persistence(stores).getCompletedRun(outcome.runId))
            assertEquals(DiagnosticsHomePacketCaptureOutcome.CLEANUP_PENDING, restored.packetCaptureDisposition.outcome)
            assertEquals("capture_cleanup_pending", restored.packetCaptureDisposition.failureCode)
            assertNull(restored.packetCaptureDisposition.captureSetId)
        }

    @Test
    fun `passive vpn receipt survives persistence with its captured generation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val base = completedOutcome()
            val passiveStage =
                DiagnosticsHomeCompositeStageSummary(
                    stageKey = "vpn_route_evidence",
                    stageLabel = "VPN route evidence",
                    profileId = "vpn-route-evidence",
                    pathMode = null,
                    evidenceType = HomeCompositeStageEvidenceType.PASSIVE_VPN_ROUTE,
                    vantage = HomeCompositeStageVantage.VPN_ROUTE_OBSERVATION,
                    targetReachability = HomeCompositeTargetReachability.UNVERIFIED,
                    status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                    headline = "VPN route evidence captured",
                    summary = "Captured owner route evidence.",
                    passiveVpnRouteEvidence =
                        NetworkPathValidationEvidence(
                            captureStatus = "captured",
                            vpnRouteEvidence =
                                VpnRouteEvidenceSnapshot(
                                    lifecycleGeneration = 42L,
                                    lifecycleState = "bridge_ready",
                                    callbackState = "complete",
                                    ownerVerification = "verified",
                                    routeConsistency = "consistent",
                                    ownPackageExcluded = true,
                                    forwardingOutcome = "cross_layer_return_observed",
                                    forwardingLifecycleGeneration = 42L,
                                ),
                        ),
                )
            val outcome =
                base.copy(
                    stageSummaries = base.stageSummaries + passiveStage,
                    completedStageCount = base.completedStageCount + 1,
                )

            persistence(stores).persistCompletedRun(outcome)

            val restored = requireNotNull(persistence(stores).getCompletedRun(outcome.runId))
            val restoredPassive = restored.stageSummaries.single { it.stageKey == "vpn_route_evidence" }
            assertEquals(null, restoredPassive.pathMode)
            assertEquals(HomeCompositeStageEvidenceType.PASSIVE_VPN_ROUTE, restoredPassive.evidenceType)
            assertEquals(42L, restoredPassive.passiveVpnRouteEvidence?.vpnRouteEvidence?.lifecycleGeneration)
        }

    @Test
    fun `malformed persisted packet capture disposition fails closed without crash`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val outcome =
                completedOutcome().copy(
                    packetCaptureDisposition =
                        DiagnosticsHomePacketCaptureDisposition(
                            requested = true,
                            outcome = DiagnosticsHomePacketCaptureOutcome.CAPTURED_SEPARATE,
                            totalDrops = 4L,
                        ),
                )
            persistence(stores).persistCompletedRun(outcome)
            stores.homeRunsState.value =
                stores.homeRunsState.value.map { row ->
                    val corruptedPayload =
                        row.payloadJson
                            .replace("\"requested\": true", "\"requested\": false")
                            .replace("\"totalDrops\": 4", "\"totalDrops\": -1")
                            .replace("\"failureCode\": null", "\"failureCode\": \"BAD!\"")
                    assertTrue(corruptedPayload != row.payloadJson)
                    row.copy(payloadJson = corruptedPayload)
                }

            val restored = runCatching { persistence(stores).getCompletedRun(outcome.runId) }

            assertNull(restored.exceptionOrNull())
            assertNull(restored.getOrThrow())
        }

    private fun persistence(stores: FakeDiagnosticsHistoryStores): HomeDiagnosticsRunPersistence =
        HomeDiagnosticsRunPersistence(
            store = stores,
            clock = TestDiagnosticsHistoryClock(currentTime = 123L),
            json = json,
        )

    private fun completedOutcome(runId: String = "home-run-1"): DiagnosticsHomeCompositeOutcome =
        DiagnosticsHomeCompositeOutcome(
            runId = runId,
            fingerprintHash = "fp-home",
            actionable = true,
            headline = "Analysis complete",
            summary = "Completed.",
            recommendedSessionId = "scan-1",
            stageSummaries =
                listOf(
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "automatic_audit",
                        stageLabel = "Automatic audit",
                        profileId = "automatic-audit",
                        pathMode = ScanPathMode.RAW_PATH,
                        sessionId = "scan-1",
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                        headline = "Audit complete",
                        summary = "Audit complete.",
                    ),
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "detection_signals",
                        stageLabel = "Detection signals",
                        profileId = "detection-signals",
                        pathMode = ScanPathMode.RAW_PATH,
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                        headline = "Detection signals complete",
                        summary = "1 local inventory match requires review.",
                    ),
                ),
            completedStageCount = 2,
            failedStageCount = 0,
            skippedStageCount = 0,
            bundleSessionIds = listOf("scan-1"),
            detectionVerdict = DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW,
            detectionRuleApplied = "R6",
            detectionEvidenceScopes = listOf(DetectionScope.LOCAL_INVENTORY),
            detectionSignalCount = 1,
            detectionDecisionSignals = listOf(localInventorySignal()),
        )

    private fun localInventorySignal(): HomeDetectionDecisionSignal =
        HomeDetectionDecisionSignal(
            category = HomeDetectionSignalCategory.LOCAL_INVENTORY_REVIEW,
            semantics = HomeDetectionSignalSemantics.LOCAL_INVENTORY_MATCH_REQUIRES_REVIEW,
            source = "INSTALLED_APP",
            confidence = "MEDIUM",
            scope = "LOCAL_INVENTORY",
        )
}
