package com.poyka.ripdpi.core.detection.probe

internal const val LocalScanDeadlineMs = 30_000L

enum class LocalScanStatus(
    val isInconclusive: Boolean = false,
) {
    MATCHED,
    NO_MATCH,
    REJECTED(true),
    TIMED_OUT(true),
    UNAVAILABLE(true),
    PARTIAL(true),
    SKIPPED,
}

internal fun LocalScanStatus.merge(other: LocalScanStatus): LocalScanStatus =
    when {
        this == other -> this
        this == LocalScanStatus.MATCHED || other == LocalScanStatus.MATCHED -> LocalScanStatus.MATCHED
        this == LocalScanStatus.SKIPPED -> other
        other == LocalScanStatus.SKIPPED -> this
        this == LocalScanStatus.NO_MATCH -> other
        other == LocalScanStatus.NO_MATCH -> this
        else -> LocalScanStatus.PARTIAL
    }

internal data class LocalScanResult<out T>(
    val match: T?,
    val status: LocalScanStatus,
)
