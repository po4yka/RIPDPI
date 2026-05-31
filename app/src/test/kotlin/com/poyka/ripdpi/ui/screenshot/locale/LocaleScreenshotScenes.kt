package com.poyka.ripdpi.ui.screenshot.locale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.ui.components.RipDpiConfigPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDiagnosticsScanPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeDisconnectedPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiIntroLargeFontPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiSettingsMediumPreviewScene

/**
 * Shared scene set for the per-locale screenshot tests.
 *
 * Each locale test class ([com.poyka.ripdpi.ui.screenshot.locale.ArabicLocaleScreenshotTest],
 * [com.poyka.ripdpi.ui.screenshot.locale.PersianLocaleScreenshotTest],
 * [com.poyka.ripdpi.ui.screenshot.locale.SimplifiedChineseLocaleScreenshotTest])
 * sets a Robolectric resource qualifier via `@Config(qualifiers = ...)`, so the
 * `RipDpi*PreviewScene` composables resolve their `stringResource` lookups against
 * the matching `values-XX/strings.xml`. RTL locales additionally force
 * [LayoutDirection.Rtl] so chevrons / padding / icon mirroring are exercised.
 *
 * The five screens mirror the epic ship-definition: Home, Config, Diagnostics,
 * Settings, Onboarding.
 *
 * Note: the `*PreviewScene` composables seed some elements with hardcoded sample
 * text for preview stability, so these suites localize the `stringResource`-driven
 * chrome and — most importantly — exercise full RTL layout (padding / chevron /
 * icon mirroring). Full per-string translation review is tracked by the manual
 * sign-off in docs/localization-provenance.md, not by these goldens.
 */
internal object LocaleScreenshotScenes {
    const val HOME_W = 420
    const val HOME_H = 800
    const val CONFIG_W = 420
    const val CONFIG_H = 900
    const val DIAG_W = 420
    const val DIAG_H = 900
    const val SETTINGS_W = 720
    const val SETTINGS_H = 1100
    const val ONBOARDING_W = 420
    const val ONBOARDING_H = 900
}

/** Wrap [content] in an explicit layout direction when [rtl] is true. */
@Composable
internal fun MaybeRtl(
    rtl: Boolean,
    content: @Composable () -> Unit,
) {
    if (rtl) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
    } else {
        content()
    }
}

@Composable internal fun HomeScene() = RipDpiHomeDisconnectedPreviewScene()

@Composable internal fun ConfigScene() = RipDpiConfigPreviewScene()

@Composable internal fun DiagnosticsScene() = RipDpiDiagnosticsScanPreviewScene()

@Composable internal fun SettingsScene() = RipDpiSettingsMediumPreviewScene()

@Composable internal fun OnboardingScene() = RipDpiIntroLargeFontPreviewScene()
