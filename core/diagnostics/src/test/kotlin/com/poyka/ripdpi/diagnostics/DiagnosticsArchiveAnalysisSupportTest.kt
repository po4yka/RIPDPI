package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveArchiveWideCounts
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveBuildProvenance
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFailureEnvelope
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFailureNetworkPath
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFailurePathProvenanceEntry
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
    fun `buildAnalysis binds a failure to its exact privacy safe network path snapshot`() {
        val expectedSnapshot =
            networkSnapshotModel(
                transport = "WIFI",
                captureGeneration = 11L,
                vpnGeneration = 7L,
                underlayGeneration = 8L,
            )
        val analysis =
            buildAnalysis(
                selectionWithExactFailurePath(expectedSnapshot),
                DiagnosticsArchiveRedactor(diagnosticsTestJson()),
            )

        assertEquals(
            listOf(
                DiagnosticsArchiveFailurePathProvenanceEntry(
                    failureTimestamp = 100L,
                    failureClass = "socket_reset",
                    activeMode = "VPN",
                    networkType = "WIFI",
                    resolverId = "cloudflare",
                    resolverProtocol = "doh",
                    resolverLatencyMs = 42L,
                    networkHandoverClass = "stable",
                    networkHandoverState = "same_network",
                    snapshotCorrelation = "exact",
                    snapshotCapturedAt = 100L,
                    networkPath =
                        DiagnosticsArchiveFailureNetworkPath(
                            transport = "WIFI",
                            networkValidated = true,
                            captivePortalDetected = false,
                            mtuBand = "standard",
                            privateDnsMode = "strict",
                            pathValidation = expectedSnapshot.pathValidation,
                            pathSnapshots = expectedSnapshot.pathSnapshots,
                        ),
                ),
            ),
            analysis.failurePathProvenance,
        )
    }

    @Test
    fun `buildAnalysis marks path provenance unavailable instead of substituting a newer snapshot`() {
        val base = selectionWithEvents(emptyList())
        val failureTelemetry =
            TelemetrySampleEntity(
                id = "failure-telemetry",
                connectionSessionId = "relay-session",
                connectionState = "Failed",
                networkType = "WIFI",
                failureClass = "socket_reset",
                txPackets = 0L,
                txBytes = 0L,
                rxPackets = 0L,
                rxBytes = 0L,
                createdAt = 100L,
            )
        val newerSnapshot =
            networkSnapshot(
                id = "newer-failure-snapshot",
                connectionSessionId = "relay-session",
                snapshotKind = "failure",
                capturedAt = 200L,
                model = networkSnapshotModel(transport = "CELLULAR", captureGeneration = 12L),
            )
        val analysis =
            buildAnalysis(
                base.copy(
                    payload = base.payload.copy(telemetry = listOf(failureTelemetry)),
                    recentSnapshots = listOf(newerSnapshot),
                ),
                DiagnosticsArchiveRedactor(diagnosticsTestJson()),
            )

        assertEquals(
            diagnosticsTestJson().parseToJsonElement(
                """
                [
                  {
                    "failureTimestamp":100,
                    "failureClass":"socket_reset",
                    "networkType":"WIFI",
                    "snapshotCorrelation":"unavailable"
                  }
                ]
                """.trimIndent(),
            ),
            diagnosticsTestJson().encodeToJsonElement(analysis).jsonObject["failurePathProvenance"],
        )
    }

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

    private fun selectionWithExactFailurePath(failureSnapshotModel: NetworkSnapshotModel): DiagnosticsArchiveSelection {
        val base = selectionWithEvents(emptyList())
        val failureTimestamp = 100L
        val failureTelemetry =
            TelemetrySampleEntity(
                id = "failure-telemetry",
                connectionSessionId = "relay-session",
                activeMode = "VPN",
                connectionState = "Failed",
                networkType = "WIFI",
                failureClass = "socket_reset",
                resolverId = "cloudflare",
                resolverProtocol = "doh",
                resolverLatencyMs = 42L,
                networkHandoverClass = "stable",
                networkHandoverState = "same_network",
                txPackets = 1L,
                txBytes = 2L,
                rxPackets = 3L,
                rxBytes = 4L,
                createdAt = failureTimestamp,
            )
        return base.copy(
            payload = base.payload.copy(telemetry = listOf(failureTelemetry)),
            recentSnapshots =
                listOf(
                    networkSnapshot(
                        id = "newer-passive-snapshot",
                        connectionSessionId = null,
                        snapshotKind = "passive",
                        capturedAt = 200L,
                        model = networkSnapshotModel(transport = "CELLULAR", captureGeneration = 12L),
                    ),
                    networkSnapshot(
                        id = "failure-snapshot",
                        connectionSessionId = "relay-session",
                        snapshotKind = "failure",
                        capturedAt = failureTimestamp,
                        model = failureSnapshotModel,
                    ),
                ),
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

    private fun networkSnapshot(
        id: String,
        connectionSessionId: String?,
        snapshotKind: String,
        capturedAt: Long,
        model: NetworkSnapshotModel,
    ) = NetworkSnapshotEntity(
        id = id,
        sessionId = null,
        connectionSessionId = connectionSessionId,
        snapshotKind = snapshotKind,
        payloadJson = diagnosticsTestJson().encodeToString(NetworkSnapshotModel.serializer(), model),
        capturedAt = capturedAt,
    )

    private fun networkSnapshotModel(
        transport: String,
        captureGeneration: Long,
        vpnGeneration: Long = 70L,
        underlayGeneration: Long = 80L,
    ): NetworkSnapshotModel {
        val vpn =
            NetworkPathObservation(
                association = "active_default",
                generation = vpnGeneration,
                transport = "VPN",
                hasInternet = true,
                validated = true,
                captivePortal = false,
                metered = false,
                addressFamilies = listOf("ipv4"),
                defaultRouteFamilies = listOf("ipv4"),
                dnsServerFamilies = listOf("ipv4"),
                privateDnsCategory = "strict",
                mtuBand = "standard",
            )
        val underlay =
            NetworkPathObservation(
                association = "service_binder",
                generation = underlayGeneration,
                transport = transport,
                hasInternet = true,
                validated = true,
                captivePortal = false,
                metered = false,
                addressFamilies = listOf("ipv4"),
                defaultRouteFamilies = listOf("ipv4"),
                dnsServerFamilies = listOf("ipv4"),
                privateDnsCategory = "strict",
                mtuBand = "standard",
            )
        return NetworkSnapshotModel(
            transport = transport,
            capabilities = listOf("INTERNET", "VALIDATED"),
            dnsServers = listOf("198.51.100.53"),
            privateDnsMode = "strict.example.invalid",
            mtu = 1_500,
            localAddresses = listOf("192.0.2.10"),
            publicIp = "203.0.113.10",
            publicAsn = "AS64500",
            captivePortalDetected = false,
            networkValidated = true,
            pathValidation =
                NetworkPathValidationEvidence(
                    captureStatus = "captured",
                    underlayAssociation = "service_binder",
                    underlayGeneration = underlayGeneration,
                    underlayPresent = true,
                    underlayTransport = transport,
                    underlayInternet = true,
                    underlayValidated = true,
                    underlayCaptivePortal = false,
                    vpnAssociation = "active_default",
                    vpnPresent = true,
                    vpnInternet = true,
                    vpnValidated = true,
                    vpnCaptivePortal = false,
                ),
            pathSnapshots = NetworkPathSnapshotPair(captureGeneration, vpn, underlay),
            capturedAt = 999L,
        )
    }
}
