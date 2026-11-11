package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.diagnostics.application.PreparedDiagnosticsScan
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DiagnosticsPostScanRecorder
    @Inject
    constructor(
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        private val networkMetadataProvider: NetworkMetadataProvider,
        private val diagnosticsContextProvider: DiagnosticsContextProvider,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal suspend fun persist(prepared: PreparedDiagnosticsScan) {
            val now = System.currentTimeMillis()
            artifactWriteStore.upsertSnapshot(
                NetworkSnapshotEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = prepared.sessionId,
                    snapshotKind = "post_scan",
                    payloadJson =
                        json.encodeToString(
                            NetworkSnapshotModel.serializer(),
                            networkMetadataProvider.captureSnapshot(
                                includePublicIp = prepared.includePublicIpInSnapshots,
                            ),
                        ),
                    capturedAt = now,
                ),
            )
            artifactWriteStore.upsertContextSnapshot(
                DiagnosticContextEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = prepared.sessionId,
                    contextKind = "post_scan",
                    payloadJson =
                        json.encodeToString(
                            DiagnosticContextModel.serializer(),
                            diagnosticsContextProvider.captureContext(),
                        ),
                    capturedAt = now,
                ),
            )
        }
    }
