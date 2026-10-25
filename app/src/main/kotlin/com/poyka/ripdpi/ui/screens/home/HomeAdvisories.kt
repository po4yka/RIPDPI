package com.poyka.ripdpi.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

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
                -> issue.message
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

                PermissionRecovery.ShowVpnPermissionDialog -> {
                    onOpenVpnPermissionDialog
                }

                PermissionRecovery.RetryPrompt,
                PermissionRecovery.OpenSettings,
                -> {
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
