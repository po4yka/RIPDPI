package com.poyka.ripdpi.ui.screenshot

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziActivity
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.inspectionMode
import com.github.takahirom.roborazzi.registerRoborazziActivityToRobolectricIfNeeded
import com.github.takahirom.roborazzi.size
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlin.math.roundToInt

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
    captureStaticRoboImage(
        widthDp = widthDp,
        heightDp = heightDp,
        roborazziOptions = CROSS_PLATFORM_OPTIONS,
        roborazziComposeOptions =
            RoborazziComposeOptions {
                size(widthDp = widthDp, heightDp = heightDp)
                fontScale(fontScale)
                inspectionMode(true)
            },
        content = content,
    )
}

@OptIn(ExperimentalRoborazziApi::class)
internal fun captureStaticRoboImage(
    filePath: String? = null,
    widthDp: Int,
    heightDp: Int,
    roborazziOptions: RoborazziOptions,
    roborazziComposeOptions: RoborazziComposeOptions,
    content: @Composable () -> Unit,
) {
    withStaticMotion {
        registerRoborazziActivityToRobolectricIfNeeded()
        val activityScenario =
            ActivityScenario.launch<RoborazziActivity>(
                RoborazziActivity.createIntent(
                    context = ApplicationProvider.getApplicationContext(),
                    theme = android.R.style.Theme_Translucent_NoTitleBar_Fullscreen,
                ),
            )
        val configuredContent = roborazziComposeOptions.configured(activityScenario) { content() }
        try {
            activityScenario.onActivity { activity ->
                activity.setContent { configuredContent() }
                val composeView =
                    activity.window.decorView
                        .findViewById<ViewGroup>(android.R.id.content)
                        .getChildAt(0) as ComposeView

                @SuppressLint("VisibleForTests")
                val viewRootForTest = composeView.getChildAt(0) as ViewRootForTest
                roborazziComposeOptions.beforeCapture()
                val captureView = viewRootForTest.view
                val density = activity.resources.displayMetrics.density
                val widthPx = (widthDp * density).roundToInt()
                val heightPx = (heightDp * density).roundToInt()
                captureView.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
                )
                captureView.layout(0, 0, widthPx, heightPx)
                val bitmap = captureView.drawToBitmap()
                if (filePath == null) {
                    bitmap.captureRoboImage(roborazziOptions = roborazziOptions)
                } else {
                    bitmap.captureRoboImage(filePath, roborazziOptions)
                }
                activity.finish()
            }
        } finally {
            roborazziComposeOptions.afterCapture()
        }
    }
}

/**
 * Render `<name>_light.png` and `<name>_dark.png` for a full screen. Unlike
 * [captureBothThemes] in RoborazziCaptureHelpers (which wraps content in a padded
 * Column for component galleries), this captures the composable as-is so a screen
 * manages its own Scaffold/insets. The content is wrapped only in [RipDpiTheme]
 * with the requested theme preference; pass the stateless screen composable with
 * deterministic preview state.
 */
@OptIn(ExperimentalRoborazziApi::class)
internal fun captureScreenBothThemes(
    name: String,
    widthDp: Int,
    heightDp: Int,
    testClassFqn: String,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val moduleRoot = System.getProperty("user.dir")
    listOf("light", "dark").forEach { themePreference ->
        captureStaticRoboImage(
            filePath = "$moduleRoot/src/test/screenshots/$testClassFqn.${name}_$themePreference.png",
            widthDp = widthDp,
            heightDp = heightDp,
            roborazziOptions = CROSS_PLATFORM_OPTIONS,
            roborazziComposeOptions =
                RoborazziComposeOptions {
                    size(widthDp = widthDp, heightDp = heightDp)
                    fontScale(fontScale)
                    inspectionMode(true)
                },
        ) {
            RipDpiTheme(themePreference = themePreference) { content() }
        }
    }
}

private fun withStaticMotion(block: () -> Unit) {
    val previous = System.getProperty(StaticMotionProperty)
    System.setProperty(StaticMotionProperty, "true")
    try {
        block()
    } finally {
        if (previous == null) {
            System.clearProperty(StaticMotionProperty)
        } else {
            System.setProperty(StaticMotionProperty, previous)
        }
    }
}

private const val StaticMotionProperty = "ripdpi.staticMotion"
