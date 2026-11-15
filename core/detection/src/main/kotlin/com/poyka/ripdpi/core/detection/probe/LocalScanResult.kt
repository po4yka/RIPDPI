package com.poyka.ripdpi.core.detection.probe

internal const val LocalScanDeadlineMs = 30_000L

enum class LocalScanStatus {
    MATCHED,
    NO_MATCH,
    REJECTED,
    TIMED_OUT,
    UNAVAILABLE,
    PARTIAL,
    SKIPPED,
}

internal fun LocalScanStatus.merge(other: LocalScanStatus): LocalScanStatus =
    when {
        this == other -> this
        this == LocalScanStatus.MATCHED || other == LocalScanStatus.MATCHED -> LocalScanStatus.MATCHED
        this == LocalScanStatus.NO_MATCH || this == LocalScanStatus.SKIPPED -> other
        other == LocalScanStatus.NO_MATCH || other == LocalScanStatus.SKIPPED -> this
        else -> LocalScanStatus.PARTIAL
    }

internal data class LocalScanResult<out T>(
    val match: T?,
    val status: LocalScanStatus,
)
