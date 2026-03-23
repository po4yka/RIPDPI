package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsTimelineSourceTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `approach stats aggregate scan and usage sessions`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = DefaultDiagnosticsTimelineSource(stores, stores, stores, stores, json)
            stores.sessionsState.value =
                listOf(
                    diagnosticsSession(
                        id = "scan-1",
                        profileId = "default",
                        pathMode = ScanPathMode.RAW_PATH.name,
                        summary = "blocked",
                        reportJson =
                            json.encodeToString(
                                ScanReport.serializer(),
                                ScanReport(
                                    sessionId = "scan-1",
                                    profileId = "default",
                                    pathMode = ScanPathMode.RAW_PATH,
                                    startedAt = 10L,
                                    finishedAt = 20L,
                                    summary = "blocked",
                                    results =
                                        listOf(
                                            ProbeResult(
                                                probeType = "dns",
                                                target = "blocked.example",
                                                outcome = "dns_blocked",
                                            ),
                                        ),
                                ),
                            ),
                    ).copy(
                        approachProfileId = "profile-fast",
                        approachProfileName = "Profile Fast",
                        strategyId = "strategy-fast",
                        strategyLabel = "Strategy Fast",
                        strategyJson = "",
                    ),
                )
            stores.usageSessionsState.value =
                listOf(
                    BypassUsageSessionEntity(
                        id = "usage-1",
                        startedAt = 30L,
                        finishedAt = 80L,
                        updatedAt = 80L,
                        serviceMode = "VPN",
                        approachProfileId = "profile-fast",
                        approachProfileName = "Profile Fast",
                        strategyId = "strategy-fast",
                        strategyLabel = "Strategy Fast",
                        strategyJson = "",
                        networkType = "wifi",
                        txBytes = 128L,
                        rxBytes = 256L,
                        totalErrors = 2L,
                        routeChanges = 3L,
                        restartCount = 1,
                        endedReason = "normal",
                    ),
                )

            val summaries = timelineSource.approachStats.first()
            val profileSummary = summaries.first { it.approachId.kind == BypassApproachKind.Profile }
            val strategySummary = summaries.first { it.approachId.kind == BypassApproachKind.Strategy }

            assertEquals("profile-fast", profileSummary.approachId.value)
            assertEquals("validated", profileSummary.verificationState)
            assertEquals(1, profileSummary.validatedScanCount)
            assertEquals(1, profileSummary.usageCount)
            assertEquals(listOf("dns_blocked (1)"), profileSummary.topFailureOutcomes)
            assertEquals("strategy-fast", strategySummary.approachId.value)
            assertEquals(3L, strategySummary.recentRuntimeHealth.routeChanges)
            assertEquals("normal", strategySummary.recentRuntimeHealth.lastEndedReason)
        }

    @Test
    fun `active scan progress is managed independently from repository flows`() {
        val stores = FakeDiagnosticsHistoryStores()
        val timelineSource = DefaultDiagnosticsTimelineSource(stores, stores, stores, stores, json)
        val progress =
            ScanProgress(
                sessionId = "scan-1",
                phase = "probing",
                completedSteps = 1,
                totalSteps = 3,
                message = "probing blocked.example",
            )

        timelineSource.updateActiveScanProgress(progress)
        assertEquals(progress, timelineSource.activeScanProgress.value)

        timelineSource.updateActiveScanProgress(null)
        assertNull(timelineSource.activeScanProgress.value)
    }
}
