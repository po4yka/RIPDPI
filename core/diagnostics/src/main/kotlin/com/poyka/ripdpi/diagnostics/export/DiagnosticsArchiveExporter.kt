package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticsExportRecordStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsContext
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsSource
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSessionSelector
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSourceLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

fun interface DiagnosticsArchiveIdGenerator {
    fun nextId(): String
}

interface DiagnosticsArchiveExporter {
    suspend fun cleanupCache()

    suspend fun createArchive(request: DiagnosticsArchiveRequest): DiagnosticsArchive
}

@Singleton
internal class DefaultDiagnosticsArchiveExporter
    @Inject
    constructor(
        private val exportRecordStore: DiagnosticsExportRecordStore,
        private val sourceLoader: DiagnosticsArchiveSourceLoader,
        private val sessionSelector: DiagnosticsArchiveSessionSelector,
        private val renderer: DiagnosticsArchiveRenderer,
        private val fileStore: DiagnosticsArchiveFileStore,
        private val zipWriter: DiagnosticsArchiveZipWriter,
        private val idGenerator: DiagnosticsArchiveIdGenerator,
        private val developerAnalyticsSource: DeveloperAnalyticsSource,
    ) : DiagnosticsArchiveExporter {
        private val archiveMutex = Mutex()

        override suspend fun cleanupCache() = archiveMutex.withLock { reconcileCache(reservedSlots = 0) }

        override suspend fun createArchive(request: DiagnosticsArchiveRequest): DiagnosticsArchive =
            archiveMutex.withLock {
                reconcileCache(reservedSlots = 1)
                val selection = buildArchiveSelection(request)
                val target = fileStore.createTarget()
                val developerAnalytics = collectDeveloperAnalytics(selection, target)
                var exportRecordId: String? = null
                try {
                    zipWriter.write(target.file, renderer.render(target, selection, developerAnalytics))
                    val recordId = idGenerator.nextId()
                    exportRecordId = recordId
                    exportRecordStore.insertExportRecord(
                        ExportRecordEntity(
                            id = recordId,
                            sessionId = selection.primarySession?.id,
                            uri = target.file.absolutePath,
                            fileName = target.fileName,
                            createdAt = target.createdAt,
                        ),
                    )
                    reconcileCache(reservedSlots = 0)
                    DiagnosticsArchive(
                        fileName = target.fileName,
                        absolutePath = target.file.absolutePath,
                        sessionId = selection.primarySession?.id,
                        createdAt = target.createdAt,
                        scope = DiagnosticsArchiveFormat.scope,
                        schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                        privacyMode = DiagnosticsArchiveFormat.privacyMode,
                    )
                } catch (error: Throwable) {
                    runCatching { fileStore.deleteArchive(target.file) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                    exportRecordId?.let { recordId ->
                        runCatching { exportRecordStore.deleteExportRecords(listOf(recordId)) }
                            .exceptionOrNull()
                            ?.let(error::addSuppressed)
                    }
                    throw error
                }
            }

        private suspend fun reconcileCache(reservedSlots: Int) {
            var cleanupFailure: Throwable? = null
            runCatching { fileStore.cleanup(reservedSlots) }
                .exceptionOrNull()
                ?.let { cleanupFailure = it }
            runCatching { fileStore.cleanupPcapFiles() }
                .exceptionOrNull()
                ?.let { failure ->
                    cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
                }
            val records = exportRecordStore.getExportRecords()
            val existingPaths = fileStore.managedArchivePaths()
            exportRecordStore.deleteExportRecords(
                records.filterNot { it.uri in existingPaths }.map { it.id },
            )
            runCatching { fileStore.reconcileFiles(records.mapTo(mutableSetOf()) { it.uri }) }
                .exceptionOrNull()
                ?.let { failure ->
                    cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
                }
            val reconciledPaths = fileStore.managedArchivePaths()
            exportRecordStore.deleteExportRecords(
                records.filterNot { it.uri in reconciledPaths }.map { it.id },
            )
            cleanupFailure?.let { throw it }
        }

        private suspend fun buildArchiveSelection(request: DiagnosticsArchiveRequest): DiagnosticsArchiveSelection {
            val sourceData = sourceLoader.load()
            val compositeOutcome =
                request.homeRunId?.let { runId ->
                    requireNotNull(sourceLoader.getCompletedHomeRun(runId)) {
                        "Requested completed home diagnostics run is unavailable: $runId"
                    }
                }
            compositeOutcome?.stageSummaries?.let { stages ->
                val stageKeys = stages.map { it.stageKey }
                require(stageKeys.distinct().size == stageKeys.size) {
                    "Completed home diagnostics run contains duplicate stage keys"
                }
                require(stageKeys.all { it.matches(ArchiveStageKeyRegex) }) {
                    "Completed home diagnostics run contains an unsafe stage key"
                }
            }
            val compositeSessionIds =
                if (compositeOutcome != null) {
                    require(request.requestedSessionId == null) {
                        "Home diagnostics archive cannot select a caller-provided primary session"
                    }
                    val unexpectedSessionIds = request.sessionIds - compositeOutcome.bundleSessionIds.toSet()
                    require(unexpectedSessionIds.isEmpty()) {
                        "Home diagnostics archive contains session IDs outside the completed run: " +
                            unexpectedSessionIds.joinToString()
                    }
                    compositeOutcome.bundleSessionIds.distinct()
                } else {
                    emptyList()
                }
            val compositeSessions =
                if (compositeOutcome != null) {
                    sourceLoader.getScanSessions(compositeSessionIds)
                } else {
                    emptyList()
                }
            val requestedSession = request.requestedSessionId?.let { sourceLoader.getScanSession(it) }
            val primarySession =
                if (compositeOutcome != null) {
                    val recommendedId =
                        requireNotNull(compositeOutcome.recommendedSessionId) {
                            "Completed home diagnostics run has no recommended session: ${compositeOutcome.runId}"
                        }
                    require(recommendedId in compositeOutcome.bundleSessionIds) {
                        "Recommended session is outside the completed home diagnostics run: $recommendedId"
                    }
                    requireNotNull(compositeSessions.firstOrNull { it.id == recommendedId }) {
                        "Recommended home diagnostics session is unavailable: $recommendedId"
                    }
                } else {
                    sessionSelector.selectPrimarySession(
                        requestedSessionId = request.requestedSessionId,
                        requestedSession = requestedSession,
                        sessions = sourceData.sessions,
                    )
                }
            val primaryResults = primarySession?.id?.let { sourceLoader.getProbeResults(it) }.orEmpty()
            val selectedSessionIds =
                (listOfNotNull(primarySession?.id) + compositeSessions.map { it.id }).distinct()
            val selectionSourceData =
                sourceData.copy(
                    snapshots =
                        mergeArchiveArtifacts(
                            sourceData.snapshots,
                            selectedSessionIds.flatMap { sessionId -> sourceLoader.getSnapshots(sessionId) },
                        ) { it.id },
                    contexts =
                        mergeArchiveArtifacts(
                            sourceData.contexts,
                            selectedSessionIds.flatMap { sessionId -> sourceLoader.getContexts(sessionId) },
                        ) { it.id },
                )
            val selection =
                sessionSelector
                    .buildSelection(
                        request = request,
                        primarySession = primarySession,
                        primaryResults = primaryResults,
                        sourceData = selectionSourceData,
                        compositeOutcome = compositeOutcome,
                        compositeSessions = compositeSessions,
                        loadProbeResults = { sessionId -> sourceLoader.getProbeResults(sessionId) },
                        loadNativeEvents = { sessionId -> sourceLoader.getNativeEvents(sessionId) },
                        loadStageTelemetry = { session, connectionSessionIds ->
                            sourceLoader.getStageTelemetry(session, connectionSessionIds)
                        },
                    )
            val pcapFiles =
                if (request.includePcap && sourceData.appSettings.rootModeEnabled) {
                    fileStore.getRecentPcapFiles()
                } else {
                    emptyList()
                }
            val missingCompletedStageWarnings =
                selection.compositeStages
                    .filter { stage -> stage.stageSummary.status.name == "COMPLETED" && stage.session == null }
                    .map { stage -> "completed_stage_evidence_unavailable:${stage.stageSummary.stageKey}" }
            return selection.copy(
                pcapFiles = pcapFiles,
                collectionWarnings = selection.collectionWarnings + missingCompletedStageWarnings,
                includedFiles =
                    DiagnosticsArchiveFormat.includedFiles(
                        logcatIncluded = selection.logcatSnapshot != null,
                        fileLogIncluded = selection.fileLogSnapshot != null,
                        composite = selection.runType == DiagnosticsArchiveRunType.HOME_COMPOSITE,
                        compositeStageKeys = selection.compositeStages.map { it.stageSummary.stageKey },
                        replayIncluded = selection.replayResults.isNotEmpty(),
                        pcapFileNames = pcapFiles.map { it.name },
                    ),
            )
        }

        private fun <T> mergeArchiveArtifacts(
            recent: List<T>,
            selected: List<T>,
            key: (T) -> String,
        ): List<T> = (recent + selected).distinctBy(key)

        private suspend fun collectDeveloperAnalytics(
            selection: DiagnosticsArchiveSelection,
            target: DiagnosticsArchiveTarget,
        ): DeveloperAnalyticsPayload {
            val primarySession = selection.primarySession
            val analyticsContext =
                DeveloperAnalyticsContext(
                    archiveCreatedAtMs = target.createdAt,
                    archiveFileName = target.fileName,
                    homeRunId = selection.homeRunId,
                    homeCompositeOutcome = selection.homeCompositeOutcome,
                    primarySessionId = primarySession?.id,
                    primaryProfileId = primarySession?.profileId,
                    pcapFiles = selection.pcapFiles,
                    compositeSessionIds = selection.compositeStages.mapNotNull { it.session?.id },
                )
            return runCatching { developerAnalyticsSource.collect(analyticsContext) }
                .getOrDefault(
                    DeveloperAnalyticsPayload(
                        notes = listOf("Developer analytics collection failed — payload is empty."),
                    ),
                )
        }
    }

private val ArchiveStageKeyRegex = Regex("[a-z0-9][a-z0-9_]{0,63}")
