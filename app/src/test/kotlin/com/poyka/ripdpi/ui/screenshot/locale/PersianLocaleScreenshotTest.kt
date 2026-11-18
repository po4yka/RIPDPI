package com.poyka.ripdpi.ui.screenshot.locale

import com.poyka.ripdpi.ui.screenshot.captureRipDpiScreenshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Persian (fa) RTL screenshot coverage for the five primary screens.
 *
 * `@Config(qualifiers = "fa-rIR")` resolves resources from `values-fa/`; scenes
 * are forced to [LayoutDirection.Rtl][androidx.compose.ui.unit.LayoutDirection.Rtl]
 * via [MaybeRtl]. Persian glyphs fall back to the platform Noto Naskh chain (Geist
 * has no Arabic-script coverage) — see docs/localization-provenance.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fa-rIR")
class PersianLocaleScreenshotTest {
    @Test
    fun home() {
        captureRipDpiScreenshot(LocaleScreenshotScenes.HOME_W, LocaleScreenshotScenes.HOME_H) {
            MaybeRtl(rtl = true) { HomeScene() }
        }
    }

    @Test
    fun config() {
        captureRipDpiScreenshot(LocaleScreenshotScenes.CONFIG_W, LocaleScreenshotScenes.CONFIG_H) {
            MaybeRtl(rtl = true) { ConfigScene() }
        }
    }

    @Test
    fun diagnostics() {
        captureRipDpiScreenshot(LocaleScreenshotScenes.DIAG_W, LocaleScreenshotScenes.DIAG_H) {
            MaybeRtl(rtl = true) { DiagnosticsScene() }
        }
    }

    @Test
    fun settings() {
        captureRipDpiScreenshot(LocaleScreenshotScenes.SETTINGS_W, LocaleScreenshotScenes.SETTINGS_H) {
            MaybeRtl(rtl = true) { SettingsScene() }
        }
    }

    @Test
    fun onboarding() {
        captureRipDpiScreenshot(LocaleScreenshotScenes.ONBOARDING_W, LocaleScreenshotScenes.ONBOARDING_H) {
            MaybeRtl(rtl = true) { OnboardingScene() }
        }
    }
}
