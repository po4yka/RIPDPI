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

private const val BreadcrumbRingCapacity = 60
private const val NativeLogTailLines = 80
private const val PanicBacktraceTailBytes = 6_144
private const val BytesPerMb = 1_048_576L
private const val TcpErrorPreviewLimit = 5
private const val TlsErrorPreviewLimit = 5
private const val DnsErrorPreviewLimit = 5
private const val HttpErrorPreviewLimit = 5

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
                    message = message.take(240),
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
        private val breadcrumbBuffer: DeveloperBreadcrumbBuffer,
        @param:Named("gitCommit") private val gitCommit: String,
        @param:Named("nativeLibVersion") private val nativeLibVersion: String,
    ) : DeveloperAnalyticsSource {
        override suspend fun collect(context: DeveloperAnalyticsContext): DeveloperAnalyticsPayload =
            withContext(Dispatchers.IO) {
                val settings = runCatching { appSettingsRepository.settings.first() }.getOrNull()
                DeveloperAnalyticsPayload(
                    schemaVersion = 1,
                    generatedAtIsoUtc = isoNowUtc(),
                    stageTimings = buildStageTimings(context),
                    failureEnvelopes = buildFailureEnvelopes(context),
                    reproductionContext = buildReproductionContext(),
                    nativeRuntime = buildNativeRuntime(),
                    effectiveConfigDiff = settings?.let(::buildConfigDiff).orEmpty(),
                    pcapManifest = buildPcapManifest(context),
                    networkSnapshots = buildNetworkSnapshots(),
                    deviceState = buildDeviceState(),
                    breadcrumbs = breadcrumbBuffer.snapshot(),
                    baselineDelta = buildBaselineDelta(context),
                    notes = buildNotes(),
                )
            }

        private fun buildStageTimings(context: DeveloperAnalyticsContext): List<DeveloperStageTimingEntry> {
            val composite = context.homeCompositeOutcome ?: return emptyList()
            return composite.stageSummaries.map { stage ->
                DeveloperStageTimingEntry(
                    stageKey = stage.stageKey,
                    wallClockMs = null,
                    cpuMs = null,
                    notes =
                        buildList {
                            if (stage.status == DiagnosticsHomeCompositeStageStatus.SKIPPED) add("skipped")
                            if (stage.status == DiagnosticsHomeCompositeStageStatus.FAILED) add("failed")
                            stage.sessionId?.let { add("session=$it") }
                        },
                )
            }
        }

        private fun buildFailureEnvelopes(context: DeveloperAnalyticsContext): List<DeveloperFailureEnvelopeEntry> {
            val composite = context.homeCompositeOutcome ?: return emptyList()
            return composite.stageSummaries
                .filter { it.status == DiagnosticsHomeCompositeStageStatus.FAILED }
                .map { stage ->
                    val summary = stage.summary
                    DeveloperFailureEnvelopeEntry(
                        stageKey = stage.stageKey,
                        stageLabel = stage.stageLabel,
                        headline = stage.headline,
                        summary = summary,
                        tcpErrors = extractHints(summary, listOf("RST", "reset", "refused", "timed out")).take(TcpErrorPreviewLimit),
                        tlsErrors = extractHints(summary, listOf("TLS", "certificate", "handshake", "SNI")).take(TlsErrorPreviewLimit),
                        dnsErrors = extractHints(summary, listOf("NXDOMAIN", "SERVFAIL", "resolve", "dns")).take(DnsErrorPreviewLimit),
                        httpErrors = extractHints(summary, listOf("HTTP", "status=", "4", "5")).take(HttpErrorPreviewLimit),
                    )
                }
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
            if (!nativeDir.exists() || !nativeDir.isDirectory) return emptyMap()
            val digest = MessageDigest.getInstance("SHA-256")
            return nativeDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".so") }
                ?.associate { file ->
                    digest.reset()
                    file.inputStream().use { stream ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    file.name to digest.digest().joinToString(separator = "") { b -> "%02x".format(b) }
                }.orEmpty()
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
                    match?.substringAfter(":")?.trim()?.removeSuffix(" kB")?.trim()?.toLongOrNull()
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
            return lines.takeLast(NativeLogTailLines).map { it.take(240) }
        }

        private fun readLastPanic(): String? {
            val files =
                File(appContext.filesDir, "native_panics")
                    .takeIf { it.isDirectory }
                    ?.listFiles()
                    ?: return null
            val latest = files.maxByOrNull { it.lastModified() } ?: return null
            val bytes = latest.readBytes().takeLast(PanicBacktraceTailBytes).toByteArray()
            return String(bytes, Charsets.UTF_8)
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
            val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
            val activeNetwork = cm.activeNetwork ?: return emptyList()
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return emptyList()
            val linkProps = cm.getLinkProperties(activeNetwork)
            return listOf(
                DeveloperNetworkSnapshot(
                    stageKey = null,
                    capturedAtIsoUtc = isoNowUtc(),
                    transport =
                        when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                            else -> null
                        },
                    operatorOrSsid = null,
                    dnsServers = linkProps?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
                    signalStrengthDbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps.signalStrength else null,
                    cellularLevel = null,
                    linkDownstreamKbps = caps.linkDownstreamBandwidthKbps,
                    linkUpstreamKbps = caps.linkUpstreamBandwidthKbps,
                    captivePortalDetected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                    meteredNetwork = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    vpnActive = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    mtu = linkProps?.mtu?.takeIf { it > 0 },
                    handoverEvents = emptyList(),
                ),
            )
        }

        private fun buildDeviceState(): DeveloperDeviceState {
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            val batteryManager = appContext.getSystemService(BatteryManager::class.java)
            val activityManager = appContext.getSystemService(ActivityManager::class.java)
            val memoryInfo = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
            val batteryPercent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
            val charging = batteryManager?.isCharging
            val standbyBucket =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    appContext.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket?.toString()
                } else {
                    null
                }
            val securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else null
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

        private fun buildBaselineDelta(context: DeveloperAnalyticsContext): DeveloperBaselineDelta? {
            val composite = context.homeCompositeOutcome ?: return null
            val total = composite.stageSummaries.size
            if (total == 0) return null
            val successRate = composite.completedStageCount.toDouble() / total
            val baselineRate = 0.85
            val verdict =
                when {
                    successRate >= baselineRate + 0.05 -> "above_baseline"
                    successRate <= baselineRate - 0.15 -> "significantly_below_baseline"
                    successRate < baselineRate -> "below_baseline"
                    else -> "within_baseline"
                }
            return DeveloperBaselineDelta(
                baselineClass = "default-v1",
                baselineVersion = "2026-04-14",
                comparisons =
                    listOf(
                        DeveloperBaselineMetric(
                            metric = "stage_success_rate",
                            userValue = "%.2f".format(successRate),
                            baselineMedian = "%.2f".format(baselineRate),
                            verdict = verdict,
                        ),
                    ),
            )
        }

        private fun buildNotes(): List<String> {
            val notes = mutableListOf<String>()
            notes += "Stage timings are coarse placeholders until per-stage timestamps are instrumented."
            notes += "Failure envelopes are heuristically extracted from the stage summary text."
            return notes
        }

        private fun extractHints(
            summary: String,
            keywords: List<String>,
        ): List<String> =
            keywords
                .filter { keyword -> summary.contains(keyword, ignoreCase = true) }
                .map { keyword -> "hint: summary mentions '$keyword'" }

        private fun isoNowUtc(): String =
            DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
    }

