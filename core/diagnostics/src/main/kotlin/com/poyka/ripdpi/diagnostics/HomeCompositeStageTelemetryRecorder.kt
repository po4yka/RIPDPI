package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class HomeCompositeStageTelemetryRecorder
    private constructor(
        private val insertSample: suspend (TelemetrySampleEntity) -> Unit,
        private val serviceStateStore: ServiceStateStore?,
    ) {
        @Inject
        constructor(
            artifactWriteStore: DiagnosticsArtifactWriteStore,
            serviceStateStore: ServiceStateStore,
        ) : this(
            insertSample = artifactWriteStore::insertTelemetrySample,
            serviceStateStore = serviceStateStore,
        )

        suspend fun record(
            runId: String,
            stageKey: String,
            sessionId: String?,
            state: DiagnosticsHomeCompositeStageStatus,
            createdAt: Long = System.currentTimeMillis(),
        ) {
            val stateStore = serviceStateStore ?: return
            val telemetry = stateStore.telemetry.value
            insertSample(
                telemetry.toTelemetrySampleEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    diagnosticsRunId = runId,
                    diagnosticsStageKey = stageKey,
                    activeModeFallback = stateStore.status.value.second.name,
                    connectionStateOverride = state.name,
                    networkType = "unknown",
                    publicIp = null,
                    createdAt = createdAt,
                ),
            )
        }

        companion object {
            fun noOp(): HomeCompositeStageTelemetryRecorder =
                HomeCompositeStageTelemetryRecorder(
                    insertSample = {},
                    serviceStateStore = null,
                )
        }
    }
