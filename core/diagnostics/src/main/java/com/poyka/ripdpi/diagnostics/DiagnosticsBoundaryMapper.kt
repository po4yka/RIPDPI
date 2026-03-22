package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NetworkFingerprintSummary
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
                request = decodeOrNull(ScanRequest.serializer(), entity.requestJson),
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
                strategySignature = decodeStrategySignature(entity.strategyJson),
                pathMode = entity.pathMode,
                serviceMode = entity.serviceMode,
                status = entity.status,
                summary = entity.summary,
                report = decodeScanReport(entity.reportJson),
                startedAt = entity.startedAt,
                finishedAt = entity.finishedAt,
            )

        fun toProbeResult(entity: ProbeResultEntity): ProbeResult {
            val details = decodeProbeDetails(entity.detailJson)
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
                snapshot = decodeNetworkSnapshot(entity.payloadJson),
                capturedAt = entity.capturedAt,
            )

        fun toDiagnosticContextSnapshot(entity: DiagnosticContextEntity): DiagnosticContextSnapshot =
            DiagnosticContextSnapshot(
                id = entity.id,
                sessionId = entity.sessionId,
                connectionSessionId = entity.connectionSessionId,
                contextKind = entity.contextKind,
                context = decodeContext(entity.payloadJson),
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
                strategySignature = decodeStrategySignature(entity.strategyJson),
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
            )

        fun toDiagnosticsRememberedPolicy(entity: RememberedNetworkPolicyEntity): DiagnosticsRememberedPolicy =
            DiagnosticsRememberedPolicy(
                id = entity.id,
                fingerprintHash = entity.fingerprintHash,
                mode = entity.mode,
                summary = decodeNetworkFingerprintSummary(entity.summaryJson),
                proxyConfigJson = entity.proxyConfigJson,
                vpnDnsPolicy = decodeVpnDnsPolicy(entity.vpnDnsPolicyJson),
                strategySignature = decodeStrategySignature(entity.strategySignatureJson),
                winningTcpStrategyFamily = entity.winningTcpStrategyFamily,
                winningQuicStrategyFamily = entity.winningQuicStrategyFamily,
                source = entity.source,
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
                fingerprintHash = policy.fingerprintHash,
                policySignature = policy.policySignature,
                appliedAt = policy.appliedAt,
                restartReason = policy.restartReason,
                handoverClassification = policy.handoverClassification,
            )

        fun decodeScanReport(payload: String?): ScanReport? =
            decodeOrNull(ScanReport.serializer(), payload)

        fun decodeStrategySignature(payload: String?): BypassStrategySignature? =
            decodeOrNull(BypassStrategySignature.serializer(), payload)

        private fun decodeContext(payload: String?): DiagnosticContextModel? =
            decodeOrNull(DiagnosticContextModel.serializer(), payload)

        private fun decodeNetworkSnapshot(payload: String?): NetworkSnapshotModel? =
            decodeOrNull(NetworkSnapshotModel.serializer(), payload)

        private fun decodeNetworkFingerprintSummary(payload: String?): NetworkFingerprintSummary? =
            decodeOrNull(NetworkFingerprintSummary.serializer(), payload)

        private fun decodeVpnDnsPolicy(payload: String?): VpnDnsPolicyJson? =
            decodeOrNull(VpnDnsPolicyJson.serializer(), payload)

        private fun decodeProbeDetails(payload: String): List<ProbeDetail> =
            runCatching {
                json.decodeFromString(ListSerializer(ProbeDetail.serializer()), payload)
            }.getOrElse { emptyList() }

        private fun <T> decodeOrNull(
            serializer: kotlinx.serialization.KSerializer<T>,
            payload: String?,
        ): T? =
            payload?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString(serializer, it) }.getOrNull()
            }
    }
