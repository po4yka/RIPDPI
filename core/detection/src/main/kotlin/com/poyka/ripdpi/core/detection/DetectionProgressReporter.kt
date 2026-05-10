package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.checker.BypassChecker

internal class DetectionProgressReporter(
    private val onProgress: (suspend (DetectionProgress) -> Unit)?,
) {
    private val completed = mutableSetOf<DetectionStage>()

    suspend fun started(stage: DetectionStage) {
        val message = DetectionStageProgressMessages.messageFor(stage)
        onProgress?.invoke(
            DetectionProgress(
                stage = stage,
                label = message.label,
                detail = message.startDetail,
                completedStages = completed.toSet(),
            ),
        )
    }

    suspend fun completed(stage: DetectionStage) {
        completed.add(stage)
        val message = DetectionStageProgressMessages.messageFor(stage)
        onProgress?.invoke(
            DetectionProgress(
                stage = stage,
                label = message.label,
                detail = "Done",
                completedStages = completed.toSet(),
            ),
        )
    }

    suspend fun bypassProgress(progress: BypassChecker.Progress) {
        onProgress?.invoke(
            DetectionProgress(
                stage = DetectionStage.BYPASS,
                label = "Bypass: ${progress.phase}",
                detail = progress.detail,
                completedStages = completed.toSet(),
            ),
        )
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
        }
}
