package com.poyka.ripdpi.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetAction
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPersonaPreference(
    persona: String,
    onPersonaSelected: (String) -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val currentPersona = if (persona == "advanced") "advanced" else "simple"

    SettingsRow(
        title = stringResource(R.string.settings_persona_title),
        subtitle = stringResource(R.string.settings_persona_body),
        value =
            stringResource(
                if (currentPersona ==
                    "advanced"
                ) {
                    R.string.persona_advanced
                } else {
                    R.string.persona_simple
                },
            ),
        onClick = { showPicker = true },
        showChevron = true,
        showDivider = true,
        testTag = RipDpiTestTags.SettingsPersona,
    )
    if (showPicker) {
        val choose: (String) -> Unit = { selection ->
            if (showPicker) {
                showPicker = false
                if (selection != currentPersona) onPersonaSelected(selection)
            }
        }
        RipDpiBottomSheet(
            onDismissRequest = { showPicker = false },
            title = stringResource(R.string.settings_persona_title),
            testTag = RipDpiTestTags.SettingsPersonaSheet,
            secondaryAction =
                RipDpiSheetAction(
                    label = stringResource(android.R.string.cancel),
                    onClick = { showPicker = false },
                    testTag = RipDpiTestTags.SettingsPersonaCancel,
                ),
        ) {
            Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                PersonaChoice(
                    title = R.string.persona_simple,
                    description = R.string.onboarding_persona_simple_body,
                    selected = currentPersona == "simple",
                    testTag = RipDpiTestTags.SettingsPersonaGuided,
                    onClick = { choose("simple") },
                )
                PersonaChoice(
                    title = R.string.persona_advanced,
                    description = R.string.onboarding_persona_advanced_body,
                    selected = currentPersona == "advanced",
                    testTag = RipDpiTestTags.SettingsPersonaAdvanced,
                    onClick = { choose("advanced") },
                )
            }
        }
    }
}

@Composable
private fun PersonaChoice(
    @StringRes title: Int,
    @StringRes description: Int,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(testTag)
                .ripDpiSelectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(vertical = spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(text = stringResource(title), style = RipDpiThemeTokens.type.bodyEmphasis, color = colors.foreground)
            Text(
                text = stringResource(description),
                style = RipDpiThemeTokens.type.body,
                color = colors.mutedForeground,
            )
        }
    }
}
