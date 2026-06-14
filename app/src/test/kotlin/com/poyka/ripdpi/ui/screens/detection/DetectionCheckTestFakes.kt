package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.core.detection.DetectionHistoryRepository
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.community.CommunityStats
import com.poyka.ripdpi.core.detection.community.CommunityStatsRefreshResult
import com.poyka.ripdpi.core.detection.community.CommunityStatsRepository
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeDetectionAppSettingsRepository(
    initial: AppSettings = AppSettings.getDefaultInstance(),
) : AppSettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

internal class FakeDetectionHistoryRepository(
    private val latest: List<DetectionHistoryEntry> = emptyList(),
) : DetectionHistoryRepository {
    val saved = mutableListOf<DetectionHistoryEntry>()
    var loadLatestCount = 0

    override suspend fun save(entry: DetectionHistoryEntry) {
        saved += entry
    }

    override suspend fun loadLatest(count: Int): List<DetectionHistoryEntry> {
        loadLatestCount += 1
        return latest
    }

    override suspend fun findByFingerprint(fingerprint: String): DetectionHistoryEntry? = null

    override suspend fun clear() = Unit
}

internal class FakeCommunityStatsRepository(
    private val cachedOrLocal: CommunityStats? = null,
    private val refreshResult: CommunityStatsRefreshResult? = null,
    private val refreshThrows: Throwable? = null,
) : CommunityStatsRepository {
    var refreshCount = 0

    override suspend fun loadCachedOrLocal(): CommunityStats? = cachedOrLocal

    override suspend fun refresh(): CommunityStatsRefreshResult {
        refreshCount += 1
        refreshThrows?.let { throw it }
        return refreshResult ?: CommunityStatsRefreshResult(
            localStats = CommunityStats(totalReports = 0),
            remoteStats = null,
            remoteError = null,
        )
    }
}

internal class FakeNetworkFingerprintProvider(
    private val fingerprint: NetworkFingerprint? = null,
) : NetworkFingerprintProvider {
    var captureCount = 0

    override fun capture(): NetworkFingerprint? {
        captureCount += 1
        return fingerprint
    }
}

internal class FakeDetectionPermissionChecker(
    private val granted: Set<String> = emptySet(),
) : DetectionPermissionChecker {
    override fun isPermissionGranted(permission: String): Boolean = permission in granted
}

internal class FakeDetectionCheckPreferenceStore(
    private var onboardingShown: Boolean = false,
    requestedPermissions: Set<String> = emptySet(),
) : DetectionCheckPreferenceStore {
    val requested = requestedPermissions.toMutableSet()
    var markOnboardingShownCount = 0

    override fun wasOnboardingShown(): Boolean = onboardingShown

    override fun markOnboardingShown() {
        markOnboardingShownCount += 1
        onboardingShown = true
    }

    override fun wasPermissionRequested(permission: String): Boolean = permission in requested

    override fun markPermissionsRequested(permissions: Array<String>) {
        requested += permissions
    }
}

internal class RecordingStringResolver : StringResolver {
    override fun getString(
        resId: Int,
        vararg formatArgs: Any,
    ): String = "string:$resId"
}

internal fun detectionResult(verdict: Verdict = Verdict.NOT_DETECTED): DetectionCheckResult =
    DetectionCheckResult(
        geoIp = emptyCategory("GeoIP"),
        directSigns = emptyCategory("Direct"),
        indirectSigns = emptyCategory("Indirect"),
        locationSignals = emptyCategory("Location"),
        bypassResult =
            BypassResult(
                proxyEndpoint = null,
                directIp = null,
                proxyIp = null,
                xrayApiScanResult = null,
                findings = emptyList(),
                detected = false,
            ),
        verdict = verdict,
    )

private fun emptyCategory(name: String): CategoryResult =
    CategoryResult(
        name = name,
        detected = false,
        findings = emptyList(),
    )

internal fun wifiFingerprint(): NetworkFingerprint =
    NetworkFingerprint(
        transport = "wifi",
        networkValidated = true,
        captivePortalDetected = false,
        privateDnsMode = "system",
        dnsServers = listOf("1.1.1.1"),
        wifi =
            com.poyka.ripdpi.data.WifiNetworkIdentityTuple(
                ssid = "ssid",
                bssid = "aa:bb:cc:dd:ee:ff",
                gateway = "192.168.0.1",
            ),
    )
