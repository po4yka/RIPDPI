@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Pill-shaped segmented control: a Row of equal-width segments where
 * the selected segment carries the foreground container and the rest
 * read as muted on the card. Matches `components-segmented.html`.
 */
@Composable
@NonRestartableComposable
fun RipDpiSegmentedButton(
    options: ImmutableList<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiSegmentedButton(
        options = options,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        modifier = modifier,
        visualStyle = CommonStyle,
    )
}

@Composable
fun RipDpiSegmentedButton(
    options: ImmutableList<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visualStyle: CommonStyle = CommonStyle,
) {
    val colors = RipDpiThemeTokens.colors
    val gap = RipDpiThemeTokens.components.rows.compactPillVerticalPadding
    val shape = RoundedCornerShape(percent = 50)
    val groupState = rememberUpdatedStyleState(null)
    val groupResolver = rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.segmentedGroup, visualStyle, groupState)
    Row(
        modifier =
            modifier
                .ripDpiStyleSurface(groupResolver, shape = shape, clip = true)
                .padding(gap)
                .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            val interactionSource = remember { MutableInteractionSource() }
            val styleState = rememberUpdatedStyleState(interactionSource) { it.isSelected = isSelected }
            val itemResolver =
                rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.segmentedItem, CommonStyle, styleState)
            val content = if (isSelected) colors.background else colors.mutedForeground
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiStyleSurface(itemResolver, shape = shape, clip = true)
                        .ripDpiSelectable(
                            selected = isSelected,
                            role = Role.Tab,
                            interactionSource = interactionSource,
                        ) { onSelect(index) }
                        .padding(horizontal = RipDpiThemeTokens.spacing.md, vertical = RipDpiThemeTokens.spacing.sm),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    style =
                        if (isSelected) {
                            RipDpiThemeTokens.type.bodyEmphasis.copy(color = content)
                        } else {
                            RipDpiThemeTokens.type.secondaryBody.copy(color = content)
                        },
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiSegmentedButton (light)")
@Composable
private fun RipDpiSegmentedLightPreview() {
    RipDpiComponentPreview {
        var sel by remember { mutableIntStateOf(0) }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiSegmentedButton(
                options = persistentListOf("Auto", "Manual"),
                selectedIndex = sel,
                onSelect = { sel = it },
                modifier = Modifier.fillMaxWidth(),
            )
            RipDpiSegmentedButton(
                options = persistentListOf("All", "Errors", "Warnings"),
                selectedIndex = 1,
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(showBackground = true, name = "RipDpiSegmentedButton (dark)")
@Composable
private fun RipDpiSegmentedDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiSegmentedButton(
            options = persistentListOf("On", "Off"),
            selectedIndex = 0,
            onSelect = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
