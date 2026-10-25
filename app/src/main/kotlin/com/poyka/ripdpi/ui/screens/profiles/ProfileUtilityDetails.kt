package com.poyka.ripdpi.ui.screens.profiles

import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.TimestampYearDateTime
import com.poyka.ripdpi.platform.formatLocalizedTimestamp
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

@Composable
internal fun ProfileUtilityDisclosure(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val description =
        stringResource(if (expanded) R.string.semantic_state_expanded else R.string.semantic_state_collapsed)
    RipDpiButton(
        text = text,
        onClick = onClick,
        variant = RipDpiButtonVariant.Ghost,
        trailingIcon = if (expanded) RipDpiIcons.KeyboardArrowUp else RipDpiIcons.KeyboardArrowDown,
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = description }.ripDpiTestTag(tag),
    )
}

@Composable
internal fun ProfileUtilityProbeOptions(
    state: ProfileUtilityUiState,
    actions: ProfileUtilityActions,
) {
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
        enabled =
            state.catalogState == ProfileCatalogState.Ready && state.profiles.isNotEmpty() &&
                state.canMeasure && !state.cleanupPending,
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(ProfileUtilityTestTags.Fastest),
    )
}

@Composable
internal fun ProfileUtilityDetails(
    profile: ProfileUtilityItem,
    state: ProfileUtilityUiState,
    actions: ProfileUtilityActions,
    locale: Locale,
) {
    var expanded by rememberSaveable(profile.reference) { mutableStateOf(false) }
    ProfileUtilityDisclosure(
        text = stringResource(R.string.profile_utility_details),
        expanded = expanded,
        onClick = { expanded = !expanded },
        tag = ProfileUtilityTestTags.action(profile.reference, "details"),
    )
    if (expanded) {
        profile.quality?.let { ProfileQualityDetails(it, locale) }
        profile.lastUsedAtMillis?.let {
            ProfileUtilityDate(stringResource(R.string.profile_utility_last_used, formatDate(it, locale)))
        }
        (profile.measurement as? ProfileMeasurementUiState.Measured)?.let {
            ProfileUtilityDate(
                stringResource(R.string.profile_utility_checked_at, formatDate(it.observedAtMillis, locale)),
            )
        }
        if (profile.measurement != ProfileMeasurementUiState.Checking) {
            RipDpiButton(
                text = stringResource(R.string.profile_utility_check),
                onClick = { actions.check(profile.reference) },
                variant = RipDpiButtonVariant.Outline,
                enabled = state.canMeasure && !state.cleanupPending,
                modifier =
                    Modifier.fillMaxWidth().ripDpiTestTag(ProfileUtilityTestTags.action(profile.reference, "check")),
            )
        }
    }
}

@Composable
private fun ProfileUtilityDate(text: String) {
    Text(text, style = RipDpiThemeTokens.type.caption, color = RipDpiThemeTokens.colors.mutedForeground)
}

@Composable
private fun formatDate(
    time: Long,
    locale: Locale,
): String =
    formatLocalizedTimestamp(
        timestampMillis = time,
        locale = locale,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
        skeleton = TimestampYearDateTime,
    )
