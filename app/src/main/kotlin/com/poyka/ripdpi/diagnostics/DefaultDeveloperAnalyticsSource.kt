package com.poyka.ripdpi.diagnostics

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.ceil

private const val BreadcrumbRingCapacity = 60
private const val NativeLogTailLines = 80
private const val PanicBacktraceTailBytes = 6_144
private const val BytesPerMb = 1_048_576L
private const val DeveloperMessagePreviewLimit = 240
private const val NativeDigestBufferBytes = 16 * 1024
private const val BatteryPercentMax = 100
private const val DeveloperBaselineVersion = "device-local-v1"
private const val StageSuccessRateMetric = "stage_success_rate"
private const val BaselineClassDeviceLocalV1 = "device-local-distinct-networks-v1"
private const val BaselineSourceDeviceLocalCache = "device_local_probe_result_cache"
private const val MinimumBaselineSampleCount = 5
private const val BaselineP50Percentile = 0.50
private const val BaselineP95Percentile = 0.95
private const val BaselineAbove = "above_baseline"
private const val BaselineBelow = "below_baseline"
private const val BaselineWithin = "within_baseline"
private const val BaselineInsufficientSample = "insufficient_sample"
private const val FailureFactPreviewLimit = 5
private val DeveloperFailureStageKeyPattern = Regex("[a-z0-9_]+")

internal fun buildDeveloperStageTimings(context: DeveloperAnalyticsContext): List<DeveloperStageTimingEntry> =
    context.stageTimings.map { timing ->
        DeveloperStageTimingEntry(
            stageKey = timing.stageKey,
            wallClockMs = timing.wallClockMs,
            cpuMs = timing.cpuMs,
            dnsMs = timing.dnsMs,
            tcpHandshakeMs = timing.tcpHandshakeMs,
            tlsHandshakeMs = timing.tlsHandshakeMs,
            ttfbMs = timing.ttfbMs,
            notes = timing.notes,
        )
    }

internal fun buildDeveloperFailureEnvelopes(context: DeveloperAnalyticsContext): List<DeveloperFailureEnvelopeEntry> =
    context.failureEnvelopes.mapNotNull { envelope ->
        val stageKey = envelope.stageKey.takeIf(DeveloperFailureStageKeyPattern::matches) ?: return@mapNotNull null
        val facts =
            envelope.facts
                .asSequence()
                .filter { fact -> fact.observationIndex >= 0 }
                .map { fact ->
                    RenderedDeveloperFailureFact(
                        category = fact.category,
                        reference =
                            "stages/$stageKey/report.json#/observations/${fact.observationIndex}/" +
                                "${fact.field.reportPath}=${fact.field.wireToken(fact.value)}",
                    )
                }.distinct()
                .toList()
        DeveloperFailureEnvelopeEntry(
            stageKey = stageKey,
            stageLabel = stageKey,
            headline = "Typed probe failures recorded",
            summary = "${facts.size} typed failure facts; see referenced stage report observations.",
            tcpErrors = facts.referencesFor(DeveloperFailureCategory.TCP),
            tlsErrors = facts.referencesFor(DeveloperFailureCategory.TLS),
            dnsErrors = facts.referencesFor(DeveloperFailureCategory.DNS),
            httpErrors = facts.referencesFor(DeveloperFailureCategory.HTTP),
            quicErrors = facts.referencesFor(DeveloperFailureCategory.QUIC),
        )
    }

private data class RenderedDeveloperFailureFact(
    val category: DeveloperFailureCategory,
    val reference: String,
)

private fun List<RenderedDeveloperFailureFact>.referencesFor(category: DeveloperFailureCategory): List<String> =
    asSequence()
        .filter { fact -> fact.category == category }
        .map { fact -> fact.reference }
        .take(FailureFactPreviewLimit)
        .toList()

