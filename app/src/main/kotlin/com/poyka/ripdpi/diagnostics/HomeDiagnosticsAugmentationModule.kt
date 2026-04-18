package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.detection.DetectionRunner
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
                DetectionRunner.run(
                    context = context,
                    config = config,
                    onProgress = { progress ->
                        onProgress(progress.label, progress.detail)
                    },
                )
            val categories =
                listOfNotNull(
                    result.geoIp,
                    result.directSigns,
                    result.indirectSigns,
                    result.locationSignals,
                    result.dnsLeak,
                    result.webRtcLeak,
                    result.tlsFingerprint,
                    result.timingAnalysis,
                )
            val categoryFindings =
                categories.flatMap { category ->
                    category.findings
                        .filter { it.detected || it.needsReview }
                        .map { finding -> "${category.name}: ${finding.description}" }
                }
            val bypassFindings =
                result.bypassResult.findings
                    .filter { it.detected || it.needsReview }
                    .map { "Bypass: ${it.description}" }
            val findings = (categoryFindings + bypassFindings).take(DetectionFindingLimit)
            val detectedSignalCount =
                categories.sumOf { category ->
                    category.findings.count { it.detected }
                } + result.bypassResult.findings.count { it.detected }
            val verdict =
                when (result.verdict) {
                    com.poyka.ripdpi.core.detection.Verdict.DETECTED -> {
                        DiagnosticsHomeDetectionVerdict.DETECTED
                    }

                    com.poyka.ripdpi.core.detection.Verdict.NEEDS_REVIEW -> {
                        DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW
                    }

                    com.poyka.ripdpi.core.detection.Verdict.NOT_DETECTED -> {
                        DiagnosticsHomeDetectionVerdict.NOT_DETECTED
                    }
                }
            return HomeDetectionStageOutcome(
                verdict = verdict,
                detectedSignalCount = detectedSignalCount,
                findings = findings,
            )
        }

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
