package com.poyka.ripdpi.ui.screenshot

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.inspectionMode
import com.github.takahirom.roborazzi.size

@OptIn(ExperimentalRoborazziApi::class)
internal fun captureRipDpiScreenshot(
    widthDp: Int,
    heightDp: Int,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    captureRoboImage(
        roborazziComposeOptions =
            RoborazziComposeOptions {
                size(widthDp = widthDp, heightDp = heightDp)
                fontScale(fontScale)
                inspectionMode(true)
            },
    ) {
        content()
    }
}
