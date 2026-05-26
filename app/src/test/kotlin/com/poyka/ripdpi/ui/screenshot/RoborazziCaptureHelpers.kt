package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.inspectionMode
import com.github.takahirom.roborazzi.size
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Shared Roborazzi capture helpers for RDS screenshot tests.
 *
 * Hosts the three variant helpers:
 * - [captureSingle] — renders one PNG for a given theme + layout direction.
 * - [captureBothThemes] — renders `_light` and `_dark` variants (LTR only).
 *   Preserved with the exact signature used by `RdsComponentsScreenshotTest` so
 *   the currently-blessed goldens continue to match byte-for-byte.
 * - [captureThreeVariants] — renders `_light`, `_dark`, and `_rtl` (light + RTL).
 *   RTL is sampled only in light mode to avoid doubling the golden surface; this
 *   matches the convention used by Material's own screenshot suite.
 *
 * File paths are derived from a caller-supplied [testClassFqn] so output names
 * stay stable when these helpers are used across multiple test classes.
 */

internal const val DefaultTestClassFqn =
    "com.poyka.ripdpi.ui.screenshot.RdsComponentsScreenshotTest"

private val CROSS_PLATFORM_OPTIONS =
    RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01F),
    )

/**
 * Render one PNG. When [layoutDirection] is `null` the composition's default
 * layout direction is used (no `CompositionLocalProvider` wrapper at all),
 * matching the original inlined implementation byte-for-byte. Pass an explicit
 * direction only to force an RTL (or LTR) override.
 */
@OptIn(ExperimentalRoborazziApi::class)
internal fun captureSingle(
    name: String,
    widthDp: Int,
    heightDp: Int,
    darkMode: Boolean,
    layoutDirection: LayoutDirection? = null,
    testClassFqn: String = DefaultTestClassFqn,
    content: @Composable () -> Unit,
) {
    val moduleRoot = System.getProperty("user.dir")
    val themePreference = if (darkMode) "dark" else "light"
    captureStaticRoboImage(
        filePath = "$moduleRoot/src/test/screenshots/$testClassFqn.$name.png",
        widthDp = widthDp,
        heightDp = heightDp,
        roborazziOptions = CROSS_PLATFORM_OPTIONS,
        roborazziComposeOptions =
            RoborazziComposeOptions {
                size(widthDp = widthDp, heightDp = heightDp)
                fontScale(1f)
                inspectionMode(true)
            },
    ) {
        RipDpiTheme(themePreference = themePreference) {
            if (layoutDirection != null) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(heightDp.dp)
                                .background(RipDpiThemeTokens.colors.background)
                                .padding(16.dp),
                    ) { content() }
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(heightDp.dp)
                            .background(RipDpiThemeTokens.colors.background)
                            .padding(16.dp),
                ) { content() }
            }
        }
    }
}

/**
 * Render `<name>_light.png` and `<name>_dark.png`, both LTR. Signature preserved
 * for backward compatibility with the blessed RDS component goldens.
 */
internal fun captureBothThemes(
    name: String,
    widthDp: Int = 360,
    heightDp: Int = 200,
    testClassFqn: String = DefaultTestClassFqn,
    content: @Composable () -> Unit,
) {
    listOf(false, true).forEach { darkMode ->
        val themeSuffix = if (darkMode) "dark" else "light"
        captureSingle(
            name = "${name}_$themeSuffix",
            widthDp = widthDp,
            heightDp = heightDp,
            darkMode = darkMode,
            // layoutDirection = null → no CompositionLocalProvider wrapper, byte-identical
            // to the original inlined implementation. Do not pass LayoutDirection.Ltr here.
            testClassFqn = testClassFqn,
            content = content,
        )
    }
}

/**
 * Render `<name>_light.png`, `<name>_dark.png`, and `<name>_rtl.png`.
 *
 * `_rtl` is rendered in light mode with [LayoutDirection.Rtl]. Sampling RTL in
 * a single theme keeps the golden set growth bounded; light + dark + RTL-light
 * is the standard tradeoff. The `_rtl` PNGs are not generated until the user
 * explicitly invokes a bless step.
 */
internal fun captureThreeVariants(
    name: String,
    widthDp: Int = 360,
    heightDp: Int = 200,
    testClassFqn: String,
    content: @Composable () -> Unit,
) {
    // _light and _dark intentionally pass layoutDirection = null so they remain
    // byte-identical to captureBothThemes output; only _rtl forces the wrapper.
    captureSingle(
        name = "${name}_light",
        widthDp = widthDp,
        heightDp = heightDp,
        darkMode = false,
        testClassFqn = testClassFqn,
        content = content,
    )
    captureSingle(
        name = "${name}_dark",
        widthDp = widthDp,
        heightDp = heightDp,
        darkMode = true,
        testClassFqn = testClassFqn,
        content = content,
    )
    captureSingle(
        name = "${name}_rtl",
        widthDp = widthDp,
        heightDp = heightDp,
        darkMode = false,
        layoutDirection = LayoutDirection.Rtl,
        testClassFqn = testClassFqn,
        content = content,
    )
}
