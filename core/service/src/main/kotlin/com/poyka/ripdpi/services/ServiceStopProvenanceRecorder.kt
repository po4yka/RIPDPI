package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal enum class ServiceStopProvenance(
    val reason: String,
    val initiator: String,
) {
    UserRequest(reason = "user_requested", initiator = "app_ui"),
    NotificationAction(reason = "user_requested", initiator = "notification_action"),
    DiagnosticsRawPathScan(reason = "diagnostics_raw_path_scan", initiator = "diagnostics_runtime"),
    DiagnosticsCompensation(reason = "diagnostics_compensation", initiator = "diagnostics_runtime"),
}

internal fun interface ServiceStopProvenanceRecorder {
    suspend fun record(
        mode: Mode,
        provenance: ServiceStopProvenance,
    )
}

@Singleton
internal class RoomServiceStopProvenanceRecorder
    @Inject
    constructor(
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        private val clock: AndroidRuntimeEvidenceClock,
    ) : ServiceStopProvenanceRecorder {
        override suspend fun record(
            mode: Mode,
            provenance: ServiceStopProvenance,
        ) {
            artifactWriteStore.insertNativeSessionEvent(
                NativeSessionEventEntity(
                    id = "service-stop-${UUID.randomUUID()}",
                    source = ServiceStopEventSource,
                    level = "info",
                    message =
                        "event=service_stop_requested reason=${provenance.reason} " +
                            "initiator=${provenance.initiator}",
                    createdAt = clock.now(),
                    mode = mode.preferenceValue,
                    subsystem = ServiceLifecycleSubsystem,
                ),
            )
        }
    }

private const val ServiceStopEventSource = "android_service_control"
private const val ServiceLifecycleSubsystem = "service_lifecycle"
