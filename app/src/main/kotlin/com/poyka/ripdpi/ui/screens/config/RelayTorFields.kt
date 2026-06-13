package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

/**
 * Tor (Arti) relay opt-in fields for the Mode Editor.
 *
 * Tor bootstraps only through a bridge plus pluggable transport -- the censored-network
 * path enforced by `ripdpi-tor::build_bridge_pt_config` and the Kotlin `TorRelayKindResolver`,
 * never a direct connection. The caveat surfaces Tor's higher latency, its distinct anonymity
 * trust model, and the TCP/DNS-only (no UDP) capability. See docs/adr/0002-tor-feasibility.md.
 */
@Composable
internal fun TorRelayFields(
    draft: ConfigDraft,
    actions: RelayMiscKindActions,
) {
    RipDpiConfigTextField(
        value = draft.relayPtBridgeLine,
        onValueChange = actions.onRelayPtBridgeLineChanged,
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.relay_tor_bridge_line_label),
                helperText = stringResource(R.string.relay_tor_bridge_line_helper),
            ),
    )
    WarningBanner(
        title = stringResource(R.string.relay_tor_warning_title),
        message = stringResource(R.string.relay_tor_warning_message),
        tone = WarningBannerTone.Info,
    )
}
