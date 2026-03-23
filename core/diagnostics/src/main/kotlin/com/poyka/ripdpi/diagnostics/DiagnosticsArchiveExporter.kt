package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import javax.inject.Inject
import javax.inject.Singleton

fun interface DiagnosticsArchiveIdGenerator {
    fun nextId(): String
}

interface DiagnosticsArchiveExporter {
    fun cleanupCache()

    suspend fun createArchive(sessionId: String?): DiagnosticsArchive
}

@Singleton
class DefaultDiagnosticsArchiveExporter
    @Inject
    constructor(
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        private val sourceLoader: DiagnosticsArchiveSourceLoader,
        private val sessionSelector: DiagnosticsArchiveSessionSelector,
        private val renderer: DiagnosticsArchiveRenderer,
        private val fileStore: DiagnosticsArchiveFileStore,
        private val zipWriter: DiagnosticsArchiveZipWriter,
        private val idGenerator: DiagnosticsArchiveIdGenerator,
    ) : DiagnosticsArchiveExporter {
        override fun cleanupCache() {
            fileStore.cleanup()
        }

        override suspend fun createArchive(sessionId: String?): DiagnosticsArchive {
            fileStore.cleanup()
            val sourceData = sourceLoader.load()
            val requestedSession = sessionId?.let { sourceLoader.getScanSession(it) }
            val primarySession =
                sessionSelector.selectPrimarySession(
                    requestedSessionId = sessionId,
                    requestedSession = requestedSession,
                    sessions = sourceData.sessions,
                )
            val primaryResults = primarySession?.id?.let { sourceLoader.getProbeResults(it) }.orEmpty()
            val selection =
                sessionSelector.buildSelection(
                    primarySession = primarySession,
                    primaryResults = primaryResults,
                    sourceData = sourceData,
                )
            val target = fileStore.createTarget()
            zipWriter.write(target.file, renderer.render(target, selection))
            artifactWriteStore.insertExportRecord(
                ExportRecordEntity(
                    id = idGenerator.nextId(),
                    sessionId = primarySession?.id,
                    uri = target.file.absolutePath,
                    fileName = target.fileName,
                    createdAt = target.createdAt,
                ),
            )
            return DiagnosticsArchive(
                fileName = target.fileName,
                absolutePath = target.file.absolutePath,
                sessionId = primarySession?.id,
                createdAt = target.createdAt,
                scope = DiagnosticsArchiveFormat.scope,
                schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                privacyMode = DiagnosticsArchiveFormat.privacyMode,
            )
        }
    }
