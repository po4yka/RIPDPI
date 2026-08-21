package com.poyka.ripdpi.diagnostics

private const val DpiFullStageTimeoutMs = 240_000L
private const val StrategyProbeStageTimeoutMs = 300_000L
private const val DefaultStageTimeoutMs = 120_000L
private const val PathComparisonStageTimeoutMs = 180_000L
private const val ThrottlingStageTimeoutMs = 240_000L
private const val SensitiveServicesStageTimeoutMs = 240_000L
private const val QuickScanStrategyProbeTimeoutMs = 90_000L

internal const val DetectionStageTimeoutMs = 90_000L

internal enum class HomeCompositeStageKind {
    PROFILE_SCAN,
    DETECTION_SIGNALS,
    PASSIVE_VPN_ROUTE_EVIDENCE,
}

enum class HomeCompositeStageEvidenceType {
    ACTIVE_SCAN,
    DETECTION_SIGNALS,
    PASSIVE_VPN_ROUTE,
}

enum class HomeCompositeStageVantage {
    RAW_PATH,
    PROXY_VANTAGE,
    VPN_ROUTE_OBSERVATION,
}

enum class HomeCompositeTargetReachability {
    VERIFIED_BY_STAGE,
    UNVERIFIED,
}

internal const val DetectionStageProfileId = "detection-signals"

internal data class HomeCompositeStageSpec(
    val key: String,
    val label: String,
    val profileId: String,
    val pathMode: ScanPathMode?,
    val kind: HomeCompositeStageKind = HomeCompositeStageKind.PROFILE_SCAN,
    val evidenceType: HomeCompositeStageEvidenceType = defaultEvidenceType(kind),
    val vantage: HomeCompositeStageVantage = defaultStageVantage(pathMode),
    val targetReachability: HomeCompositeTargetReachability = HomeCompositeTargetReachability.VERIFIED_BY_STAGE,
    val startsScanSession: Boolean = kind != HomeCompositeStageKind.PASSIVE_VPN_ROUTE_EVIDENCE,
)

private fun defaultEvidenceType(kind: HomeCompositeStageKind): HomeCompositeStageEvidenceType =
    when (kind) {
        HomeCompositeStageKind.PROFILE_SCAN -> HomeCompositeStageEvidenceType.ACTIVE_SCAN
        HomeCompositeStageKind.DETECTION_SIGNALS -> HomeCompositeStageEvidenceType.DETECTION_SIGNALS
        HomeCompositeStageKind.PASSIVE_VPN_ROUTE_EVIDENCE -> HomeCompositeStageEvidenceType.PASSIVE_VPN_ROUTE
    }

private fun defaultStageVantage(pathMode: ScanPathMode?): HomeCompositeStageVantage =
    when (pathMode) {
        ScanPathMode.RAW_PATH -> HomeCompositeStageVantage.RAW_PATH
        ScanPathMode.IN_PATH -> HomeCompositeStageVantage.PROXY_VANTAGE
        null -> HomeCompositeStageVantage.VPN_ROUTE_OBSERVATION
    }

internal val HomeCompositeStageSpecs =
    listOf(
        HomeCompositeStageSpec(
            key = "automatic_audit",
            label = "Automatic audit",
            profileId = "automatic-audit",
            pathMode = ScanPathMode.RAW_PATH,
        ),
        HomeCompositeStageSpec(
            key = "detection_signals",
            label = "Detection signals",
            profileId = DetectionStageProfileId,
            pathMode = ScanPathMode.RAW_PATH,
            kind = HomeCompositeStageKind.DETECTION_SIGNALS,
        ),
        HomeCompositeStageSpec(
            key = "default_connectivity",
            label = "Default diagnostics",
            profileId = "default",
            pathMode = ScanPathMode.RAW_PATH,
        ),
        HomeCompositeStageSpec(
            key = "ru_throttling",
            label = "Throttling check",
            profileId = "ru-throttling",
            pathMode = ScanPathMode.RAW_PATH,
        ),
        HomeCompositeStageSpec(
            key = "ru_circumvention",
            label = "Sensitive services reachability",
            // Keep the stable profile id for catalog/session compatibility.
            profileId = "ru-circumvention",
            pathMode = ScanPathMode.RAW_PATH,
        ),
        HomeCompositeStageSpec(
            key = "dpi_full",
            label = "DPI detector full",
            profileId = "ru-dpi-full",
            pathMode = ScanPathMode.RAW_PATH,
        ),
        HomeCompositeStageSpec(
            key = "path_comparison",
            label = "Proxy vs direct path",
            profileId = "path-comparison",
            pathMode = ScanPathMode.IN_PATH,
        ),
        HomeCompositeStageSpec(
            key = "vpn_route_evidence",
            label = "VPN route evidence",
            profileId = "vpn-route-evidence",
            pathMode = null,
            kind = HomeCompositeStageKind.PASSIVE_VPN_ROUTE_EVIDENCE,
            evidenceType = HomeCompositeStageEvidenceType.PASSIVE_VPN_ROUTE,
            vantage = HomeCompositeStageVantage.VPN_ROUTE_OBSERVATION,
            targetReachability = HomeCompositeTargetReachability.UNVERIFIED,
            startsScanSession = false,
        ),
        HomeCompositeStageSpec(
            key = "dpi_strategy",
            label = "DPI strategy probe",
            profileId = "ru-dpi-strategy",
            pathMode = ScanPathMode.RAW_PATH,
        ),
    )

