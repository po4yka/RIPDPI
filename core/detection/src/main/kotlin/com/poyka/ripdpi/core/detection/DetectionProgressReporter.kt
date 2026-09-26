package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.checker.BypassChecker
import kotlinx.coroutines.sync.Mutex

internal class DetectionProgressReporter(
    private val onProgress: (suspend (DetectionProgress) -> Unit)?,
) {
    private val completed = mutableSetOf<DetectionStage>()
    private val progressMutex = Mutex()

    suspend fun started(stage: DetectionStage) {
        progressMutex.lock()
        try {
            val message = DetectionStageProgressMessages.messageFor(stage)
            onProgress?.invoke(
                DetectionProgress(stage, message.label, message.startDetail, completed.toSet()),
            )
        } finally {
            progressMutex.unlock()
        }
    }

    suspend fun completed(stage: DetectionStage) {
        progressMutex.lock()
        try {
            completed.add(stage)
            val message = DetectionStageProgressMessages.messageFor(stage)
            onProgress?.invoke(
                DetectionProgress(stage, message.label, "Done", completed.toSet()),
            )
        } finally {
            progressMutex.unlock()
        }
    }

    suspend fun bypassProgress(progress: BypassChecker.Progress) {
        progressMutex.lock()
        try {
            onProgress?.invoke(
                DetectionProgress(
                    DetectionStage.BYPASS,
                    "Bypass: ${progress.phase}",
                    progress.detail,
                    completed.toSet(),
                ),
            )
        } finally {
            progressMutex.unlock()
        }
    }
}

private data class DetectionStageProgressMessage(
    val label: String,
    val startDetail: String,
)

private object DetectionStageProgressMessages {
    fun messageFor(stage: DetectionStage): DetectionStageProgressMessage =
        when (stage) {
            DetectionStage.GEO_IP -> {
                DetectionStageProgressMessage("GeoIP", "Checking IP geolocation...")
            }

            DetectionStage.DIRECT_SIGNS -> {
                DetectionStageProgressMessage("Direct signs", "Checking VPN transport and installed apps...")
            }

            DetectionStage.INDIRECT_SIGNS -> {
                DetectionStageProgressMessage("Indirect signs", "Checking network interfaces and DNS...")
            }

            DetectionStage.LOCATION_SIGNALS -> {
                DetectionStageProgressMessage("Location", "Checking cellular signals...")
            }

            DetectionStage.BYPASS -> {
                DetectionStageProgressMessage("Bypass", "Checking proxy bypass...")
            }

            DetectionStage.DNS_LEAK -> {
                DetectionStageProgressMessage("DNS Leak", "Checking DNS leak exposure...")
            }

            DetectionStage.WEBRTC_LEAK -> {
                DetectionStageProgressMessage("WebRTC Leak", "Checking WebRTC exposure...")
            }

            DetectionStage.TLS_FINGERPRINT -> {
                DetectionStageProgressMessage("TLS Fingerprint", "Checking TLS fingerprint profile...")
            }

            DetectionStage.TIMING_ANALYSIS -> {
                DetectionStageProgressMessage("Timing Analysis", "Checking timing side channels...")
            }

            DetectionStage.ICMP_SPOOFING -> {
                DetectionStageProgressMessage("ICMP Spoofing", "Checking ICMP spoofing signals...")
            }

            DetectionStage.IP_COMPARISON -> {
                DetectionStageProgressMessage("IP Comparison", "Comparing public IP reflection endpoints...")
            }

            DetectionStage.RTT_TRIANGULATION -> {
                DetectionStageProgressMessage("RTT Triangulation", "Measuring RTT to Russian and foreign targets...")
            }

            DetectionStage.CDN_PULLING -> {
                DetectionStageProgressMessage("CDN Pulling", "Checking CDN trace endpoints...")
            }

            DetectionStage.NATIVE_SIGNS -> {
                DetectionStageProgressMessage("Native Signs", "Checking native interface and process signals...")
            }

            DetectionStage.CALL_TRANSPORT -> {
                DetectionStageProgressMessage("Call Transport", "Checking STUN and MTProto call transport paths...")
            }
        }
}
