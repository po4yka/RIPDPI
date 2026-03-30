package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.VpnDnsPolicyJson
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.decodedSource
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DiagnosticsBoundaryMapper
    @Inject
    constructor(
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        fun toDiagnosticProfile(entity: DiagnosticProfileEntity): DiagnosticProfile =
            DiagnosticProfile(
                id = entity.id,
                name = entity.name,
                source = entity.source,
                version = entity.version,
                request = decodeProfileProjection(json, entity.requestJson),
                updatedAt = entity.updatedAt,
            )

        fun toDiagnosticScanSession(entity: ScanSessionEntity): DiagnosticScanSession =
            DiagnosticScanSession(
                id = entity.id,
                profileId = entity.profileId,
                approachProfileId = entity.approachProfileId,
                approachProfileName = entity.approachProfileName,
                strategyId = entity.strategyId,
                strategyLabel = entity.strategyLabel,
                strategySignature = decodeStrategySignature(json, entity.strategyJson),
                pathMode = entity.pathMode,
                serviceMode = entity.serviceMode,
                status = entity.status,
                summary = entity.summary,
                report = decodeScanProjection(json, entity.reportJson),
                startedAt = entity.startedAt,
                finishedAt = entity.finishedAt,
                launchOrigin = DiagnosticsScanLaunchOrigin.fromStorageValue(entity.launchOrigin),
                launchTrigger =
                    DiagnosticsScanLaunchTrigger.fromStorage(
                        type = entity.triggerType,
                        classification = entity.triggerClassification,
                        occurredAt = entity.triggerOccurredAt,
                        previousFingerprintHash = entity.triggerPreviousFingerprintHash,
                        currentFingerprintHash = entity.triggerCurrentFingerprintHash,
                    ),
            )

        fun toProbeResult(entity: ProbeResultEntity): ProbeResult {
            val details = decodeProbeDetails(json, entity.detailJson)
            return ProbeResult(
                probeType = entity.probeType,
                target = entity.target,
                outcome = entity.outcome,
                details = details,
                probeRetryCount = deriveProbeRetryCount(details),
            )
        }

        fun toDiagnosticNetworkSnapshot(entity: NetworkSnapshotEntity): DiagnosticNetworkSnapshot =
            DiagnosticNetworkSnapshot(
                id = entity.id,
                sessionId = entity.sessionId,
                connectionSessionId = entity.connectionSessionId,
                snapshotKind = entity.snapshotKind,
                snapshot = decodeNetworkSnapshot(json, entity.payloadJson),
                capturedAt = entity.capturedAt,
            )

        fun toDiagnosticContextSnapshot(entity: DiagnosticContextEntity): DiagnosticContextSnapshot =
            DiagnosticContextSnapshot(
                id = entity.id,
                sessionId = entity.sessionId,
                connectionSessionId = entity.connectionSessionId,
                contextKind = entity.contextKind,
                context = decodeContext(json, entity.payloadJson),
                capturedAt = entity.capturedAt,
            )

        fun toDiagnosticTelemetrySample(entity: TelemetrySampleEntity): DiagnosticTelemetrySample =
            DiagnosticTelemetrySample(
                id = entity.id,
                sessionId = entity.sessionId,
                connectionSessionId = entity.connectionSessionId,
                activeMode = entity.activeMode,
                connectionState = entity.connectionState,
                networkType = entity.networkType,
                publicIp = entity.publicIp,
                failureClass = entity.failureClass,
                telemetryNetworkFingerprintHash = entity.telemetryNetworkFingerprintHash,
                winningTcpStrategyFamily = entity.winningTcpStrategyFamily,
                winningQuicStrategyFamily = entity.winningQuicStrategyFamily,
                proxyRttBand = entity.proxyRttBand,
                resolverRttBand = entity.resolverRttBand,
                proxyRouteRetryCount = entity.proxyRouteRetryCount,
                tunnelRecoveryRetryCount = entity.tunnelRecoveryRetryCount,
                resolverId = entity.resolverId,
                resolverProtocol = entity.resolverProtocol,
                resolverEndpoint = entity.resolverEndpoint,
                resolverLatencyMs = entity.resolverLatencyMs,
                dnsFailuresTotal = entity.dnsFailuresTotal,
                resolverFallbackActive = entity.resolverFallbackActive,
                resolverFallbackReason = entity.resolverFallbackReason,
                networkHandoverClass = entity.networkHandoverClass,
                lastFailureClass = entity.lastFailureClass,
                lastFallbackAction = entity.lastFallbackAction,
                txPackets = entity.txPackets,
                txBytes = entity.txBytes,
                rxPackets = entity.rxPackets,
                rxBytes = entity.rxBytes,
                createdAt = entity.createdAt,
            )

        fun toDiagnosticEvent(entity: NativeSessionEventEntity): DiagnosticEvent =
            DiagnosticEvent(
                id = entity.id,
                sessionId = entity.sessionId,
                connectionSessionId = entity.connectionSessionId,
                source = entity.source,
                level = entity.level,
                message = entity.message,
                createdAt = entity.createdAt,
                runtimeId = entity.runtimeId,
                mode = entity.mode,
                policySignature = entity.policySignature,
                fingerprintHash = entity.fingerprintHash,
                subsystem = entity.subsystem,
            )

        fun toDiagnosticExportRecord(entity: ExportRecordEntity): DiagnosticExportRecord =
            DiagnosticExportRecord(
                id = entity.id,
                sessionId = entity.sessionId,
                uri = entity.uri,
                fileName = entity.fileName,
                createdAt = entity.createdAt,
            )

        fun toDiagnosticConnectionSession(entity: BypassUsageSessionEntity): DiagnosticConnectionSession =
            DiagnosticConnectionSession(
                id = entity.id,
                startedAt = entity.startedAt,
                finishedAt = entity.finishedAt,
                updatedAt = entity.updatedAt,
                serviceMode = entity.serviceMode,
                connectionState = entity.connectionState,
                health = entity.health,
                approachProfileId = entity.approachProfileId,
                approachProfileName = entity.approachProfileName,
                strategyId = entity.strategyId,
                strategyLabel = entity.strategyLabel,
                strategySignature = decodeStrategySignature(json, entity.strategyJson),
                networkType = entity.networkType,
                publicIp = entity.publicIp,
                failureClass = entity.failureClass,
                telemetryNetworkFingerprintHash = entity.telemetryNetworkFingerprintHash,
                winningTcpStrategyFamily = entity.winningTcpStrategyFamily,
                winningQuicStrategyFamily = entity.winningQuicStrategyFamily,
                proxyRttBand = entity.proxyRttBand,
                resolverRttBand = entity.resolverRttBand,
                proxyRouteRetryCount = entity.proxyRouteRetryCount,
                tunnelRecoveryRetryCount = entity.tunnelRecoveryRetryCount,
                txBytes = entity.txBytes,
                rxBytes = entity.rxBytes,
                totalErrors = entity.totalErrors,
                routeChanges = entity.routeChanges,
                restartCount = entity.restartCount,
                endedReason = entity.endedReason,
                failureMessage = entity.failureMessage,
                rememberedPolicyAudit =
                    entity.toRememberedPolicyApplicationAuditOrNull(),
            )

        fun toDiagnosticsRememberedPolicy(entity: RememberedNetworkPolicyEntity): DiagnosticsRememberedPolicy =
            DiagnosticsRememberedPolicy(
                id = entity.id,
                fingerprintHash = entity.fingerprintHash,
                mode = entity.mode,
                summary = decodeNetworkFingerprintSummary(json, entity.summaryJson),
                proxyConfigJson = entity.proxyConfigJson,
                vpnDnsPolicy = decodeVpnDnsPolicy(json, entity.vpnDnsPolicyJson),
                strategySignature = decodeStrategySignature(json, entity.strategySignatureJson),
                winningTcpStrategyFamily = entity.winningTcpStrategyFamily,
                winningQuicStrategyFamily = entity.winningQuicStrategyFamily,
                source = entity.decodedSource(),
                status = entity.status,
                successCount = entity.successCount,
                failureCount = entity.failureCount,
                consecutiveFailureCount = entity.consecutiveFailureCount,
                firstObservedAt = entity.firstObservedAt,
                lastValidatedAt = entity.lastValidatedAt,
                lastAppliedAt = entity.lastAppliedAt,
                suppressedUntil = entity.suppressedUntil,
                updatedAt = entity.updatedAt,
            )

        fun toDiagnosticActiveConnectionPolicy(policy: ActiveConnectionPolicy): DiagnosticActiveConnectionPolicy =
            DiagnosticActiveConnectionPolicy(
                mode = policy.mode,
                policy = policy.policy,
                matchedPolicy = policy.matchedPolicy?.let(::toDiagnosticsRememberedPolicy),
                usedRememberedPolicy = policy.usedRememberedPolicy,
                rememberedPolicyAppliedByExactMatch = policy.rememberedPolicyAppliedByExactMatch,
                fingerprintHash = policy.fingerprintHash,
                policySignature = policy.policySignature,
                appliedAt = policy.appliedAt,
                restartReason = policy.restartReason,
                handoverClassification = policy.handoverClassification,
            )
    }

