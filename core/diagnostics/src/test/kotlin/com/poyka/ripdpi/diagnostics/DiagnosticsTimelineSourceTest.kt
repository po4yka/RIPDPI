package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsTimelineSourceTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `approach stats aggregate scan and usage sessions`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineScope = timelineScope()
            try {
                val timelineSource = timelineSource(stores, timelineScope)
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
            } finally {
                timelineScope.cancel()
            }
        }

    @Test
    fun `active scan progress is managed independently from repository flows`() {
        val stores = FakeDiagnosticsHistoryStores()
        val timelineSource = timelineSource(stores)
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

    @Test
    fun `active connection session selects newest unfinished usage session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineScope = timelineScope()
            try {
                val timelineSource = timelineSource(stores, timelineScope)
                stores.usageSessionsState.value =
                    listOf(
                        usageSession(id = "connection-finished", startedAt = 10L, updatedAt = 20L, finishedAt = 30L),
                        usageSession(id = "connection-old", startedAt = 40L, updatedAt = 50L, finishedAt = null),
                        usageSession(id = "connection-new", startedAt = 60L, updatedAt = 90L, finishedAt = null),
                    )

                assertEquals("connection-new", timelineSource.activeConnectionSession.value?.id)
            } finally {
                timelineScope.cancel()
            }
        }

    @Test
    fun `active connection session keeps usage history retained without external subscribers`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineSource = timelineSource(stores, backgroundScope)

            runCurrent()

            assertEquals(1, stores.usageSessionsCollectorCount.get())

            stores.usageSessionsState.value =
                listOf(
                    usageSession(id = "connection-active", startedAt = 10L, updatedAt = 20L, finishedAt = null),
                )
            runCurrent()

            assertEquals("connection-active", timelineSource.activeConnectionSession.value?.id)
        }

    @Test
    fun `active connection session releases usage history when owning scope is cancelled`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            timelineSource(stores, timelineScope)
            runCurrent()

            assertEquals(1, stores.usageSessionsCollectorCount.get())

            timelineScope.cancel()
            runCurrent()

            assertEquals(0, stores.usageSessionsCollectorCount.get())
        }

    @Test
    fun `live runtime flows switch when the active connection session changes`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineScope = timelineScope()
            try {
                val timelineSource = timelineSource(stores, timelineScope)
                stores.telemetryState.value =
                    listOf(
                        telemetrySample(id = "telemetry-a", connectionSessionId = "connection-a"),
                        telemetrySample(id = "telemetry-b", connectionSessionId = "connection-b"),
                    )
                stores.usageSessionsState.value =
                    listOf(
                        usageSession(id = "connection-a", startedAt = 10L, updatedAt = 20L, finishedAt = null),
                        usageSession(id = "connection-b", startedAt = 30L, updatedAt = 40L, finishedAt = 50L),
                    )

                assertEquals(listOf("telemetry-a"), timelineSource.liveTelemetry.first().map { it.id })

                stores.usageSessionsState.value =
                    listOf(
                        usageSession(id = "connection-a", startedAt = 10L, updatedAt = 20L, finishedAt = 21L),
                        usageSession(id = "connection-b", startedAt = 30L, updatedAt = 60L, finishedAt = null),
                    )

                assertEquals(listOf("telemetry-b"), timelineSource.liveTelemetry.first().map { it.id })
            } finally {
                timelineScope.cancel()
            }
        }

    @Test
    fun `live runtime flows are empty when there is no active connection session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineScope = timelineScope()
            try {
                val timelineSource = timelineSource(stores, timelineScope)
                stores.telemetryState.value =
                    listOf(
                        telemetrySample(id = "telemetry-a", connectionSessionId = "connection-a"),
                    )
                stores.usageSessionsState.value =
                    listOf(
                        usageSession(id = "connection-a", startedAt = 10L, updatedAt = 20L, finishedAt = 30L),
                    )

                assertNull(timelineSource.activeConnectionSession.value)
                assertTrue(timelineSource.liveTelemetry.first().isEmpty())
                assertTrue(timelineSource.liveNativeEvents.first().isEmpty())
            } finally {
                timelineScope.cancel()
            }
        }

    @Test
    fun `live runtime artifacts exclude scan snapshots and contexts`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timelineScope = timelineScope()
            try {
                val timelineSource = timelineSource(stores, timelineScope)
                stores.usageSessionsState.value =
                    listOf(
                        usageSession(id = "connection-a", startedAt = 10L, updatedAt = 20L, finishedAt = null),
                    )
                stores.snapshotsState.value =
                    listOf(
                        snapshotSample(
                            id = "scan-snapshot",
                            connectionSessionId = "connection-a",
                            snapshotKind = "post_scan",
                        ),
                        snapshotSample(
                            id = "runtime-snapshot",
                            connectionSessionId = "connection-a",
                            snapshotKind = "connection_sample",
                        ),
                    )
                stores.contextsState.value =
                    listOf(
                        contextSample(
                            id = "scan-context",
                            connectionSessionId = "connection-a",
                            contextKind = "post_scan",
                        ),
                        contextSample(
                            id = "runtime-context",
                            connectionSessionId = "connection-a",
                            contextKind = "connection_sample",
                        ),
                    )

                assertEquals(listOf("runtime-snapshot"), timelineSource.liveSnapshots.first().map { it.id })
                assertEquals(listOf("runtime-context"), timelineSource.liveContexts.first().map { it.id })
            } finally {
                timelineScope.cancel()
            }
        }

    private fun usageSession(
        id: String,
        startedAt: Long,
        updatedAt: Long,
        finishedAt: Long?,
    ): BypassUsageSessionEntity =
        BypassUsageSessionEntity(
            id = id,
            startedAt = startedAt,
            finishedAt = finishedAt,
            updatedAt = updatedAt,
            serviceMode = "VPN",
            connectionState = if (finishedAt == null) "Running" else "Stopped",
            health = "active",
            approachProfileId = null,
            approachProfileName = null,
            strategyId = "strategy-default",
            strategyLabel = "Strategy Default",
            strategyJson = "",
            networkType = "wifi",
            txBytes = 128L,
            rxBytes = 256L,
            totalErrors = 0L,
            routeChanges = 0L,
            restartCount = 0,
            endedReason = if (finishedAt == null) null else "stopped",
        )

    private fun telemetrySample(
        id: String,
        connectionSessionId: String,
    ): com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity =
        com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity(
            id = id,
            sessionId = null,
            connectionSessionId = connectionSessionId,
            activeMode = "VPN",
            connectionState = "Running",
            networkType = "wifi",
            publicIp = "198.51.100.8",
            txPackets = 1,
            txBytes = 2,
            rxPackets = 3,
            rxBytes = 4,
            createdAt = 100L,
        )

    private fun snapshotSample(
        id: String,
        connectionSessionId: String,
        snapshotKind: String,
    ): com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity =
        com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity(
            id = id,
            sessionId = null,
            connectionSessionId = connectionSessionId,
            snapshotKind = snapshotKind,
            payloadJson =
                json.encodeToString(
                    NetworkSnapshotModel.serializer(),
                    NetworkSnapshotModel(
                        transport = "wifi",
                        capabilities = listOf("validated"),
                        dnsServers = listOf("1.1.1.1"),
                        privateDnsMode = "strict",
                        mtu = 1500,
                        localAddresses = listOf("192.168.1.2"),
                        publicIp = "198.51.100.8",
                        publicAsn = "AS64500",
                        captivePortalDetected = false,
                        networkValidated = true,
                        wifiDetails = null,
                        cellularDetails = null,
                        capturedAt = 100L,
                    ),
                ),
            capturedAt = 100L,
        )

    private fun contextSample(
        id: String,
        connectionSessionId: String,
        contextKind: String,
    ): com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity =
        com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity(
            id = id,
            sessionId = null,
            connectionSessionId = connectionSessionId,
            contextKind = contextKind,
            payloadJson =
                json.encodeToString(
                    DiagnosticContextModel.serializer(),
                    DiagnosticContextModel(
                        service =
                            ServiceContextModel(
                                serviceStatus = "Running",
                                configuredMode = "VPN",
                                activeMode = "VPN",
                                selectedProfileId = "default",
                                selectedProfileName = "Default",
                                configSource = "ui",
                                proxyEndpoint = "127.0.0.1:1080",
                                desyncMethod = "split",
                                chainSummary = "tcp: split(1)",
                                routeGroup = "3",
                                sessionUptimeMs = 10_000L,
                                lastNativeErrorHeadline = "none",
                                restartCount = 0,
                                hostAutolearnEnabled = "enabled",
                                learnedHostCount = 0,
                                penalizedHostCount = 0,
                                lastAutolearnHost = "none",
                                lastAutolearnGroup = "0",
                                lastAutolearnAction = "none",
                            ),
                        permissions =
                            PermissionContextModel(
                                vpnPermissionState = "enabled",
                                notificationPermissionState = "enabled",
                                batteryOptimizationState = "whitelisted",
                                dataSaverState = "disabled",
                            ),
                        device =
                            DeviceContextModel(
                                appVersionName = "0.0.1",
                                appVersionCode = 1L,
                                buildType = "debug",
                                androidVersion = "16",
                                apiLevel = 36,
                                manufacturer = "Google",
                                model = "Pixel",
                                primaryAbi = "arm64-v8a",
                                locale = "en-US",
                                timezone = "UTC",
                            ),
                        environment =
                            EnvironmentContextModel(
                                networkMeteredState = "false",
                                roamingState = "false",
                                powerSaveModeState = "disabled",
                                batterySaverState = "disabled",
                            ),
                    ),
                ),
            capturedAt = 100L,
        )

    private fun timelineSource(
        stores: FakeDiagnosticsHistoryStores,
        scope: CoroutineScope? = null,
    ): DefaultDiagnosticsTimelineSource =
        if (scope == null) {
            DefaultDiagnosticsTimelineSource(stores, stores, stores, stores, json)
        } else {
            DefaultDiagnosticsTimelineSource(
                profileCatalog = stores,
                scanRecordStore = stores,
                artifactReadStore = stores,
                bypassUsageHistoryStore = stores,
                mapper = DiagnosticsBoundaryMapper(json),
                scope = scope,
                json = json,
            )
        }

    private fun timelineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
}
