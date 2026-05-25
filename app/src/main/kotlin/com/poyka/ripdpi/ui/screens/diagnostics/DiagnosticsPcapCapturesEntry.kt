package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal fun LazyListScope.pcapCaptureListItem(onOpenPcapCaptureList: () -> Unit) {
    item {
        PcapCapturesEntryCard(onOpenPcapCaptureList = onOpenPcapCaptureList)
    }
}

@Composable
private fun PcapCapturesEntryCard(onOpenPcapCaptureList: () -> Unit) {
    ShareActionCard(
        title = stringResource(R.string.diagnostics_pcap_captures_label),
        body = stringResource(R.string.diagnostics_pcap_captures_description),
        buttonLabel = stringResource(R.string.title_pcap_capture_list),
        onClick = onOpenPcapCaptureList,
        iconTint = RipDpiThemeTokens.colors.foreground,
        variant = RipDpiButtonVariant.Outline,
    )
}
