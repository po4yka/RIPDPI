package com.poyka.ripdpi.ui.screenshot

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.inspectionMode
import com.github.takahirom.roborazzi.size

private val CROSS_PLATFORM_OPTIONS =
    RoborazziOptions(
        compareOptions =
            RoborazziOptions.CompareOptions(
                changeThreshold = 0.01F,
            ),
    )

@OptIn(ExperimentalRoborazziApi::class)
internal fun captureRipDpiScreenshot(
    widthDp: Int,
    heightDp: Int,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    captureRoboImage(
        roborazziOptions = CROSS_PLATFORM_OPTIONS,
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

// TODO(po4yka) Re-enable when Roborazzi adds layoutDirection API
// @OptIn(ExperimentalRoborazziApi::class)
// internal fun captureRipDpiScreenshotRtl(
//     widthDp: Int,
//     heightDp: Int,
//     fontScale: Float = 1f,
//     content: @Composable () -> Unit,
// ) {
//     captureRoboImage(
//         roborazziComposeOptions =
//             RoborazziComposeOptions {
//                 size(widthDp = widthDp, heightDp = heightDp)
//                 fontScale(fontScale)
//                 inspectionMode(true)
//                 layoutDirection(androidx.compose.ui.unit.LayoutDirection.Rtl)
//             },
//     ) {
//         content()
//     }
// }
