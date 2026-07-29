package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsContext
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsSource
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSessionSelector
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSourceLoader
import javax.inject.Inject
import javax.inject.Singleton

fun interface DiagnosticsArchiveIdGenerator {
    fun nextId(): String
}

interface DiagnosticsArchiveExporter {
    fun cleanupCache()

    suspend fun createArchive(request: DiagnosticsArchiveRequest): DiagnosticsArchive
}

@Singleton
internal class DefaultDiagnosticsArchiveExporter
    @Inject
    constructor(
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        private val sourceLoader: DiagnosticsArchiveSourceLoader,
        private val sessionSelector: DiagnosticsArchiveSessionSelector,
        private val renderer: DiagnosticsArchiveRenderer,
        private val fileStore: DiagnosticsArchiveFileStore,
        private val zipWriter: DiagnosticsArchiveZipWriter,
        private val idGenerator: DiagnosticsArchiveIdGenerator,
        private val developerAnalyticsSource: DeveloperAnalyticsSource,
    ) : DiagnosticsArchiveExporter {
        override fun cleanupCache() {
            fileStore.cleanup()
            fileStore.cleanupPcapFiles()
        }

        override suspend fun createArchive(request: DiagnosticsArchiveRequest): DiagnosticsArchive {
            fileStore.cleanup()
            val selection = buildArchiveSelection(request)
            val target = fileStore.createTarget()
            val developerAnalytics = collectDeveloperAnalytics(selection, target)
            zipWriter.write(target.file, renderer.render(target, selection, developerAnalytics))
            artifactWriteStore.insertExportRecord(
                ExportRecordEntity(
                    id = idGenerator.nextId(),
                    sessionId = selection.primarySession?.id,
                    uri = target.file.absolutePath,
                    fileName = target.fileName,
                    createdAt = target.createdAt,
                ),
            )
            return DiagnosticsArchive(
                fileName = target.fileName,
                absolutePath = target.file.absolutePath,
                sessionId = selection.primarySession?.id,
                createdAt = target.createdAt,
                scope = DiagnosticsArchiveFormat.scope,
                schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                privacyMode = DiagnosticsArchiveFormat.privacyMode,
            )
        }

        private suspend fun buildArchiveSelection(request: DiagnosticsArchiveRequest): DiagnosticsArchiveSelection {
            val sourceData = sourceLoader.load()
            val compositeOutcome =
                request.homeRunId
                    ?.takeIf { request.sessionIds.isNotEmpty() }
                    ?.let { runId -> sourceLoader.getCompletedHomeRun(runId) }
            val compositeSessions =
                if (request.homeRunId != null && request.sessionIds.isNotEmpty()) {
                    sourceLoader.getScanSessions(request.sessionIds)
                } else {
                    emptyList()
                }
            val requestedSession = request.requestedSessionId?.let { sourceLoader.getScanSession(it) }
            val primarySession =
                if (compositeOutcome != null) {
                    compositeOutcome.recommendedSessionId
                        ?.let { recommendedId -> compositeSessions.firstOrNull { it.id == recommendedId } }
                        ?: compositeSessions.firstOrNull()
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
                    )
            val pcapFiles =
                if (request.includePcap && sourceData.appSettings.rootModeEnabled) {
                    fileStore.getRecentPcapFiles()
                } else {
                    emptyList()
                }
            return selection.copy(
                pcapFiles = pcapFiles,
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
