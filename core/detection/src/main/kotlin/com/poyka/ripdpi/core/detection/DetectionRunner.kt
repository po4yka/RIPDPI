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
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

data class DetectionRunnerConfig(
    val ownProxyPort: Int? = null,
    val ownPackageName: String? = null,
    val includeBypassCheck: Boolean = true,
    val includeLocationCheck: Boolean = true,
    val includeDnsLeakCheck: Boolean = true,
    val includeWebRtcCheck: Boolean = true,
    val includeTlsFingerprintCheck: Boolean = true,
    val includeTimingAnalysis: Boolean = true,
    val encryptedDnsEnabled: Boolean = false,
    val webRtcProtectionEnabled: Boolean = false,
    val tlsFingerprintProfile: String = "chrome_stable",
)

enum class DetectionStage {
    GEO_IP,
    DIRECT_SIGNS,
    INDIRECT_SIGNS,
    LOCATION_SIGNALS,
    BYPASS,
    DNS_LEAK,
    WEBRTC_LEAK,
    TLS_FINGERPRINT,
    TIMING_ANALYSIS,
}

data class DetectionProgress(
    val stage: DetectionStage,
    val label: String,
    val detail: String,
    val completedStages: Set<DetectionStage> = emptySet(),
)

interface GeoIpCheckerPort {
    suspend fun check(): CategoryResult
}

interface DirectSignsCheckerPort {
    fun check(
        context: Context,
        excludePackage: String?,
    ): CategoryResult
}

interface IndirectSignsCheckerPort {
    fun check(context: Context): CategoryResult
}

interface LocationSignalsCheckerPort {
    fun check(context: Context): CategoryResult
}

interface BypassCheckerPort {
    suspend fun check(
        excludePorts: Set<Int>,
        onProgress: (suspend (BypassChecker.Progress) -> Unit)?,
    ): BypassResult
}

interface DnsLeakCheckerPort {
    suspend fun check(
        context: Context,
        encryptedDnsEnabled: Boolean,
    ): CategoryResult
}

interface WebRtcLeakCheckerPort {
    suspend fun check(webRtcProtectionEnabled: Boolean): CategoryResult
}

interface TlsFingerprintCheckerPort {
    suspend fun check(tlsFingerprintProfile: String): CategoryResult
}

interface TimingAnalysisCheckerPort {
    suspend fun check(): CategoryResult
}

interface DetectionVerdictEvaluator {
    fun evaluate(
        geoIp: CategoryResult,
        directSigns: CategoryResult,
        indirectSigns: CategoryResult,
        locationSignals: CategoryResult,
        bypassResult: BypassResult,
    ): Verdict
}

interface DetectionCheckRunner {
    suspend fun run(
        context: Context,
        config: DetectionRunnerConfig = DetectionRunnerConfig(),
        onProgress: (suspend (DetectionProgress) -> Unit)? = null,
    ): DetectionCheckResult
}

