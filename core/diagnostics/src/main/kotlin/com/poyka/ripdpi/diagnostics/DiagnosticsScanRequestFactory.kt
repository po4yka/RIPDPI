package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.toActiveDnsSettings
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.domain.ScanPlan
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

internal data class PreparedDiagnosticsScan(
    val sessionId: String,
    val settings: com.poyka.ripdpi.proto.AppSettings,
    val pathMode: ScanPathMode,
    val intent: DiagnosticsIntent,
    val context: ScanContext,
    val plan: ScanPlan,
    val requestJson: String,
    val exposeProgress: Boolean,
    val registerActiveBridge: Boolean,
    val networkFingerprint: com.poyka.ripdpi.data.NetworkFingerprint?,
    val preferredDnsPath: com.poyka.ripdpi.data.EncryptedDnsPathCandidate?,
    val initialSession: ScanSessionEntity,
    val preScanSnapshot: NetworkSnapshotEntity,
    val preScanContext: DiagnosticContextEntity,
)

@Singleton
internal class DiagnosticsScanRequestFactory
    @Inject
    constructor(
        private val networkMetadataProvider: NetworkMetadataProvider,
        private val intentResolver: DiagnosticsIntentResolver,
        private val scanContextCollector: ScanContextCollector,
        private val diagnosticsPlanner: DiagnosticsPlanner,
        private val engineRequestEncoder: EngineRequestEncoder,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        suspend fun prepareScan(
            profile: DiagnosticProfileEntity,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
            exposeProgress: Boolean,
            registerActiveBridge: Boolean,
        ): PreparedDiagnosticsScan {
            val intent = intentResolver.resolve(profile.id, pathMode).copy(settings = settings)
            val scanContext = scanContextCollector.collect(intent)
            val plan = diagnosticsPlanner.plan(intent, scanContext)
            val engineRequest =
                engineRequestEncoder
                    .encode(plan)
                    .withStrategyProbeBaseConfig(
                        settings = settings,
                        preferredDnsPath = scanContext.preferredDnsPath,
                    )
            val sessionId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            return PreparedDiagnosticsScan(
                sessionId = sessionId,
                settings = settings,
                pathMode = pathMode,
                intent = intent,
                context = scanContext,
                plan = plan,
                requestJson =
                    json.encodeToString(
                        com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
                            .serializer(),
                        engineRequest,
                    ),
                exposeProgress = exposeProgress,
                registerActiveBridge = registerActiveBridge,
                networkFingerprint = scanContext.networkFingerprint,
                preferredDnsPath = scanContext.preferredDnsPath,
                initialSession =
                    ScanSessionEntity(
                        id = sessionId,
                        profileId = profile.id,
                        approachProfileId = scanContext.approachSnapshot.profileId,
                        approachProfileName = scanContext.approachSnapshot.profileName,
                        strategyId = scanContext.approachSnapshot.strategyId,
                        strategyLabel = scanContext.approachSnapshot.strategyLabel,
                        strategyJson = scanContext.approachSnapshot.strategyJson,
                        pathMode = pathMode.name,
                        serviceMode = scanContext.serviceMode,
                        status = "running",
                        summary = "Scan started",
                        reportJson = null,
                        startedAt = now,
                        finishedAt = null,
                    ),
                preScanSnapshot =
                    NetworkSnapshotEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        snapshotKind = "pre_scan",
                        payloadJson =
                            json.encodeToString(
                                NetworkSnapshotModel.serializer(),
                                networkMetadataProvider.captureSnapshot(includePublicIp = true),
                            ),
                        capturedAt = now,
                    ),
                preScanContext =
                    DiagnosticContextEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        contextKind = "pre_scan",
                        payloadJson =
                            json.encodeToString(
                                DiagnosticContextModel.serializer(),
                                scanContext.contextSnapshot,
                            ),
                        capturedAt = now,
                    ),
            )
        }
    }

private fun EngineScanRequestWire.withStrategyProbeBaseConfig(
    settings: com.poyka.ripdpi.proto.AppSettings,
    preferredDnsPath: EncryptedDnsPathCandidate?,
): EngineScanRequestWire {
    val strategyProbe = strategyProbe ?: return this
    if (!strategyProbe.baseProxyConfigJson.isNullOrBlank()) {
        return this
    }
    return copy(
        strategyProbe =
            strategyProbe.copy(
                baseProxyConfigJson =
                    RipDpiProxyUIPreferences
                        .fromSettings(
                            settings = settings,
                            runtimeContext = resolveStrategyProbeRuntimeContext(settings, preferredDnsPath),
                        ).toNativeConfigJson(),
            ),
    )
}

private fun resolveStrategyProbeRuntimeContext(
    settings: com.poyka.ripdpi.proto.AppSettings,
    preferredDnsPath: EncryptedDnsPathCandidate?,
) = settings
    .activeDnsSettings()
    .toRipDpiRuntimeContext()
    ?: preferredDnsPath?.toActiveDnsSettings()?.toRipDpiRuntimeContext()
    ?: canonicalDefaultEncryptedDnsSettings().toRipDpiRuntimeContext()
