package com.poyka.ripdpi.core.detection

import android.content.Context
import com.poyka.ripdpi.core.detection.checker.BypassChecker
import com.poyka.ripdpi.core.detection.checker.CdnPullingChecker
import com.poyka.ripdpi.core.detection.checker.DirectSignsChecker
import com.poyka.ripdpi.core.detection.checker.DnsLeakChecker
import com.poyka.ripdpi.core.detection.checker.GeoIpChecker
import com.poyka.ripdpi.core.detection.checker.IcmpSpoofingChecker
import com.poyka.ripdpi.core.detection.checker.IndirectSignsChecker
import com.poyka.ripdpi.core.detection.checker.IpComparisonChecker
import com.poyka.ripdpi.core.detection.checker.LocationSignalsChecker
import com.poyka.ripdpi.core.detection.checker.NativeSignsChecker
import com.poyka.ripdpi.core.detection.checker.RttTriangulationChecker
import com.poyka.ripdpi.core.detection.checker.SystemPingProber
import com.poyka.ripdpi.core.detection.checker.TimingAnalysisChecker
import com.poyka.ripdpi.core.detection.checker.TlsFingerprintChecker
import com.poyka.ripdpi.core.detection.checker.VerdictEngine
import com.poyka.ripdpi.core.detection.checker.WebRtcLeakChecker
import com.poyka.ripdpi.core.detection.consensus.IpConsensusResult
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import javax.inject.Inject

class DefaultGeoIpCheckerPort
    @Inject
    constructor(
        private val dispatchers: AppCoroutineDispatchers,
    ) : GeoIpCheckerPort {
        override suspend fun check(): CategoryResult = GeoIpChecker.check(dispatchers)
    }

class DefaultDirectSignsCheckerPort
    @Inject
    constructor() : DirectSignsCheckerPort {
        override fun check(
            context: Context,
            excludePackage: String?,
        ): CategoryResult = DirectSignsChecker.check(context, excludePackage)
    }

class DefaultIndirectSignsCheckerPort
    @Inject
    constructor() : IndirectSignsCheckerPort {
        override fun check(context: Context): CategoryResult = IndirectSignsChecker.check(context)
    }

class DefaultLocationSignalsCheckerPort
    @Inject
    constructor() : LocationSignalsCheckerPort {
        override fun check(context: Context): CategoryResult = LocationSignalsChecker.check(context)
    }

class DefaultBypassCheckerPort
    @Inject
    constructor(
        private val dispatchers: AppCoroutineDispatchers,
    ) : BypassCheckerPort {
        override suspend fun check(
            excludePorts: Set<Int>,
            options: BypassScanOptions,
            onProgress: (suspend (BypassChecker.Progress) -> Unit)?,
        ): BypassResult =
            BypassChecker.check(
                dispatchers = dispatchers,
                excludePorts = excludePorts,
                options = options,
                onProgress = onProgress,
            )
    }

class DefaultDnsLeakCheckerPort
    @Inject
    constructor(
        private val dispatchers: AppCoroutineDispatchers,
    ) : DnsLeakCheckerPort {
        override suspend fun check(
            context: Context,
            encryptedDnsEnabled: Boolean,
        ): CategoryResult =
            DnsLeakChecker.check(
                dispatchers = dispatchers,
                context = context,
                encryptedDnsEnabled = encryptedDnsEnabled,
            )
    }

class DefaultWebRtcLeakCheckerPort
    @Inject
    constructor(
        private val dispatchers: AppCoroutineDispatchers,
    ) : WebRtcLeakCheckerPort {
        override suspend fun check(webRtcProtectionEnabled: Boolean): CategoryResult =
            WebRtcLeakChecker.check(dispatchers = dispatchers, webRtcProtectionEnabled = webRtcProtectionEnabled)
    }

class DefaultTlsFingerprintCheckerPort
    @Inject
    constructor(
        private val dispatchers: AppCoroutineDispatchers,
    ) : TlsFingerprintCheckerPort {
        override suspend fun check(tlsFingerprintProfile: String): CategoryResult =
            TlsFingerprintChecker.check(dispatchers = dispatchers, tlsFingerprintProfile = tlsFingerprintProfile)
    }

class DefaultTimingAnalysisCheckerPort
    @Inject
    constructor(
        private val dispatchers: AppCoroutineDispatchers,
    ) : TimingAnalysisCheckerPort {
        override suspend fun check(): CategoryResult = TimingAnalysisChecker.check(dispatchers)
    }

