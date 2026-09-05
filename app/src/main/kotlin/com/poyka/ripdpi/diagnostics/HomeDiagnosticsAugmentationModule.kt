package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.detection.DecisionSignal
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionCheckRunner
import com.poyka.ripdpi.core.detection.DetectionRunnerConfig
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveClock
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFileStore
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeController
import com.poyka.ripdpi.pcap.PcapController
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultHomeDetectionStageRunner
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val detectionCheckRunner: DetectionCheckRunner,
    ) : HomeDetectionStageRunner {
        override suspend fun run(
            onProgress: suspend (label: String, detail: String) -> Unit,
        ): HomeDetectionStageOutcome? {
            val settings = appSettingsRepository.settings.first()
            val result =
                detectionCheckRunner.run(
                    context = context,
                    config =
                        DetectionRunnerConfig(
                            ownProxyPort = settings.proxyPort.takeIf { it > 0 },
                            ownPackageName = context.packageName,
                            encryptedDnsEnabled = settings.dnsMode == "encrypted",
                            webRtcProtectionEnabled = settings.webrtcProtectionEnabled,
                            tlsFingerprintProfile = settings.tlsFingerprintProfile.ifEmpty { "chrome_stable" },
                        ),
                    onProgress = { progress ->
                        onProgress(progress.label, progress.detail)
                    },
                )
            return result.toHomeDetectionStageOutcome()
        }

        private fun DetectionCheckResult.toHomeDetectionStageOutcome(): HomeDetectionStageOutcome =
            HomeDetectionStageOutcome(
                verdict = verdict.toHomeVerdict(),
                detectedSignalCount = verdictExplanation?.uniqueSignalCount ?: 0,
                findings = findingDescriptions(),
                ruleApplied = verdictExplanation?.ruleApplied,
                evidenceScopes = verdictExplanation?.appliedScopes.orEmpty(),
                localFindings = localFindingDescriptions(),
                networkFindings = networkFindingDescriptions(),
                decisionSignals =
                    verdictExplanation
                        ?.decisionSignals
                        .orEmpty()
                        .map { signal -> signal.toHomeSignal() },
            )

        private fun DetectionCheckResult.findingDescriptions(): List<String> {
            val categoryFindings =
                categories().flatMap { category ->
                    category.findings
                        .filter { it.detected || it.needsReview }
                        .map { finding -> "${category.name}: ${finding.description}" }
                }
            val bypassFindings =
                bypassResult.findings
                    .filter { it.detected || it.needsReview }
                    .map { "Bypass: ${it.description}" }
            return (categoryFindings + bypassFindings).take(DetectionFindingLimit)
        }

        private fun DetectionCheckResult.localFindingDescriptions(): List<String> =
            evidenceItems()
                .filter { it.detected && it.scope != DetectionScope.NETWORK_OBSERVATION }
                .distinctBy { listOfNotNull(it.packageName, it.family).firstOrNull() ?: it.description }
                .map { it.description }
                .take(DetectionFindingLimit)

        private fun DetectionCheckResult.networkFindingDescriptions(): List<String> =
            evidenceItems()
                .filter { it.detected && it.scope == DetectionScope.NETWORK_OBSERVATION }
                .distinctBy { "${it.source}:${it.family.orEmpty()}:${it.description}" }
                .map { it.description }
                .take(DetectionFindingLimit)

        private fun DetectionCheckResult.evidenceItems() = categories().flatMap { it.evidence } + bypassResult.evidence

        private fun DetectionCheckResult.categories() =
            listOfNotNull(
                geoIp,
                directSigns,
                indirectSigns,
                locationSignals,
                dnsLeak,
                webRtcLeak,
                tlsFingerprint,
                timingAnalysis,
            )

        private fun Verdict.toHomeVerdict(): DiagnosticsHomeDetectionVerdict =
            when (this) {
                Verdict.DETECTED -> DiagnosticsHomeDetectionVerdict.DETECTED
                Verdict.NEEDS_REVIEW -> DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW
                Verdict.NOT_DETECTED -> DiagnosticsHomeDetectionVerdict.NOT_DETECTED
            }

        private fun DecisionSignal.toHomeSignal(): HomeDetectionDecisionSignal =
            HomeDetectionDecisionSignal(
                category = HomeDetectionSignalCategory.valueOf(category.name),
                semantics = HomeDetectionSignalSemantics.valueOf(semantics.name),
                source = source.name,
                confidence = confidence.name,
                scope = scope.name,
            )

        private companion object {
            const val DetectionFindingLimit = 6
        }
    }

@Singleton
class DefaultHomeDetectorCatalogSource
    @Inject
    constructor(
        private val catalogService: RoutingProtectionCatalogService,
    ) : HomeDetectorCatalogSource {
        override fun snapshot(): HomeDetectorCatalogSnapshot {
            val serviceSnapshot = catalogService.snapshot()
            val detectors = serviceSnapshot.detectedApps.filter { it.vpnDetection }
            return HomeDetectorCatalogSnapshot(
                installedVpnDetectorCount = detectors.size,
                topDetectorPackages = detectors.take(TopDetectorAppsLimit).map { it.packageName },
            )
        }

        private companion object {
            const val TopDetectorAppsLimit = 5
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class HomeDiagnosticsAugmentationModule {
    companion object {
        @Provides
        @Singleton
        fun provideDiagnosticsArchiveFileStore(
            @ApplicationContext context: Context,
            clock: DiagnosticsArchiveClock,
            pcapController: PcapController,
            captureRuntime: PcapCaptureRuntimeController,
        ): DiagnosticsArchiveFileStore =
            DiagnosticsArchiveFileStore(
                cacheDir = context.cacheDir,
                clock = clock,
                pcapDirectory = pcapController.captureDirectory,
                withCaptureStorageLock = captureRuntime::withCaptureStorageLock,
            )
    }

    @Binds
    @Singleton
    abstract fun bindHomeDetectionStageRunner(runner: DefaultHomeDetectionStageRunner): HomeDetectionStageRunner

    @Binds
    @Singleton
    abstract fun bindHomeDetectorCatalogSource(source: DefaultHomeDetectorCatalogSource): HomeDetectorCatalogSource

    @Binds
    @Singleton
    abstract fun bindHomeAnalysisAugmentationSource(
        source: DefaultHomeAnalysisAugmentationSource,
    ): HomeAnalysisAugmentationSource

    @Binds
    @Singleton
    abstract fun bindDeveloperAnalyticsSource(source: DefaultDeveloperAnalyticsSource): DeveloperAnalyticsSource
}
