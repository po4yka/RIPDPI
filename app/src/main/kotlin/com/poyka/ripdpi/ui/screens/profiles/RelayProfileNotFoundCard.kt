package com.poyka.ripdpi.ui.screens.profiles

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader

/** Shown by a profile edit route whose profile id is missing or no longer exists. */
@Composable
internal fun RelayProfileNotFoundCard() {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.relay_editor_profile_not_found_title),
            supporting = stringResource(R.string.relay_editor_profile_not_found_body),
        )
    }
}
