package com.poyka.ripdpi.core.detection

import android.content.Context
import com.poyka.ripdpi.core.detection.checker.BypassChecker
import com.poyka.ripdpi.core.detection.checker.DirectSignsChecker
import com.poyka.ripdpi.core.detection.checker.DnsLeakChecker
import com.poyka.ripdpi.core.detection.checker.GeoIpChecker
import com.poyka.ripdpi.core.detection.checker.IndirectSignsChecker
import com.poyka.ripdpi.core.detection.checker.LocationSignalsChecker
import com.poyka.ripdpi.core.detection.checker.TimingAnalysisChecker
import com.poyka.ripdpi.core.detection.checker.TlsFingerprintChecker
import com.poyka.ripdpi.core.detection.checker.VerdictEngine
import com.poyka.ripdpi.core.detection.checker.WebRtcLeakChecker
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
            onProgress: (suspend (BypassChecker.Progress) -> Unit)?,
        ): BypassResult =
            BypassChecker.check(
                dispatchers = dispatchers,
                excludePorts = excludePorts,
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

class DefaultDetectionVerdictEvaluator
    @Inject
    constructor() : DetectionVerdictEvaluator {
        override fun evaluate(
            geoIp: CategoryResult,
            directSigns: CategoryResult,
            indirectSigns: CategoryResult,
            locationSignals: CategoryResult,
            bypassResult: BypassResult,
        ): Verdict =
            VerdictEngine.evaluate(
                geoIp = geoIp,
                directSigns = directSigns,
                indirectSigns = indirectSigns,
                locationSignals = locationSignals,
                bypassResult = bypassResult,
            )
    }
