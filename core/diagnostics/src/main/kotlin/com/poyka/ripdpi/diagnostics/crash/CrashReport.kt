package com.poyka.ripdpi.diagnostics.crash

import kotlinx.serialization.Serializable

@Serializable
data class CrashReport(
    val timestamp: String = "",
    val exceptionClass: String = "",
    val message: String = "",
    val stacktrace: String = "",
    val threadName: String = "",
    val deviceModel: String = "",
    val deviceManufacturer: String = "",
    val androidVersion: String = "",
    val sdkInt: Int = 0,
    val appVersionName: String = "",
    val appVersionCode: Long = 0,
)
