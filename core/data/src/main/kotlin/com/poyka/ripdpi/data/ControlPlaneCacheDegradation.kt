package com.poyka.ripdpi.data

enum class ControlPlaneCacheDegradationCode {
    CachedSnapshotUnreadable,
    CachedSnapshotIncompatible,
}

data class ControlPlaneCacheDegradation(
    val code: ControlPlaneCacheDegradationCode,
    val detail: String? = null,
)
