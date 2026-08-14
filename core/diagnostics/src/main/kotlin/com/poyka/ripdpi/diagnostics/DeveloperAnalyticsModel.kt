package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

/**
 * Developer-facing analytics payload bundled into the diagnostics share archive as
 * `developer-analytics.json`. Each section is nullable / defaulted so the payload
 * degrades gracefully when any single signal is unavailable.
 */
@Serializable
data class DeveloperAnalyticsPayload(
    val schemaVersion: Int = 1,
    val generatedAtIsoUtc: String? = null,
    val stageTimings: List<DeveloperStageTimingEntry> = emptyList(),
    val failureEnvelopes: List<DeveloperFailureEnvelopeEntry> = emptyList(),
    val reproductionContext: DeveloperReproductionContext? = null,
    val nativeRuntime: DeveloperNativeRuntimeSnapshot? = null,
    val effectiveConfigDiff: List<DeveloperConfigDiffEntry> = emptyList(),
    val pcapManifest: List<DeveloperPcapFileEntry> = emptyList(),
    val networkSnapshots: List<DeveloperNetworkSnapshot> = emptyList(),
    val deviceState: DeveloperDeviceState? = null,
    val breadcrumbs: List<DeveloperBreadcrumb> = emptyList(),
    val baselineDelta: DeveloperBaselineDelta? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
data class DeveloperStageTimingEntry(
    val stageKey: String,
    val wallClockMs: Long? = null,
    val cpuMs: Long? = null,
    val dnsMs: Long? = null,
    val tcpHandshakeMs: Long? = null,
    val tlsHandshakeMs: Long? = null,
    val ttfbMs: Long? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
data class DeveloperFailureEnvelopeEntry(
    val stageKey: String,
    val stageLabel: String,
    val headline: String,
    val summary: String,
    val tcpErrors: List<String> = emptyList(),
    val tlsErrors: List<String> = emptyList(),
    val dnsErrors: List<String> = emptyList(),
    val httpErrors: List<String> = emptyList(),
)

@Serializable
data class DeveloperReproductionContext(
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    val buildCommit: String? = null,
    val buildFlavor: String? = null,
    val buildType: String? = null,
    val buildTimestampIsoUtc: String? = null,
    val nativeLibVersion: String? = null,
    val nativeLibDigests: Map<String, String> = emptyMap(),
    val kotlinVersion: String? = null,
    val rustToolchain: String? = null,
    val ndkVersion: String? = null,
    val cargoProfile: String? = null,
    val runRandomSeed: String? = null,
    val featureFlags: Map<String, String> = emptyMap(),
)

@Serializable
data class DeveloperNativeRuntimeSnapshot(
    val openFileDescriptors: Int? = null,
    val threadCount: Int? = null,
    val virtualMemoryKb: Long? = null,
    val residentSetKb: Long? = null,
    val recentLogTail: List<String> = emptyList(),
    val lastPanicBacktrace: String? = null,
)

@Serializable
data class DeveloperConfigDiffEntry(
    val key: String,
    val defaultValue: String?,
    val actualValue: String?,
)

@Serializable
data class DeveloperPcapFileEntry(
    val name: String,
    val sizeBytes: Long,
    val capturedAtIsoUtc: String? = null,
)

@Serializable
data class DeveloperNetworkSnapshot(
    val stageKey: String? = null,
    val capturedAtIsoUtc: String? = null,
    val transport: String? = null,
    val operatorOrSsid: String? = null,
    val dnsServers: List<String> = emptyList(),
    val signalStrengthDbm: Int? = null,
    val cellularLevel: Int? = null,
    val linkDownstreamKbps: Int? = null,
    val linkUpstreamKbps: Int? = null,
    val captivePortalDetected: Boolean? = null,
    val meteredNetwork: Boolean? = null,
    val vpnActive: Boolean? = null,
    val mtu: Int? = null,
    val handoverEvents: List<String> = emptyList(),
)

@Serializable
data class DeveloperDeviceState(
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val androidSdk: Int? = null,
    val androidSecurityPatch: String? = null,
    val abi: String? = null,
    val locale: String? = null,
    val timeZone: String? = null,
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean? = null,
    val thermalStatus: String? = null,
    val dozeModeActive: Boolean? = null,
    val powerSaveActive: Boolean? = null,
    val appStandbyBucket: String? = null,
    val availableMemoryMb: Long? = null,
    val totalMemoryMb: Long? = null,
    val lowMemory: Boolean? = null,
)

@Serializable
data class DeveloperBreadcrumb(
    val timestampMs: Long,
    val category: String,
    val message: String,
)

@Serializable
data class DeveloperBaselineDelta(
    val baselineClass: String? = null,
    val baselineVersion: String? = null,
    val comparisons: List<DeveloperBaselineMetric> = emptyList(),
)

@Serializable
data class DeveloperBaselineMetric(
    val metric: String,
    val userValue: String?,
    val baselineMedian: String?,
    val verdict: String,
)
