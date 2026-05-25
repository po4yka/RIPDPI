package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal fun LazyListScope.pastReplaysItem(onOpenPastReplays: () -> Unit) {
    item {
        PastReplaysEntryCard(onOpenPastReplays = onOpenPastReplays)
    }
}

@Composable
private fun PastReplaysEntryCard(onOpenPastReplays: () -> Unit) {
    ShareActionCard(
        title = stringResource(R.string.diagnostics_past_replays_label),
        body = stringResource(R.string.diagnostics_past_replays_description),
        buttonLabel = stringResource(R.string.title_replay_history),
        onClick = onOpenPastReplays,
        iconTint = RipDpiThemeTokens.colors.foreground,
        variant = RipDpiButtonVariant.Outline,
    )
}
