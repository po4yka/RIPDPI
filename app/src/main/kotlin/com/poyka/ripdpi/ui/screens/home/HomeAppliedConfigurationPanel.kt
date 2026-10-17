package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeAppliedConfigurationUiState
import com.poyka.ripdpi.activities.ReconnectSettingsIdentity
import com.poyka.ripdpi.services.RunningReconnectRequest
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun HomeAppliedConfigurationPanel(
    state: HomeAppliedConfigurationUiState,
    onReconnect: (com.poyka.ripdpi.services.RunningReconnectRequest.ConfirmedRuntime) -> Unit,
    onCancelReconnect: () -> Unit,
) {
    var confirming by remember {
        mutableStateOf<ReconnectReviewCapture?>(null)
    }
    val currentState by rememberUpdatedState(state)
    val eligible = state.visible && state.pending && !state.reconnecting && state.confirmation != null
    LaunchedEffect(state.confirmation, state.reconnectSettingsIdentity, eligible) {
        if (!eligible || confirming?.matches(state) != true) confirming = null
    }
    if (!state.visible) return
    RipDpiCard(modifier = Modifier.ripDpiTestTag("home_runtime_configuration")) {
        HomeAppliedConfigurationSummary(state)
        if (state.reconnecting) {
            RipDpiButton(
                text = stringResource(R.string.config_cancel),
                onClick = onCancelReconnect,
                variant = RipDpiButtonVariant.Secondary,
                wrapLabel = true,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag("home_runtime_cancel"),
            )
        } else if (eligible) {
            RipDpiButton(
                text = stringResource(R.string.oom_recovery_action_reconnect),
                onClick = {
                    val latest = currentState
                    latest.confirmation?.let {
                        confirming =
                            ReconnectReviewCapture(
                                it,
                                latest.reconnectSettingsIdentity,
                            )
                    }
                },
                variant = RipDpiButtonVariant.Secondary,
                wrapLabel = true,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag("home_runtime_reconnect"),
            )
        }
    }
    val captured = confirming
    if (captured != null && eligible && captured.matches(state)) {
        HomeReconnectReviewDialog(
            state = state,
            onDismiss = { confirming = null },
            onConfirm = {
                if (confirming === captured) {
                    confirming = null
                    val latest = currentState
                    val remainsEligible = latest.visible && latest.pending && !latest.reconnecting
                    if (remainsEligible && captured.matches(latest)) onReconnect(captured.runtime)
                }
            },
        )
    }
}

private class ReconnectReviewCapture(
    val runtime: RunningReconnectRequest.ConfirmedRuntime,
    private val settingsIdentity: ReconnectSettingsIdentity,
) {
    fun matches(state: HomeAppliedConfigurationUiState): Boolean =
        runtime == state.confirmation && settingsIdentity === state.reconnectSettingsIdentity
}

@Composable
private fun HomeAppliedConfigurationSummary(state: HomeAppliedConfigurationUiState) {
    val tokens = RipDpiThemeTokens
    Text(
        stringResource(R.string.runtime_config_title),
        style = tokens.type.bodyEmphasis,
        color = tokens.colors.foreground,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        state.status,
        style = tokens.type.body,
        color = tokens.colors.foreground,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.previous) {
        Text(
            stringResource(R.string.runtime_config_last_confirmed),
            style = tokens.type.secondaryBody,
            color = tokens.colors.mutedForeground,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.confirmedSummary?.let {
        Text(
            it,
            style = tokens.type.secondaryBody,
            color = tokens.colors.mutedForeground,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.message?.let {
        Text(
            it,
            style = tokens.type.secondaryBody,
            color = tokens.colors.mutedForeground,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (state.pending) {
        Text(
            stringResource(R.string.runtime_config_pending_body),
            style = tokens.type.secondaryBody,
            color = tokens.colors.mutedForeground,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