internal val QuickScanStageSpecs =
    listOf(
        HomeCompositeStageSpec(
            key = "automatic_audit",
            label = "Automatic audit",
            profileId = "automatic-audit",
            pathMode = ScanPathMode.RAW_PATH,
        ),
        HomeCompositeStageSpec(
            key = "detection_signals",
            label = "Detection signals",
            profileId = DetectionStageProfileId,
            pathMode = ScanPathMode.RAW_PATH,
            kind = HomeCompositeStageKind.DETECTION_SIGNALS,
        ),
        HomeCompositeStageSpec(
            key = "vpn_route_evidence",
            label = "VPN route evidence",
            profileId = "vpn-route-evidence",
            pathMode = null,
            kind = HomeCompositeStageKind.PASSIVE_VPN_ROUTE_EVIDENCE,
            evidenceType = HomeCompositeStageEvidenceType.PASSIVE_VPN_ROUTE,
            vantage = HomeCompositeStageVantage.VPN_ROUTE_OBSERVATION,
            targetReachability = HomeCompositeTargetReachability.UNVERIFIED,
            startsScanSession = false,
        ),
        HomeCompositeStageSpec(
            key = "dpi_strategy",
            label = "DPI strategy probe",
            profileId = "ru-dpi-strategy",
            pathMode = ScanPathMode.RAW_PATH,
        ),
    )

internal sealed interface StageSessionSignal {
    data class Finished(
        val session: DiagnosticScanSession,
    ) : StageSessionSignal

    data class RawPathSettled(
        val session: DiagnosticScanSession,
        val result: com.poyka.ripdpi.data.RawPathExecutionResult?,
    ) : StageSessionSignal

    data object VpnHalted : StageSessionSignal
}

internal class HomeRawPathSupersededByUserStopException(
    sessionId: String,
) : IllegalStateException("Raw-path runtime settlement was superseded by user stop: $sessionId")

internal fun stageTimeoutMs(
    spec: HomeCompositeStageSpec,
    quickScan: Boolean = false,
): Long =
    when {
        spec.kind == HomeCompositeStageKind.DETECTION_SIGNALS -> DetectionStageTimeoutMs
        quickScan && spec.profileId == "ru-dpi-strategy" -> QuickScanStrategyProbeTimeoutMs
        spec.profileId == "ru-dpi-full" -> DpiFullStageTimeoutMs
        spec.profileId in listOf("automatic-audit", "ru-dpi-strategy") -> StrategyProbeStageTimeoutMs
        spec.profileId == "path-comparison" -> PathComparisonStageTimeoutMs
        spec.profileId == "ru-throttling" -> ThrottlingStageTimeoutMs
        spec.profileId == "ru-circumvention" -> SensitiveServicesStageTimeoutMs
        else -> DefaultStageTimeoutMs
    }

internal inline fun Map<String, DiagnosticsHomeCompositeProgress>.updatedRun(
    runId: String,
    transform: (DiagnosticsHomeCompositeProgress) -> DiagnosticsHomeCompositeProgress,
): Map<String, DiagnosticsHomeCompositeProgress> = this[runId]?.let { this + (runId to transform(it)) } ?: this

internal inline fun List<DiagnosticsHomeCompositeStageSummary>.updated(
    index: Int,
    transform: (DiagnosticsHomeCompositeStageSummary) -> DiagnosticsHomeCompositeStageSummary,
): List<DiagnosticsHomeCompositeStageSummary> =
    mapIndexed { currentIndex, value -> if (currentIndex == index) transform(value) else value }

internal fun List<DiagnosticsHomeCompositeStageSummary>.activeStageIndexAfterUpdate(
    previousIndex: Int?,
    updatedIndex: Int,
): Int? {
    val preferredIndex =
        updatedIndex.takeIf { getOrNull(it)?.status == DiagnosticsHomeCompositeStageStatus.RUNNING }
            ?: previousIndex?.takeIf { getOrNull(it)?.status == DiagnosticsHomeCompositeStageStatus.RUNNING }
    return preferredIndex ?: indexOfLast { it.status == DiagnosticsHomeCompositeStageStatus.RUNNING }.takeIf { it >= 0 }
}
