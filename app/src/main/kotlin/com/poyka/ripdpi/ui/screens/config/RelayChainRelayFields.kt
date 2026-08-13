package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayChain
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.RelayChainHopUiState
import com.poyka.ripdpi.activities.RelayChainMaxHopsUi
import com.poyka.ripdpi.activities.RelayChainMinHopsUi
import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.activities.relayChainHopProfileIds
import com.poyka.ripdpi.data.RelayTrustDomainWarning
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.roundToInt

/**
 * Multi-hop relay-chain editor: an ordered list of hops (entry first, exit last) the user can
 * add to, remove from, and drag-reorder under the `[RelayChainMinHopsUi], [RelayChainMaxHopsUi]`
 * bound. Each hop picks a saved relay profile and surfaces its per-hop trust + live status/latency
 * caption; a cumulative anonymity/latency caveat banner mirrors the Tor caveat treatment in
 * [TorRelayFields], and the existing shared-trust-domain warning is preserved.
 *
 * RDS: all spacing / type / colors come from [RipDpiThemeTokens]; the drag-commit threshold is
 * derived from an existing layout token (not a `.dp` literal) so the no-literal-token floor holds.
 *
 * RDS deviation: relay-chain-editor — no dedicated spec card exists for this composite screen yet;
 * it reuses RipDpiCard, RipDpiButton, RipDpiDropdown, and WarningBanner tokens only (layout).
 */
@Composable
internal fun RelayChainFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    onRelayChainHopProfileIdChanged: (Int, String) -> Unit,
    onRelayChainHopAdded: () -> Unit,
    onRelayChainHopRemoved: (Int) -> Unit,
    onRelayChainHopMoved: (Int, Int) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val hops = draft.relayChainHopProfileIds()
    val chainErrorText = chainValidationMessage(uiState.validationErrors[ConfigFieldRelayChain])
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.config_relay_chain_editor_intro),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        hops.forEachIndexed { index, profileId ->
            RelayChainEditorHopRow(
                hopIndex = index,
                hopCount = hops.size,
                profileId = profileId,
                profiles = uiState.relayProfiles,
                hopStatus = uiState.relayChainHopStatus.hopStatusAt(index, hops.size),
                onProfileIdChanged = { onRelayChainHopProfileIdChanged(index, it) },
                onMoveUp = { onRelayChainHopMoved(index, index - 1) },
                onMoveDown = { onRelayChainHopMoved(index, index + 1) },
                onDragBy = { delta -> onRelayChainHopMoved(index, index + delta) },
                onRemove = { onRelayChainHopRemoved(index) },
            )
        }
        RelayChainHopBoundsFeedback(hopCount = hops.size)
        RelayChainValidationError(chainErrorText)
        RipDpiButton(
            text = stringResource(R.string.config_relay_chain_hop_add),
            onClick = onRelayChainHopAdded,
            variant = RipDpiButtonVariant.Secondary,
            density = RipDpiControlDensity.Compact,
            enabled = hops.size < RelayChainMaxHopsUi,
            leadingIcon = RipDpiIcons.Config,
            modifier = Modifier.fillMaxWidth(),
        )
        RelayChainCumulativeCaveat(
            hopCount = hops.size,
            cumulativeLatencyMs = uiState.relayChainHopStatus.cumulativeLatencyMs,
        )
        RelayChainTrustWarning(uiState)
    }
}

@Composable
private fun RelayChainEditorHopRow(
    hopIndex: Int,
    hopCount: Int,
    profileId: String,
    profiles: List<RelayProfileUiState>,
    hopStatus: RelayChainHopUiState,
    onProfileIdChanged: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragBy: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    // Drag distance (px) that commits a one-slot reorder, derived from a layout token rather than
    // a literal .dp so the RDS no-literal-token floor holds (same pattern as RoutesScreen).
    val dragThreshold = RipDpiThemeTokens.components.inputs.multilineFieldMinHeight
    val rowHeightPx = with(LocalDensity.current) { dragThreshold.toPx() }
    var dragOffsetY by remember { mutableStateOf(0f) }

    RipDpiCard(
        modifier =
            Modifier
                .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                .pointerInput(hopIndex, hopCount) {
                    detectDragGesturesAfterLongPress(
                        onDragEnd = { dragOffsetY = 0f },
                        onDragCancel = { dragOffsetY = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        dragOffsetY += dragAmount.y
                        if (dragOffsetY > rowHeightPx) {
                            onDragBy(1)
                            dragOffsetY = 0f
                        } else if (dragOffsetY < -rowHeightPx) {
                            onDragBy(-1)
                            dragOffsetY = 0f
                        }
                    }
                },
    ) {
        Text(
            text = relayChainHopRoleLabel(hopIndex = hopIndex, hopCount = hopCount),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        RelayChainProfileSelector(
            label = stringResource(R.string.config_relay_chain_hop_profile_label),
            selectedProfileId = profileId,
            profiles = profiles,
            errorText = null,
            onProfileSelected = onProfileIdChanged,
        )
        RelayChainSelectedProfileDetails(
            profile = profiles.firstOrNull { it.id == profileId },
            hopStatus = hopStatus,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            IconButton(onClick = onMoveUp, enabled = hopIndex > 0) {
                Icon(
                    imageVector = RipDpiIcons.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.config_relay_chain_hop_move_up),
                    tint = colors.foreground,
                )
            }
            IconButton(onClick = onMoveDown, enabled = hopIndex < hopCount - 1) {
                Icon(
                    imageVector = RipDpiIcons.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.config_relay_chain_hop_move_down),
                    tint = colors.foreground,
                )
            }
            IconButton(onClick = onRemove, enabled = hopCount > RelayChainMinHopsUi) {
                Icon(
                    imageVector = RipDpiIcons.Delete,
                    contentDescription = stringResource(R.string.config_relay_chain_hop_remove),
                    tint = colors.destructive,
                )
            }
        }
    }
}

