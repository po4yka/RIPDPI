package com.poyka.ripdpi.ui.screens.detection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.core.detection.ui.DetectionVisualState

/**
 * App-layer @Composable bridge mapping the Android-free core/detection enums to localized labels.
 *
 * The core enums ([DetectionVisualState] / [DetectionColorVisionMode]) keep hardcoded English
 * `displayName` values so `:core:detection` stays Android-free; the app renders those enums through
 * these `stringResource`-backed bridges (same style as `DiagnosticsTonePalette.displayLabel()` and
 * the `XrayProfileImportScreen` `stringIdFor` bridge).
 */
@Composable
internal fun DetectionVisualState.displayLabel(): String =
    when (this) {
        DetectionVisualState.CLEAN -> stringResource(R.string.detection_state_clean)
        DetectionVisualState.REVIEW -> stringResource(R.string.detection_state_review)
        DetectionVisualState.DETECTED -> stringResource(R.string.detection_state_detected)
        DetectionVisualState.ERROR -> stringResource(R.string.detection_state_error)
        DetectionVisualState.NEUTRAL -> stringResource(R.string.detection_state_neutral)
    }

@Composable
internal fun DetectionColorVisionMode.displayLabel(): String =
    when (this) {
        DetectionColorVisionMode.OFF -> stringResource(R.string.detection_color_vision_standard)
        DetectionColorVisionMode.RED_GREEN -> stringResource(R.string.detection_color_vision_red_green)
        DetectionColorVisionMode.BLUE_YELLOW -> stringResource(R.string.detection_color_vision_blue_yellow)
        DetectionColorVisionMode.ACHROMATOPSIA -> stringResource(R.string.detection_color_vision_achromatopsia)
    }
