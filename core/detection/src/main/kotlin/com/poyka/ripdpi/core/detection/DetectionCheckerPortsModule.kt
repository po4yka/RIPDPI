package com.poyka.ripdpi.core.detection

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DetectionCheckerPortsModule {
    @Binds
    @Singleton
    abstract fun bindDetectionCheckRunner(runner: DefaultDetectionCheckRunner): DetectionCheckRunner

    @Binds
    abstract fun bindGeoIpCheckerPort(checker: DefaultGeoIpCheckerPort): GeoIpCheckerPort

    @Binds
    abstract fun bindDirectSignsCheckerPort(checker: DefaultDirectSignsCheckerPort): DirectSignsCheckerPort

    @Binds
    abstract fun bindIndirectSignsCheckerPort(checker: DefaultIndirectSignsCheckerPort): IndirectSignsCheckerPort

    @Binds
    abstract fun bindLocationSignalsCheckerPort(checker: DefaultLocationSignalsCheckerPort): LocationSignalsCheckerPort

    @Binds
    abstract fun bindBypassCheckerPort(checker: DefaultBypassCheckerPort): BypassCheckerPort

    @Binds
    abstract fun bindDnsLeakCheckerPort(checker: DefaultDnsLeakCheckerPort): DnsLeakCheckerPort

    @Binds
    abstract fun bindWebRtcLeakCheckerPort(checker: DefaultWebRtcLeakCheckerPort): WebRtcLeakCheckerPort

    @Binds
    abstract fun bindTlsFingerprintCheckerPort(checker: DefaultTlsFingerprintCheckerPort): TlsFingerprintCheckerPort

    @Binds
    abstract fun bindTimingAnalysisCheckerPort(checker: DefaultTimingAnalysisCheckerPort): TimingAnalysisCheckerPort

    @Binds
    abstract fun bindIcmpSpoofingCheckerPort(checker: DefaultIcmpSpoofingCheckerPort): IcmpSpoofingCheckerPort

    @Binds
    abstract fun bindIpComparisonCheckerPort(checker: DefaultIpComparisonCheckerPort): IpComparisonCheckerPort

    @Binds
    abstract fun bindRttTriangulationCheckerPort(
        checker: DefaultRttTriangulationCheckerPort,
    ): RttTriangulationCheckerPort

    @Binds
    abstract fun bindCdnPullingCheckerPort(checker: DefaultCdnPullingCheckerPort): CdnPullingCheckerPort

    @Binds
    abstract fun bindDetectionVerdictEvaluator(evaluator: DefaultDetectionVerdictEvaluator): DetectionVerdictEvaluator
}
