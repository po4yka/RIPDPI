package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.NativeSignsResult

internal fun String.nativeSignsReasonResource(): Int? =
    R.string.detection_exposure_native_signs_reason.takeIf {
        this == "Native checks found hook, root, or hidden-interface artifacts."
    }

internal fun NativeSignsResult.presentedCategory(): CategoryResult = category
