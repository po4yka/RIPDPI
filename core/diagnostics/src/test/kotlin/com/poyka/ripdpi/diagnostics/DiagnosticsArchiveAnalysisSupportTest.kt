package com.poyka.ripdpi.diagnostics

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
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsArchiveAnalysisSupportTest {
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
