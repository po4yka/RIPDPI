package com.poyka.ripdpi.core.detection

import android.content.Context
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("LongParameterList")
@Singleton
class DefaultDetectionCheckRunner
    @Inject
    constructor(
        geoIpChecker: GeoIpCheckerPort,
        directSignsChecker: DirectSignsCheckerPort,
        indirectSignsChecker: IndirectSignsCheckerPort,
        locationSignalsChecker: LocationSignalsCheckerPort,
        bypassChecker: BypassCheckerPort,
        dnsLeakChecker: DnsLeakCheckerPort,
        webRtcLeakChecker: WebRtcLeakCheckerPort,
        tlsFingerprintChecker: TlsFingerprintCheckerPort,
        timingAnalysisChecker: TimingAnalysisCheckerPort,
        icmpSpoofingChecker: IcmpSpoofingCheckerPort,
        ipComparisonChecker: IpComparisonCheckerPort,
        verdictEvaluator: DetectionVerdictEvaluator,
    ) : DetectionCheckRunner {
        private val scheduler =
            DetectionPipelineScheduler(
                geoIpChecker = geoIpChecker,
                directSignsChecker = directSignsChecker,
                indirectSignsChecker = indirectSignsChecker,
                locationSignalsChecker = locationSignalsChecker,
                bypassChecker = bypassChecker,
                dnsLeakChecker = dnsLeakChecker,
                webRtcLeakChecker = webRtcLeakChecker,
                tlsFingerprintChecker = tlsFingerprintChecker,
                timingAnalysisChecker = timingAnalysisChecker,
                icmpSpoofingChecker = icmpSpoofingChecker,
                ipComparisonChecker = ipComparisonChecker,
            )
        private val resultAssembler = DetectionPipelineResultAssembler(verdictEvaluator)

        override suspend fun run(
            context: Context,
            config: DetectionRunnerConfig,
            onProgress: (suspend (DetectionProgress) -> Unit)?,
        ): DetectionCheckResult =
            resultAssembler.assemble(
                scheduler.runChecks(
                    context = context,
                    config = config,
                    onProgress = onProgress,
                ),
            )
    }

object DetectionRunner {
    private fun defaultRunner(dispatchers: AppCoroutineDispatchers): DetectionCheckRunner =
        DefaultDetectionCheckRunner(
            geoIpChecker = DefaultGeoIpCheckerPort(dispatchers),
            directSignsChecker = DefaultDirectSignsCheckerPort(),
            indirectSignsChecker = DefaultIndirectSignsCheckerPort(),
            locationSignalsChecker = DefaultLocationSignalsCheckerPort(),
            bypassChecker = DefaultBypassCheckerPort(dispatchers),
            dnsLeakChecker = DefaultDnsLeakCheckerPort(dispatchers),
            webRtcLeakChecker = DefaultWebRtcLeakCheckerPort(dispatchers),
            tlsFingerprintChecker = DefaultTlsFingerprintCheckerPort(dispatchers),
            timingAnalysisChecker = DefaultTimingAnalysisCheckerPort(dispatchers),
            icmpSpoofingChecker = DefaultIcmpSpoofingCheckerPort(dispatchers),
            ipComparisonChecker = DefaultIpComparisonCheckerPort(dispatchers),
            verdictEvaluator = DefaultDetectionVerdictEvaluator(),
        )

    suspend fun run(
        dispatchers: AppCoroutineDispatchers,
        context: Context,
        config: DetectionRunnerConfig = DetectionRunnerConfig(),
        onProgress: (suspend (DetectionProgress) -> Unit)? = null,
    ): DetectionCheckResult =
        defaultRunner(dispatchers).run(
            context = context,
            config = config,
            onProgress = onProgress,
        )
}
