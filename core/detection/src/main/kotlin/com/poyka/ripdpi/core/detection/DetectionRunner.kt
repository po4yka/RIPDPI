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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
    val tlsFingerprintProfile: String = "native_default",
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

object DetectionRunner {
    suspend fun run(
        context: Context,
        config: DetectionRunnerConfig = DetectionRunnerConfig(),
        onProgress: (suspend (DetectionProgress) -> Unit)? = null,
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
                    GeoIpChecker.check().also {
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
                    DirectSignsChecker.check(context, excludePackage).also {
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
                    IndirectSignsChecker.check(context).also {
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
                        LocationSignalsChecker.check(context).also {
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
                        BypassChecker
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
                        DnsLeakChecker.check(context, config.encryptedDnsEnabled).also {
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
                        WebRtcLeakChecker.check(config.webRtcProtectionEnabled).also {
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
                        TlsFingerprintChecker.check(config.tlsFingerprintProfile).also {
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
                        TimingAnalysisChecker.check().also {
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
                VerdictEngine.evaluate(
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
