package com.poyka.ripdpi.ui.screens.status

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
): StatusAdvisory? =
    when (condition) {
        is NetworkCondition.CaptivePortalAssist -> {
            StatusAdvisory(
                title = stringResource(R.string.home_network_condition_captive_title),
                message = stringResource(R.string.home_network_condition_captive_body),
                // Signing in is a step the user can take, not a fault.
                severity = StatusAdvisorySeverity.Info,
                presentation = StatusAdvisoryPresentation.Banner,
                tone = WarningBannerTone.Info,
                actionLabel = stringResource(R.string.home_network_condition_captive_action),
                onClick = onCaptivePortalSignIn,
                testTag = RipDpiTestTags.StatusNetworkConditionBanner,
            )
        }

        is NetworkCondition.WhitelistSuspected -> {
            val body = stringResource(R.string.home_network_condition_whitelist_body)
            StatusAdvisory(
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
                severity = StatusAdvisorySeverity.Warning,
                presentation = StatusAdvisoryPresentation.Banner,
                tone = WarningBannerTone.Restricted,
                actionLabel = null,
                onClick = null,
                testTag = RipDpiTestTags.StatusNetworkConditionBanner,
            )
        }

        is NetworkCondition.NoConnectivity -> {
            StatusAdvisory(
                title = stringResource(R.string.home_network_condition_no_connectivity_title),
                message = stringResource(R.string.home_network_condition_no_connectivity_body),
                severity = StatusAdvisorySeverity.Warning,
                presentation = StatusAdvisoryPresentation.Banner,
                tone = WarningBannerTone.Warning,
                actionLabel = null,
                onClick = null,
                testTag = RipDpiTestTags.StatusNetworkConditionBanner,
            )
        }

        NetworkCondition.Normal,
        NetworkCondition.BlockedReconnecting,
        -> {
            null
        }
    }

@Composable
private fun StatusNetworkConditionBanner(
    condition: NetworkCondition,
    onCaptivePortalSignIn: () -> Unit,
) {
    networkConditionAdvisory(condition, onCaptivePortalSignIn)?.let { AdvisoryBanner(it) }
}

@Preview(showBackground = true)
@Composable
private fun StatusNetworkConditionCaptivePreview() {
    RipDpiComponentPreview {
        StatusNetworkConditionBanner(
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
private fun StatusNetworkConditionWhitelistWithRelayPreview() {
    RipDpiComponentPreview {
        StatusNetworkConditionBanner(
            condition = NetworkCondition.WhitelistSuspected(suggestRelayProfile = true),
            onCaptivePortalSignIn = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusNetworkConditionWhitelistNoRelayPreview() {
    RipDpiComponentPreview {
        StatusNetworkConditionBanner(
            condition = NetworkCondition.WhitelistSuspected(suggestRelayProfile = false),
            onCaptivePortalSignIn = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusNetworkConditionNoConnectivityPreview() {
    RipDpiComponentPreview {
        StatusNetworkConditionBanner(
            condition = NetworkCondition.NoConnectivity,
            onCaptivePortalSignIn = {},
        )
    }
}