private fun BypassUsageSessionEntity.toRememberedPolicyApplicationAuditOrNull(): RememberedPolicyApplicationAudit? {
    if (rememberedPolicyMatchedFingerprintHash == null &&
        rememberedPolicySource == null &&
        rememberedPolicyAppliedByExactMatch == null &&
        rememberedPolicyPreviousSuccessCount == null &&
        rememberedPolicyPreviousFailureCount == null &&
        rememberedPolicyPreviousConsecutiveFailureCount == null
    ) {
        return null
    }
    return RememberedPolicyApplicationAudit(
        matchedFingerprintHash = rememberedPolicyMatchedFingerprintHash,
        source = RememberedNetworkPolicySource.fromStorageValue(rememberedPolicySource),
        appliedByExactMatch = rememberedPolicyAppliedByExactMatch == true,
        previousSuccessCount = rememberedPolicyPreviousSuccessCount ?: 0,
        previousFailureCount = rememberedPolicyPreviousFailureCount ?: 0,
        previousConsecutiveFailureCount = rememberedPolicyPreviousConsecutiveFailureCount ?: 0,
    )
}

private fun decodeScanProjection(
    json: Json,
    payload: String?,
): DiagnosticsSessionProjection? =
    payload
        ?.takeIf { it.isNotBlank() }
        ?.let { json.decodeEngineScanReportWire(it).toSessionProjection() }

