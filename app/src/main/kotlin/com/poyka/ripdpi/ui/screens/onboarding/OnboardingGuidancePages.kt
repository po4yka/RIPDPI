package com.poyka.ripdpi.ui.screens.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.BuiltInDnsProviders
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.canonicalDefaultDnsProviderDefinition
import com.poyka.ripdpi.data.dnsProviderById
import com.poyka.ripdpi.ui.components.LanguagePickerSheet
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiFeatureSurface

/**
 * Low-emphasis info card on the same neutral surface as an unselected option card: a glyph, a title, and
 * supporting text, read as one TalkBack stop. An optional action sits under the text as its own stop.
 */
@Composable
internal fun OnboardingNote(
    icon: ImageVector,
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
    modifier: Modifier = Modifier,
    @StringRes actionLabelRes: Int? = null,
    onAction: () -> Unit = {},
    actionTestTag: String? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val components = RipDpiThemeTokens.components
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .ripDpiFeatureSurface(
                    container = colors.card,
                    shape = RipDpiThemeTokens.shapes.xl,
                    border = colors.outlineVariant,
                    borderWidth = spacing.xs / 4,
                    clip = true,
                ).padding(
                    horizontal = components.inputs.fieldHorizontalPadding,
                    vertical = components.rows.settingsRowVerticalPadding,
                ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.mutedForeground,
                modifier = Modifier.size(components.inputs.chipIconSize),
            )
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = stringResource(titleRes),
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = stringResource(bodyRes),
                    style = type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
        }
        if (actionLabelRes != null) {
            RipDpiButton(
                text = stringResource(actionLabelRes),
                onClick = onAction,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth().then(actionTestTag?.let { Modifier.ripDpiTestTag(it) } ?: Modifier),
            )
        }
    }
}

/** The language choice on the welcome step, so the user can read every later step. */
@Composable
internal fun OnboardingLanguageAction(modifier: Modifier = Modifier) {
    var showLanguagePicker by rememberSaveable { mutableStateOf(false) }
    RipDpiButton(
        text = stringResource(R.string.settings_language_title),
        onClick = { showLanguagePicker = true },
        variant = RipDpiButtonVariant.Outline,
        leadingIcon = RipDpiIcons.Public,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.OnboardingLanguage),
    )
    if (showLanguagePicker) {
        LanguagePickerSheet(onDismissRequest = { showLanguagePicker = false })
    }
}

/**
 * The built-in resolvers. The default resolver stands for [OnboardingDnsSystemId], which keeps the default DNS
 * settings untouched.
 */
@Composable
internal fun OnboardingDnsSelectionContent(
    selectedDnsProviderId: String,
    onDnsSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultProviderId = canonicalDefaultDnsProviderDefinition().providerId
    Column(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
    ) {
        BuiltInDnsProviders.forEach { provider ->
            val isDefault = provider.providerId == defaultProviderId
            val optionId = if (isDefault) OnboardingDnsSystemId else provider.providerId
            OnboardingOptionCard(
                title = provider.displayName,
                description = provider.host,
                badgeText = stringResource(R.string.onboarding_badge_recommended).takeIf { isDefault },
                selected = selectedDnsProviderId == optionId,
                onClick = { onDnsSelected(optionId) },
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingDnsOptionPrefix + provider.providerId),
            )
        }
    }
}

/**
 * What keeps the connection up in the background, in the words of the main-screen advisories. Each action opens
 * a system settings screen; the step requests no permission.
 */
@Composable
internal fun OnboardingBackgroundGuidanceContent(
    mode: Mode,
    onOpenBatterySettings: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
    ) {
        OnboardingNote(
            icon = RipDpiIcons.Timer,
            titleRes = R.string.permissions_battery_title,
            bodyRes = R.string.permissions_battery_body,
            actionLabelRes = R.string.settings_permission_action_open_settings,
            onAction = onOpenBatterySettings,
            actionTestTag = RipDpiTestTags.OnboardingBatterySettings,
        )
        // Always-on VPN and lockdown are settings of the system VPN, so they do not apply to the proxy.
        if (mode == Mode.VPN) {
            OnboardingNote(
                icon = RipDpiIcons.Vpn,
                titleRes = R.string.settings_permissions_always_on_title,
                bodyRes = R.string.settings_permissions_always_on_needed,
            )
            OnboardingNote(
                icon = RipDpiIcons.Lock,
                titleRes = R.string.settings_permissions_lockdown_title,
                bodyRes = R.string.settings_permissions_lockdown_needed,
                actionLabelRes = R.string.settings_permission_action_open_vpn_settings,
                onAction = onOpenVpnSettings,
                actionTestTag = RipDpiTestTags.OnboardingVpnSettings,
            )
        }
    }
}

/** What onboarding set up, shown before every exit to the main screen. */
@Composable
internal fun OnboardingCompletionContent(
    uiState: OnboardingUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val components = RipDpiThemeTokens.components
    val dnsProvider =
        if (uiState.selectedDnsProviderId == OnboardingDnsSystemId) {
            canonicalDefaultDnsProviderDefinition()
        } else {
            dnsProviderById(uiState.selectedDnsProviderId)
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.OnboardingCompletionSummary)
                .ripDpiFeatureSurface(
                    container = colors.card,
                    shape = RipDpiThemeTokens.shapes.xl,
                    border = colors.outlineVariant,
                    borderWidth = spacing.xs / 4,
                    clip = true,
                ).padding(
                    horizontal = components.inputs.fieldHorizontalPadding,
                    vertical = components.rows.settingsRowVerticalPadding,
                ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        OnboardingSummaryRow(
            labelRes = R.string.onboarding_done_interface,
            value =
                stringResource(
                    when (OnboardingPersona.fromId(uiState.selectedPersona)) {
                        OnboardingPersona.Guided -> R.string.persona_guided
                        OnboardingPersona.Advanced -> R.string.persona_advanced
                    },
                ),
        )
        OnboardingSummaryRow(
            labelRes = R.string.onboarding_done_mode,
            value =
                stringResource(
                    when (uiState.selectedMode) {
                        Mode.VPN -> R.string.status_mode_vpn
                        Mode.Proxy -> R.string.status_mode_proxy
                    },
                ),
        )
        OnboardingSummaryRow(
            labelRes = R.string.onboarding_done_dns,
            value = dnsProvider?.displayName ?: stringResource(R.string.dns_custom_title),
        )
        OnboardingSummaryRow(
            labelRes = R.string.onboarding_done_test,
            value =
                stringResource(
                    when (uiState.validationState) {
                        is OnboardingValidationState.Success -> R.string.onboarding_done_test_passed
                        is OnboardingValidationState.Failed -> R.string.onboarding_done_test_failed
                        else -> R.string.onboarding_done_test_not_run
                    },
                ),
        )
    }
}

/** A label and its value, stacked so both wrap at large font scales, read as one TalkBack stop. */
@Composable
private fun OnboardingSummaryRow(
    @StringRes labelRes: Int,
    value: String,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(text = stringResource(labelRes), style = type.secondaryBody, color = colors.mutedForeground)
        Text(text = value, style = type.bodyEmphasis, color = colors.foreground)
    }
}