internal fun buildDeveloperBaselineDelta(
    context: DeveloperAnalyticsContext,
    baselineSamples: List<CachedProbeOutcome>,
): DeveloperBaselineDelta? {
    val composite =
        context.homeCompositeOutcome
            ?.takeIf { outcome -> outcome.stageSummaries.isNotEmpty() }
            ?: return null
    val totalStageCount = composite.stageSummaries.size
    val userValue = composite.completedStageCount.toDouble() / totalStageCount
    val cohortSamples =
        baselineSamples
            .asSequence()
            .filterNot { sample -> sample.fingerprintHash == composite.fingerprintHash }
            .filter { sample ->
                val sampleTotalStageCount = sample.totalStageCount
                sampleTotalStageCount != null &&
                    sampleTotalStageCount == totalStageCount &&
                    sample.completedStageCount in 0..sampleTotalStageCount
            }.groupBy(CachedProbeOutcome::fingerprintHash)
            .values
            .mapNotNull { samples -> samples.maxByOrNull(CachedProbeOutcome::cachedAtMs) }
            .sortedBy(CachedProbeOutcome::cachedAtMs)
    val distribution =
        cohortSamples
            .mapNotNull { sample ->
                sample.totalStageCount?.let { sampleTotalStageCount ->
                    sample.completedStageCount.toDouble() / sampleTotalStageCount
                }
            }.sorted()
    val p50 = distribution.nearestRankPercentile(BaselineP50Percentile)
    val p95 = distribution.nearestRankPercentile(BaselineP95Percentile)
    val verdict =
        when {
            distribution.size < MinimumBaselineSampleCount || p50 == null || p95 == null -> BaselineInsufficientSample
            userValue < p50 -> BaselineBelow
            userValue >= p95 -> BaselineAbove
            else -> BaselineWithin
        }
    val baseline =
        DeveloperBaselineDistribution(
            cohort = "device_local_distinct_networks:${totalStageCount}_stages",
            sampleCount = distribution.size,
            p50 = p50,
            p95 = p95,
            asOfDate = cohortSamples.maxOfOrNull(CachedProbeOutcome::cachedAtMs)?.let(::isoDateUtc),
            source = BaselineSourceDeviceLocalCache,
        )
    return DeveloperBaselineDelta(
        baselineClass = BaselineClassDeviceLocalV1,
        baselineVersion = DeveloperBaselineVersion,
        comparisons =
            listOf(
                DeveloperBaselineMetric(
                    metric = StageSuccessRateMetric,
                    userValue = "%.2f".format(Locale.US, userValue),
                    baseline = baseline,
                    verdict = verdict,
                ),
            ),
    )
}

private fun List<Double>.nearestRankPercentile(percentile: Double): Double? {
    if (isEmpty()) return null
    val index = (ceil(percentile * size).toInt() - 1).coerceIn(indices)
    return this[index]
}

private fun isoDateUtc(timestampMs: Long): String =
    Instant
        .ofEpochMilli(timestampMs)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .toString()

/**
 * Lightweight in-memory ring buffer for breadcrumbs surfaced into
 * `developer-analytics.json`. Add breadcrumbs from any layer that wants them
 * bundled into the archive (navigation, connection state, permissions, etc.).
 */
@Singleton
class DeveloperBreadcrumbBuffer
    @Inject
    constructor() {
        private val entries = ArrayDeque<DeveloperBreadcrumb>(BreadcrumbRingCapacity)

        @Synchronized
        fun record(
            category: String,
            message: String,
            timestampMs: Long = System.currentTimeMillis(),
        ) {
            if (entries.size >= BreadcrumbRingCapacity) entries.removeFirst()
            entries.addLast(
                DeveloperBreadcrumb(
                    timestampMs = timestampMs,
                    category = category,
                    message = message.take(DeveloperMessagePreviewLimit),
                ),
            )
        }

        @Synchronized
        fun snapshot(): List<DeveloperBreadcrumb> = entries.toList()
    }

