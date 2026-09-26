@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named

internal class HiddenProbeConflictRequestFactory
    @Inject
    constructor(
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private var requestIdGenerator: () -> String = { UUID.randomUUID().toString() }

        internal constructor(
            json: Json,
            requestIdGenerator: () -> String,
        ) : this(json) {
            this.requestIdGenerator = requestIdGenerator
        }

        private companion object {
            const val StrategyProbeSuiteFullMatrixV1 = "full_matrix_v1"
        }

        fun create(
            profile: DiagnosticProfileEntity,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
            scanDeadlineMs: Long? = null,
            maxCandidates: Int? = null,
            targetOverrides: DiagnosticsScanTargetOverrides? = null,
            ownerId: String? = null,
            resumeRuntimeAfterRawPath: Boolean = false,
        ): PendingHiddenConflictRequest {
            val projection = json.decodeProfileSpecWire(profile.requestJson).toProfileProjection()
            return PendingHiddenConflictRequest(
                requestId = requestIdGenerator(),
                profile = profile,
                settings = settings,
                pathMode = pathMode,
                scanDeadlineMs = scanDeadlineMs,
                maxCandidates = maxCandidates,
                targetOverrides =
                    targetOverrides?.copy(
                        domainTargets = targetOverrides.domainTargets?.toList(),
                        serviceTargets = targetOverrides.serviceTargets?.toList(),
                        circumventionTargets = targetOverrides.circumventionTargets?.toList(),
                    ),
                ownerId = ownerId,
                resumeRuntimeAfterRawPath = resumeRuntimeAfterRawPath,
                profileName = profile.name,
                scanKind = projection.kind,
                isFullAudit = projection.strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1,
            )
        }
    }

internal suspend fun DiagnosticsRuntimeCoordinator.runManualRawPath(
    block: suspend () -> Unit,
    resumeRuntimeAfterRawPath: Boolean,
): RawPathExecutionResult =
    if (resumeRuntimeAfterRawPath) {
        runAutomaticRawPathScan(block)
    } else {
        runRawPathScan(block)
    }

internal fun PendingHiddenConflictRequest.rawPathRunner(
    runtimeCoordinator: DiagnosticsRuntimeCoordinator,
): suspend (suspend () -> Unit) -> RawPathExecutionResult =
    { block ->
        runtimeCoordinator.runManualRawPath(block, resumeRuntimeAfterRawPath)
    }

internal data class PendingHiddenConflictRequest(
    val requestId: String,
    val profile: DiagnosticProfileEntity,
    val settings: com.poyka.ripdpi.proto.AppSettings,
    val pathMode: ScanPathMode,
    val scanDeadlineMs: Long?,
    val maxCandidates: Int?,
    val targetOverrides: DiagnosticsScanTargetOverrides?,
    val ownerId: String?,
    val resumeRuntimeAfterRawPath: Boolean,
    val profileName: String,
    val scanKind: ScanKind,
    val isFullAudit: Boolean,
)

internal fun PendingHiddenConflictRequest.toConflictResult():
    DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution =
    DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution(
        requestId = requestId,
        profileName = profileName,
        pathMode = pathMode,
        scanKind = scanKind,
        isFullAudit = isFullAudit,
    )
