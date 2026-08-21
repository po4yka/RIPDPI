package com.poyka.ripdpi.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDatabase
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHomeDetectionLaunchOriginStorageValue
import com.poyka.ripdpi.data.diagnostics.RoomHomeDiagnosticsRunStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class DiagnosticsHomeRunRoomArchiveTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `room persisted home run survives database recreation and exports typed detection evidence`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "home-run-recreation-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            val outcome = sampleOutcomeWithDetectionEvidence()

            withDatabase(context, databaseName) { database ->
                val persistence = persistence(database)
                persistence.persistCompletedRun(outcome)
                val rawPersistedPayload =
                    requireNotNull(database.diagnosticsDao().getHomeDiagnosticsRun(outcome.runId)).payloadJson
                assertFalse(rawPersistedPayload.contains("captureSetId"))
                assertFalse(rawPersistedPayload.contains("73"))
                assertSingleHiddenDetectionSession(database)
            }

            withDatabase(context, databaseName) { recreatedDatabase ->
                val recreatedPersistence = persistence(recreatedDatabase)
                val restored = requireNotNull(recreatedPersistence.getCompletedRun(outcome.runId))
                assertEquals(
                    DiagnosticsHomePacketCaptureOutcome.CAPTURED_SEPARATE,
                    restored.packetCaptureDisposition.outcome,
                )
                assertEquals(4L, restored.packetCaptureDisposition.totalDrops)
                assertNull(restored.packetCaptureDisposition.captureSetId)
                val stores = FakeDiagnosticsHistoryStores()
                seedCompositeSessionStores(stores)
                val archive =
                    createArchiveExporterForTest(
                        stores = stores,
                        context = TestContext(),
                        rootModeEnabled = false,
                        compositeRunService = persistenceBackedRunService(recreatedPersistence),
                        json = json,
                    ).createArchive(
                        DiagnosticsArchiveRequest(
                            sessionIds = outcome.bundleSessionIds,
                            homeRunId = outcome.runId,
                            reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                            requestedAt = 27L,
                        ),
                    )

                ZipFile(archive.absolutePath).use { zip ->
                    val raw = zip.getInputStream(zip.getEntry("home-analysis.json")).bufferedReader().readText()
                    val provenance =
                        requireNotNull(
                            json.parseToJsonElement(raw).jsonObject["detectionProvenance"]?.jsonObject,
                        )
                    assertEquals(5, provenance["findingDetails"]?.jsonArray?.size)
                    assertFalse(raw.contains("com.example"))
                    assertFalse(raw.contains("captureSetId"))
                    val stageIndexRaw =
                        zip.getInputStream(zip.getEntry("stage-index.json")).bufferedReader().readText()
                    val passiveStage =
                        requireNotNull(
                            json
                                .parseToJsonElement(stageIndexRaw)
                                .jsonObject["stages"]
                                ?.jsonArray
                                ?.map { it.jsonObject }
                                ?.single { it["stageKey"].toString() == "\"vpn_route_evidence\"" },
                        )
                    assertEquals("\"completed\"", passiveStage["status"].toString())
                    assertEquals("\"passive_vpn_route\"", passiveStage["evidenceType"].toString())
                    assertEquals("\"vpn_route_observation\"", passiveStage["vantage"].toString())
                    assertEquals("\"unverified\"", passiveStage["targetReachability"].toString())
                    assertEquals(
                        "42",
                        passiveStage["passiveVpnRouteEvidence"]
                            ?.jsonObject
                            ?.get("vpnRouteEvidence")
                            ?.jsonObject
                            ?.get("lifecycleGeneration")
                            .toString(),
                    )
                }
            }
            context.deleteDatabase(databaseName)
        }

    @Test
    fun `room hidden local detection session is excluded from recent timeline but directly readable`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "home-run-hidden-session-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            val outcome = sampleOutcomeWithDetectionEvidence()

            withDatabase(context, databaseName) { database ->
                val persisted = persistence(database).persistCompletedRun(outcome)
                val sessionId =
                    requireNotNull(
                        persisted.stageSummaries.single { it.stageKey == "detection_signals" }.sessionId,
                    )

                assertTrue(
                    database
                        .diagnosticsDao()
                        .observeRecentScanSessions()
                        .first()
                        .isEmpty(),
                )
                val directSession = requireNotNull(database.diagnosticsDao().getScanSession(sessionId))
                assertEquals(DiagnosticsHomeDetectionLaunchOriginStorageValue, directSession.launchOrigin)
            }
            context.deleteDatabase(databaseName)
        }

    @Test
    fun `room completed home run persistence rolls back hidden session when home row write fails`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "home-run-rollback-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            val outcome = sampleOutcomeWithDetectionEvidence()

            withDatabase(context, databaseName) { database ->
                database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER abort_home_run_insert BEFORE INSERT ON home_diagnostics_runs " +
                        "BEGIN SELECT RAISE(ABORT, 'abort home run'); END",
                )

                val failure = runCatching { persistence(database).persistCompletedRun(outcome) }.exceptionOrNull()

                assertTrue(failure != null)
                assertNull(database.diagnosticsDao().getHomeDiagnosticsRun(outcome.runId))
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM scan_sessions").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
            context.deleteDatabase(databaseName)
        }

    private fun openDatabase(
        context: Context,
        name: String,
    ): DiagnosticsDatabase =
        Room
            .databaseBuilder(context, DiagnosticsDatabase::class.java, name)
            .build()

    private fun sampleOutcomeWithDetectionEvidence(): DiagnosticsHomeCompositeOutcome {
        val sample = buildSampleCompositeOutcome()
        return sample.copy(
            stageSummaries =
                sample.stageSummaries +
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "detection_signals",
                        stageLabel = "Detection signals",
                        profileId = DetectionStageProfileId,
                        pathMode = ScanPathMode.RAW_PATH,
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                        headline = "Detection signals complete",
                        summary = "Five privacy-safe local signals recorded.",
                    ) +
                    DiagnosticsHomeCompositeStageSummary(
                        stageKey = "vpn_route_evidence",
                        stageLabel = "VPN route evidence",
                        profileId = "vpn-route-evidence",
                        pathMode = null,
                        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                        headline = "VPN route evidence captured",
                        summary = "Generation-bound passive evidence captured.",
                        passiveVpnRouteEvidence = capturedPassiveVpnEvidence(),
                    ),
            completedStageCount = sample.completedStageCount + 2,
            detectionVerdict = DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW,
            detectionRuleApplied = "R6",
            detectionEvidenceScopes = listOf(DetectionScope.LOCAL_INVENTORY),
            detectionSignalCount = 5,
            detectionDecisionSignals = List(5) { localInventorySignal() },
            packetCaptureDisposition =
                DiagnosticsHomePacketCaptureDisposition(
                    requested = true,
                    outcome = DiagnosticsHomePacketCaptureOutcome.CAPTURED_SEPARATE,
                    captureSetId = 73L,
                    totalDrops = 4L,
                ),
        )
    }

    private fun assertSingleHiddenDetectionSession(database: DiagnosticsDatabase) {
        database.openHelper.readableDatabase.query("SELECT launchOrigin FROM scan_sessions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(DiagnosticsHomeDetectionLaunchOriginStorageValue, cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private suspend fun <T> withDatabase(
        context: Context,
        name: String,
        block: suspend (DiagnosticsDatabase) -> T,
    ): T {
        val database = openDatabase(context, name)
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun persistence(database: DiagnosticsDatabase): HomeDiagnosticsRunPersistence =
        HomeDiagnosticsRunPersistence(
            store = RoomHomeDiagnosticsRunStore(database, database.diagnosticsDao()),
            clock = TestDiagnosticsHistoryClock(currentTime = 123L),
            json = json,
        )

    private fun localInventorySignal(): HomeDetectionDecisionSignal =
        HomeDetectionDecisionSignal(
            category = HomeDetectionSignalCategory.LOCAL_INVENTORY_REVIEW,
            semantics = HomeDetectionSignalSemantics.LOCAL_INVENTORY_MATCH_REQUIRES_REVIEW,
            source = "INSTALLED_APP",
            confidence = "MEDIUM",
            scope = "LOCAL_INVENTORY",
        )

    private fun capturedPassiveVpnEvidence() =
        NetworkPathValidationEvidence(
            captureStatus = "captured",
            vpnRouteEvidence =
                VpnRouteEvidenceSnapshot(
                    lifecycleGeneration = 42L,
                    lifecycleState = "established",
                    callbackState = "complete",
                    ownerVerification = "verified",
                    ownPackageExcluded = true,
                    routeConsistency = "consistent",
                    forwardingOutcome = "running",
                    forwardingLifecycleGeneration = 42L,
                ),
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