@Composable
private fun relayChainHopRoleLabel(
    hopIndex: Int,
    hopCount: Int,
): String =
    when (hopIndex) {
        0 -> stringResource(R.string.config_relay_chain_hop_entry, hopIndex + 1)
        hopCount - 1 -> stringResource(R.string.config_relay_chain_hop_exit, hopIndex + 1)
        else -> stringResource(R.string.config_relay_chain_hop_middle, hopIndex + 1)
    }

@Composable
private fun RelayChainHopBoundsFeedback(hopCount: Int) {
    val message =
        when {
            hopCount >= RelayChainMaxHopsUi -> {
                stringResource(R.string.config_relay_chain_hop_max_reached, RelayChainMaxHopsUi)
            }

            hopCount <= RelayChainMinHopsUi -> {
                stringResource(R.string.config_relay_chain_hop_min_reached, RelayChainMinHopsUi)
            }

            else -> {
                return
            }
        }
    Text(
        text = message,
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}

@Composable
private fun RelayChainCumulativeCaveat(
    hopCount: Int,
    cumulativeLatencyMs: Long?,
) {
    val baseMessage = stringResource(R.string.config_relay_chain_cumulative_caveat_body, hopCount)
    val message =
        cumulativeLatencyMs
            ?.let { stringResource(R.string.config_relay_chain_cumulative_latency_caveat, it) }
            ?.let { "$baseMessage $it" }
            ?: baseMessage
    WarningBanner(
        title = stringResource(R.string.config_relay_chain_cumulative_caveat_title),
        message = message,
        tone = WarningBannerTone.Info,
    )
}

@Composable
private fun RelayChainProfileSelector(
    label: String,
    selectedProfileId: String,
    profiles: List<RelayProfileUiState>,
    errorText: String?,
    onProfileSelected: (String) -> Unit,
) {
    if (profiles.isEmpty()) {
        RipDpiTextField(
            state =
                rememberRipDpiTextFieldState(
                    value = selectedProfileId,
                    onValueChange = onProfileSelected,
                ),
            decoration =
                RipDpiTextFieldDecoration(
                    label = label,
                    helperText = stringResource(R.string.config_relay_chain_hop_empty_profiles),
                    errorText = errorText,
                ),
        )
        return
    }
    RipDpiDropdown(
        options =
            profiles
                .map { RipDpiDropdownOption(value = it.id, label = it.selectorLabel) }
                .toImmutableList(),
        selectedValue = selectedProfileId,
        onValueSelected = onProfileSelected,
        label = label,
        placeholder = stringResource(R.string.config_relay_chain_hop_select_placeholder),
        errorText = errorText,
    )
}

@Composable
private fun RelayChainSelectedProfileDetails(
    profile: RelayProfileUiState?,
    hopStatus: RelayChainHopUiState,
) {
    if (profile == null && hopStatus.displayLabel == null) {
        return
    }
    profile?.let {
        Text(
            text = it.trustLabel,
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
    hopStatus.displayLabel?.let { label ->
        Text(
            text = label,
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
private fun RelayChainValidationError(errorText: String?) {
    if (errorText == null) {
        return
    }
    Text(
        text = errorText,
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.destructive,
    )
}

@Composable
private fun RelayChainTrustWarning(uiState: ConfigUiState) {
    val warning = uiState.relayChainTrustWarning ?: return
    val shared =
        listOfNotNull(
            warning.sharedJurisdiction?.let { stringResource(R.string.config_relay_chain_shared_jurisdiction, it) },
            warning.sharedOperatorName?.let { stringResource(R.string.config_relay_chain_shared_operator, it) },
        ).joinToString(stringResource(R.string.config_relay_chain_shared_join))
    val missing = missingTrustDomainMessage(warning)
    if (shared.isBlank()) {
        WarningBanner(
            title = stringResource(R.string.relay_chain_missing_trust_title),
            message = missing,
            tone = WarningBannerTone.Warning,
        )
        return
    }
    WarningBanner(
        title = stringResource(R.string.relay_chain_shared_trust_title),
        message = sharedTrustDomainMessage(shared, missing),
        tone = WarningBannerTone.Warning,
    )
}

@Composable
private fun sharedTrustDomainMessage(
    shared: String,
    missing: String,
): String =
    listOf(
        stringResource(R.string.config_relay_chain_shared_trust_body, shared),
        missing,
    ).filter(String::isNotBlank).joinToString(" ")

@Composable
private fun missingTrustDomainMessage(warning: RelayTrustDomainWarning): String =
    buildList {
        if (warning.missingEntryTrustDomain) {
            add(stringResource(R.string.config_relay_chain_missing_entry_trust))
        }
        if (warning.missingExitTrustDomain) {
            add(stringResource(R.string.config_relay_chain_missing_exit_trust))
        }
    }.joinToString(" ")

@Composable
private fun chainValidationMessage(errorKey: String?): String? =
    when (errorKey) {
        "required" -> stringResource(R.string.config_relay_chain_error_required)
        "unsupported_entry" -> stringResource(R.string.config_relay_chain_error_unsupported_entry)
        "unsupported_exit" -> stringResource(R.string.config_relay_chain_error_unsupported_exit)
        else -> validationMessage(errorKey)
    }
