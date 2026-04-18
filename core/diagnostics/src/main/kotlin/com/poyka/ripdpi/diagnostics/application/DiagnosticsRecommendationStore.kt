package com.poyka.ripdpi.diagnostics.application

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.DiagnosticsSessionQueries
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DiagnosticsRecommendationStore
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        suspend fun loadResolverRecommendation(sessionId: String): ResolverRecommendation? =
            scanRecordStore
                .getScanSession(sessionId)
                ?.reportJson
                ?.let { DiagnosticsSessionQueries.decodeScanReport(json, it) }
                ?.resolverRecommendation
    }
