package com.poyka.ripdpi.ui.screens.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchControls
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchEmpty
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchFilter
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchState
import com.poyka.ripdpi.ui.components.profiles.rememberProfileSearchState
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.text.NumberFormat

internal class ProfileUtilityActions(
    val favorite: (ProfileUtilityReference, Long, Boolean) -> Unit,
    val check: (ProfileUtilityReference) -> Unit,
    val checkAndSelect: (ProfileUtilityReference) -> Unit,
    val cancel: () -> Unit,
    val retryCleanup: () -> Unit,
    val probeUrl: (String) -> Unit,
    val checkFastest: () -> Unit,
    val retryCatalog: () -> Unit = {},
    // Null where the host has no import entry, such as the Simple flavor.
    val importProfile: (() -> Unit)? = null,
)

@Composable
internal fun ProfileUtilityScreen(
    state: ProfileUtilityUiState,
    onBack: () -> Unit,
    actions: ProfileUtilityActions,
    modifier: Modifier = Modifier,
) {
    val search = rememberProfileSearchState()
    val catalog =
        remember(state.profiles, search.query, search.filter) {
            projectProfileCatalog(state.profiles, search.query, search.filter)
        }
    RipDpiSettingsScaffold(
        title = stringResource(R.string.profile_utility_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        modifier = modifier.ripDpiTestTag(ProfileUtilityTestTags.Screen),
    ) {
        item(key = "controls") { ProfileUtilityHeader(state, search, actions) }
        when {
            state.loading -> {
                item(key = "loading") {
                    Text(
                        stringResource(R.string.profile_utility_loading),
                        style = RipDpiThemeTokens.type.body,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
            }

            state.catalogState == ProfileCatalogState.Ready && state.profiles.isEmpty() -> {
                item(key = "empty") { ProfileUtilityEmptyState(actions.importProfile) }
            }

            state.catalogState == ProfileCatalogState.Failed -> {
                item(key = "catalog-retry") {
                    RipDpiButton(
                        text = stringResource(R.string.ui_profile_catalog_retry),
                        onClick = actions.retryCatalog,
                        modifier = Modifier.fillMaxWidth(),
                        wrapLabel = true,
                    )
                }
            }

            catalog.visible.isEmpty() -> {
                item(key = "no-matches") {
                    Column { ProfileSearchEmpty(search, ProfileUtilityTestTags.Search) }
                }
            }

            search.filter == "recent" -> {
                items(
                    catalog.visible,
                    key = { ProfileUtilityTestTags.action(it.reference, "row") },
                    contentType = { "profile" },
                ) {
                    ProfileUtilityRow(it, state, actions)
                }
            }

            else -> {
                catalog.sections.forEach { section ->
                    item(key = "section:${section.key}", contentType = "section") {
                        Text(
                            sectionTitle(section.titleProfile),
                            style = RipDpiThemeTokens.type.sectionTitle,
                            color = RipDpiThemeTokens.colors.foreground,
                        )
                    }
                    items(
                        section.profiles,
                        key = { ProfileUtilityTestTags.action(it.reference, "row") },
                        contentType = { "profile" },
                    ) {
                        ProfileUtilityRow(it, state, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileUtilityHeader(
    state: ProfileUtilityUiState,
    search: ProfileSearchState,
    actions: ProfileUtilityActions,
) {
    var optionsExpanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        ProfileSearchControls(
            search,
            listOf(
                ProfileSearchFilter("favorites", stringResource(R.string.profile_utility_favorites)),
                ProfileSearchFilter("recent", stringResource(R.string.profile_utility_recents)),
            ),
            ProfileUtilityTestTags.Search,
        )
        // Check help and probe options mean nothing until a profile exists to check.
        if (state.profiles.isNotEmpty()) {
            Text(
                stringResource(R.string.profile_utility_disclosure),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            ProfileUtilityDisclosure(
                text = stringResource(R.string.profile_utility_probe_options),
                expanded = optionsExpanded,
                onClick = { optionsExpanded = !optionsExpanded },
                tag = ProfileUtilityTestTags.ProbeOptions,
            )
            if (optionsExpanded) {
                ProfileUtilityProbeOptions(state, actions)
            }
        }
        if (state.cleanupPending) {
            Text(
                stringResource(R.string.profile_utility_cleanup_pending),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.foreground,
            )
            RipDpiButton(
                text = stringResource(R.string.profile_utility_retry_cleanup),
                onClick = actions.retryCleanup,
                wrapLabel = true,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(ProfileUtilityTestTags.Cleanup),
            )
        }
        state.failure?.takeUnless { state.cleanupPending && it == ProfileUtilityFailure.CleanupPending }?.let {
            Text(
                failureText(it),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.foreground,
            )
        }
    }
}

@Composable
private fun ProfileUtilityEmptyState(importProfile: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        Text(
            stringResource(R.string.profile_utility_empty),
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        importProfile?.let {
            RipDpiButton(
                text = stringResource(R.string.profile_utility_empty_import),
                onClick = it,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(ProfileUtilityTestTags.EmptyImport),
                leadingIcon = RipDpiIcons.Add,
            )
        }
    }
}

@Composable
private fun sectionTitle(profile: ProfileUtilityItem): String =
    when (val ref = profile.reference) {
        is ProfileUtilityReference.NativeRelay -> stringResource(R.string.profile_utility_saved)
        is ProfileUtilityReference.Xray -> "Xray"
        is ProfileUtilityReference.SelectorMember -> profile.groupLabel?.takeIf(String::isNotBlank) ?: ref.groupId
    }

@Composable
private fun ProfileUtilityRow(
    profile: ProfileUtilityItem,
    state: ProfileUtilityUiState,
    actions: ProfileUtilityActions,
) {
    val locale = LocalConfiguration.current.locales[0]
    RipDpiCard(
        modifier =
            Modifier
                .semantics { selected = profile.applied }
                .ripDpiTestTag(ProfileUtilityTestTags.action(profile.reference, "row")),
        variant = if (profile.applied) RipDpiCardVariant.Tonal else RipDpiCardVariant.Outlined,
    ) {
        ProfileUtilityHeader(profile, state, actions)
        ProfileUtilityMeasurement(profile, locale)
        ProfileUtilityControls(profile, state, actions)
        ProfileUtilityDetails(profile, state, actions, locale)
    }
}

@Composable
private fun ProfileUtilityHeader(
    profile: ProfileUtilityItem,
    state: ProfileUtilityUiState,
    actions: ProfileUtilityActions,
) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(profile.label, style = type.cardTitle, color = colors.foreground)
            Text(
                listOfNotNull(profile.groupLabel, profile.kind).joinToString(" · "),
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
        RipDpiIconButton(
            icon = RipDpiIcons.Favorite,
            contentDescription =
                stringResource(
                    if (profile.favorite) {
                        R.string.profile_utility_favorite_remove
                    } else {
                        R.string.profile_utility_favorite_add
                    },
                    profile.label,
                ),
            onClick = { actions.favorite(profile.reference, state.catalogGeneration, !profile.favorite) },
            selected = profile.favorite,
            enabled = !state.loading,
            modifier =
                Modifier
                    .semantics { selected = profile.favorite }
                    .ripDpiTestTag(ProfileUtilityTestTags.action(profile.reference, "favorite")),
        )
    }
    if (profile.applied) {
        Text(
            stringResource(R.string.profile_utility_applied),
            style = type.caption,
            color = colors.foreground,
        )
    }
}

@Composable
private fun ProfileUtilityMeasurement(
    profile: ProfileUtilityItem,
    locale: java.util.Locale,
) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Text(
        when (val result = profile.measurement) {
            ProfileMeasurementUiState.NotChecked -> {
                stringResource(R.string.profile_utility_not_checked)
            }

            ProfileMeasurementUiState.Checking -> {
                stringResource(R.string.profile_utility_checking)
            }

            is ProfileMeasurementUiState.Measured -> {
                stringResource(
                    R.string.profile_utility_measured,
                    NumberFormat.getIntegerInstance(locale).format(result.latencyMillis),
                )
            }

            is ProfileMeasurementUiState.Failed -> {
                failureText(result.reason)
            }
        },
        style = type.body,
        color = colors.foreground,
    )
}

@Composable
private fun ProfileUtilityControls(
    profile: ProfileUtilityItem,
    state: ProfileUtilityUiState,
    actions: ProfileUtilityActions,
) {
    if (profile.measurement == ProfileMeasurementUiState.Checking) {
        RipDpiButton(
            text = stringResource(R.string.config_cancel),
            onClick = actions.cancel,
            wrapLabel = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(ProfileUtilityTestTags.action(profile.reference, "cancel")),
        )
    } else {
        RipDpiButton(
            text = stringResource(R.string.profile_utility_check_select),
            onClick = { actions.checkAndSelect(profile.reference) },
            wrapLabel = true,
            enabled = state.canMeasure && !state.cleanupPending,
            modifier =
                Modifier.fillMaxWidth().ripDpiTestTag(
                    ProfileUtilityTestTags.action(profile.reference, "check_select"),
                ),
        )
    }
}

@Composable
private fun failureText(failure: ProfileUtilityFailure): String =
    stringResource(
        when (failure) {
            ProfileUtilityFailure.Persistence -> R.string.profile_utility_command_failed
            ProfileUtilityFailure.EnvironmentChanged -> R.string.profile_utility_environment_changed
            ProfileUtilityFailure.Unsupported -> R.string.profile_utility_unsupported
            ProfileUtilityFailure.Busy -> R.string.profile_utility_busy
            ProfileUtilityFailure.HttpFailed -> R.string.profile_utility_failed
            ProfileUtilityFailure.TimedOut -> R.string.profile_utility_timeout
            ProfileUtilityFailure.CleanupPending -> R.string.profile_utility_cleanup_pending
            ProfileUtilityFailure.SelectionFailed -> R.string.profile_utility_selection_failed
        },
    )
