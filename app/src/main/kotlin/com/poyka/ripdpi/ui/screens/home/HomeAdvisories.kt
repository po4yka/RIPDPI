package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.cards.SettingsRowVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * How loudly an advisory has to be heard. Ordering is the declaration order, so
 * `Error` sorts first.
 */
internal enum class HomeAdvisorySeverity { Error, Warning, Info }

/** How an advisory looks when it is the only thing the slot has to show. */
internal enum class HomeAdvisoryPresentation {
    /** A full banner in its own tone, for conditions the user did not cause. */
    Banner,

    /** A card row with the message underneath, for setup that needs repairing. */
    Row,

    /** A tonal one-liner, for hints that are worth offering and not worth a card. */
    CompactRow,
}

internal data class HomeAdvisory(
    val title: String,
    val message: String,
    val severity: HomeAdvisorySeverity,
    val presentation: HomeAdvisoryPresentation,
    val tone: WarningBannerTone,
    val actionLabel: String?,
    val onClick: (() -> Unit)?,
    val testTag: String,
    val announce: Boolean = true,
)

/**
 * Everything on Home that wants the user's attention, in one ordered list.
 *
 * These used to be four independent surfaces stacked between the actuator and
 * the content — setup health, the Xray provider stage, the network condition
 * and subscription expiry — each deciding on its own whether to render. In the
 * worst case the primary control was separated from the screen's content by
 * four full-width banners, in declaration order rather than by how much any of
 * them mattered.
 *
 * The connection-quality strip is deliberately not here: it reports
 * measurements the user asked for, not a condition needing attention, and
 * folding it in would have hidden the traffic counter behind a warning header.
 */
@Composable
@Suppress("LongParameterList")
internal fun buildHomeAdvisories(
    uiState: MainUiState,
    subscriptionExpiry: SubscriptionExpirySummaryUiState,
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    onDismissBatteryBanner: () -> Unit,
    onDismissBackgroundGuidance: () -> Unit,
    onOpenSubscriptionStatus: () -> Unit,
    onCaptivePortalSignIn: () -> Unit,
): List<HomeAdvisory> {
    val advisories =
        buildSetupHealthAdvisories(
            uiState = uiState,
            onRepairPermission = onRepairPermission,
            onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
            onDismissBatteryBanner = onDismissBatteryBanner,
            onDismissBackgroundGuidance = onDismissBackgroundGuidance,
        ).toMutableList()
    xrayProviderAdvisory(uiState.xrayProviderSnapshot)?.let { advisories += it }
    networkConditionAdvisory(uiState.networkCondition, onCaptivePortalSignIn)?.let { advisories += it }
    subscriptionExpiryAdvisory(subscriptionExpiry, onOpenSubscriptionStatus)?.let { advisories += it }
    // Stable, so advisories of equal severity keep the order they were built in.
    return advisories.sortedBy { it.severity.ordinal }
}

/**
 * The one slot every advisory shares.
 *
 * A lone advisory renders exactly as it did when it owned its own surface, so
 * the common case is unchanged. More than one collapses into a single card
 * headed by the most severe of them, which is the ordering the stacked version
 * never had: it showed whatever was declared first.
 */
@Composable
internal fun HomeAdvisorySlot(advisories: List<HomeAdvisory>) {
    if (advisories.isEmpty()) return
    val top = advisories.first()
    if (advisories.size == 1) {
        when (top.presentation) {
            HomeAdvisoryPresentation.Banner -> AdvisoryBanner(top)
            HomeAdvisoryPresentation.Row -> AdvisoryCard(top)
            HomeAdvisoryPresentation.CompactRow -> RipDpiCard { AdvisoryRow(top, showDivider = false) }
        }
        return
    }
    AdvisoryCard(top, rest = advisories)
}

@Composable
internal fun AdvisoryBanner(advisory: HomeAdvisory) {
    WarningBanner(
        title = advisory.title,
        message = advisory.message,
        tone = advisory.tone,
        modifier = Modifier.fillMaxWidth(),
        onClick = advisory.onClick,
        testTag = advisory.testTag,
        announce = advisory.announce,
    )
}

/**
 * Header plus, when there is more than one advisory, the rest behind a
 * disclosure. The header is named by the advisory that matters most rather than
 * by a generic label, so a subscription that expired is not filed under a title
 * about setup.
 */
@Composable
private fun AdvisoryCard(
    top: HomeAdvisory,
    rest: List<HomeAdvisory> = emptyList(),
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    RipDpiCard {
        SettingsRow(
            // Collapsed, the header is the only thing naming what needs
            // attention, so it takes the most severe advisory's title. Expanded,
            // the list names every one of them and a header repeating the first
            // row would print the same sentence twice in a row.
            title = if (expanded) stringResource(R.string.home_setup_health_title) else top.title,
            subtitle =
                when {
                    expanded -> stringResource(R.string.home_setup_health_expanded)
                    rest.size > 1 -> stringResource(R.string.home_setup_health_collapsed_format, rest.size)
                    else -> top.message
                },
            value =
                stringResource(
                    if (expanded) R.string.semantic_action_collapse else R.string.settings_permission_action_review,
                ),
            onClick = { expanded = !expanded },
            leadingIcon = if (rest.size > 1) RipDpiIcons.Settings else RipDpiIcons.Info,
            showChevron = true,
            testTag = RipDpiTestTags.HomeSetupHealthRow,
        )
        if (expanded) {
            Column(
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HomeSetupHealthDetails),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                rest.ifEmpty { listOf(top) }.forEach { advisory ->
                    AdvisoryRow(advisory, showDivider = true)
                }
            }
        }
    }
}

