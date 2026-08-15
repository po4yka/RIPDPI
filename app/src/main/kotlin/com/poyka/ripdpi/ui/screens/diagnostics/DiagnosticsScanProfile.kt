package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun CompactProfileRow(
    profile: com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel?,
    onChangeProfile: () -> Unit,
) {
    RipDpiCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = profile?.name ?: stringResource(R.string.diagnostics_profiles_title),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                if (profile != null) {
                    Text(
                        text = displayFamilyLabel(profile.family),
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
                if (profile?.requiresExplicitConsent == true) {
                    Text(
                        text = stringResource(R.string.diagnostics_profile_explicit_consent_required),
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = RipDpiThemeTokens.colors.warning,
                    )
                }
            }
            RipDpiButton(
                text = stringResource(R.string.diagnostics_profile_change_action),
                onClick = onChangeProfile,
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}

@Composable
internal fun ProfilePickerContent(
    profiles: List<com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Text(
        text = stringResource(R.string.diagnostics_profiles_title).uppercase(),
        style = RipDpiThemeTokens.type.sectionTitle,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Text(
        text = stringResource(R.string.diagnostics_profiles_body),
        style = RipDpiThemeTokens.type.secondaryBody,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        profiles.groupBy { it.family }.forEach { (family, familyProfiles) ->
            Text(
                text = displayFamilyLabel(family),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            familyProfiles.forEach { profile ->
                DiagnosticsProfileCard(
                    profile = profile,
                    selected = profile.id == selectedProfileId,
                    onClick = { onSelectProfile(profile.id) },
                )
            }
        }
    }
}

@Composable
@ReadOnlyComposable
private fun displayFamilyLabel(family: com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily): String =
    when (family) {
        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.GENERAL -> {
            stringResource(
                R.string.diagnostics_family_general,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.WEB_CONNECTIVITY -> {
            stringResource(
                R.string.diagnostics_family_web_connectivity,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.MESSAGING -> {
            stringResource(
                R.string.diagnostics_family_messaging,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.CIRCUMVENTION -> {
            stringResource(
                R.string.diagnostics_family_adaptation,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.THROTTLING -> {
            stringResource(
                R.string.diagnostics_family_throttling,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.DPI_FULL -> {
            stringResource(
                R.string.diagnostics_family_network_full,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.AUTOMATIC_PROBING -> {
            stringResource(
                R.string.diagnostics_family_automatic_probing,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.AUTOMATIC_AUDIT -> {
            stringResource(
                R.string.diagnostics_family_automatic_audit,
            )
        }
    }
