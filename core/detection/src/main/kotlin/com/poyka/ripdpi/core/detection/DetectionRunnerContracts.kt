package com.poyka.ripdpi.core.detection

import android.content.Context
import com.poyka.ripdpi.core.detection.checker.BypassChecker

data class DetectionRunnerConfig(
    val ownProxyPort: Int? = null,
    val ownPackageName: String? = null,
    val includeBypassCheck: Boolean = true,
    val includeLocationCheck: Boolean = true,
    val includeDnsLeakCheck: Boolean = true,
    val includeWebRtcCheck: Boolean = true,
    val includeTlsFingerprintCheck: Boolean = true,
    val includeTimingAnalysis: Boolean = true,
    val includeIcmpSpoofingCheck: Boolean = false,
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
    ICMP_SPOOFING,
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

interface IcmpSpoofingCheckerPort {
    suspend fun check(homeRoutedRoaming: Boolean): IcmpSpoofingResult
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
