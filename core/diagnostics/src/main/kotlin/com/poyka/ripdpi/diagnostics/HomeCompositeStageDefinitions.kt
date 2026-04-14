package com.poyka.ripdpi.diagnostics

private const val DpiFullStageTimeoutMs = 240_000L
private const val StrategyProbeStageTimeoutMs = 300_000L
private const val DefaultStageTimeoutMs = 120_000L
private const val PathComparisonStageTimeoutMs = 180_000L
private const val ThrottlingStageTimeoutMs = 240_000L
private const val CircumventionStageTimeoutMs = 240_000L
private const val QuickScanStrategyProbeTimeoutMs = 90_000L

internal const val DetectionStageTimeoutMs = 90_000L
internal const val StageRetryDelayMs = 2_000L
internal const val QuickScanMaxCandidates = 5

internal enum class HomeCompositeStageKind {
    PROFILE_SCAN,
    DETECTION_SIGNALS,
}

internal const val DetectionStageProfileId = "detection-signals"

internal data class HomeCompositeStageSpec(
    val key: String,
    val label: String,
    val profileId: String,
    val pathMode: ScanPathMode,
    val kind: HomeCompositeStageKind = HomeCompositeStageKind.PROFILE_SCAN,
)

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
            label = "Circumvention reach",
            profileId = "ru-circumvention",
            pathMode = ScanPathMode.RAW_PATH,
        ),
        HomeCompositeStageSpec(
            key = "path_comparison",
            label = "VPN vs direct path",
            profileId = "path-comparison",
            pathMode = ScanPathMode.RAW_PATH,
        ),
        HomeCompositeStageSpec(
            key = "dpi_full",
            label = "DPI detector full",
            profileId = "ru-dpi-full",
            pathMode = ScanPathMode.RAW_PATH,
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

    data object VpnHalted : StageSessionSignal
}

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
        spec.profileId == "ru-circumvention" -> CircumventionStageTimeoutMs
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
