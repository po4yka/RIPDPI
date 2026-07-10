package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.subscription.SubscriptionExpiryStatus
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

@Composable
internal fun HomeSubscriptionExpiryBanner(
    state: SubscriptionExpirySummaryUiState,
    onOpenStatus: () -> Unit,
) {
    val item = state.attention
    if (item == null) return
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
    WarningBanner(
        title = title,
        message = message,
        tone =
            if (item.status == SubscriptionExpiryStatus.EXPIRING) {
                WarningBannerTone.Warning
            } else {
                WarningBannerTone.Error
            },
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenStatus,
        testTag = RipDpiTestTags.HomeSubscriptionExpiryBanner,
    )
}
