package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.proto.AppSettings

internal val AppSettings.redGreenStatusAltEnabled: Boolean
    get() = detectionCheckProtanopiaVariantUnlocked

internal fun AppSettings.Builder.enableRedGreenStatusAlt() {
    detectionCheckProtanopiaVariantUnlocked = true
    detectionCheckColorVisionMode = DetectionColorVisionMode.RED_GREEN.wireValue
}
