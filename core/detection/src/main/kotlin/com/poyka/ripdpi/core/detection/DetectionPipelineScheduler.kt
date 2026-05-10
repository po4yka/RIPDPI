package com.poyka.ripdpi.core.detection

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Suppress("LongParameterList")
internal class DetectionPipelineScheduler(
    private val geoIpChecker: GeoIpCheckerPort,
    private val directSignsChecker: DirectSignsCheckerPort,
    private val indirectSignsChecker: IndirectSignsCheckerPort,
    private val locationSignalsChecker: LocationSignalsCheckerPort,
    private val bypassChecker: BypassCheckerPort,
    private val dnsLeakChecker: DnsLeakCheckerPort,
    private val webRtcLeakChecker: WebRtcLeakCheckerPort,
    private val tlsFingerprintChecker: TlsFingerprintCheckerPort,
    private val timingAnalysisChecker: TimingAnalysisCheckerPort,
    private val icmpSpoofingChecker: IcmpSpoofingCheckerPort,
) {
    suspend fun runChecks(
        context: Context,
        config: DetectionRunnerConfig,
        onProgress: (suspend (DetectionProgress) -> Unit)?,
    ): DetectionPipelineOutputs =
        coroutineScope {
            val reporter = DetectionProgressReporter(onProgress)
            val excludePackage = config.ownPackageName ?: context.packageName
            val excludePorts = setOfNotNull(config.ownProxyPort)

            val geoIpDeferred =
                async {
                    reporter.started(DetectionStage.GEO_IP)
                    geoIpChecker.check().also {
                        reporter.completed(DetectionStage.GEO_IP)
                    }
                }
            val directSignsDeferred =
                async {
                    reporter.started(DetectionStage.DIRECT_SIGNS)
                    directSignsChecker.check(context, excludePackage).also {
                        reporter.completed(DetectionStage.DIRECT_SIGNS)
                    }
                }
            val indirectSignsDeferred =
                async {
                    reporter.started(DetectionStage.INDIRECT_SIGNS)
                    indirectSignsChecker.check(context).also {
                        reporter.completed(DetectionStage.INDIRECT_SIGNS)
                    }
                }
            val locationSignalsDeferred =
                if (config.includeLocationCheck) {
                    async {
                        reporter.started(DetectionStage.LOCATION_SIGNALS)
                        locationSignalsChecker.check(context).also {
                            reporter.completed(DetectionStage.LOCATION_SIGNALS)
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
                                onProgress = reporter::bypassProgress,
                            ).also {
                                reporter.completed(DetectionStage.BYPASS)
                            }
                    }
                } else {
                    null
                }
            val dnsLeakDeferred =
                if (config.includeDnsLeakCheck) {
                    async {
                        dnsLeakChecker.check(context, config.encryptedDnsEnabled).also {
                            reporter.completed(DetectionStage.DNS_LEAK)
                        }
                    }
                } else {
                    null
                }
            val webRtcDeferred =
                if (config.includeWebRtcCheck) {
                    async {
                        webRtcLeakChecker.check(config.webRtcProtectionEnabled).also {
                            reporter.completed(DetectionStage.WEBRTC_LEAK)
                        }
                    }
                } else {
                    null
                }
            val tlsFingerprintDeferred =
                if (config.includeTlsFingerprintCheck) {
                    async {
                        tlsFingerprintChecker.check(config.tlsFingerprintProfile).also {
                            reporter.completed(DetectionStage.TLS_FINGERPRINT)
                        }
                    }
                } else {
                    null
                }

            val geoIp = geoIpDeferred.await()
            val directSigns = directSignsDeferred.await()
            val indirectSigns = indirectSignsDeferred.await()
            val locationSignals = locationSignalsDeferred?.await()
            val bypassResult = bypassDeferred?.await()
            val icmpSpoofing =
                if (config.includeIcmpSpoofingCheck) {
                    reporter.started(DetectionStage.ICMP_SPOOFING)
                    icmpSpoofingChecker
                        .check(homeRoutedRoaming = locationSignals.isHomeRoutedRoaming())
                        .also {
                            reporter.completed(DetectionStage.ICMP_SPOOFING)
                        }
                } else {
                    null
                }

            val timingDeferred =
                if (config.includeTimingAnalysis) {
                    async {
                        timingAnalysisChecker.check().also {
                            reporter.completed(DetectionStage.TIMING_ANALYSIS)
                        }
                    }
                } else {
                    null
                }

            DetectionPipelineOutputs(
                geoIp = geoIp,
                directSigns = directSigns,
                indirectSigns = indirectSigns,
                locationSignals = locationSignals,
                bypassResult = bypassResult,
                dnsLeak = dnsLeakDeferred?.await(),
                webRtcLeak = webRtcDeferred?.await(),
                tlsFingerprint = tlsFingerprintDeferred?.await(),
                timingAnalysis = timingDeferred?.await(),
                icmpSpoofing = icmpSpoofing,
            )
        }

    private fun CategoryResult?.isHomeRoutedRoaming(): Boolean {
        if (this == null) return false
        val hasRussianNetwork = findings.any { it.description == "network_mcc_ru:true" }
        val hasRoaming = findings.any { it.description == "Roaming: yes" }
        val simMcc =
            findings.firstNotNullOfOrNull { finding ->
                SIM_MCC_REGEX.find(finding.description)?.groupValues?.get(1)
            }
        return hasRussianNetwork && hasRoaming && simMcc != null && simMcc != RUSSIA_MCC
    }

    private companion object {
        private const val RUSSIA_MCC = "250"
        private val SIM_MCC_REGEX = Regex("""SIM MCC: (\d{3})""")
    }
}