@Singleton
class DefaultDeveloperAnalyticsSource
    @Inject
    constructor(
        @param:ApplicationContext private val appContext: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val probeResultCache: ProbeResultCache,
        private val breadcrumbBuffer: DeveloperBreadcrumbBuffer,
        @param:Named("gitCommit") private val gitCommit: String,
        @param:Named("nativeLibVersion") private val nativeLibVersion: String,
    ) : DeveloperAnalyticsSource {
        override suspend fun collect(context: DeveloperAnalyticsContext): DeveloperAnalyticsPayload =
            withContext(Dispatchers.IO) {
                val settings = runCatching { appSettingsRepository.settings.first() }.getOrNull()
                val baselineSamples = runCatching { probeResultCache.snapshot() }.getOrDefault(emptyList())
                DeveloperAnalyticsPayload(
                    schemaVersion = DeveloperAnalyticsSchemaVersion,
                    generatedAtIsoUtc = isoNowUtc(),
                    stageTimings = buildDeveloperStageTimings(context),
                    failureEnvelopes = buildDeveloperFailureEnvelopes(context),
                    reproductionContext = buildReproductionContext(),
                    nativeRuntime = buildNativeRuntime(),
                    effectiveConfigDiff = settings?.let(::buildConfigDiff).orEmpty(),
                    pcapManifest = buildPcapManifest(context),
                    networkSnapshots = buildNetworkSnapshots(),
                    deviceState = buildDeviceState(),
                    breadcrumbs = breadcrumbBuffer.snapshot(),
                    baselineDelta = buildDeveloperBaselineDelta(context, baselineSamples),
                    notes = buildNotes(),
                )
            }

        private fun buildReproductionContext(): DeveloperReproductionContext {
            val digests = computeNativeLibDigests()
            return DeveloperReproductionContext(
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                buildCommit = gitCommit.takeIf { it.isNotBlank() },
                buildFlavor = BuildConfig.BUILD_TYPE,
                buildType = BuildConfig.BUILD_TYPE,
                buildTimestampIsoUtc = null,
                nativeLibVersion = nativeLibVersion.takeIf { it.isNotBlank() },
                nativeLibDigests = digests,
                kotlinVersion = KotlinVersion.CURRENT.toString(),
                rustToolchain = null,
                ndkVersion = null,
                cargoProfile = null,
                runRandomSeed = null,
                featureFlags = emptyMap(),
            )
        }

        private fun computeNativeLibDigests(): Map<String, String> {
            val nativeDir = appContext.applicationInfo.nativeLibraryDir?.let(::File) ?: return emptyMap()
            val nativeFiles =
                nativeDir
                    .takeIf { it.exists() && it.isDirectory }
                    ?.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".so") }
                    .orEmpty()
            val digest = MessageDigest.getInstance("SHA-256")
            return nativeFiles.associate { file ->
                digest.reset()
                file.inputStream().use { stream ->
                    val buffer = ByteArray(NativeDigestBufferBytes)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                file.name to digest.digest().joinToString(separator = "") { b -> "%02x".format(b) }
            }
        }

        private fun buildNativeRuntime(): DeveloperNativeRuntimeSnapshot {
            val fdDir = File("/proc/self/fd")
            val taskDir = File("/proc/self/task")
            val statusFile = File("/proc/self/status")
            val vmSize = parseProcStatusKb(statusFile, "VmSize")
            val vmRss = parseProcStatusKb(statusFile, "VmRSS")
            val logTail = runCatching { readLogcatTail() }.getOrDefault(emptyList())
            val panic = runCatching { readLastPanic() }.getOrNull()
            return DeveloperNativeRuntimeSnapshot(
                openFileDescriptors = fdDir.takeIf { it.isDirectory }?.listFiles()?.size,
                threadCount = taskDir.takeIf { it.isDirectory }?.listFiles()?.size,
                virtualMemoryKb = vmSize,
                residentSetKb = vmRss,
                recentLogTail = logTail,
                lastPanicBacktrace = panic,
            )
        }

        private fun parseProcStatusKb(
            statusFile: File,
            key: String,
        ): Long? {
            if (!statusFile.exists()) return null
            return runCatching {
                statusFile.useLines { lines ->
                    val match = lines.firstOrNull { it.startsWith("$key:") }
                    match
                        ?.substringAfter(":")
                        ?.trim()
                        ?.removeSuffix(" kB")
                        ?.trim()
                        ?.toLongOrNull()
                }
            }.getOrNull()
        }

        private fun readLogcatTail(): List<String> {
            val process =
                ProcessBuilder("logcat", "-d", "-t", "$NativeLogTailLines", "-v", "time", "ripdpi:*", "*:S")
                    .redirectErrorStream(true)
                    .start()
            val lines =
                process.inputStream.bufferedReader().use { it.readLines() }
            process.waitFor()
            return lines.takeLast(NativeLogTailLines).map { it.take(DeveloperMessagePreviewLimit) }
        }

        private fun readLastPanic(): String? {
            val latest =
                File(appContext.filesDir, "native_panics")
                    .takeIf { it.isDirectory }
                    ?.listFiles()
                    ?.maxByOrNull { it.lastModified() }
            return latest
                ?.readBytes()
                ?.takeLast(PanicBacktraceTailBytes)
                ?.toByteArray()
                ?.let { bytes -> String(bytes, Charsets.UTF_8) }
        }

        private fun buildConfigDiff(settings: AppSettings): List<DeveloperConfigDiffEntry> {
            val defaults = AppSettings.getDefaultInstance()
            if (settings == defaults) return emptyList()
            val entries = mutableListOf<DeveloperConfigDiffEntry>()

            fun <T> add(
                key: String,
                actual: T,
                default: T,
            ) {
                if (actual != default) {
                    entries +=
                        DeveloperConfigDiffEntry(
                            key = key,
                            defaultValue = default?.toString(),
                            actualValue = actual?.toString(),
                        )
                }
            }
            add("dnsMode", settings.dnsMode, defaults.dnsMode)
            add("fullTunnelMode", settings.fullTunnelMode, defaults.fullTunnelMode)
            add("entropyMode", settings.entropyMode, defaults.entropyMode)
            add("tlsFingerprintProfile", settings.tlsFingerprintProfile, defaults.tlsFingerprintProfile)
            add("webrtcProtectionEnabled", settings.webrtcProtectionEnabled, defaults.webrtcProtectionEnabled)
            add("strategyEvolution", settings.strategyEvolution, defaults.strategyEvolution)
            add("rootModeEnabled", settings.rootModeEnabled, defaults.rootModeEnabled)
            add("enableCmdSettings", settings.enableCmdSettings, defaults.enableCmdSettings)
            add("proxyPort", settings.proxyPort, defaults.proxyPort)
            return entries
        }

        private fun buildPcapManifest(context: DeveloperAnalyticsContext): List<DeveloperPcapFileEntry> =
            context.pcapFiles.map { file ->
                DeveloperPcapFileEntry(
                    name = file.name,
                    sizeBytes = file.length(),
                    capturedAtIsoUtc = Instant.ofEpochMilli(file.lastModified()).toString(),
                )
            }

        private fun buildNetworkSnapshots(): List<DeveloperNetworkSnapshot> {
            val cm = appContext.getSystemService(ConnectivityManager::class.java)
            val activeNetwork = cm?.activeNetwork
            val caps = activeNetwork?.let(cm::getNetworkCapabilities)
            val linkProps = activeNetwork?.let(cm::getLinkProperties)
            return caps
                ?.let { capabilities ->
                    listOf(
                        DeveloperNetworkSnapshot(
                            stageKey = null,
                            capturedAtIsoUtc = isoNowUtc(),
                            transport =
                                when {
                                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                                    else -> null
                                },
                            operatorOrSsid = null,
                            dnsServers = linkProps?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
                            signalStrengthDbm =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    capabilities.signalStrength
                                } else {
                                    null
                                },
                            cellularLevel = null,
                            linkDownstreamKbps = capabilities.linkDownstreamBandwidthKbps,
                            linkUpstreamKbps = capabilities.linkUpstreamBandwidthKbps,
                            captivePortalDetected =
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                            meteredNetwork =
                                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                            vpnActive = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                            mtu = linkPropertiesMtuOrNull(linkProps),
                            handoverEvents = emptyList(),
                        ),
                    )
                }.orEmpty()
        }

        private fun buildDeviceState(): DeveloperDeviceState {
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            val batteryManager = appContext.getSystemService(BatteryManager::class.java)
            val activityManager = appContext.getSystemService(ActivityManager::class.java)
            val memoryInfo = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
            val batteryPercent =
                batteryManager
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    ?.takeIf { it in 0..BatteryPercentMax }
            val charging = batteryManager?.isCharging
            val standbyBucket =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    appContext.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket?.toString()
                } else {
                    null
                }
            val securityPatch =
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.M
                ) {
                    Build.VERSION.SECURITY_PATCH
                } else {
                    null
                }
            return DeveloperDeviceState(
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                androidSdk = Build.VERSION.SDK_INT,
                androidSecurityPatch = securityPatch,
                abi = Build.SUPPORTED_ABIS.firstOrNull(),
                locale = Locale.getDefault().toLanguageTag(),
                timeZone = TimeZone.getDefault().id,
                batteryPercent = batteryPercent,
                batteryCharging = charging,
                thermalStatus =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        powerManager?.currentThermalStatus?.toString()
                    } else {
                        null
                    },
                dozeModeActive =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        powerManager?.isDeviceIdleMode
                    } else {
                        null
                    },
                powerSaveActive = powerManager?.isPowerSaveMode,
                appStandbyBucket = standbyBucket,
                availableMemoryMb = memoryInfo.availMem / BytesPerMb,
                totalMemoryMb = memoryInfo.totalMem / BytesPerMb,
                lowMemory = memoryInfo.lowMemory,
            )
        }

        private fun buildNotes(): List<String> {
            val notes = mutableListOf<String>()
            notes += "Stage wallClockMs reflects the scan session duration (finishedAt - startedAt)."
            notes +=
                "Stage cpuMs is app-process CPU consumed during the stage window; concurrent stage windows may overlap."
            notes += "Protocol phase timings are sums of measurements emitted by individual probes."
            notes += "Failure envelopes are projected from typed probe observations."
            notes +=
                "Baseline delta uses cached runs from other device-local network fingerprints " +
                "with the same stage count; verdicts require at least " +
                "$MinimumBaselineSampleCount samples."
            return notes
        }

        private fun isoNowUtc(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
    }
