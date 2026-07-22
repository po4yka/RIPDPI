package com.poyka.ripdpi.ui.screenshot.locale

import com.poyka.ripdpi.ui.screenshot.assumeFullExperienceScreenshot
import com.poyka.ripdpi.ui.screenshot.captureRipDpiScreenshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Simplified Chinese (zh-CN) screenshot coverage for the five primary screens.
 *
 * `@Config(qualifiers = "zh-rCN")` resolves resources from `values-zh-rCN/`.
 * zh-CN is left-to-right, so no layout-direction override is applied; this suite
 * guards against zh-CN string-length / wrapping regressions on the reviewed
 * screens (Home, Config, Diagnostics, Settings, Onboarding).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN")
class SimplifiedChineseLocaleScreenshotTest {
    @Test
    fun home() {
        assumeFullExperienceScreenshot()
        captureRipDpiScreenshot(LocaleScreenshotScenes.HOME_W, LocaleScreenshotScenes.HOME_H) {
            MaybeRtl(rtl = false) { HomeScene() }
        }
    }

    @Test
    fun config() {
        captureRipDpiScreenshot(LocaleScreenshotScenes.CONFIG_W, LocaleScreenshotScenes.CONFIG_H) {
            MaybeRtl(rtl = false) { ConfigScene() }
        }
    }

    @Test
    fun diagnostics() {
        captureRipDpiScreenshot(LocaleScreenshotScenes.DIAG_W, LocaleScreenshotScenes.DIAG_H) {
            MaybeRtl(rtl = false) { DiagnosticsScene() }
        }
    }

    @Test
    fun settings() {
        captureRipDpiScreenshot(LocaleScreenshotScenes.SETTINGS_W, LocaleScreenshotScenes.SETTINGS_H) {
            MaybeRtl(rtl = false) { SettingsScene() }
        }
    }

    @Test
    fun onboarding() {
        captureRipDpiScreenshot(LocaleScreenshotScenes.ONBOARDING_W, LocaleScreenshotScenes.ONBOARDING_H) {
            MaybeRtl(rtl = false) { OnboardingScene() }
        }
    }
}
