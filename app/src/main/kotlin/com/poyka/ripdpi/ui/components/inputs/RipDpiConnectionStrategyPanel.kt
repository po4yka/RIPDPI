@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeModeSummaryFacet
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenSurface
import com.poyka.ripdpi.ui.theme.screens
import kotlinx.collections.immutable.ImmutableList

private const val StackedFacetFontScale = 1.3f
private const val FacetLabelWeight = 0.4f
private const val FacetValueWeight = 0.6f

/**
 * Names the active bypass strategy under the connection status. The step chain stays
 * visible; the explanation and the labeled settings open on demand, so the primary
 * action does not move down by a full settings list.
 */
@Composable
internal fun RipDpiConnectionStrategyPanel(
    strategyLabel: String,
    facets: ImmutableList<HomeModeSummaryFacet>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val expandable = facets.isNotEmpty()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expandedLabel = stringResource(R.string.semantic_state_expanded)
    val collapsedLabel = stringResource(R.string.semantic_state_collapsed)
    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = RipDpiThemeTokens.motion.stateTween(),
        label = "strategyChevron",
    )
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorStrategyPanel)
                .ripDpiScreenSurface(
                    RipDpiThemeTokens.styles.screens.mutedPanel,
                    RipDpiThemeTokens.shapes.md,
                    clip = true,
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (expandable) {
                            Modifier
                                .semantics { stateDescription = if (expanded) expandedLabel else collapsedLabel }
                                .ripDpiClickable(role = Role.Button) { expanded = !expanded }
                        } else {
                            Modifier
                        },
                    ).padding(horizontal = spacing.md, vertical = spacing.sm),
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
            if (expandable) {
                Icon(
                    imageVector = RipDpiIcons.KeyboardArrowDown,
                    contentDescription = stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand),
                    tint = colors.mutedForeground,
                    modifier = Modifier.size(RipDpiIconSizes.Default).rotate(chevronAngle),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && expandable,
            enter = RipDpiThemeTokens.motion.sectionEnterTransition(),
            exit = RipDpiThemeTokens.motion.sectionExitTransition(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = spacing.md, end = spacing.md, bottom = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.home_connection_strategy_explainer),
                    style = type.secondaryBody,
                    color = colors.mutedForeground,
                )
                facets.forEach { StrategyFacetRow(it) }
            }
        }
    }
}

@Composable
private fun StrategyFacetRow(facet: HomeModeSummaryFacet) {
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
