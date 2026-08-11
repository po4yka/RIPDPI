package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveArchiveWideCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveBuildProvenance
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFailureEnvelope
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveNativeLibraryProvenance
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchivePayload
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchivePrimarySessionCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRunType
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveScopedCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSelection
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSessionSelectionStatus
import com.poyka.ripdpi.diagnostics.export.buildAnalysis
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsArchiveAnalysisSupportTest {
    @Test
    fun `buildAnalysis orders runtime snapshots with temporal provenance`() {
        val analysis =
            buildAnalysis(
                selectionWithRuntimeSnapshots(),
                DiagnosticsArchiveRedactor(diagnosticsTestJson()),
            )

        assertEquals(
            diagnosticsTestJson().parseToJsonElement(
                """
                [
                  {"source":"scan_session","capturedAt":10,"serviceStatus":"Running","proxyHealth":"degraded"},
                  {"source":"assessment","capturedAt":20,"serviceStatus":"Halted","proxyHealth":"idle"},
                  {"source":"passive_runtime","capturedAt":30,"serviceStatus":"Running","proxyHealth":"healthy"}
                ]
                """.trimIndent(),
            ),
            diagnosticsTestJson().encodeToJsonElement(analysis).jsonObject["runtimeSnapshotTimeline"],
        )
    }

    @Test
    fun `buildAnalysis keeps native diagnostics events out of failure envelope without telemetry failures`() {
        val events =
            listOf(
                nativeEvent("route-advanced", "route", "warn", "route advanced", 100L),
                nativeEvent("tls-version-split", "strategy", "warn", "tls_version_split", 110L),
                nativeEvent("relay-configuration", "relay", "error", "relay configuration failure", 120L),
            )

        assertEquals(
            DiagnosticsArchiveFailureEnvelope(),
            buildAnalysis(
                selectionWithEvents(events),
                DiagnosticsArchiveRedactor(diagnosticsTestJson()),
            ).failureEnvelope,
        )
    }

    private fun selectionWithEvents(events: List<NativeSessionEventEntity>) =
        DiagnosticsArchiveSelection(
            runType = DiagnosticsArchiveRunType.SINGLE_SESSION,
            request =
                DiagnosticsArchiveRequest(
                    reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                    requestedAt = 200L,
                ),
            payload =
                DiagnosticsArchivePayload(
                    schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                    scope = DiagnosticsArchiveFormat.scope,
                    privacyMode = DiagnosticsArchiveFormat.privacyMode,
                    session = null,
                    results = emptyList(),
                    sessionSnapshots = emptyList(),
                    sessionContexts = emptyList(),
                    sessionEvents = events,
                    latestPassiveSnapshot = null,
                    latestPassiveContext = null,
                    telemetry = emptyList(),
                    globalEvents = emptyList(),
                    approachSummaries = emptyList(),
                ),
            primarySession = null,
            primaryReport = null,
            primaryResults = emptyList(),
            primarySnapshots = emptyList(),
            primaryContexts = emptyList(),
            primaryEvents = events,
            latestPassiveSnapshot = null,
            latestPassiveContext = null,
            globalEvents = emptyList(),
            selectedApproachSummary = null,
            latestSnapshotModel = null,
            latestContextModel = null,
            sessionContextModel = null,
            buildProvenance =
                DiagnosticsArchiveBuildProvenance(
                    applicationId = "com.poyka.ripdpi",
                    appVersionName = "test",
                    appVersionCode = 1L,
                    buildType = "debug",
                    gitCommit = "test",
                    nativeLibraries = emptyList<DiagnosticsArchiveNativeLibraryProvenance>(),
                ),
            sessionSelectionStatus = DiagnosticsArchiveSessionSelectionStatus.REQUESTED_SESSION,
            effectiveStrategySignature = null,
            appSettings = AppSettings.getDefaultInstance(),
            sourceCounts =
                DiagnosticsArchiveScopedCounts(
                    archiveWide =
                        DiagnosticsArchiveArchiveWideCounts(
                            telemetrySamples = 0,
                            nativeEvents = events.size,
                            snapshots = 0,
                            contexts = 0,
                        ),
                    primarySession =
                        DiagnosticsArchivePrimarySessionCounts(
                            results = 0,
                            snapshots = 0,
                            contexts = 0,
                            events = events.size,
                        ),
                ),
            collectionWarnings = emptyList(),
            includedFiles = emptyList(),
            logcatSnapshot = null,
            fileLogSnapshot = null,
        )

    private fun selectionWithRuntimeSnapshots(): DiagnosticsArchiveSelection {
        val sessionContextModel = runtimeContextModel(serviceStatus = "Running", proxyHealth = "degraded")
        val passiveContextModel = runtimeContextModel(serviceStatus = "Running", proxyHealth = "healthy")
        val sessionContext =
            runtimeContextEntity(
                id = "context-session",
                sessionId = "session-1",
                capturedAt = 10L,
                model = sessionContextModel,
            )
        val passiveContext =
            runtimeContextEntity(
                id = "context-passive",
                sessionId = null,
                capturedAt = 30L,
                model = passiveContextModel,
            )
        val homeOutcome = runtimeHomeOutcome()
        val base = selectionWithEvents(emptyList())
        return base.copy(
            runType = DiagnosticsArchiveRunType.HOME_COMPOSITE,
            request =
                base.request.copy(
                    homeRunId = homeOutcome.runId,
                    reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                    requestedAt = 40L,
                ),
            payload =
                base.payload.copy(
                    sessionContexts = listOf(sessionContext),
                    latestPassiveContext = passiveContext,
                ),
            primaryContexts = listOf(sessionContext),
            latestPassiveContext = passiveContext,
            latestContextModel = passiveContextModel,
            sessionContextModel = sessionContextModel,
            sourceCounts =
                base.sourceCounts.copy(
                    archiveWide =
                        base.sourceCounts.archiveWide.copy(
                            contexts = 2,
                        ),
                    primarySession =
                        base.sourceCounts.primarySession.copy(
                            contexts = 1,
                        ),
                ),
            homeRunId = homeOutcome.runId,
            homeCompositeOutcome = homeOutcome,
        )
    }

    private fun runtimeHomeOutcome() =
        DiagnosticsHomeCompositeOutcome(
            runId = "home-run-1",
            actionable = false,
            headline = "Runtime snapshots",
            summary = "Runtime snapshots captured at different times.",
            connectivityAssessment =
                ConnectivityAssessment(
                    assessmentCode = ConnectivityAssessmentCode.SERVICE_RUNTIME_FAILURE,
                    assessmentSummary = "Service runtime snapshot",
                    confidence = "medium",
                    serviceRuntimeAssessment =
                        ConnectivityServiceRuntimeAssessment(
                            serviceStatus = "Halted",
                            capturedAt = 20L,
                            proxy = RuntimeComponentSummary(state = "idle", health = "idle"),
                        ),
                ),
        )

    private fun runtimeContextEntity(
        id: String,
        sessionId: String?,
        capturedAt: Long,
        model: DiagnosticContextModel,
    ) = DiagnosticContextEntity(
        id = id,
        sessionId = sessionId,
        contextKind = if (sessionId == null) "passive" else "post_scan",
        payloadJson = diagnosticsTestJson().encodeToString(DiagnosticContextModel.serializer(), model),
        capturedAt = capturedAt,
    )

    private fun runtimeContextModel(
        serviceStatus: String,
        proxyHealth: String,
    ) = DiagnosticContextModel(
        service =
            ServiceContextModel(
                serviceStatus = serviceStatus,
                configuredMode = "vpn",
                activeMode = "VPN",
                selectedProfileId = "default",
                selectedProfileName = "Default",
                configSource = "ui",
                proxyEndpoint = "127.0.0.1:1080",
                desyncMethod = "none",
                chainSummary = "none",
                routeGroup = "unknown",
                sessionUptimeMs = null,
                lastNativeErrorHeadline = "none",
                restartCount = 0,
                hostAutolearnEnabled = "disabled",
                learnedHostCount = 0,
                penalizedHostCount = 0,
                lastAutolearnHost = "none",
                lastAutolearnGroup = "none",
                lastAutolearnAction = "none",
                proxy = RuntimeComponentSummary(state = "running", health = proxyHealth),
            ),
        permissions =
            PermissionContextModel(
                vpnPermissionState = "enabled",
                notificationPermissionState = "enabled",
                batteryOptimizationState = "disabled",
                dataSaverState = "disabled",
            ),
        device =
            DeviceContextModel(
                appVersionName = "test",
                appVersionCode = 1L,
                buildType = "debug",
                androidVersion = "35",
                apiLevel = 35,
                manufacturer = "test",
                model = "test",
                primaryAbi = "arm64-v8a",
                locale = "en-US",
                timezone = "UTC",
            ),
        environment =
            EnvironmentContextModel(
                batterySaverState = "disabled",
                powerSaveModeState = "disabled",
                networkMeteredState = "disabled",
                roamingState = "disabled",
            ),
    )

    private fun nativeEvent(
        id: String,
        source: String,
        level: String,
        message: String,
        createdAt: Long,
    ) = NativeSessionEventEntity(
        id = id,
        source = source,
        level = level,
        message = message,
        createdAt = createdAt,
    )
}
