package com.poyka.ripdpi.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.services.network.NetworkCondition
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

/**
 * A user-visible [NetworkCondition] as a home advisory.
 *
 * This is presentation-only. It NEVER alters routing or tunnel behavior — it explains a
 * restricted-network condition so it does not look like a generic VPN failure. The
 * captive-portal assist action is an explicit user tap ([onCaptivePortalSignIn]); the
 * whitelist relay suggestion is shown only when [NetworkCondition.WhitelistSuspected.suggestRelayProfile]
 * is true.
 *
 * [NetworkCondition.Normal] and [NetworkCondition.BlockedReconnecting] yield nothing:
 * Normal needs no advisory, and Blocked/Reconnecting is already owned by the connection
 * actuator and its own fault surface.
 */
@Composable
internal fun networkConditionAdvisory(
    condition: NetworkCondition,
    onCaptivePortalSignIn: () -> Unit,
): HomeAdvisory? =
    when (condition) {
        is NetworkCondition.CaptivePortalAssist -> {
            HomeAdvisory(
                title = stringResource(R.string.home_network_condition_captive_title),
                message = stringResource(R.string.home_network_condition_captive_body),
                // Signing in is a step the user can take, not a fault.
                severity = HomeAdvisorySeverity.Info,
                presentation = HomeAdvisoryPresentation.Banner,
                tone = WarningBannerTone.Info,
                actionLabel = null,
                onClick = onCaptivePortalSignIn,
                testTag = RipDpiTestTags.HomeNetworkConditionBanner,
            )
        }

        is NetworkCondition.WhitelistSuspected -> {
            val body = stringResource(R.string.home_network_condition_whitelist_body)
            HomeAdvisory(
                title = stringResource(R.string.home_network_condition_whitelist_title),
                message =
                    if (condition.suggestRelayProfile) {
                        stringResource(
                            R.string.home_network_condition_whitelist_message,
                            body,
                            stringResource(R.string.home_network_condition_whitelist_relay_suggestion),
                        )
                    } else {
                        body
                    },
                severity = HomeAdvisorySeverity.Warning,
                presentation = HomeAdvisoryPresentation.Banner,
                tone = WarningBannerTone.Restricted,
                actionLabel = null,
                onClick = null,
                testTag = RipDpiTestTags.HomeNetworkConditionBanner,
            )
        }

        is NetworkCondition.NoConnectivity -> {
            HomeAdvisory(
                title = stringResource(R.string.home_network_condition_no_connectivity_title),
                message = stringResource(R.string.home_network_condition_no_connectivity_body),
                severity = HomeAdvisorySeverity.Warning,
                presentation = HomeAdvisoryPresentation.Banner,
                tone = WarningBannerTone.Warning,
                actionLabel = null,
                onClick = null,
                testTag = RipDpiTestTags.HomeNetworkConditionBanner,
            )
        }

        NetworkCondition.Normal,
        NetworkCondition.BlockedReconnecting,
        -> {
            null
        }
    }

@Composable
private fun HomeNetworkConditionBanner(
    condition: NetworkCondition,
    onCaptivePortalSignIn: () -> Unit,
) {
    networkConditionAdvisory(condition, onCaptivePortalSignIn)?.let { AdvisoryBanner(it) }
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
