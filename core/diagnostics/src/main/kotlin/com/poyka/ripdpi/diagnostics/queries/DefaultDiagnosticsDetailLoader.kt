package com.poyka.ripdpi.diagnostics.queries

import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactQueryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticsBoundaryMapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsSessionQueries
import com.poyka.ripdpi.diagnostics.detailValue
import com.poyka.ripdpi.diagnostics.inferEdgeHost
import com.poyka.ripdpi.diagnostics.summarizeCapabilityEvidence
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DefaultDiagnosticsDetailLoader
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactQueryStore: DiagnosticsArtifactQueryStore,
        private val bypassUsageHistoryStore: BypassUsageHistoryStore,
        private val serverCapabilityStore: ServerCapabilityStore,
        private val mapper: DiagnosticsBoundaryMapper,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) : DiagnosticsDetailLoader {
        constructor(
            scanRecordStore: DiagnosticsScanRecordStore,
            artifactQueryStore: DiagnosticsArtifactQueryStore,
            bypassUsageHistoryStore: BypassUsageHistoryStore,
            serverCapabilityStore: ServerCapabilityStore,
            json: Json,
        ) : this(
            scanRecordStore = scanRecordStore,
            artifactQueryStore = artifactQueryStore,
            bypassUsageHistoryStore = bypassUsageHistoryStore,
            serverCapabilityStore = serverCapabilityStore,
            mapper = DiagnosticsBoundaryMapper(json),
            json = json,
        )

        override suspend fun loadSessionDetail(sessionId: String): DiagnosticSessionDetail {
            val detail =
                DiagnosticsSessionQueries.loadSessionDetail(
                    sessionId,
                    scanRecordStore,
                    artifactQueryStore,
                    mapper,
                )
            val fingerprintHash = detail.resolveFingerprintHash()
            val evidence =
                if (fingerprintHash != null) {
                    val records = serverCapabilityStore.directPathCapabilitiesForFingerprint(fingerprintHash)
                    summarizeCapabilityEvidence(
                        records = records,
                        relevantAuthorities = detail.relevantCapabilityAuthorities(),
                    )
                } else {
                    emptyList()
                }
            return detail.copy(capabilityEvidence = evidence)
        }

        override suspend fun loadApproachDetail(
            kind: BypassApproachKind,
            id: String,
        ): BypassApproachDetail =
            DiagnosticsSessionQueries.loadApproachDetail(
                kind = kind,
                id = id,
                scanRecordStore = scanRecordStore,
                bypassUsageHistoryStore = bypassUsageHistoryStore,
                mapper = mapper,
                json = json,
            )
    }

private fun DiagnosticSessionDetail.resolveFingerprintHash(): String? =
    session.launchTrigger?.currentFingerprintHash
        ?: events.lastOrNull { !it.fingerprintHash.isNullOrBlank() }?.fingerprintHash

private fun DiagnosticSessionDetail.relevantCapabilityAuthorities(): Set<String> =
    results
        .mapNotNull { result ->
            result.detailValue("targetHost")
                ?: result.detailValue("handshakeHost")
                ?: result.detailValue("quicHost")
                ?: result.inferEdgeHost()
        }.toSet()
