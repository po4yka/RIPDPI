package com.poyka.ripdpi.ui.components.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal data class ProfileSearchFilter(
    val value: String,
    val label: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileSearchControls(
    state: ProfileSearchState,
    filters: List<ProfileSearchFilter>,
    tag: String,
) {
    RipDpiTextField(
        value = state.query,
        onValueChange = { state.query = it },
        modifier = Modifier.fillMaxWidth(),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.profile_search_label),
                testTag = RipDpiTestTags.profileSearchQuery(tag),
            ),
        trailingContent = {
            if (state.query.isNotEmpty()) {
                RipDpiIconButton(
                    icon = RipDpiIcons.Close,
                    contentDescription = stringResource(R.string.profile_search_clear),
                    onClick = { state.query = "" },
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.profileSearchClear(tag)),
                )
            }
        },
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        ProfileFilterChip(state, tag, null, stringResource(R.string.profile_search_all))
        filters.forEach { ProfileFilterChip(state, tag, it.value, it.label) }
    }
}

@Composable
private fun ProfileFilterChip(
    state: ProfileSearchState,
    tag: String,
    value: String?,
    label: String,
) {
    RipDpiChip(
        text = label,
        selected = state.filter == value,
        onClick = { state.filter = value },
        modifier = Modifier.width(IntrinsicSize.Max).ripDpiTestTag(RipDpiTestTags.profileSearchFilter(tag, value)),
        wrapLabel = true,
        role = Role.RadioButton,
    )
}

@Composable
internal fun ProfileSearchEmpty(
    state: ProfileSearchState,
    tag: String,
) {
    Text(
        text = stringResource(R.string.profile_search_no_results),
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.profileSearchEmpty(tag)),
        style = RipDpiThemeTokens.type.body,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    RipDpiButton(
        text = stringResource(R.string.profile_search_reset),
        onClick = state::reset,
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.profileSearchReset(tag)),
        variant = RipDpiButtonVariant.Outline,
        wrapLabel = true,
    )
}
