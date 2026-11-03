package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone

internal fun SettingsNoticeTone.toBannerTone(): WarningBannerTone =
    when (this) {
        SettingsNoticeTone.Info -> WarningBannerTone.Info
        SettingsNoticeTone.Warning -> WarningBannerTone.Warning
        SettingsNoticeTone.Error -> WarningBannerTone.Error
    }

/**
 * Runs [scrollToBanner] when a notice arrives that this screen has not shown yet, not when one is restored (a
 * rotation): the banner is the first item of its screen, so it must be brought into view.
 */
@Composable
internal fun ScrollToNewNotice(
    notice: SettingsEffect.Notice?,
    scrollToBanner: suspend () -> Unit,
) {
    // Saved, so only a true restore finds the notice it already scrolled to; a notice posted while this screen was
    // away differs from it and scrolls. 0 stands for no notice.
    var shownKey by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(notice) {
        val key = notice?.hashCode() ?: 0
        if (notice != null && key != shownKey) scrollToBanner()
        shownKey = key
    }
}

/** A dismissible banner for a notice a screen owns, in the tone the notice carries. */
@Composable
internal fun SettingsNoticeBanner(
    notice: SettingsEffect.Notice,
    onDismiss: () -> Unit,
) {
    WarningBanner(
        title = stringResource(notice.title),
        message = notice.messageOverride ?: stringResource(notice.message),
        tone = notice.tone.toBannerTone(),
        onDismiss = onDismiss,
    )
}
