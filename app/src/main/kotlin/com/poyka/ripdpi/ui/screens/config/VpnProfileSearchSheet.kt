package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetAction
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchControls
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchEmpty
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchFilter
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VpnProfileSearchSheet(
    profiles: List<RelayProfileUiState>,
    selectedProfileId: String?,
    search: ProfileSearchState,
    onDismiss: () -> Unit,
    actions: ConfigProfileActions,
) {
    var actionTaken by remember { mutableStateOf(false) }

    fun act(
        action: (String) -> Unit,
        id: String,
    ) {
        if (!actionTaken) {
            actionTaken = true
            onDismiss()
            action(id)
        }
    }
    RipDpiBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.config_vpn_profiles_title),
        icon = RipDpiIcons.Search,
        testTag = RipDpiTestTags.RelayProfileSearchSheet,
        scrollPolicy = com.poyka.ripdpi.ui.components.feedback.RipDpiSheetScrollPolicy.Content,
        secondaryAction =
            RipDpiSheetAction(
                label = stringResource(R.string.config_cancel),
                onClick = onDismiss,
                testTag = RipDpiTestTags.profileSearchCancel(RipDpiTestTags.RelayProfileSearch),
                variant = RipDpiButtonVariant.Outline,
                wrapLabel = true,
            ),
    ) {
        VpnProfilePickerContent(
            profiles = profiles,
            selectedProfileId = selectedProfileId,
            search = search,
            actions =
                ConfigProfileActions(
                    share = { act(actions.share, it) },
                    select = { act(actions.select, it) },
                    edit = { act(actions.edit, it) },
                ),
        )
    }
}

@Composable
internal fun ColumnScope.VpnProfilePickerContent(
    profiles: List<RelayProfileUiState>,
    selectedProfileId: String?,
    search: ProfileSearchState,
    actions: ConfigProfileActions,
) {
    val tag = RipDpiTestTags.RelayProfileSearch
    val visible = filterRelayProfiles(profiles, search.query, search.filter)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).ripDpiTestTag(RipDpiTestTags.ConfigVpnProfileList),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        item(key = "search-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
                ProfileSearchControls(
                    state = search,
                    filters = profiles.distinctBy { it.kind }.map { ProfileSearchFilter(it.kind, it.kindLabel) },
                    tag = tag,
                )
            }
        }
        if (visible.isEmpty()) {
            item(key = "empty") {
                Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
                    ProfileSearchEmpty(search, tag)
                }
            }
        } else {
            visible.groupBy { it.kind }.forEach { (kind, group) ->
                item(key = "kind:$kind") {
                    Text(
                        text = group.first().kindLabel,
                        modifier = Modifier.fillMaxWidth(),
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = RipDpiThemeTokens.colors.foreground,
                    )
                }
                items(group, key = { "profile:${it.id}" }) { profile ->
                    VpnProfileItem(
                        profile = profile,
                        selected = profile.id == selectedProfileId,
                        onProfileShare = actions.share,
                        onProfileSelect = actions.select,
                        onProfileEdit = actions.edit,
                    )
                }
            }
        }
    }
}