class DefaultIcmpSpoofingCheckerPort
    @Inject
    constructor(
        private val dispatchers: AppCoroutineDispatchers,
    ) : IcmpSpoofingCheckerPort {
        override suspend fun check(homeRoutedRoaming: Boolean): IcmpSpoofingResult =
            IcmpSpoofingChecker.check(
                prober = SystemPingProber(dispatchers),
                homeRoutedRoaming = homeRoutedRoaming,
            )
    }

class DefaultIpComparisonCheckerPort
    @Inject
    constructor(
        private val dispatchers: AppCoroutineDispatchers,
    ) : IpComparisonCheckerPort {
        override suspend fun check(): IpComparisonResult = IpComparisonChecker.check(dispatchers)
    }

class DefaultRttTriangulationCheckerPort
    @Inject
    constructor(
        private val dispatchers: AppCoroutineDispatchers,
    ) : RttTriangulationCheckerPort {
        override suspend fun check(homeCountryIso: String?): RttTriangulationResult =
            RttTriangulationChecker.check(
                prober = SystemPingProber(dispatchers, sampleCount = RTT_SAMPLE_COUNT),
                enabled = true,
                homeCountryIso = homeCountryIso,
            )

        private companion object {
            private const val RTT_SAMPLE_COUNT = 4
        }
    }

class DefaultCdnPullingCheckerPort
    @Inject
    constructor(
        private val dispatchers: AppCoroutineDispatchers,
    ) : CdnPullingCheckerPort {
        override suspend fun check(enabled: Boolean): CdnPullingResult =
            CdnPullingChecker.check(
                dispatchers = dispatchers,
                enabled = enabled,
            )
    }

class DefaultNativeSignsCheckerPort
    @Inject
    constructor() : NativeSignsCheckerPort {
        override fun check(enabled: Boolean): NativeSignsResult =
            if (enabled) {
                NativeSignsChecker.check()
            } else {
                NativeSignsResult(
                    category =
                        CategoryResult(
                            name = "Native signs",
                            detected = false,
                            findings = listOf(Finding("Native signs check disabled")),
                        ),
                )
            }
    }

class DefaultDetectionVerdictEvaluator
    @Inject
    constructor() : DetectionVerdictEvaluator {
        override fun evaluate(
            geoIp: CategoryResult,
            directSigns: CategoryResult,
            indirectSigns: CategoryResult,
            locationSignals: CategoryResult,
            bypassResult: BypassResult,
            icmpSpoofing: IcmpSpoofingResult?,
            ipComparison: IpComparisonResult?,
            rttTriangulation: RttTriangulationResult?,
            cdnPulling: CdnPullingResult?,
            ipConsensus: IpConsensusResult?,
            nativeSigns: NativeSignsResult?,
        ): Verdict =
            explain(
                geoIp = geoIp,
                directSigns = directSigns,
                indirectSigns = indirectSigns,
                locationSignals = locationSignals,
                bypassResult = bypassResult,
                icmpSpoofing = icmpSpoofing,
                ipComparison = ipComparison,
                rttTriangulation = rttTriangulation,
                cdnPulling = cdnPulling,
                ipConsensus = ipConsensus,
                nativeSigns = nativeSigns,
            ).verdict

        override fun explain(
            geoIp: CategoryResult,
            directSigns: CategoryResult,
            indirectSigns: CategoryResult,
            locationSignals: CategoryResult,
            bypassResult: BypassResult,
            icmpSpoofing: IcmpSpoofingResult?,
            ipComparison: IpComparisonResult?,
            rttTriangulation: RttTriangulationResult?,
            cdnPulling: CdnPullingResult?,
            ipConsensus: IpConsensusResult?,
            nativeSigns: NativeSignsResult?,
        ): VerdictExplanation =
            VerdictEngine.evaluateDetailed(
                geoIp = geoIp,
                directSigns = directSigns,
                indirectSigns = indirectSigns,
                locationSignals = locationSignals,
                bypassResult = bypassResult,
                icmpSpoofing = icmpSpoofing,
                ipComparison = ipComparison,
                rttTriangulation = rttTriangulation,
                cdnPulling = cdnPulling,
                ipConsensus = ipConsensus,
                nativeSigns = nativeSigns,
            )
    }
