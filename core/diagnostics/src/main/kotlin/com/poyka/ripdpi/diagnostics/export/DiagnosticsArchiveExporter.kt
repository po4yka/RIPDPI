package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticsExportRecordStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsContext
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsSource
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveException
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveFailureCode
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSessionSelector
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSourceLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

fun interface DiagnosticsArchiveIdGenerator {
    fun nextId(): String
}

interface DiagnosticsArchiveExporter {
    suspend fun cleanupCache()

    suspend fun createArchive(request: DiagnosticsArchiveRequest): DiagnosticsArchive

    suspend fun writeArchive(
        request: DiagnosticsArchiveRequest,
        destination: OutputStream,
    ): Unit = error("Direct archive export is unavailable")
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
                require(!request.includePcap) {
                    "PCAP cannot be embedded in a redacted diagnostics archive; export it as a separate raw artifact"
                }
                archiveStage(DiagnosticsArchiveFailureCode.STORAGE) {
                    reconcileCache(reservedSlots = 1)
                }
                val selection =
                    archiveStage(DiagnosticsArchiveFailureCode.INCONSISTENT_RESULT) {
                        buildArchiveSelection(request)
                    }
                val target = archiveStage(DiagnosticsArchiveFailureCode.STORAGE) { fileStore.createTarget() }
                val developerAnalytics = collectDeveloperAnalytics(selection, target)
                var exportRecordId: String? = null
                var archiveWritten = false
                runCatching {
                    archiveStage(DiagnosticsArchiveFailureCode.IO) {
                        zipWriter.write(target.file, renderer.render(target, selection, developerAnalytics))
                    }
                    archiveWritten = true
                    val recordId = idGenerator.nextId()
                    exportRecordId = recordId
                    archiveStage(DiagnosticsArchiveFailureCode.DATABASE) {
                        exportRecordStore.insertExportRecord(
                            target.toExportRecord(
                                recordId = recordId,
                                sessionId = selection.primarySession?.id,
                            ),
                        )
                    }
                    archiveStage(DiagnosticsArchiveFailureCode.STORAGE) {
                        reconcileCache(reservedSlots = 0)
                    }
                    target.toArchive(selection.primarySession?.id)
                }.getOrElse { error ->
                    rollbackArchive(target, exportRecordId, archiveWritten, error)
                }
            }

        override suspend fun writeArchive(
            request: DiagnosticsArchiveRequest,
            destination: OutputStream,
        ) = archiveMutex.withLock {
            require(!request.includePcap) {
                "PCAP cannot be embedded in a redacted diagnostics archive; export it as a separate raw artifact"
            }
            val target = archiveStage(DiagnosticsArchiveFailureCode.STORAGE) { fileStore.createTarget() }
            val selection =
                archiveStage(DiagnosticsArchiveFailureCode.INCONSISTENT_RESULT) {
                    buildArchiveSelection(request)
                }
            val developerAnalytics = collectDeveloperAnalytics(selection, target)
            archiveStage(DiagnosticsArchiveFailureCode.IO) {
                zipWriter.write(destination, renderer.render(target, selection, developerAnalytics))
            }
        }

        private suspend fun <T> archiveStage(
            failureCode: DiagnosticsArchiveFailureCode,
            block: suspend () -> T,
        ): T =
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: DiagnosticsArchiveException) {
                throw error
            } catch (error: Error) {
                throw error
            } catch (error: Throwable) {
                throw DiagnosticsArchiveException(failureCode, error)
            }

        private fun DiagnosticsArchiveTarget.toExportRecord(
            recordId: String,
            sessionId: String?,
        ): ExportRecordEntity =
            ExportRecordEntity(
                id = recordId,
                sessionId = sessionId,
                uri = file.absolutePath,
                fileName = fileName,
                createdAt = createdAt,
            )

        private fun DiagnosticsArchiveTarget.toArchive(sessionId: String?): DiagnosticsArchive =
            DiagnosticsArchive(
                fileName = fileName,
                absolutePath = file.absolutePath,
                sessionId = sessionId,
                createdAt = createdAt,
                scope = DiagnosticsArchiveFormat.scope,
                schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                privacyMode = DiagnosticsArchiveFormat.privacyMode,
            )

        private suspend fun rollbackArchive(
            target: DiagnosticsArchiveTarget,
            exportRecordId: String?,
            archiveWritten: Boolean,
            error: Throwable,
        ): Nothing =
            withContext(NonCancellable) {
                val preservesWrittenArchive =
                    (error as? DiagnosticsArchiveException)?.failureCode == DiagnosticsArchiveFailureCode.DATABASE
                if (archiveWritten && !preservesWrittenArchive) {
                    runCatching { fileStore.deleteArchive(target.file) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                }
                exportRecordId?.let { recordId ->
                    runCatching { exportRecordStore.deleteExportRecords(listOf(recordId)) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                }
                throw error
            }

        private suspend fun reconcileCache(reservedSlots: Int) =
            withContext(NonCancellable) {
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
                Unit
            }

        private suspend fun buildArchiveSelection(request: DiagnosticsArchiveRequest): DiagnosticsArchiveSelection {
            val sourceData = sourceLoader.load()
            val compositeOutcome = loadCompositeOutcome(request)
            val compositeSessions = loadCompositeSessions(request, compositeOutcome)
            val primarySession = selectPrimarySession(request, sourceData, compositeOutcome, compositeSessions)
            val primaryResults = primarySession?.id?.let { sourceLoader.getProbeResults(it) }.orEmpty()
            val selectedSessionIds =
                (listOfNotNull(primarySession?.id) + compositeSessions.map { it.id }).distinct()
            val selectionSourceData = hydrateSelectedArtifacts(sourceData, selectedSessionIds)
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
            return finalizeSelection(selection)
        }

        private suspend fun loadCompositeOutcome(
            request: DiagnosticsArchiveRequest,
        ): DiagnosticsHomeCompositeOutcome? =
            request.homeRunId?.let { runId ->
                requireNotNull(sourceLoader.getCompletedHomeRun(runId)) {
                    "Requested completed home diagnostics run is unavailable: $runId"
                }.also(::validateCompositeStageKeys)
            }

        private fun validateCompositeStageKeys(outcome: DiagnosticsHomeCompositeOutcome) {
            val stageKeys = outcome.stageSummaries.map { it.stageKey }
            require(stageKeys.distinct().size == stageKeys.size) {
                "Completed home diagnostics run contains duplicate stage keys"
            }
            require(stageKeys.all { it.matches(ArchiveStageKeyRegex) }) {
                "Completed home diagnostics run contains an unsafe stage key"
            }
        }

        private suspend fun loadCompositeSessions(
            request: DiagnosticsArchiveRequest,
            outcome: DiagnosticsHomeCompositeOutcome?,
        ): List<ScanSessionEntity> {
            if (outcome == null) return emptyList()
            require(request.requestedSessionId == null) {
                "Home diagnostics archive cannot select a caller-provided primary session"
            }
            val unexpectedSessionIds = request.sessionIds - outcome.bundleSessionIds.toSet()
            require(unexpectedSessionIds.isEmpty()) {
                "Home diagnostics archive contains session IDs outside the completed run: " +
                    unexpectedSessionIds.joinToString()
            }
            return sourceLoader.getScanSessions(outcome.bundleSessionIds.distinct())
        }

        private suspend fun selectPrimarySession(
            request: DiagnosticsArchiveRequest,
            sourceData: DiagnosticsArchiveSourceData,
            outcome: DiagnosticsHomeCompositeOutcome?,
            compositeSessions: List<ScanSessionEntity>,
        ): ScanSessionEntity? {
            if (outcome == null) {
                return sessionSelector.selectPrimarySession(
                    requestedSessionId = request.requestedSessionId,
                    requestedSession = request.requestedSessionId?.let { sourceLoader.getScanSession(it) },
                    sessions = sourceData.sessions,
                )
            }
            val recommendedSessionId = outcome.recommendedSessionId
            require(recommendedSessionId != null || !outcome.actionable) {
                "Actionable home diagnostics run has no recommended session: ${outcome.runId}"
            }
            return if (recommendedSessionId != null) {
                require(
                    outcome.stageSummaries.any { stage ->
                        stage.sessionId == recommendedSessionId &&
                            stage.status == DiagnosticsHomeCompositeStageStatus.COMPLETED
                    },
                ) {
                    "Recommended session does not belong to a completed home diagnostics stage: $recommendedSessionId"
                }
                require(recommendedSessionId in outcome.bundleSessionIds) {
                    "Primary session is outside the completed home diagnostics run: $recommendedSessionId"
                }
                requireNotNull(compositeSessions.firstOrNull { it.id == recommendedSessionId }) {
                    "Primary home diagnostics session is unavailable: $recommendedSessionId"
                }
            } else {
                outcome.stageSummaries
                    .asSequence()
                    .filter { stage -> stage.status == DiagnosticsHomeCompositeStageStatus.COMPLETED }
                    .mapNotNull { stage -> stage.sessionId }
                    .filter { sessionId -> sessionId in outcome.bundleSessionIds }
                    .mapNotNull { sessionId -> compositeSessions.firstOrNull { it.id == sessionId } }
                    .firstOrNull()
            }
        }

        private suspend fun hydrateSelectedArtifacts(
            sourceData: DiagnosticsArchiveSourceData,
            selectedSessionIds: List<String>,
        ): DiagnosticsArchiveSourceData =
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

        private fun finalizeSelection(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveSelection {
            val missingCompletedStageWarnings =
                selection.compositeStages
                    .filter { stage -> stage.stageSummary.status.name == "COMPLETED" && stage.session == null }
                    .map { stage -> "completed_stage_evidence_unavailable:${stage.stageSummary.stageKey}" }
            return selection.copy(
                pcapFiles = emptyList(),
                collectionWarnings = selection.collectionWarnings + missingCompletedStageWarnings,
                includedFiles =
                    DiagnosticsArchiveFormat.includedFiles(
                        logcatIncluded = selection.logcatSnapshot != null,
                        fileLogIncluded = selection.fileLogSnapshot != null,
                        composite = selection.runType == DiagnosticsArchiveRunType.HOME_COMPOSITE,
                        compositeStageKeys = selection.compositeStages.map { it.stageSummary.stageKey },
                        replayIncluded = selection.replayResults.isNotEmpty(),
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
