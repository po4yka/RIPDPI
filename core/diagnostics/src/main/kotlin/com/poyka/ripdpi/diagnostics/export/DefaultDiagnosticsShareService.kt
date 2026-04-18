package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactQueryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.ShareSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DefaultDiagnosticsShareService
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactReadStore: DiagnosticsArtifactReadStore,
        private val artifactQueryStore: DiagnosticsArtifactQueryStore,
        private val archiveExporter: DiagnosticsArchiveExporter,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) : DiagnosticsShareService {
        override suspend fun buildShareSummary(sessionId: String?): ShareSummary =
            withContext(Dispatchers.IO) {
                DiagnosticsShareSummaryBuilder.build(
                    sessionId = sessionId,
                    scanRecordStore = scanRecordStore,
                    artifactReadStore = artifactReadStore,
                    artifactQueryStore = artifactQueryStore,
                    json = json,
                )
            }

        override suspend fun createArchive(request: DiagnosticsArchiveRequest): com.poyka.ripdpi.diagnostics.DiagnosticsArchive =
            withContext(Dispatchers.IO) { archiveExporter.createArchive(request) }
    }
