package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun CompactProfileRow(
    profile: com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel?,
    onChangeProfile: () -> Unit,
    enabled: Boolean = true,
) {
    RipDpiCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            Column(
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
                enabled = enabled,
                modifier =
                    Modifier.ripDpiTestTag(
                        com.poyka.ripdpi.ui.testing.RipDpiTestTags.DiagnosticProfileSearchOpen,
                    ),
                variant = RipDpiButtonVariant.Outline,
                wrapLabel = true,
            )
        }
    }
}

@Composable
internal fun ProfilePickerContent(
    profiles: List<com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel>,
    selectedProfileId: String?,
    search: com.poyka.ripdpi.ui.components.profiles.ProfileSearchState,
    onSelectProfile: (String) -> Unit,
) {
    val tag = com.poyka.ripdpi.ui.testing.RipDpiTestTags.DiagnosticProfileSearch
    val entries =
        profiles.map {
            DiagnosticProfileSearchEntry(it, displayFamilyLabel(it.family), displayedDiagnosticProfileDescription(it))
        }
    val visible = filterDiagnosticProfiles(entries, search.query, search.filter)
    com.poyka.ripdpi.ui.components.profiles.ProfileSearchControls(
        state = search,
        filters =
            entries.distinctBy { it.profile.family }.map {
                com.poyka.ripdpi.ui.components.profiles
                    .ProfileSearchFilter(it.profile.family.name, it.familyLabel)
            },
        tag = tag,
    )
    if (visible.isEmpty()) {
        com.poyka.ripdpi.ui.components.profiles
            .ProfileSearchEmpty(search, tag)
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(tag),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            visible.groupBy { it.profile.family }.forEach { (_, group) ->
                Text(
                    text = group.first().familyLabel,
                    modifier = Modifier.fillMaxWidth(),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                group.forEach { entry ->
                    DiagnosticsProfileCard(
                        profile = entry.profile,
                        selected = entry.profile.id == selectedProfileId,
                        onClick = { onSelectProfile(entry.profile.id) },
                    )
                }
            }
        }
    }
}
