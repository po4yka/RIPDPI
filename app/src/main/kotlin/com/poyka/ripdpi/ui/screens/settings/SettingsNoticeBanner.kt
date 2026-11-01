package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsNoticeTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone

internal fun SettingsNoticeTone.toBannerTone(): WarningBannerTone =
    when (this) {
        SettingsNoticeTone.Info -> WarningBannerTone.Info
        SettingsNoticeTone.Warning -> WarningBannerTone.Warning
        SettingsNoticeTone.Error -> WarningBannerTone.Error
    }

/**
 * Runs [scrollToBanner] when a new notice arrives, not when one is restored (a rotation): the banner is the first
 * item of its screen, so it must be brought into view.
 */
@Composable
internal fun ScrollToNewNotice(
    notice: SettingsEffect.Notice?,
    scrollToBanner: suspend () -> Unit,
) {
    var shown by remember { mutableStateOf(notice) }
    LaunchedEffect(notice) {
        if (notice != null && notice != shown) scrollToBanner()
        shown = notice
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
