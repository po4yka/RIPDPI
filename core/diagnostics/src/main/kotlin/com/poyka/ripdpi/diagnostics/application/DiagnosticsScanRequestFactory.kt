package com.poyka.ripdpi.diagnostics.application

import android.content.Context
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRuntimeContext
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.toActiveDnsSettings
import com.poyka.ripdpi.diagnostics.ActiveProbeSafetyPolicy
import com.poyka.ripdpi.diagnostics.DefaultDiagnosticsPlanner
import com.poyka.ripdpi.diagnostics.DefaultEngineRequestEncoder
import com.poyka.ripdpi.diagnostics.DefaultScanContextCollector
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticsContextProvider
import com.poyka.ripdpi.diagnostics.DiagnosticsIntentResolver
import com.poyka.ripdpi.diagnostics.DiagnosticsPlanner
import com.poyka.ripdpi.diagnostics.DiagnosticsScanTargetOverrides
import com.poyka.ripdpi.diagnostics.DomainTarget
import com.poyka.ripdpi.diagnostics.EngineRequestEncoder
import com.poyka.ripdpi.diagnostics.NetworkMetadataProvider
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.QuicTarget
import com.poyka.ripdpi.diagnostics.ScanContextCollector
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.StrategyProbeTargetSelection
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.domain.ScanPlan
import com.poyka.ripdpi.diagnostics.dpich.TlsKeylogPathValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
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
private val RequestFactoryLog = Logger.withTag("DiagnosticsScanRequestFactory")

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
        @param:ApplicationContext
        private val context: Context,
        private val networkMetadataProvider: NetworkMetadataProvider,
        private val intentResolver: DiagnosticsIntentResolver,
        private val scanContextCollector: ScanContextCollector,
        private val diagnosticsPlanner: DiagnosticsPlanner,
        private val engineRequestEncoder: EngineRequestEncoder,
        private val activeProbeSafetyPolicy: ActiveProbeSafetyPolicy,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        suspend fun prepareReprobe(
            original: PreparedDiagnosticsScan,
            preferredDnsPathOverride: EncryptedDnsPathCandidate? = null,
        ): PreparedDiagnosticsScan {
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
                    scanOrigin = DiagnosticsScanOrigin.DNS_CORRECTED_REPROBE,
                    activeProbeSafetyPolicy = activeProbeSafetyPolicy,
                )
            val scanContext = scanContextCollector.collect(intent).copy(pathMode = pathMode)
            val effectivePreferredDnsPath = preferredDnsPathOverride ?: scanContext.preferredDnsPath
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
                        preferredDnsPath = effectivePreferredDnsPath,
                        preferredEdges = scanContext.preferredEdges,
                        protectPath = resolveProtectPath(context, pathMode),
                    ).withActiveProbeSafetyBudget(
                        scanOrigin = DiagnosticsScanOrigin.DNS_CORRECTED_REPROBE,
                        requestedMaxCandidates = null,
                        activeProbeSafetyPolicy = activeProbeSafetyPolicy,
                    ).withDiagnosticTlsKeylogPath(original.settings, context.filesDir)
            val now = System.currentTimeMillis()
            return PreparedDiagnosticsScan(
                sessionId = sessionId,
                settings = original.settings,
                pathMode = pathMode,
                intent = intent,
                context = scanContext,
                plan = plan,
                requestJson = json.encodeToString(EngineScanRequestWire.serializer(), engineRequest),
                scanOrigin = DiagnosticsScanOrigin.DNS_CORRECTED_REPROBE,
                launchTrigger = original.launchTrigger,
                exposeProgress = false,
                registerActiveBridge = false,
                networkFingerprint = scanContext.networkFingerprint,
                preferredDnsPath = effectivePreferredDnsPath,
                initialSession = buildReprobeSessionEntity(sessionId, pathMode, scanContext, original, now),
                preScanSnapshot = buildReprobeSnapshotEntity(sessionId, now),
                preScanContext = buildReprobeContextEntity(sessionId, scanContext, now),
                reprobeForSessionId = original.sessionId,
            )
        }

        private fun buildReprobeSessionEntity(
            sessionId: String,
            pathMode: ScanPathMode,
            scanContext: ScanContext,
            original: PreparedDiagnosticsScan,
            now: Long,
        ): ScanSessionEntity =
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
            )

        private suspend fun buildReprobeSnapshotEntity(
            sessionId: String,
            now: Long,
        ): NetworkSnapshotEntity =
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
            )

        private fun buildReprobeContextEntity(
            sessionId: String,
            scanContext: ScanContext,
            now: Long,
        ): DiagnosticContextEntity =
            DiagnosticContextEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                contextKind = "pre_scan",
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), scanContext.contextSnapshot),
                capturedAt = now,
            )

        suspend fun prepareScan(
            profile: DiagnosticProfileEntity,
            settings: com.poyka.ripdpi.proto.AppSettings,
            pathMode: ScanPathMode,
            scanOrigin: DiagnosticsScanOrigin,
            launchTrigger: DiagnosticsScanLaunchTrigger? = null,
            exposeProgress: Boolean,
            registerActiveBridge: Boolean,
            scanDeadlineMs: Long? = null,
            maxCandidates: Int? = null,
            targetOverrides: DiagnosticsScanTargetOverrides? = null,
            sessionIdOverride: String? = null,
        ): PreparedDiagnosticsScan {
            val sessionId = sessionIdOverride ?: UUID.randomUUID().toString()
            val intent =
                selectStrategyProbeTargetsForSession(
                    sessionId = sessionId,
                    intent = intentResolver.resolve(profile.id, pathMode).copy(settings = settings),
                    isManual = scanOrigin == DiagnosticsScanOrigin.USER_INITIATED,
                    scanOrigin = scanOrigin,
                    activeProbeSafetyPolicy = activeProbeSafetyPolicy,
                ).applyTargetOverrides(targetOverrides, pathMode)
                    .let { selectedIntent ->
                        activeProbeSafetyPolicy.enforceTargetBudget(
                            intent = selectedIntent,
                            scanOrigin = scanOrigin,
                            isManual = scanOrigin == DiagnosticsScanOrigin.USER_INITIATED,
                        )
                    }
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
                        preferredEdges = scanContext.preferredEdges,
                        protectPath = resolveProtectPath(context, pathMode),
                    ).withActiveProbeSafetyBudget(
                        scanOrigin = scanOrigin,
                        requestedMaxCandidates = maxCandidates,
                        activeProbeSafetyPolicy = activeProbeSafetyPolicy,
                    ).withDiagnosticTlsKeylogPath(settings, context.filesDir)
                    .copy(scanDeadlineMs = scanDeadlineMs)
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
                    buildInitialSession(sessionId, profile, scanContext, pathMode, scanOrigin, launchTrigger, now),
                preScanSnapshot = buildPreScanSnapshot(sessionId, now),
                preScanContext = buildPreScanContext(sessionId, scanContext, now),
            )
        }

        private fun buildInitialSession(
            sessionId: String,
            profile: DiagnosticProfileEntity,
            scanContext: ScanContext,
            pathMode: ScanPathMode,
            scanOrigin: DiagnosticsScanOrigin,
            launchTrigger: DiagnosticsScanLaunchTrigger?,
            now: Long,
        ): ScanSessionEntity =
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
            )

        private suspend fun buildPreScanSnapshot(
            sessionId: String,
            now: Long,
        ): NetworkSnapshotEntity =
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
            )

        private fun buildPreScanContext(
            sessionId: String,
            scanContext: ScanContext,
            now: Long,
        ): DiagnosticContextEntity =
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
            )
    }

