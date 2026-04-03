package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiLogContext
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

internal enum class DiagnosticsScanOrigin {
    USER_INITIATED,
    AUTOMATIC_BACKGROUND,
    DNS_CORRECTED_REPROBE,
}

private const val AutomaticAuditProfileId = "automatic-audit"
private const val StrategyProbeSuiteFullMatrixV1 = "full_matrix_v1"
private const val AutomaticAuditDomainTargetCount = 3
private const val AutomaticAuditQuicTargetCount = 2

internal data class PreparedDiagnosticsScan(
    val sessionId: String,
    val settings: com.poyka.ripdpi.proto.AppSettings,
    val pathMode: ScanPathMode,
    val intent: DiagnosticsIntent,
    val context: ScanContext,
    val plan: ScanPlan,
    val requestJson: String,
    val scanOrigin: DiagnosticsScanOrigin,
    val launchTrigger: DiagnosticsScanLaunchTrigger?,
    val exposeProgress: Boolean,
    val registerActiveBridge: Boolean,
    val networkFingerprint: com.poyka.ripdpi.data.NetworkFingerprint?,
    val preferredDnsPath: com.poyka.ripdpi.data.EncryptedDnsPathCandidate?,
    val initialSession: ScanSessionEntity,
    val preScanSnapshot: NetworkSnapshotEntity,
    val preScanContext: DiagnosticContextEntity,
    val reprobeForSessionId: String? = null,
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
        suspend fun prepareReprobe(original: PreparedDiagnosticsScan): PreparedDiagnosticsScan {
            val sessionId = UUID.randomUUID().toString()
            val pathMode = ScanPathMode.IN_PATH
            val intent =
                selectStrategyProbeTargetsForSession(
                    sessionId = sessionId,
                    intent =
                        intentResolver
                            .resolve(
                                original.intent.profileId,
                                pathMode,
                            ).copy(settings = original.settings),
                    isManual = false,
                )
            val scanContext = scanContextCollector.collect(intent)
            val plan = diagnosticsPlanner.plan(intent, scanContext)
            val engineRequest =
                engineRequestEncoder
                    .encode(plan)
                    .copy(
                        logContext =
                            RipDpiLogContext(
                                mode = scanContext.serviceMode.lowercase(),
                                diagnosticsSessionId = sessionId,
                            ),
                    ).withStrategyProbeBaseConfig(
                        settings = original.settings,
                        preferredDnsPath = scanContext.preferredDnsPath,
                    )
            val now = System.currentTimeMillis()
            return PreparedDiagnosticsScan(
                sessionId = sessionId,
                settings = original.settings,
                pathMode = pathMode,
                intent = intent,
                context = scanContext,
                plan = plan,
                requestJson =
                    json.encodeToString(
                        EngineScanRequestWire.serializer(),
                        engineRequest,
                    ),
                scanOrigin = DiagnosticsScanOrigin.DNS_CORRECTED_REPROBE,
                launchTrigger = original.launchTrigger,
                exposeProgress = false,
                registerActiveBridge = false,
                networkFingerprint = scanContext.networkFingerprint,
                preferredDnsPath = scanContext.preferredDnsPath,
                initialSession =
                    ScanSessionEntity(
                        id = sessionId,
                        profileId = original.intent.profileId,
                        approachProfileId = scanContext.approachSnapshot.profileId,
                        approachProfileName = scanContext.approachSnapshot.profileName,
                        strategyId = scanContext.approachSnapshot.strategyId,
                        strategyLabel = scanContext.approachSnapshot.strategyLabel,
                        strategyJson = scanContext.approachSnapshot.strategyJson,
                        pathMode = pathMode.name,
                        serviceMode = scanContext.serviceMode,
                        status = "running",
                        summary = "DNS-corrected re-probe for ${original.sessionId}",
                        reportJson = null,
                        startedAt = now,
                        finishedAt = null,
                        launchOrigin = DiagnosticsScanOrigin.DNS_CORRECTED_REPROBE.toLaunchOrigin().storageValue,
                        triggerType = original.launchTrigger?.type?.storageValue,
                        triggerClassification = original.launchTrigger?.classification,
                        triggerOccurredAt = original.launchTrigger?.occurredAt,
                        triggerPreviousFingerprintHash = original.launchTrigger?.previousFingerprintHash,
                        triggerCurrentFingerprintHash = original.launchTrigger?.currentFingerprintHash,
                    ),
                preScanSnapshot =
                    NetworkSnapshotEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        snapshotKind = "pre_scan",
                        payloadJson =
                            json.encodeToString(
                                NetworkSnapshotModel.serializer(),
                                networkMetadataProvider.captureSnapshot(includePublicIp = false),
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
                reprobeForSessionId = original.sessionId,
            )
        }

        suspend fun prepareScan(
            profile: DiagnosticProfileEntity,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
            scanOrigin: DiagnosticsScanOrigin,
            launchTrigger: DiagnosticsScanLaunchTrigger? = null,
            exposeProgress: Boolean,
            registerActiveBridge: Boolean,
        ): PreparedDiagnosticsScan {
            val sessionId = UUID.randomUUID().toString()
            val intent =
                selectStrategyProbeTargetsForSession(
                    sessionId = sessionId,
                    intent = intentResolver.resolve(profile.id, pathMode).copy(settings = settings),
                    isManual = scanOrigin == DiagnosticsScanOrigin.USER_INITIATED,
                )
            val scanContext = scanContextCollector.collect(intent)
            val plan = diagnosticsPlanner.plan(intent, scanContext)
            val engineRequest =
                engineRequestEncoder
                    .encode(plan)
                    .copy(
                        logContext =
                            RipDpiLogContext(
                                mode = scanContext.serviceMode.lowercase(),
                                diagnosticsSessionId = sessionId,
                            ),
                    ).withStrategyProbeBaseConfig(
                        settings = settings,
                        preferredDnsPath = scanContext.preferredDnsPath,
                    )
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
                scanOrigin = scanOrigin,
                launchTrigger = launchTrigger,
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
                        launchOrigin = scanOrigin.toLaunchOrigin().storageValue,
                        triggerType = launchTrigger?.type?.storageValue,
                        triggerClassification = launchTrigger?.classification,
                        triggerOccurredAt = launchTrigger?.occurredAt,
                        triggerPreviousFingerprintHash = launchTrigger?.previousFingerprintHash,
                        triggerCurrentFingerprintHash = launchTrigger?.currentFingerprintHash,
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

internal fun selectStrategyProbeTargetsForSession(
    sessionId: String,
    intent: DiagnosticsIntent,
    isManual: Boolean = false,
): DiagnosticsIntent {
    val strategyProbe = intent.strategyProbe
    val validCohorts =
        intent.strategyProbeTargetCohorts.filter { cohort ->
            cohort.domainTargets.size == AutomaticAuditDomainTargetCount &&
                cohort.quicTargets.size == AutomaticAuditQuicTargetCount
        }
    val isFullMatrixSuite = strategyProbe?.suiteId == StrategyProbeSuiteFullMatrixV1
    val isApplicable =
        isFullMatrixSuite &&
            intent.profileId == AutomaticAuditProfileId && validCohorts.isNotEmpty()
    if (!isApplicable || strategyProbe == null) return intent
    return if (isManual) {
        val allDomainTargets = validCohorts.flatMap { it.domainTargets }.distinctBy { it.host }
        val allQuicTargets = validCohorts.flatMap { it.quicTargets }.distinctBy { it.host }
        intent.copy(
            domainTargets = allDomainTargets,
            quicTargets = allQuicTargets,
            strategyProbe =
                strategyProbe.copy(
                    targetSelection =
                        StrategyProbeTargetSelection(
                            cohortId = "all",
                            cohortLabel = "All cohorts",
                            domainHosts = allDomainTargets.map(DomainTarget::host),
                            quicHosts = allQuicTargets.map(QuicTarget::host),
                        ),
                ),
        )
    } else {
        val selectedCohort = validCohorts[Math.floorMod(sessionId.hashCode(), validCohorts.size)]
        intent.copy(
            domainTargets = selectedCohort.domainTargets,
            quicTargets = selectedCohort.quicTargets,
            strategyProbe =
                strategyProbe.copy(
                    targetSelection =
                        StrategyProbeTargetSelection(
                            cohortId = selectedCohort.id,
                            cohortLabel = selectedCohort.label,
                            domainHosts = selectedCohort.domainTargets.map(DomainTarget::host),
                            quicHosts = selectedCohort.quicTargets.map(QuicTarget::host),
                        ),
                ),
        )
    }
}
