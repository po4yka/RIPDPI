package com.poyka.ripdpi.core.detection.export

data class DetectionExportMetadata(
    val timestamp: String,
    val appVersion: String,
    val buildType: String,
    val privacyMode: Boolean,
)
