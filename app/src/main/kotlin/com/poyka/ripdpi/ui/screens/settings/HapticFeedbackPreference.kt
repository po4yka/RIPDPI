package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownLabels
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import kotlinx.collections.immutable.persistentListOf

@Composable
internal fun HapticFeedbackPreference(
    disabled: Boolean,
    onDisabledChange: (Boolean) -> Unit,
) {
    val title = stringResource(R.string.haptic_feedback_title)
    val selectedLabel = stringResource(if (disabled) R.string.haptic_feedback_off else R.string.haptic_feedback_system)
    RipDpiDropdown(
        options =
            persistentListOf(
                RipDpiDropdownOption("system", stringResource(R.string.haptic_feedback_system)),
                RipDpiDropdownOption("off", stringResource(R.string.haptic_feedback_off)),
            ),
        selectedValue = if (disabled) "off" else "system",
        onValueSelected = { onDisabledChange(it == "off") },
        labels =
            RipDpiDropdownLabels(
                label = title,
                helperText = stringResource(R.string.haptic_feedback_body),
                accessibilityLabel = stringResource(R.string.haptic_feedback_accessibility, title, selectedLabel),
            ),
        testTag = RipDpiTestTags.SettingsHapticFeedback,
        optionTagForValue = { RipDpiTestTags.dropdownOption(RipDpiTestTags.SettingsHapticFeedback, it) },
    )
}

@Composable
internal fun SimpleInterfaceSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RipDpiSettingsScaffold(
        title = stringResource(R.string.settings_appearance_section),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.SimpleInterfaceSettingsScreen),
    ) {
        item(key = "haptic-feedback") {
            HapticFeedbackPreference(
                disabled = state.hapticFeedbackDisabled,
                onDisabledChange = remember(viewModel) { viewModel::setHapticFeedbackDisabled },
            )
        }
    }
}
