package com.poyka.ripdpi.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.subscription.SubscriptionExpiryStatus
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

@Composable
internal fun subscriptionExpiryAdvisory(
    state: SubscriptionExpirySummaryUiState,
    onOpenStatus: () -> Unit,
): HomeAdvisory? {
    val item = state.attention ?: return null
    val title =
        if (state.affectedCount > 1) {
            stringResource(R.string.subscription_home_multiple_title, state.affectedCount)
        } else {
            when (item.status) {
                SubscriptionExpiryStatus.EXPIRING -> stringResource(R.string.subscription_home_expiring_title)

                SubscriptionExpiryStatus.EXPIRED -> stringResource(R.string.subscription_home_expired_title)

                SubscriptionExpiryStatus.INVALIDATED -> stringResource(R.string.subscription_home_invalidated_title)

                SubscriptionExpiryStatus.ACTIVE,
                SubscriptionExpiryStatus.UNKNOWN,
                -> ""
            }
        }
    val message =
        when (item.status) {
            SubscriptionExpiryStatus.EXPIRING -> {
                stringResource(
                    R.string.subscription_home_expiring_body,
                    item.groupName,
                    item.daysRemaining ?: 0L,
                )
            }

            SubscriptionExpiryStatus.EXPIRED -> {
                stringResource(R.string.subscription_home_expired_body, item.groupName)
            }

            SubscriptionExpiryStatus.INVALIDATED -> {
                stringResource(R.string.subscription_home_invalidated_body, item.groupName)
            }

            SubscriptionExpiryStatus.ACTIVE,
            SubscriptionExpiryStatus.UNKNOWN,
            -> {
                ""
            }
        }
    val expiring = item.status == SubscriptionExpiryStatus.EXPIRING
    return HomeAdvisory(
        title = title,
        message = message,
        // A subscription still counting down can wait; one that already lapsed or
        // was invalidated has taken the exit away.
        severity = if (expiring) HomeAdvisorySeverity.Warning else HomeAdvisorySeverity.Error,
        presentation = HomeAdvisoryPresentation.Banner,
        tone = if (expiring) WarningBannerTone.Warning else WarningBannerTone.Error,
        actionLabel = null,
        onClick = onOpenStatus,
        testTag = RipDpiTestTags.HomeSubscriptionExpiryBanner,
    )
}

/** The advisory on its own, for the test that exercises it in isolation. */
@Composable
internal fun HomeSubscriptionExpiryBanner(
    state: SubscriptionExpirySummaryUiState,
    onOpenStatus: () -> Unit,
) {
    subscriptionExpiryAdvisory(state, onOpenStatus)?.let { AdvisoryBanner(it) }
}
