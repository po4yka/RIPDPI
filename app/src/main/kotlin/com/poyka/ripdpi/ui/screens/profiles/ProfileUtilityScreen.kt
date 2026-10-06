package com.poyka.ripdpi.ui.screens.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchControls
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchEmpty
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchFilter
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchState
import com.poyka.ripdpi.ui.components.profiles.matchesProfileQuery
import com.poyka.ripdpi.ui.components.profiles.rememberProfileSearchState
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

internal class ProfileUtilityActions(
    val favorite: (ProfileUtilityReference, Long, Boolean) -> Unit,
    val check: (ProfileUtilityReference) -> Unit,
    val checkAndSelect: (ProfileUtilityReference) -> Unit,
    val cancel: () -> Unit,
    val retryCleanup: () -> Unit,
    val probeUrl: (String) -> Unit,
    val checkFastest: () -> Unit,
)

@Composable
internal fun ProfileUtilityScreen(
    state: ProfileUtilityUiState,
    onBack: () -> Unit,
    actions: ProfileUtilityActions,
    modifier: Modifier = Modifier,
) {
    val search = rememberProfileSearchState()
    val visible =
        state.profiles
            .filter { profile ->
                matchesProfileQuery(search.query, listOf(profile.label, profile.groupLabel.orEmpty(), profile.kind)) &&
                    when (search.filter) {
                        "favorites" -> profile.favorite
                        "recent" -> profile.recentSequence != null
                        else -> true
                    }
            }.let { if (search.filter == "recent") it.sortedByDescending { profile -> profile.recentSequence } else it }
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
                item(key = "empty") {
                    Text(
                        stringResource(R.string.profile_utility_empty),
                        style = RipDpiThemeTokens.type.body,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
            }

            state.catalogState == ProfileCatalogState.Failed -> {
                Unit
            }

            visible.isEmpty() -> {
                item(key = "no-matches") {
                    Column { ProfileSearchEmpty(search, ProfileUtilityTestTags.Search) }
                }
            }

            search.filter == "recent" -> {
                items(visible, key = { ProfileUtilityTestTags.action(it.reference, "row") }) {
                    ProfileUtilityRow(it, state, actions)
                }
            }

            else -> {
                visible.groupBy { it.sectionKey() }.forEach { (key, profiles) ->
                    item(key = "section:$key") {
                        Text(
                            sectionTitle(profiles.first()),
                            style = RipDpiThemeTokens.type.sectionTitle,
                            color = RipDpiThemeTokens.colors.foreground,
                        )
                    }
                    items(profiles, key = { ProfileUtilityTestTags.action(it.reference, "row") }) {
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
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        Text(
            stringResource(R.string.profile_utility_disclosure),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        RipDpiTextField(
            value = state.probeUrl,
            onValueChange = actions.probeUrl,
            modifier = Modifier.fillMaxWidth(),
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.profile_utility_url),
                    testTag = ProfileUtilityTestTags.ProbeUrl,
                ),
        )
        RipDpiButton(
            text = stringResource(R.string.profile_utility_check_fastest),
            onClick = actions.checkFastest,
            wrapLabel = true,
            enabled =
                state.catalogState == ProfileCatalogState.Ready && state.profiles.isNotEmpty() &&
                    state.canMeasure && !state.cleanupPending,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(ProfileUtilityTestTags.Fastest),
        )
        ProfileSearchControls(
            search,
            listOf(
                ProfileSearchFilter("favorites", stringResource(R.string.profile_utility_favorites)),
                ProfileSearchFilter("recent", stringResource(R.string.profile_utility_recents)),
            ),
            ProfileUtilityTestTags.Search,
        )
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

private fun ProfileUtilityItem.sectionKey(): String =
    when (val ref = reference) {
        is ProfileUtilityReference.NativeRelay -> "native"
        is ProfileUtilityReference.Xray -> "xray"
        is ProfileUtilityReference.SelectorMember -> "selector:${ref.groupId}"
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
        modifier = Modifier.ripDpiTestTag(ProfileUtilityTestTags.action(profile.reference, "row")),
        variant = if (profile.applied) RipDpiCardVariant.Tonal else RipDpiCardVariant.Outlined,
    ) {
        ProfileUtilityHeader(profile, state, actions, locale)
        ProfileUtilityMeasurement(profile, locale)
        ProfileUtilityControls(profile, state, actions)
    }
}

@Composable
private fun ProfileUtilityHeader(
    profile: ProfileUtilityItem,
    state: ProfileUtilityUiState,
    actions: ProfileUtilityActions,
    locale: java.util.Locale,
) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(profile.label, style = type.sectionTitle, color = colors.foreground)
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
    profile.lastUsedAtMillis?.let { time ->
        Text(
            stringResource(
                R.string.profile_utility_last_used,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(time)),
            ),
            style = type.caption,
            color = colors.mutedForeground,
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
    (profile.measurement as? ProfileMeasurementUiState.Measured)?.let { result ->
        Text(
            stringResource(
                R.string.profile_utility_checked_at,
                DateFormat
                    .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
                    .format(Date(result.observedAtMillis)),
            ),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
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
            text = stringResource(R.string.profile_utility_check),
            onClick = { actions.check(profile.reference) },
            variant = RipDpiButtonVariant.Outline,
            wrapLabel = true,
            enabled = state.canMeasure && !state.cleanupPending,
            modifier =
                Modifier.fillMaxWidth().ripDpiTestTag(
                    ProfileUtilityTestTags.action(profile.reference, "check"),
                ),
        )
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
