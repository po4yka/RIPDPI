package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.services.network.NetworkCondition
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

/**
 * Surfaces a user-visible network [NetworkCondition] as a banner in the home flow.
 *
 * This is presentation-only. It NEVER alters routing or tunnel behavior — it explains a
 * restricted-network condition so it does not look like a generic VPN failure. The
 * captive-portal assist action is an explicit user tap ([onCaptivePortalSignIn]); the
 * whitelist relay suggestion is shown only when [NetworkCondition.WhitelistSuspected.suggestRelayProfile]
 * is true.
 *
 * [NetworkCondition.Normal] and [NetworkCondition.BlockedReconnecting] render nothing here:
 * Normal needs no banner, and Blocked/Reconnecting is already owned by the connection actuator
 * and error banners.
 */
@Composable
fun HomeNetworkConditionBanner(
    condition: NetworkCondition,
    onCaptivePortalSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (condition) {
        is NetworkCondition.CaptivePortalAssist -> {
            WarningBanner(
                title = stringResource(R.string.home_network_condition_captive_title),
                message = stringResource(R.string.home_network_condition_captive_body),
                tone = WarningBannerTone.Info,
                modifier = modifier.fillMaxWidth(),
                onClick = onCaptivePortalSignIn,
                testTag = RipDpiTestTags.HomeNetworkConditionBanner,
            )
        }

        is NetworkCondition.WhitelistSuspected -> {
            val body = stringResource(R.string.home_network_condition_whitelist_body)
            val message =
                if (condition.suggestRelayProfile) {
                    "$body ${stringResource(R.string.home_network_condition_whitelist_relay_suggestion)}"
                } else {
                    body
                }
            WarningBanner(
                title = stringResource(R.string.home_network_condition_whitelist_title),
                message = message,
                tone = WarningBannerTone.Restricted,
                modifier = modifier.fillMaxWidth(),
                testTag = RipDpiTestTags.HomeNetworkConditionBanner,
            )
        }

        is NetworkCondition.NoConnectivity -> {
            WarningBanner(
                title = stringResource(R.string.home_network_condition_no_connectivity_title),
                message = stringResource(R.string.home_network_condition_no_connectivity_body),
                tone = WarningBannerTone.Warning,
                modifier = modifier.fillMaxWidth(),
                testTag = RipDpiTestTags.HomeNetworkConditionBanner,
            )
        }

        NetworkCondition.Normal,
        NetworkCondition.BlockedReconnecting,
        -> {
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeNetworkConditionCaptivePreview() {
    RipDpiComponentPreview {
        HomeNetworkConditionBanner(
            condition =
                NetworkCondition.CaptivePortalAssist(
                    activatedAtMillis = 0L,
                    expiresAtMillis = 600_000L,
                ),
            onCaptivePortalSignIn = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeNetworkConditionWhitelistWithRelayPreview() {
    RipDpiComponentPreview {
        HomeNetworkConditionBanner(
            condition = NetworkCondition.WhitelistSuspected(suggestRelayProfile = true),
            onCaptivePortalSignIn = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeNetworkConditionWhitelistNoRelayPreview() {
    RipDpiComponentPreview {
        HomeNetworkConditionBanner(
            condition = NetworkCondition.WhitelistSuspected(suggestRelayProfile = false),
            onCaptivePortalSignIn = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeNetworkConditionNoConnectivityPreview() {
    RipDpiComponentPreview {
        HomeNetworkConditionBanner(
            condition = NetworkCondition.NoConnectivity,
            onCaptivePortalSignIn = {},
        )
    }
}
