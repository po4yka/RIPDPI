package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayChain
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.RelayChainHopUiState
import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun RelayChainFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    onRelayChainEntryProfileIdChanged: (String) -> Unit,
    onRelayChainExitProfileIdChanged: (String) -> Unit,
    onRelayChainHopsSwapped: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val chainErrorText = chainValidationMessage(uiState.validationErrors[ConfigFieldRelayChain])
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = "Chain relay uses saved relay profiles for both hops. UDP is disabled for chains.",
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        RelayChainProfileSelector(
            label = "Entry hop",
            selectedProfileId = draft.relayChainEntryProfileId,
            profiles = uiState.relayProfiles,
            errorText = null,
            onProfileSelected = onRelayChainEntryProfileIdChanged,
        )
        RelayChainSelectedProfileDetails(
            profile = uiState.relayProfiles.firstOrNull { it.id == draft.relayChainEntryProfileId },
            hopStatus = uiState.relayChainHopStatus.entry,
        )
        RelayChainProfileSelector(
            label = "Exit hop",
            selectedProfileId = draft.relayChainExitProfileId,
            profiles = uiState.relayProfiles,
            errorText = null,
            onProfileSelected = onRelayChainExitProfileIdChanged,
        )
        RelayChainSelectedProfileDetails(
            profile = uiState.relayProfiles.firstOrNull { it.id == draft.relayChainExitProfileId },
            hopStatus = uiState.relayChainHopStatus.exit,
        )
        RelayChainValidationError(chainErrorText)
        RipDpiButton(
            text = "Swap entry/exit",
            onClick = onRelayChainHopsSwapped,
            variant = RipDpiButtonVariant.Secondary,
            density = RipDpiControlDensity.Compact,
            modifier = Modifier.fillMaxWidth(),
        )
        RelayChainTrustWarning(uiState)
    }
}

@Composable
private fun RelayChainProfileSelector(
    label: String,
    selectedProfileId: String,
    profiles: List<RelayProfileUiState>,
    errorText: String?,
    onProfileSelected: (String) -> Unit,
) {
    if (profiles.isEmpty()) {
        RipDpiTextField(
            value = selectedProfileId,
            onValueChange = onProfileSelected,
            decoration =
                RipDpiTextFieldDecoration(
                    label = label,
                    helperText = "Save relay profiles first, then choose them as chain hops.",
                    errorText = errorText,
                ),
        )
        return
    }
    RipDpiDropdown(
        options =
            profiles
                .map { RipDpiDropdownOption(value = it.id, label = it.selectorLabel) }
                .toImmutableList(),
        selectedValue = selectedProfileId,
        onValueSelected = onProfileSelected,
        label = label,
        placeholder = "Select profile",
        errorText = errorText,
    )
}

@Composable
private fun RelayChainSelectedProfileDetails(
    profile: RelayProfileUiState?,
    hopStatus: RelayChainHopUiState,
) {
    if (profile == null && hopStatus.displayLabel == null) {
        return
    }
    profile?.let {
        Text(
            text = it.trustLabel,
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
    hopStatus.displayLabel?.let { label ->
        Text(
            text = label,
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
private fun RelayChainValidationError(errorText: String?) {
    if (errorText == null) {
        return
    }
    Text(
        text = errorText,
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.destructive,
    )
}

@Composable
private fun RelayChainTrustWarning(uiState: ConfigUiState) {
    val warning = uiState.relayChainTrustWarning ?: return
    val shared =
        listOfNotNull(
            warning.sharedJurisdiction?.let { "jurisdiction $it" },
            warning.sharedOperatorName?.let { "operator $it" },
        ).joinToString(" and ")
    WarningBanner(
        title = "Shared trust domain",
        message = "Entry and exit share $shared. Use different jurisdictions and operators to reduce correlation risk.",
        tone = WarningBannerTone.Warning,
    )
}

@Composable
private fun chainValidationMessage(errorKey: String?): String? =
    when (errorKey) {
        "required" -> "Select both entry and exit profiles."
        "unsupported_entry" -> "Selected entry profile cannot be used as a chain entry."
        "unsupported_exit" -> "Selected exit profile cannot be used as a chain exit."
        else -> validationMessage(errorKey)
    }