private fun com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent.applyTargetOverrides(
    targetOverrides: DiagnosticsScanTargetOverrides?,
    pathMode: ScanPathMode,
): com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent {
    val overrides = targetOverrides ?: return this
    return copy(
        domainTargets = overrides.domainTargets ?: domainTargets,
        serviceTargets = overrides.serviceTargets ?: serviceTargets,
        circumventionTargets = overrides.circumventionTargets ?: circumventionTargets,
        quicTargets = emptyList(),
        throughputTargets = emptyList(),
        requestedPathMode = pathMode,
    )
}

private fun EngineScanRequestWire.withStrategyProbeBaseConfig(
    settings: com.poyka.ripdpi.proto.AppSettings,
    preferredDnsPath: EncryptedDnsPathCandidate?,
    preferredEdges: Map<String, List<com.poyka.ripdpi.data.PreferredEdgeCandidate>>,
    protectPath: String?,
): EngineScanRequestWire {
    val strategyProbe = strategyProbe ?: return this
    val runtimeContext = resolveStrategyProbeRuntimeContext(settings, preferredDnsPath, preferredEdges, protectPath)
    val baseProxyConfigJson =
        strategyProbe.baseProxyConfigJson
            ?.takeIf { it.isNotBlank() }
            ?.let { explicitBaseConfig ->
                decodeRipDpiProxyUiPreferences(explicitBaseConfig)
                    ?.withSessionOverrides(runtimeContext = runtimeContext)
                    ?.toNativeConfigJson()
                    ?: run {
                        RequestFactoryLog.d {
                            "Strategy probe base config was not decodable; " +
                                "keeping explicit baseProxyConfigJson unchanged"
                        }
                        explicitBaseConfig
                    }
            } ?: RipDpiProxyUIPreferences
            .fromSettings(
                settings = settings,
                runtimeContext = runtimeContext,
            ).toNativeConfigJson()
    return copy(
        strategyProbe =
            strategyProbe.copy(
                baseProxyConfigJson = baseProxyConfigJson,
            ),
    )
}

