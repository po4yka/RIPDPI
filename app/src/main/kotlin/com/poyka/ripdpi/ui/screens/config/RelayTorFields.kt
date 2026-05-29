package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
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
                label = "Bridge line",
                helperText =
                    "Paste an obfs4 bridge line. Tor bootstraps only through this pluggable transport, " +
                        "never a direct connection.",
            ),
    )
    WarningBanner(
        title = "Tor is slower and anonymized differently",
        message =
            "Traffic is relayed through the volunteer Tor network, so expect higher latency than a direct relay. " +
                "Anonymity is provided by Tor, not by RIPDPI. Tor carries TCP and DNS only; UDP is not supported.",
        tone = WarningBannerTone.Info,
    )
}
