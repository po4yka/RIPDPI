package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomePauseUiState
import com.poyka.ripdpi.data.PauseFailure
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetAction
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.homePauseDuration
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.text.NumberFormat
import java.util.Date

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun HomePauseControls(
    state: HomePauseUiState,
    onPause: (Long) -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    var chooseDuration by rememberSaveable { mutableStateOf(false) }
    val eligible by rememberUpdatedState(state.available && state.phase == null)
    LaunchedEffect(state.available, state.phase) { if (!eligible) chooseDuration = false }
    Column(
        Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.HomePauseControls),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (state.available) {
            RipDpiButton(
                text = stringResource(R.string.pause_action),
                onClick = {
                    chooseDuration =
                        true
                },
                variant = RipDpiButtonVariant.Ghost,
                wrapLabel = true,
                modifier =
                    Modifier.ripDpiTestTag(
                        RipDpiTestTags.HomePauseOpen,
                    ),
            )
        }
        if (state.phase != null) HomePauseStatus(state, onResume, onStop)
        if (state.requestFailed) {
            Text(
                style = type.body,
                color = colors.foreground,
                text = stringResource(R.string.pause_persistence_failed),
            )
        }
    }
    if (chooseDuration) {
        HomePauseDurationSheet(
            onDismiss = { chooseDuration = false },
            onSelect = { minutes ->
                chooseDuration = false
                if (eligible) onPause(minutes * MillisPerMinute)
            },
        )
    }
}

@Composable
private fun HomePauseStatus(
    state: HomePauseUiState,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val phase = checkNotNull(state.phase)
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val deadline =
        state.deadlineWallMillis
            ?.let {
                android.text.format.DateFormat
                    .getTimeFormat(context)
                    .format(Date(it))
            }.orEmpty()
    val minutes =
        state.remainingMillis?.let {
            NumberFormat
                .getIntegerInstance(
                    configuration.locales[0],
                ).format((it + MillisPerMinute - 1) / MillisPerMinute)
        }
    state.mode?.let { mode ->
        Text(
            style = type.sectionTitle,
            color = colors.foreground,
            text =
                stringResource(
                    if (mode ==
                        com.poyka.ripdpi.data.Mode.VPN
                    ) {
                        R.string.home_mode_vpn
                    } else {
                        R.string.home_mode_proxy
                    },
                ),
        )
    }
    Text(
        style = type.body,
        color = colors.foreground,
        text =
            stringResource(
                when (phase) {
                    PausePhase.Paused -> R.string.pause_until
                    PausePhase.Releasing -> R.string.pause_releasing
                    PausePhase.Resuming -> R.string.pause_resuming
                    PausePhase.CleanupPending -> R.string.pause_cleanup_pending
                    PausePhase.Deferred -> R.string.pause_deferred
                },
                deadline,
            ),
    )
    if (minutes != null &&
        phase == PausePhase.Paused
    ) {
        Text(
            style = type.body,
            color = colors.foreground,
            text = stringResource(R.string.pause_remaining, minutes),
        )
    }
    Text(style = type.body, color = colors.foreground, text = stringResource(R.string.pause_saved_settings))
    state.failure?.let { HomePauseFailure(it) }
    HomePauseActions(phase, onResume, onStop)
}

@Composable
private fun HomePauseFailure(failure: PauseFailure) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Text(
        style = type.body,
        color = colors.foreground,
        text =
            stringResource(
                when (failure) {
                    PauseFailure.ClockUnavailable, PauseFailure.ClockChanged -> R.string.pause_clock_failed
                    PauseFailure.ConsentRequired -> R.string.pause_consent_required
                    PauseFailure.Lockdown -> R.string.pause_lockdown
                    PauseFailure.BackgroundRestricted -> R.string.pause_background_restricted
                    PauseFailure.UserStopped -> R.string.pause_user_stopped
                    PauseFailure.RuntimeRejected -> R.string.pause_resume_failed
                },
            ),
    )
}

@Composable
private fun HomePauseActions(
    phase: PausePhase?,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    if (phase == PausePhase.Paused || phase == PausePhase.Deferred) {
        RipDpiButton(
            text = stringResource(R.string.pause_resume_now),
            onClick = onResume,
            wrapLabel = true,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.HomePauseResume),
        )
    }
    RipDpiButton(
        text = stringResource(R.string.pause_stop),
        onClick = onStop,
        variant = RipDpiButtonVariant.Outline,
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.HomePauseStop),
        wrapLabel = true,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HomePauseDurationSheet(
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    RipDpiBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.pause_action),
        testTag = RipDpiTestTags.HomePauseDurationSheet,
        message = stringResource(R.string.pause_saved_settings),
        secondaryAction = RipDpiSheetAction(stringResource(android.R.string.cancel), onDismiss),
    ) {
        PauseDurationsMinutes.forEach { minutes ->
            RipDpiButton(
                text =
                    stringResource(
                        R.string.pause_duration_minutes,
                        NumberFormat.getIntegerInstance(configuration.locales[0]).format(minutes),
                    ),
                onClick = { onSelect(minutes) },
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.homePauseDuration(minutes)),
                variant = RipDpiButtonVariant.Outline,
                wrapLabel = true,
            )
        }
    }
}

private const val MillisPerMinute = 60_000L
private val PauseDurationsMinutes = listOf(5, 15, 30, 60)
