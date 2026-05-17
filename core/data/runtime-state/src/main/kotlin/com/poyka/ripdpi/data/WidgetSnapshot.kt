package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

@Serializable
data class WidgetSnapshot(
    val status: AppStatus = AppStatus.Halted,
    val mode: Mode? = null,
    val uptimeMs: Long = 0L,
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L,
    val restartCount: Int = 0,
)
