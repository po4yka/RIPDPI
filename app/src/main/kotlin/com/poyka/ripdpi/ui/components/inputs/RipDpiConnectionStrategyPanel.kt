@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StatusModeSummaryFacet
import com.poyka.ripdpi.ui.screens.status.StrategyPassageScene
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiFeatureSurface
import kotlinx.collections.immutable.ImmutableList

private const val CollapsedChainLines = 2
private const val StackedFacetFontScale = 1.3f
private const val FacetLabelWeight = 0.4f
private const val FacetValueWeight = 0.6f

/**
 * Names the active bypass strategy under the connection status and, when a passage is set,
 * draws what it does to a packet. The step chain stays visible; the explanation and the
 * labeled settings open on demand, so the primary action does not move down by a full
 * settings list.
 */
@Composable
internal fun RipDpiConnectionStrategyPanel(
    strategyLabel: String,
    facets: ImmutableList<StatusModeSummaryFacet>,
    status: StatusConnectionActuatorStatus,
    passage: StrategyPassageScene?,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val expandable = facets.isNotEmpty()
    val inset = RipDpiThemeTokens.layout.cardPadding
    var expanded by rememberSaveable { mutableStateOf(false) }
    val playback = remember { PassagePlaybackMemory() }
    val expandedLabel = stringResource(R.string.semantic_state_expanded)
    val collapsedLabel = stringResource(R.string.semantic_state_collapsed)
    val tone = strategyTone(status, expandable, passage != null)
    val headerModifier =
        Modifier
            .fillMaxWidth()
            .then(
                if (expandable) {
                    Modifier
                        .semantics { stateDescription = if (expanded) expandedLabel else collapsedLabel }
                        .ripDpiClickable(role = Role.Button) {
                            expanded = !expanded
                            if (passage != null) playback.replayRequests += 1
                        }
                } else {
                    // TalkBack reads the name, the scene, and the chain as one item.
                    Modifier.semantics(mergeDescendants = true) {}
                },
            ).padding(horizontal = if (expandable) inset else 0.dp, vertical = spacing.sm)
    val toggle: (@Composable () -> Unit)? =
        if (expandable) {
            {
                // A text toggle, not a chevron: a filled box with a chevron reads as a dropdown.
                Text(
                    text =
                        stringResource(
                            if (expanded) R.string.status_strategy_hide_details else R.string.status_strategy_details,
                        ),
                    style = type.smallLabel,
                    color = tone.content,
                )
            }
        } else {
            null
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorStrategyPanel)
                .then(
                    if (expandable) {
                        // Fill without an outline: an outlined box with a chevron reads as a dropdown field.
                        Modifier.ripDpiFeatureSurface(tone.surface, RipDpiThemeTokens.shapes.md, clip = true)
                    } else {
                        // A fill marks the control; with nothing to open, it would make a plain value look like one.
                        Modifier
                    },
                ),
    ) {
        if (passage != null) {
            PassageHeader(
                strategyLabel = strategyLabel,
                passage = passage,
                status = status,
                tone = tone,
                showWholeChain = expanded || !expandable,
                playback = playback,
                toggle = toggle,
                modifier = headerModifier,
            )
        } else {
            ChainHeader(strategyLabel, toggle, headerModifier)
        }
        StrategyDetails(facets, visible = expanded && expandable)
    }
}

@Composable
private fun StrategyDetails(
    facets: ImmutableList<StatusModeSummaryFacet>,
    visible: Boolean,
) {
    val inset = RipDpiThemeTokens.layout.cardPadding
    val spacing = RipDpiThemeTokens.spacing
    AnimatedVisibility(
        visible = visible,
        enter = RipDpiThemeTokens.motion.sectionEnterTransition(),
        exit = RipDpiThemeTokens.motion.sectionExitTransition(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = inset, end = inset, bottom = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.status_connection_strategy_explainer),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            facets.forEach { StrategyFacetRow(it) }
        }
    }
}

@Composable
private fun StrategyFacetRow(facet: StatusModeSummaryFacet) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val label = @Composable { modifier: Modifier ->
        Text(text = facet.label, modifier = modifier, style = type.secondaryBody, color = colors.mutedForeground)
    }
    val value = @Composable { modifier: Modifier ->
        Text(text = facet.value, modifier = modifier, style = type.secondaryBody, color = colors.foreground)
    }
    // TalkBack reads each setting as one "label, value" item.
    val rowModifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
    if (LocalDensity.current.fontScale >= StackedFacetFontScale) {
        Column(modifier = rowModifier) {
            label(Modifier)
            value(Modifier)
        }
    } else {
        Row(
            modifier = rowModifier,
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            label(Modifier.weight(FacetLabelWeight))
            value(Modifier.weight(FacetValueWeight))
        }
    }
}

@Composable
private fun PassageHeader(
    strategyLabel: String,
    passage: StrategyPassageScene,
    status: StatusConnectionActuatorStatus,
    tone: StrategyTone,
    showWholeChain: Boolean,
    playback: PassagePlaybackMemory,
    toggle: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strategyPassageName(passage),
                modifier = Modifier.weight(1f),
                style = type.bodyEmphasis,
                color = tone.content,
            )
            toggle?.invoke()
        }
        RipDpiStrategyPassage(passage, status, playback, tone.surface, tone.content)
        Text(
            text = strategyLabel,
            style = type.monoSmall,
            color = tone.content,
            maxLines = if (showWholeChain) Int.MAX_VALUE else CollapsedChainLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChainHeader(
    strategyLabel: String,
    toggle: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = stringResource(R.string.config_local_bypass_strategy_title),
                style = type.caption,
                color = colors.mutedForeground,
            )
            Text(text = strategyLabel, style = type.monoValue, color = colors.foreground)
        }
        toggle?.invoke()
    }
}

@Immutable
private class StrategyTone(
    val surface: Color,
    val content: Color,
)

/** Color follows the live connection only where the scene is drawn. */
@Composable
private fun strategyTone(
    status: StatusConnectionActuatorStatus,
    expandable: Boolean,
    hasPassage: Boolean,
): StrategyTone {
    val colors = RipDpiThemeTokens.colors
    return when {
        !expandable -> {
            StrategyTone(colors.background, colors.foreground)
        }

        hasPassage && status == StatusConnectionActuatorStatus.Locked -> {
            StrategyTone(colors.infoContainer, colors.infoContainerForeground)
        }

        hasPassage && status == StatusConnectionActuatorStatus.Degraded -> {
            StrategyTone(colors.warningContainer, colors.warningContainerForeground)
        }

        else -> {
            StrategyTone(colors.muted, colors.foreground)
        }
    }
}
