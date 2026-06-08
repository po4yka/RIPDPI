package com.poyka.ripdpi.ui.screens.preflight

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val SessionPreviewLength = 8

@Composable
fun ProfilePreflightRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfilePreflightViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.profile_preflight_share_title)
    ProfilePreflightScreen(
        state = state,
        onBack = onBack,
        onRun = viewModel::runPreflight,
        onShare = {
            val body = state.shareBody ?: return@ProfilePreflightScreen
            val title = state.shareTitle ?: chooserTitle
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, title)
                        .putExtra(Intent.EXTRA_TEXT, body),
                    chooserTitle,
                ),
            )
        },
        modifier = modifier,
    )
}

@Composable
internal fun ProfilePreflightScreen(
    state: ProfilePreflightUiState,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.title_profile_preflight),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.ProfilePreflight)),
    ) {
        ProfilePreflightHeaderCard(state)
        ProfilePreflightVerdictCard(state)
        ProfilePreflightActions(
            state = state,
            onRun = onRun,
            onShare = onShare,
        )
    }
}

@Composable
private fun ProfilePreflightHeaderCard(state: ProfilePreflightUiState) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.profile_preflight_active_profile),
                        style = MaterialTheme.typography.labelLarge,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                    Text(
                        text = state.activeProfileId,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                StatusIndicator(
                    label = state.runState.label(),
                    tone = state.runState.tone(),
                )
            }
            Text(
                text = stringResource(R.string.profile_preflight_body),
                style = MaterialTheme.typography.bodyMedium,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            if (state.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ProfilePreflightVerdictCard(state: ProfilePreflightUiState) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = stringResource(R.string.profile_preflight_verdict_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text =
                    state.errorMessage
                        ?: state.summary
                        ?: stringResource(R.string.profile_preflight_idle_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = state.verdictTextColor(),
            )
            state.sessionId?.let { sessionId ->
                Text(
                    text =
                        stringResource(
                            R.string.profile_preflight_session_format,
                            sessionId.take(SessionPreviewLength),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
private fun ProfilePreflightActions(
    state: ProfilePreflightUiState,
    onRun: () -> Unit,
    onShare: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
        RipDpiButton(
            text =
                if (state.isRunning) {
                    stringResource(R.string.profile_preflight_running)
                } else {
                    stringResource(R.string.profile_preflight_run)
                },
            onClick = onRun,
            enabled = !state.isRunning,
            modifier = Modifier.weight(1f),
        )
        RipDpiButton(
            text = stringResource(R.string.profile_preflight_share),
            onClick = onShare,
            enabled = state.canShare,
            variant = RipDpiButtonVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProfilePreflightUiState.verdictTextColor() =
    if (runState == ProfilePreflightRunState.Failed || runState == ProfilePreflightRunState.Error) {
        RipDpiThemeTokens.colors.destructive
    } else {
        RipDpiThemeTokens.colors.foreground
    }

@Composable
private fun ProfilePreflightRunState.label(): String =
    when (this) {
        ProfilePreflightRunState.Idle -> stringResource(R.string.profile_preflight_state_idle)
        ProfilePreflightRunState.Running -> stringResource(R.string.profile_preflight_state_running)
        ProfilePreflightRunState.Passed -> stringResource(R.string.profile_preflight_state_passed)
        ProfilePreflightRunState.Failed -> stringResource(R.string.profile_preflight_state_failed)
        ProfilePreflightRunState.Error -> stringResource(R.string.profile_preflight_state_error)
    }

private fun ProfilePreflightRunState.tone(): StatusIndicatorTone =
    when (this) {
        ProfilePreflightRunState.Idle -> StatusIndicatorTone.Idle
        ProfilePreflightRunState.Running -> StatusIndicatorTone.Active
        ProfilePreflightRunState.Passed -> StatusIndicatorTone.Active
        ProfilePreflightRunState.Failed -> StatusIndicatorTone.Error
        ProfilePreflightRunState.Error -> StatusIndicatorTone.Error
    }
