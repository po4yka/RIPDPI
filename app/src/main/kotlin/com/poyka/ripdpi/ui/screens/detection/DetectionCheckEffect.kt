package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.Verdict

sealed interface DetectionCheckEffect {
    data class CheckCompleted(
        val verdict: Verdict,
    ) : DetectionCheckEffect

    data object CheckFailed : DetectionCheckEffect
}