@Composable
private fun AdvisoryRow(
    advisory: HomeAdvisory,
    showDivider: Boolean,
) {
    val compact = advisory.presentation == HomeAdvisoryPresentation.CompactRow
    SettingsRow(
        title = advisory.title,
        subtitle = advisory.message.takeUnless { compact },
        value =
            advisory.actionLabel?.let { label ->
                if (compact) "$label →" else label
            },
        onClick = advisory.onClick,
        leadingIcon = RipDpiIcons.Info,
        showDivider = showDivider,
        variant = if (compact) SettingsRowVariant.Tonal else SettingsRowVariant.Default,
        testTag = advisory.testTag,
    )
}

@Composable
private fun buildSetupHealthAdvisories(
    uiState: MainUiState,
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    onDismissBatteryBanner: () -> Unit,
    onDismissBackgroundGuidance: () -> Unit,
): List<HomeAdvisory> {
    val items = mutableListOf<HomeAdvisory>()
    val blocking =
        uiState.permissionSummary.issue?.let { issue ->
            permissionIssueAdvisory(issue, onRepairPermission, onOpenVpnPermissionDialog)
        }
    if (blocking != null) {
        items += blocking
    } else {
        uiState.permissionSummary.recommendedIssue?.let { warning ->
            items += recommendationAdvisory(warning, onRepairPermission, onDismissBatteryBanner)
        }
        uiState.permissionSummary.backgroundGuidance?.let { guidance ->
            items +=
                HomeAdvisory(
                    title = guidance.title,
                    message = guidance.message,
                    severity = HomeAdvisorySeverity.Info,
                    presentation = HomeAdvisoryPresentation.Row,
                    tone = WarningBannerTone.Info,
                    actionLabel = stringResource(R.string.settings_permission_action_review),
                    onClick = onDismissBackgroundGuidance,
                    testTag = RipDpiTestTags.HomeSetupHealthAction,
                )
        }
    }
    lockdownAdvisory(uiState, onRepairPermission)?.let { items += it }
    return items
}

/** A permission the app cannot work without. */
@Composable
private fun permissionIssueAdvisory(
    issue: PermissionIssueUiState,
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
): HomeAdvisory =
    HomeAdvisory(
        title = issue.title,
        message =
            when (issue.recovery) {
                PermissionRecovery.OpenSettings,
                PermissionRecovery.OpenBatteryOptimizationSettings,
                -> stringResource(R.string.home_permission_issue_with_settings, issue.message)

                PermissionRecovery.ShowVpnPermissionDialog,
                PermissionRecovery.RetryPrompt,
                -> stringResource(R.string.home_permission_issue_with_retry, issue.message)
            },
        // The one advisory that stops the app from doing its job at all.
        severity = HomeAdvisorySeverity.Error,
        presentation = setupPresentation(issue.kind),
        tone = WarningBannerTone.Error,
        actionLabel = issue.actionLabel,
        onClick =
            when (issue.recovery) {
                PermissionRecovery.OpenBatteryOptimizationSettings -> {
                    { onRepairPermission(PermissionKind.BatteryOptimization) }
                }

                PermissionRecovery.ShowVpnPermissionDialog,
                PermissionRecovery.RetryPrompt,
                -> {
                    onOpenVpnPermissionDialog
                }

                PermissionRecovery.OpenSettings -> {
                    { onRepairPermission(issue.kind) }
                }
            },
        testTag = RipDpiTestTags.HomeSetupHealthAction,
    )

/** A permission worth having that nothing is currently blocked on. */
@Composable
private fun recommendationAdvisory(
    warning: PermissionIssueUiState,
    onRepairPermission: (PermissionKind) -> Unit,
    onDismissBatteryBanner: () -> Unit,
): HomeAdvisory =
    HomeAdvisory(
        title = warning.title,
        message = warning.message,
        severity = HomeAdvisorySeverity.Info,
        presentation = setupPresentation(warning.kind),
        tone = WarningBannerTone.Info,
        actionLabel = warning.actionLabel,
        onClick =
            if (warning.kind == PermissionKind.BatteryOptimization) {
                {
                    onDismissBatteryBanner()
                    onRepairPermission(PermissionKind.BatteryOptimization)
                }
            } else {
                { onDismissBatteryBanner() }
            },
        testTag = RipDpiTestTags.HomeSetupHealthAction,
    )

/**
 * Gated on relevance to the configured path only. Requiring an active VPN card
 * withheld the advisory in exactly the states where it can still be acted on --
 * idle, connecting, and a path that just failed.
 */
private fun lockdownAdvisory(
    uiState: MainUiState,
    onRepairPermission: (PermissionKind) -> Unit,
): HomeAdvisory? {
    val lockdown = uiState.hardKillSwitch
    if (!lockdown.visible) return null
    return HomeAdvisory(
        title = lockdown.label,
        message = lockdown.summary,
        severity = if (lockdown.warning) HomeAdvisorySeverity.Warning else HomeAdvisorySeverity.Info,
        presentation = HomeAdvisoryPresentation.Row,
        tone = WarningBannerTone.Warning,
        actionLabel = lockdown.actionLabel,
        onClick =
            if (lockdown.warning) {
                { onRepairPermission(PermissionKind.VpnLockdown) }
            } else {
                null
            },
        testTag = RipDpiTestTags.HomeSetupHealthAction,
    )
}

/** The battery hint is the only setup item worth less than a full row. */
private fun setupPresentation(kind: PermissionKind): HomeAdvisoryPresentation =
    if (kind == PermissionKind.BatteryOptimization) {
        HomeAdvisoryPresentation.CompactRow
    } else {
        HomeAdvisoryPresentation.Row
    }