private fun EngineScanRequestWire.withActiveProbeSafetyBudget(
    scanOrigin: DiagnosticsScanOrigin,
    requestedMaxCandidates: Int?,
    activeProbeSafetyPolicy: ActiveProbeSafetyPolicy,
): EngineScanRequestWire {
    val strategyProbe = strategyProbe ?: return this
    val maxCandidates =
        activeProbeSafetyPolicy.enforceMaxCandidates(
            scanOrigin = scanOrigin,
            requestedMaxCandidates = requestedMaxCandidates ?: strategyProbe.maxCandidates,
        )
    return copy(strategyProbe = strategyProbe.copy(maxCandidates = maxCandidates))
}

private fun EngineScanRequestWire.withDiagnosticTlsKeylogPath(
    settings: com.poyka.ripdpi.proto.AppSettings,
    appFilesDir: File,
): EngineScanRequestWire {
    val configuredPath =
        settings.detectionDiagnosticTlsKeylogPath
            .takeUnless { path ->
                !settings.detectionCheckDebugModeEnabled ||
                    settings.detectionCheckPrivacyModeEnabled ||
                    path.isBlank()
            }
    val validatedPath =
        configuredPath?.let { path ->
            runCatching {
                TlsKeylogPathValidator(appFilesDir).validate(path)
            }.getOrElse { error ->
                RequestFactoryLog.w(error) { "Rejected diagnostics TLS keylog path outside app storage" }
                null
            }
        }
    return validatedPath?.let { path -> copy(diagnosticTlsKeylogPath = path) } ?: this
}

private fun resolveStrategyProbeRuntimeContext(
    settings: com.poyka.ripdpi.proto.AppSettings,
    preferredDnsPath: EncryptedDnsPathCandidate?,
    preferredEdges: Map<String, List<com.poyka.ripdpi.data.PreferredEdgeCandidate>>,
    protectPath: String?,
): RipDpiRuntimeContext? {
    // canonicalDefaultEncryptedDnsSettings always returns a valid context.
    val dnsContext =
        settings.activeDnsSettings().toRipDpiRuntimeContext()
            ?: preferredDnsPath?.toActiveDnsSettings()?.toRipDpiRuntimeContext()
            ?: canonicalDefaultEncryptedDnsSettings().toRipDpiRuntimeContext()!!
    return dnsContext.copy(protectPath = protectPath, preferredEdges = preferredEdges)
}

/**
 * Returns the absolute path to the protect_path socket used by VpnService.protect()
 * only while the scan remains inside the active VPN path.
 *
 * RAW_PATH orchestration stops the VPN runtime before starting the native bridge, so its
 * protect backend is intentionally unavailable and must not be advertised to native probes.
 */
private fun resolveProtectPath(
    context: Context,
    pathMode: ScanPathMode,
): String? =
    when (pathMode) {
        ScanPathMode.RAW_PATH -> null
        ScanPathMode.IN_PATH -> File(context.filesDir, "protect_path").absolutePath
    }

internal fun selectStrategyProbeTargetsForSession(
    sessionId: String,
    intent: DiagnosticsIntent,
    isManual: Boolean = false,
    scanOrigin: DiagnosticsScanOrigin =
        if (isManual) {
            DiagnosticsScanOrigin.USER_INITIATED
        } else {
            DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND
        },
    activeProbeSafetyPolicy: ActiveProbeSafetyPolicy = ActiveProbeSafetyPolicy(),
): DiagnosticsIntent {
    val strategyProbe = intent.strategyProbe
    val validCohorts = activeProbeSafetyPolicy.validAutomaticAuditCohorts(intent)
    val isApplicable =
        strategyProbe != null &&
            strategyProbe.suiteId == StrategyProbeSuiteFullMatrixV1 &&
            intent.profileId == AutomaticAuditProfileId &&
            validCohorts.isNotEmpty()
    if (!isApplicable) {
        return activeProbeSafetyPolicy.enforceTargetBudget(intent, scanOrigin, isManual)
    }
    checkNotNull(strategyProbe)
    val selectedIntent =
        if (isManual) {
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
    return activeProbeSafetyPolicy.enforceTargetBudget(selectedIntent, scanOrigin, isManual)
}
