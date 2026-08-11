package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionCheckRunner
import com.poyka.ripdpi.core.detection.DetectionRunnerConfig
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import dagger.Binds
import dagger.Module
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
            val config =
                DetectionRunnerConfig(
                    ownProxyPort = settings.proxyPort.takeIf { it > 0 },
                    ownPackageName = context.packageName,
                    encryptedDnsEnabled = settings.dnsMode == "encrypted",
                    webRtcProtectionEnabled = settings.webrtcProtectionEnabled,
                    tlsFingerprintProfile = settings.tlsFingerprintProfile.ifEmpty { "chrome_stable" },
                )
            val result =
                detectionCheckRunner.run(
                    context = context,
                    config = config,
                    onProgress = { progress ->
                        onProgress(progress.label, progress.detail)
                    },
                )
            return mapDetectionCheckResultToHomeDetectionStageOutcome(result)
        }
    }

internal fun mapDetectionCheckResultToHomeDetectionStageOutcome(
    result: DetectionCheckResult,
): HomeDetectionStageOutcome? {
    val categories = result.homeDetectionCategories()
    val findingSignals =
        categories.flatMap { category ->
            category.findings
                .filter { it.detected || it.needsReview }
                .map { finding -> HomeDetectionSignal("${category.name}: ${finding.description}", finding.detected) }
        }
    val evidenceSignals =
        categories.flatMap { category ->
            category.evidence
                .filter { it.detected }
                .map { evidence -> HomeDetectionSignal("${category.name}: ${evidence.description}", detected = true) }
        }
    val signals = (evidenceSignals + findingSignals).distinctBy(HomeDetectionSignal::description)
    val verdict = result.verdict.toHomeDetectionVerdict()
    val detectedSignalCount = signals.count(HomeDetectionSignal::detected)
    if (verdict == DiagnosticsHomeDetectionVerdict.DETECTED && detectedSignalCount == 0) {
        return null
    }
    return HomeDetectionStageOutcome(
        verdict = verdict,
        detectedSignalCount = detectedSignalCount,
        findings = signals.map(HomeDetectionSignal::description).take(DetectionFindingLimit),
    )
}

private fun DetectionCheckResult.homeDetectionCategories(): List<CategoryResult> =
    listOfNotNull(
        geoIp,
        directSigns,
        indirectSigns,
        locationSignals,
        dnsLeak,
        webRtcLeak,
        tlsFingerprint,
        timingAnalysis,
        icmpSpoofing?.category,
        ipComparison?.category,
        rttTriangulation?.category,
        cdnPulling?.category,
        nativeSigns?.category,
        callTransport?.category,
        ipConsensus?.toCategoryResult(),
        bypassResult.toCategoryResult(),
    )

private fun com.poyka.ripdpi.core.detection.BypassResult.toCategoryResult(): CategoryResult =
    CategoryResult(
        name = "Bypass",
        detected = detected,
        needsReview = needsReview,
        findings = findings,
        evidence = evidence,
    )

private fun com.poyka.ripdpi.core.detection.Verdict.toHomeDetectionVerdict(): DiagnosticsHomeDetectionVerdict =
    when (this) {
        com.poyka.ripdpi.core.detection.Verdict.DETECTED -> DiagnosticsHomeDetectionVerdict.DETECTED
        com.poyka.ripdpi.core.detection.Verdict.NEEDS_REVIEW -> DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW
        com.poyka.ripdpi.core.detection.Verdict.NOT_DETECTED -> DiagnosticsHomeDetectionVerdict.NOT_DETECTED
    }

private data class HomeDetectionSignal(
    val description: String,
    val detected: Boolean,
)

private const val DetectionFindingLimit = 6

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
