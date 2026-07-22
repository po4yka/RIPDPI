package com.poyka.ripdpi.ui.screenshot.locale

import com.poyka.ripdpi.ui.screenshot.assumeFullExperienceScreenshot
import com.poyka.ripdpi.ui.screenshot.captureRipDpiScreenshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Arabic (ar) RTL screenshot coverage for the five primary screens.
 *
 * `@Config(qualifiers = "ar-rSA")` makes Robolectric resolve resources from
 * `values-ar/`, so the rendered text is Arabic; every scene is wrapped in
 * [LayoutDirection.Rtl][androidx.compose.ui.unit.LayoutDirection.Rtl] via
 * [MaybeRtl] to exercise RTL padding / chevron / icon mirroring.
 *
 * Goldens are recorded with `:app:recordRoborazziDebug` under the golden bless
 * discipline; see docs/localization-provenance.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ar-rSA")
class ArabicLocaleScreenshotTest {
    @Test
    fun home() {
        assumeFullExperienceScreenshot()
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