private fun decodeProfileProjection(
    json: Json,
    payload: String?,
): com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection? =
    payload
        ?.takeIf { it.isNotBlank() }
        ?.let { json.decodeProfileSpecWire(it).toProfileProjection() }

private fun decodeStrategySignature(
    json: Json,
    payload: String?,
): BypassStrategySignature? = decodeOrNull(json, BypassStrategySignature.serializer(), payload)

private fun decodeContext(
    json: Json,
    payload: String?,
): DiagnosticContextModel? = decodeOrNull(json, DiagnosticContextModel.serializer(), payload)

private fun decodeNetworkSnapshot(
    json: Json,
    payload: String?,
): NetworkSnapshotModel? = decodeOrNull(json, NetworkSnapshotModel.serializer(), payload)

private fun decodeNetworkFingerprintSummary(
    json: Json,
    payload: String?,
): NetworkFingerprintSummary? = decodeOrNull(json, NetworkFingerprintSummary.serializer(), payload)

private fun decodeVpnDnsPolicy(
    json: Json,
    payload: String?,
): VpnDnsPolicyJson? = decodeOrNull(json, VpnDnsPolicyJson.serializer(), payload)

private fun decodeProbeDetails(
    json: Json,
    payload: String,
): List<ProbeDetail> =
    runCatching {
        json.decodeFromString(ListSerializer(ProbeDetail.serializer()), payload)
    }.onFailure { Logger.w(it) { "Failed to decode probe details" } }.getOrElse { emptyList() }

private fun <T> decodeOrNull(
    json: Json,
    serializer: KSerializer<T>,
    payload: String?,
): T? =
    payload?.takeIf { it.isNotBlank() }?.let {
        runCatching { json.decodeFromString(serializer, it) }
            .onFailure { e ->
                Logger.w(e) { "Failed to decode ${serializer.descriptor.serialName}" }
            }.getOrNull()
    }
