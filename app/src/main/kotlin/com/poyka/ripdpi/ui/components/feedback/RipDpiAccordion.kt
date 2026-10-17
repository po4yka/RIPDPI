@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStyleStateKeys
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.accordion
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface

/**
 * Single-row expandable accordion. Title row + trailing chevron that
 * rotates 180° on expansion. Content panel reveals with
 * AnimatedVisibility and `animateContentSize`. Visual rule: 1dp
 * border, card container, divider between header and body when open.
 *
 * Matches `components-accordion.html`.
 */
@Composable
@NonRestartableComposable
fun RipDpiAccordion(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    headerTestTag: String? = null,
    stateTestTag: String? = null,
    body: @Composable () -> Unit,
) {
    RipDpiAccordion(
        title = title,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
        headerTestTag = headerTestTag,
        stateTestTag = stateTestTag,
        body = body,
        visualStyle = CommonStyle,
    )
}

@Composable
fun RipDpiAccordion(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    headerTestTag: String? = null,
    stateTestTag: String? = null,
    visualStyle: CommonStyle = CommonStyle,
    body: @Composable () -> Unit,
) {
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing
    val shape = RoundedCornerShape(spacing.md)
    val interactionSource = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(interactionSource) { it[RipDpiStyleStateKeys.Expanded] = expanded }
    val styleResolver = rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.accordion, visualStyle, styleState)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(stateTestTag?.let { Modifier.ripDpiTestTag(it) } ?: Modifier)
                .ripDpiStyleSurface(styleResolver, shape)
                .animateContentSize(animationSpec = motion.stateTween()),
    ) {
        RipDpiAccordionHeader(title, expanded, onExpandedChange, headerTestTag, interactionSource)
        AnimatedVisibility(
            visible = expanded,
            enter = motion.sectionEnterTransition(),
            exit = motion.sectionExitTransition(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = RipDpiThemeTokens.spacing.lg,
                            vertical = RipDpiThemeTokens.spacing.md,
                        ),
            ) {
                body()
            }
        }
    }
}

@Composable
private fun RipDpiAccordionHeader(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    testTag: String?,
    interactionSource: MutableInteractionSource,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val density = LocalDensity.current
    val minimumWordWidth =
        rememberTextMeasurer()
            .measure(AnnotatedString(title), style = type.bodyEmphasis.copy(hyphens = Hyphens.None))
            .multiParagraph.minIntrinsicWidth
    val expandedLabel = stringResource(R.string.semantic_state_expanded)
    val collapsedLabel = stringResource(R.string.semantic_state_collapsed)
    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = RipDpiThemeTokens.motion.stateTween(),
        label = "accordionChevron",
    )
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { stateDescription = if (expanded) expandedLabel else collapsedLabel }
                .ripDpiClickable(
                    role = Role.Button,
                    interactionSource = interactionSource,
                ) { onExpandedChange(!expanded) }
                .then(testTag?.let { Modifier.ripDpiTestTag(it) } ?: Modifier)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
    ) {
        if (with(density) { minimumWordWidth.toDp() } > maxWidth - RipDpiIconSizes.Default - spacing.sm) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    title,
                    Modifier.fillMaxWidth(),
                    style = type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    RipDpiAccordionChevron(expanded, chevronAngle)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(title, Modifier.weight(1f), style = type.bodyEmphasis, color = RipDpiThemeTokens.colors.foreground)
                RipDpiAccordionChevron(expanded, chevronAngle)
            }
        }
    }
}

@Composable
private fun RipDpiAccordionChevron(
    expanded: Boolean,
    angle: Float,
) {
    Icon(
        imageVector = RipDpiIcons.KeyboardArrowDown,
        contentDescription = stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand),
        tint = RipDpiThemeTokens.colors.mutedForeground,
        modifier = Modifier.size(RipDpiIconSizes.Default).rotate(angle),
    )
}

@Preview(showBackground = true, name = "RipDpiAccordion (light)")
@Composable
private fun RipDpiAccordionLightPreview() {
    RipDpiComponentPreview {
        var open1 by remember { mutableStateOf(true) }
        var open2 by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiAccordion(title = "Advanced settings", expanded = open1, onExpandedChange = { open1 = it }) {
                Text(
                    "Inside content — DNS, kill switch, split tunnel.",
                    style = RipDpiThemeTokens.type.secondaryBody.copy(color = RipDpiThemeTokens.colors.mutedForeground),
                )
            }
            RipDpiAccordion(title = "Diagnostics history", expanded = open2, onExpandedChange = { open2 = it }) {
                Text("Hidden by default", style = RipDpiThemeTokens.type.body)
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiAccordion (dark)")
@Composable
private fun RipDpiAccordionDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        var open by remember { mutableStateOf(true) }
        RipDpiAccordion(title = "Strategy details", expanded = open, onExpandedChange = { open = it }) {
            Text("desync-tlsrec, fake-ttl=4, OOB=0x00", style = RipDpiThemeTokens.type.monoSmall)
        }
    }
}