class DefaultGeoIpCheckerPort
    @Inject
    constructor() : GeoIpCheckerPort {
        override suspend fun check(): CategoryResult = GeoIpChecker.check()
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
    constructor() : BypassCheckerPort {
        override suspend fun check(
            excludePorts: Set<Int>,
            onProgress: (suspend (BypassChecker.Progress) -> Unit)?,
        ): BypassResult = BypassChecker.check(excludePorts, onProgress)
    }

class DefaultDnsLeakCheckerPort
    @Inject
    constructor() : DnsLeakCheckerPort {
        override suspend fun check(
            context: Context,
            encryptedDnsEnabled: Boolean,
        ): CategoryResult = DnsLeakChecker.check(context, encryptedDnsEnabled)
    }

class DefaultWebRtcLeakCheckerPort
    @Inject
    constructor() : WebRtcLeakCheckerPort {
        override suspend fun check(webRtcProtectionEnabled: Boolean): CategoryResult =
            WebRtcLeakChecker.check(webRtcProtectionEnabled)
    }

class DefaultTlsFingerprintCheckerPort
    @Inject
    constructor() : TlsFingerprintCheckerPort {
        override suspend fun check(tlsFingerprintProfile: String): CategoryResult =
            TlsFingerprintChecker.check(tlsFingerprintProfile)
    }

class DefaultTimingAnalysisCheckerPort
    @Inject
    constructor() : TimingAnalysisCheckerPort {
        override suspend fun check(): CategoryResult = TimingAnalysisChecker.check()
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

@Suppress("LongParameterList")
@Singleton
class DefaultDetectionCheckRunner
    @Inject
    constructor(
        private val geoIpChecker: GeoIpCheckerPort,
        private val directSignsChecker: DirectSignsCheckerPort,
        private val indirectSignsChecker: IndirectSignsCheckerPort,
        private val locationSignalsChecker: LocationSignalsCheckerPort,
        private val bypassChecker: BypassCheckerPort,
        private val dnsLeakChecker: DnsLeakCheckerPort,
        private val webRtcLeakChecker: WebRtcLeakCheckerPort,
        private val tlsFingerprintChecker: TlsFingerprintCheckerPort,
        private val timingAnalysisChecker: TimingAnalysisCheckerPort,
        private val verdictEvaluator: DetectionVerdictEvaluator,
    ) : DetectionCheckRunner {
        override suspend fun run(
            context: Context,
            config: DetectionRunnerConfig,
            onProgress: (suspend (DetectionProgress) -> Unit)?,
        ): DetectionCheckResult =
            coroutineScope {
                val excludePackage = config.ownPackageName ?: context.packageName
                val excludePorts = setOfNotNull(config.ownProxyPort)
                val completed = mutableSetOf<DetectionStage>()

                onProgress?.invoke(
                    DetectionProgress(DetectionStage.GEO_IP, "GeoIP", "Checking IP geolocation..."),
                )
                val geoIpDeferred =
                    async {
                        geoIpChecker.check().also {
                            completed.add(DetectionStage.GEO_IP)
                            onProgress?.invoke(
                                DetectionProgress(
                                    DetectionStage.GEO_IP,
                                    "GeoIP",
                                    "Done",
                                    completedStages = completed.toSet(),
                                ),
                            )
                        }
                    }

                onProgress?.invoke(
                    DetectionProgress(
                        DetectionStage.DIRECT_SIGNS,
                        "Direct signs",
                        "Checking VPN transport and installed apps...",
                    ),
                )
                val directSignsDeferred =
                    async {
                        directSignsChecker.check(context, excludePackage).also {
                            completed.add(DetectionStage.DIRECT_SIGNS)
                            onProgress?.invoke(
                                DetectionProgress(
                                    DetectionStage.DIRECT_SIGNS,
                                    "Direct signs",
                                    "Done",
                                    completedStages = completed.toSet(),
                                ),
                            )
                        }
                    }

                onProgress?.invoke(
                    DetectionProgress(
                        DetectionStage.INDIRECT_SIGNS,
                        "Indirect signs",
                        "Checking network interfaces and DNS...",
                    ),
                )
                val indirectSignsDeferred =
                    async {
                        indirectSignsChecker.check(context).also {
                            completed.add(DetectionStage.INDIRECT_SIGNS)
                            onProgress?.invoke(
                                DetectionProgress(
                                    DetectionStage.INDIRECT_SIGNS,
                                    "Indirect signs",
                                    "Done",
                                    completedStages = completed.toSet(),
                                ),
                            )
                        }
                    }

                val locationSignalsDeferred =
                    if (config.includeLocationCheck) {
                        onProgress?.invoke(
                            DetectionProgress(
                                DetectionStage.LOCATION_SIGNALS,
                                "Location",
                                "Checking cellular signals...",
                            ),
                        )
                        async {
                            locationSignalsChecker.check(context).also {
                                completed.add(DetectionStage.LOCATION_SIGNALS)
                                onProgress?.invoke(
                                    DetectionProgress(
                                        DetectionStage.LOCATION_SIGNALS,
                                        "Location",
                                        "Done",
                                        completedStages = completed.toSet(),
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    }

                val bypassDeferred =
                    if (config.includeBypassCheck) {
                        async {
                            bypassChecker
                                .check(
                                    excludePorts = excludePorts,
                                    onProgress = { progress ->
                                        onProgress?.invoke(
                                            DetectionProgress(
                                                DetectionStage.BYPASS,
                                                "Bypass: ${progress.phase}",
                                                progress.detail,
                                                completedStages = completed.toSet(),
                                            ),
                                        )
                                    },
                                ).also {
                                    completed.add(DetectionStage.BYPASS)
                                    onProgress?.invoke(
                                        DetectionProgress(
                                            DetectionStage.BYPASS,
                                            "Bypass",
                                            "Done",
                                            completedStages = completed.toSet(),
                                        ),
                                    )
                                }
                        }
                    } else {
                        null
                    }

                val dnsLeakDeferred =
                    if (config.includeDnsLeakCheck) {
                        async {
                            dnsLeakChecker.check(context, config.encryptedDnsEnabled).also {
                                completed.add(DetectionStage.DNS_LEAK)
                                onProgress?.invoke(
                                    DetectionProgress(
                                        DetectionStage.DNS_LEAK,
                                        "DNS Leak",
                                        "Done",
                                        completedStages = completed.toSet(),
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    }

                val webRtcDeferred =
                    if (config.includeWebRtcCheck) {
                        async {
                            webRtcLeakChecker.check(config.webRtcProtectionEnabled).also {
                                completed.add(DetectionStage.WEBRTC_LEAK)
                                onProgress?.invoke(
                                    DetectionProgress(
                                        DetectionStage.WEBRTC_LEAK,
                                        "WebRTC Leak",
                                        "Done",
                                        completedStages = completed.toSet(),
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    }

                val tlsFingerprintDeferred =
                    if (config.includeTlsFingerprintCheck) {
                        async {
                            tlsFingerprintChecker.check(config.tlsFingerprintProfile).also {
                                completed.add(DetectionStage.TLS_FINGERPRINT)
                                onProgress?.invoke(
                                    DetectionProgress(
                                        DetectionStage.TLS_FINGERPRINT,
                                        "TLS Fingerprint",
                                        "Done",
                                        completedStages = completed.toSet(),
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    }

                val geoIp = geoIpDeferred.await()
                val directSigns = directSignsDeferred.await()
                val indirectSigns = indirectSignsDeferred.await()
                val locationSignals =
                    locationSignalsDeferred?.await() ?: CategoryResult(
                        name = "Location signals",
                        detected = false,
                        findings = listOf(Finding("Location check disabled")),
                    )
                val bypassResult =
                    bypassDeferred?.await() ?: BypassResult(
                        proxyEndpoint = null,
                        directIp = null,
                        proxyIp = null,
                        xrayApiScanResult = null,
                        findings = listOf(Finding("Bypass check disabled")),
                        detected = false,
                    )

                val timingDeferred =
                    if (config.includeTimingAnalysis) {
                        async {
                            timingAnalysisChecker.check().also {
                                completed.add(DetectionStage.TIMING_ANALYSIS)
                                onProgress?.invoke(
                                    DetectionProgress(
                                        DetectionStage.TIMING_ANALYSIS,
                                        "Timing Analysis",
                                        "Done",
                                        completedStages = completed.toSet(),
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    }

                val dnsLeak = dnsLeakDeferred?.await()
                val webRtcLeak = webRtcDeferred?.await()
                val tlsFingerprint = tlsFingerprintDeferred?.await()
                val timingAnalysis = timingDeferred?.await()

                val verdict =
                    verdictEvaluator.evaluate(
                        geoIp = geoIp,
                        directSigns = directSigns,
                        indirectSigns = indirectSigns,
                        locationSignals = locationSignals,
                        bypassResult = bypassResult,
                    )

                DetectionCheckResult(
                    geoIp = geoIp,
                    directSigns = directSigns,
                    indirectSigns = indirectSigns,
                    locationSignals = locationSignals,
                    bypassResult = bypassResult,
                    dnsLeak = dnsLeak,
                    webRtcLeak = webRtcLeak,
                    tlsFingerprint = tlsFingerprint,
                    timingAnalysis = timingAnalysis,
                    verdict = verdict,
                )
            }
    }

object DetectionRunner {
    private val defaultRunner: DetectionCheckRunner =
        DefaultDetectionCheckRunner(
            geoIpChecker = DefaultGeoIpCheckerPort(),
            directSignsChecker = DefaultDirectSignsCheckerPort(),
            indirectSignsChecker = DefaultIndirectSignsCheckerPort(),
            locationSignalsChecker = DefaultLocationSignalsCheckerPort(),
            bypassChecker = DefaultBypassCheckerPort(),
            dnsLeakChecker = DefaultDnsLeakCheckerPort(),
            webRtcLeakChecker = DefaultWebRtcLeakCheckerPort(),
            tlsFingerprintChecker = DefaultTlsFingerprintCheckerPort(),
            timingAnalysisChecker = DefaultTimingAnalysisCheckerPort(),
            verdictEvaluator = DefaultDetectionVerdictEvaluator(),
        )

    suspend fun run(
        context: Context,
        config: DetectionRunnerConfig = DetectionRunnerConfig(),
        onProgress: (suspend (DetectionProgress) -> Unit)? = null,
    ): DetectionCheckResult =
        defaultRunner.run(
            context = context,
            config = config,
            onProgress = onProgress,
        )
}

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
    abstract fun bindDetectionVerdictEvaluator(evaluator: DefaultDetectionVerdictEvaluator): DetectionVerdictEvaluator
}
