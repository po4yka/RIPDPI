package com.poyka.ripdpi.diagnostics.application

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.DiagnosticsSessionQueries
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
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
        /** The session's recommendation, only when the session was measured on [networkScopeKey]. */
        suspend fun loadResolverRecommendation(
            sessionId: String,
            networkScopeKey: String,
        ): ResolverRecommendation? =
            scanRecordStore
                .getScanSession(sessionId)
                ?.takeIf { it.networkScopeKey == networkScopeKey }
                ?.reportJson
                ?.let { DiagnosticsSessionQueries.decodeScanReport(json, it) }
                ?.resolverRecommendation
    }
