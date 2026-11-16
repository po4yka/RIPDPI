@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.screens.status

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.distinctFrom
import com.poyka.ripdpi.ui.components.feedback.RipDpiMeasurementTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.measurementAccent
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface

/**
 * The one slot every advisory shares.
 *
 * A lone banner or compact hint keeps its existing surface. A row shows its
 * recovery directly. Multiple advisories share a card that preserves the
 * highest-priority warning and the first available recovery action.
 */
@Composable
internal fun StatusAdvisorySlot(
    advisories: List<StatusAdvisory>,
    initiallyExpanded: Boolean = false,
) {
    if (advisories.isEmpty()) return
    val top = advisories.first()
    if (advisories.size == 1) {
        when (top.presentation) {
            StatusAdvisoryPresentation.Banner -> AdvisoryBanner(top)
            StatusAdvisoryPresentation.Row -> AdvisoryCard(top)
            StatusAdvisoryPresentation.CompactRow -> RipDpiCard { AdvisoryRow(top) }
        }
        return
    }
    AdvisoryCard(top, advisories = advisories, initiallyExpanded = initiallyExpanded)
}

@Composable
internal fun AdvisoryBanner(advisory: StatusAdvisory) {
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
 * Keep the priority warning and first recovery visible; disclose only the remaining items.
 * A stripe on the leading edge gives the top notice's severity before its text is read.
 */
@Composable
private fun AdvisoryCard(
    top: StatusAdvisory,
    advisories: List<StatusAdvisory> = listOf(top),
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    // A list that is restored open is already there; only a tap reveals it item by item.
    var revealOnExpand by remember { mutableStateOf(false) }
    val recoveryIndex = advisories.indexOfFirst { it.onClick != null }
    val remaining = advisories.filterIndexed { index, _ -> index != 0 && index != recoveryIndex }
    val cardPadding = RipDpiThemeTokens.layout.cardPadding
    val spacing = RipDpiThemeTokens.spacing
    val motion = RipDpiThemeTokens.motion
    RipDpiCard(
        variant = RipDpiCardVariant.Tonal,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.StatusAdvisoryCard),
        // The stripe runs edge to edge, so the padding moves inside the card.
        paddingValues = PaddingValues(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            top.severity.stripeTone()?.let { tone -> AdvisoryToneStripe(tone) }
            Column(
                modifier =
                    Modifier.fillMaxWidth().padding(
                        // A collapsed disclosure's touch target already adds space under its label.
                        PaddingValues(
                            start = cardPadding,
                            top = cardPadding,
                            end = cardPadding,
                            bottom = if (remaining.isEmpty() || expanded) cardPadding else spacing.sm,
                        ),
                    ),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                AdvisoryRow(
                    advisory = top,
                    testTag = if (recoveryIndex == 0) RipDpiTestTags.StatusAdvisoryPrimaryAction else top.testTag,
                )
                if (recoveryIndex > 0) {
                    AdvisoryRow(
                        advisory = advisories[recoveryIndex],
                        testTag = RipDpiTestTags.StatusAdvisoryPrimaryAction,
                    )
                }
                if (remaining.isNotEmpty()) {
                    HorizontalDivider(color = RipDpiThemeTokens.colors.divider)
                    AdvisoryDisclosure(count = remaining.size, expanded = expanded) {
                        expanded = !expanded
                        revealOnExpand = true
                    }
                    // Below the toggle: the toggle keeps its place, and the new items follow it in reading order.
                    AnimatedVisibility(
                        visible = expanded,
                        // Each notice brings its own entrance.
                        enter = EnterTransition.None,
                        exit = motion.sectionExitTransition(),
                    ) {
                        Column(
                            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.StatusSetupHealthDetails),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            remaining.forEachIndexed { index, advisory ->
                                StatusStaggeredReveal(index = index, animate = revealOnExpand) { AdvisoryRow(advisory) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The same leading stripe as the measurements card, so a warning looks the same in both. */
@Composable
private fun BoxScope.AdvisoryToneStripe(tone: RipDpiMeasurementTone) {
    val resolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.measurementAccent(tone),
            CommonStyle,
            rememberUpdatedStyleState(null),
        )
    Box(modifier = Modifier.matchParentSize()) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .width(RipDpiThemeTokens.spacing.xs)
                    .fillMaxHeight()
                    .ripDpiStyleSurface(resolver),
        )
    }
}

/**
 * The icon carries the severity, so a warning does not look like a hint. The
 * recovery is a labeled action under the message rather than a value line, so
 * it does not read as a third line of the message.
 */
@Composable
private fun AdvisoryRow(
    advisory: StatusAdvisory,
    testTag: String = advisory.testTag,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val onClick = advisory.onClick
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(testTag)
                .semantics(mergeDescendants = true) {}
                .then(
                    onClick?.let {
                        Modifier.ripDpiClickable(role = Role.Button, onClickLabel = advisory.actionLabel, onClick = it)
                    } ?: Modifier,
                ).padding(vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Icon(
            imageVector = advisory.severity.icon(),
            contentDescription = null,
            tint = advisory.severity.iconTint(),
            modifier = Modifier.size(RipDpiIconSizes.Default),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(text = advisory.title, style = type.bodyEmphasis, color = colors.foreground)
            advisory.message
                .distinctFrom(advisory.title)
                ?.takeIf {
                    advisory.presentation != StatusAdvisoryPresentation.CompactRow
                }?.let { Text(text = it, style = type.secondaryBody, color = colors.mutedForeground) }
            if (onClick == null) {
                // A label without an action is a status, such as lockdown managed by Android.
                advisory.actionLabel?.let { status ->
                    Text(text = status, style = type.secondaryBody, color = colors.mutedForeground)
                }
            } else if (advisory.actionLabel != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Pushes the chevron to the edge, in line with the disclosure chevron.
                    Text(
                        text = advisory.actionLabel,
                        modifier = Modifier.weight(1f),
                        style = type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    Icon(
                        imageVector = RipDpiIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.foreground,
                        modifier = Modifier.size(RipDpiIconSizes.Small),
                    )
                }
            }
        }
    }
}

/** Expands in place, so it carries a disclosure chevron and state instead of a navigation arrow. */
@Composable
private fun AdvisoryDisclosure(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val stateLabel =
        stringResource(if (expanded) R.string.semantic_state_expanded else R.string.semantic_state_collapsed)
    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = RipDpiThemeTokens.motion.stateTween(),
        label = "advisoryDisclosureChevron",
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.StatusAdvisoryMore)
                .semantics { stateDescription = stateLabel }
                .ripDpiClickable(role = Role.Button, onClick = onToggle)
                .padding(vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Lines the label up with the advisory titles above it.
        Spacer(Modifier.width(RipDpiIconSizes.Default))
        Text(
            text = pluralStringResource(R.plurals.status_advisories_more, count, count),
            modifier = Modifier.weight(1f),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        Icon(
            imageVector = RipDpiIcons.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.mutedForeground,
            modifier = Modifier.size(RipDpiIconSizes.Small).rotate(chevronAngle),
        )
    }
}

private fun StatusAdvisorySeverity.icon(): ImageVector =
    when (this) {
        StatusAdvisorySeverity.Error -> RipDpiIcons.Error
        StatusAdvisorySeverity.Warning -> RipDpiIcons.Warning
        StatusAdvisorySeverity.Info -> RipDpiIcons.Info
    }

@Composable
private fun StatusAdvisorySeverity.iconTint(): Color =
    when (this) {
        StatusAdvisorySeverity.Error -> RipDpiThemeTokens.colors.destructive
        StatusAdvisorySeverity.Warning -> RipDpiThemeTokens.colors.warning
        StatusAdvisorySeverity.Info -> RipDpiThemeTokens.colors.mutedForeground
    }

/** A hint has no stripe: the measurements card leaves its resting state unmarked too. */
private fun StatusAdvisorySeverity.stripeTone(): RipDpiMeasurementTone? =
    when (this) {
        StatusAdvisorySeverity.Error -> RipDpiMeasurementTone.Critical
        StatusAdvisorySeverity.Warning -> RipDpiMeasurementTone.Warning
        StatusAdvisorySeverity.Info -> null
    }

@Preview(showBackground = true, name = "Advisory: one notice (light)")
@Composable
private fun AdvisoryOneNoticePreview() = AdvisoryPreview(count = 1)

@Preview(showBackground = true, name = "Advisory: one notice (dark)")
@Composable
private fun AdvisoryOneNoticeDarkPreview() = AdvisoryPreview(count = 1, dark = true)

@Preview(showBackground = true, name = "Advisory: collapsed (light)")
@Composable
private fun AdvisoryCollapsedPreview() = AdvisoryPreview(count = PreviewAdvisoryCount)

@Preview(showBackground = true, name = "Advisory: collapsed (dark)")
@Composable
private fun AdvisoryCollapsedDarkPreview() = AdvisoryPreview(count = PreviewAdvisoryCount, dark = true)

@Preview(showBackground = true, name = "Advisory: expanded (light)")
@Composable
private fun AdvisoryExpandedPreview() = AdvisoryPreview(count = PreviewAdvisoryCount, expanded = true)

@Preview(showBackground = true, name = "Advisory: expanded (dark)")
@Composable
private fun AdvisoryExpandedDarkPreview() = AdvisoryPreview(count = PreviewAdvisoryCount, expanded = true, dark = true)

private const val PreviewAdvisoryCount = 4

@Composable
private fun AdvisoryPreview(
    count: Int,
    expanded: Boolean = false,
    dark: Boolean = false,
) {
    val advisories =
        listOf(
            previewAdvisory("Subscription expired", StatusAdvisorySeverity.Error, action = "Renew"),
            previewAdvisory("System lockdown not enabled", StatusAdvisorySeverity.Warning, action = "Open settings"),
            previewAdvisory("Battery optimization is on", StatusAdvisorySeverity.Warning),
            previewAdvisory("Background guidance", StatusAdvisorySeverity.Info),
        ).take(count)
    RipDpiComponentPreview(themePreference = if (dark) "dark" else "light") {
        StatusAdvisorySlot(advisories = advisories, initiallyExpanded = expanded)
    }
}

private fun previewAdvisory(
    title: String,
    severity: StatusAdvisorySeverity,
    action: String? = null,
) = StatusAdvisory(
    title = title,
    message = "What happened and what it means for the connection.",
    severity = severity,
    presentation = StatusAdvisoryPresentation.Row,
    tone = WarningBannerTone.Warning,
    actionLabel = action,
    onClick = action?.let { {} },
    testTag = title,
)
