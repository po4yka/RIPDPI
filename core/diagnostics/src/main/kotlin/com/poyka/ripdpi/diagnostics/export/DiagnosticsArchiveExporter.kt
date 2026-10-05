package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticsExportRecordStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsContext
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsSource
import com.poyka.ripdpi.diagnostics.DeveloperStageProbeEvidence
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveException
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveFailureCode
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSessionSelector
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        sourceLoader: DiagnosticsArchiveSourceLoader,
        sessionSelector: DiagnosticsArchiveSessionSelector,
        private val renderer: DiagnosticsArchiveRenderer,
        private val fileStore: DiagnosticsArchiveFileStore,
        private val zipWriter: DiagnosticsArchiveZipWriter,
        private val idGenerator: DiagnosticsArchiveIdGenerator,
        private val developerAnalyticsSource: DeveloperAnalyticsSource,
        private val preparedExports: DiagnosticsPreparedExportManager,
    ) : DiagnosticsArchiveExporter,
        PreparedDiagnosticsExportService {
        private val archiveMutex = Mutex()
        private val selectionBuilder = DiagnosticsArchiveSelectionBuilder(sourceLoader, sessionSelector)

        override suspend fun prepare(
            id: DiagnosticsExportLeaseId,
            preparation: DiagnosticsExportPreparation,
        ): PreparedDiagnosticsExport =
            withContext(Dispatchers.IO) {
                archiveMutex.withLock {
                    archiveStage(DiagnosticsArchiveFailureCode.STORAGE) {
                        reconcileCache(reservedSlots = 0)
                        preparedExports.prepare(id, preparation) { target, request ->
                            val selection = selectionBuilder.build(request)
                            val analytics = collectDeveloperAnalytics(selection, target)
                            PreparedArchiveContent(
                                renderer.render(target, selection, analytics),
                                selection.primarySession?.id,
                            )
                        }
                    }
                }
            }

        override suspend fun inspect(id: DiagnosticsExportLeaseId): PreparedDiagnosticsExport =
            withContext(Dispatchers.IO) {
                archiveMutex.withLock {
                    archiveStage(
                        DiagnosticsArchiveFailureCode.STORAGE,
                    ) { preparedExports.inspect(id) }
                }
            }

        override suspend fun consume(id: DiagnosticsExportLeaseId): CheckedDiagnosticsExportHandoff =
            withContext(Dispatchers.IO) {
                archiveMutex.withLock {
                    archiveStage(
                        DiagnosticsArchiveFailureCode.STORAGE,
                    ) { preparedExports.consume(id) }
                }
            }

        override suspend fun validateHandoff(id: DiagnosticsExportLeaseId): CheckedDiagnosticsExportHandoff =
            withContext(Dispatchers.IO) {
                archiveMutex.withLock {
                    archiveStage(
                        DiagnosticsArchiveFailureCode.STORAGE,
                    ) { preparedExports.validateHandoff(id) }
                }
            }

        override suspend fun copyPrepared(
            id: DiagnosticsExportLeaseId,
            destination: OutputStream,
        ) = withContext(Dispatchers.IO) {
            archiveMutex.withLock {
                archiveStage(DiagnosticsArchiveFailureCode.IO) { preparedExports.copyPrepared(id, destination) }
            }
        }

        override suspend fun discard(id: DiagnosticsExportLeaseId) =
            withContext(NonCancellable + Dispatchers.IO) {
                archiveMutex.withLock {
                    archiveStage(
                        DiagnosticsArchiveFailureCode.STORAGE,
                    ) { preparedExports.discard(id) }
                }
            }

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
                        selectionBuilder.build(request)
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
                    selectionBuilder.build(request)
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
            runCatching { block() }.getOrElse { error ->
                when (error) {
                    is CancellationException,
                    is DiagnosticsArchiveException,
                    is Error,
                    -> throw error

                    else -> throw DiagnosticsArchiveException(failureCode, error)
                }
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
                preparedExports.cleanup()
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
                val existingPaths = fileStore.managedArchivePaths() + preparedExports.managedPaths()
                exportRecordStore.deleteExportRecords(
                    records.filterNot { it.uri in existingPaths }.map { it.id },
                )
                runCatching { fileStore.reconcileFiles(records.mapTo(mutableSetOf()) { it.uri }) }
                    .exceptionOrNull()
                    ?.let { failure ->
                        cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
                    }
                val reconciledPaths = fileStore.managedArchivePaths() + preparedExports.managedPaths()
                exportRecordStore.deleteExportRecords(
                    records.filterNot { it.uri in reconciledPaths }.map { it.id },
                )
                cleanupFailure?.let { throw it }
                Unit
            }

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
                    stageProbeEvidence =
                        selection.compositeStages.flatMap { stage ->
                            stage.results.map { result ->
                                DeveloperStageProbeEvidence(
                                    stageKey = stage.stageSummary.stageKey,
                                    probeType = result.probeType,
                                    outcome = result.outcome,
                                )
                            }
                        },
                )
            return runCatching { developerAnalyticsSource.collect(analyticsContext) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    DeveloperAnalyticsPayload(
                        notes = listOf("Developer analytics collection failed — payload is empty."),
                    )
                }
        }
    }
