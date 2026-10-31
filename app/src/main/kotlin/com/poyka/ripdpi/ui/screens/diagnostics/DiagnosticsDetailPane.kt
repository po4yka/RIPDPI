package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsScreenUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground

/** The detail opened most recently: session, approach, event, probe or strategy-probe candidate. */
@Composable
internal fun diagnosticsDetails(
    uiState: DiagnosticsScreenUiState,
    actions: DiagnosticsScreenActions,
    expertMode: Boolean,
): List<DiagnosticsDetail> =
    diagnosticsDetails(
        selectedSessionDetail = uiState.selectedSessionDetail,
        selectedApproachDetail = uiState.selectedApproachDetail,
        selectedEvent = uiState.selectedEvent,
        probePresentation =
            DiagnosticsProbeSheetPresentation(
                selectedProbe = uiState.selectedProbe,
                expertMode = expertMode,
                onReplayProbe = actions.onReplayProbe,
            ),
        selectedStrategyProbeCandidate = uiState.selectedStrategyProbeCandidate,
        onDismissSessionDetail = actions.onDismissSessionDetail,
        onToggleSensitiveSessionDetails = actions.onToggleSensitiveSessionDetails,
        onSelectStrategyProbeCandidate = actions.onSelectStrategyProbeCandidate,
        onDismissStrategyProbeCandidate = actions.onDismissStrategyProbeCandidate,
        onSelectEvent = actions.onSelectEvent,
        onDismissEventDetail = actions.onDismissEventDetail,
        onSelectProbe = actions.onSelectProbe,
        onDismissProbeDetail = actions.onDismissProbeDetail,
        onDismissApproachDetail = actions.onDismissApproachDetail,
    )

/** Wide-window host for the Diagnostics details that open as sheets on compact windows. */
@Composable
internal fun DiagnosticsDetailPane(
    detail: DiagnosticsDetail?,
    modifier: Modifier = Modifier,
) {
    val padding = RipDpiThemeTokens.layout.horizontalPadding
    Column(modifier = modifier.fillMaxSize().ripDpiScreenBackground()) {
        if (detail == null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .ripDpiTestTag(RipDpiTestTags.DiagnosticsDetailPlaceholder)
                        .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.history_detail_placeholder),
                    style = RipDpiThemeTokens.type.body,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }
        RipDpiTopAppBar(
            title = detail.title,
            actions = {
                RipDpiIconButton(
                    icon = RipDpiIcons.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    onClick = detail.onDismiss,
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsDetailClose),
                )
            },
        )
        Column(
            modifier =
                Modifier
                    .ripDpiTestTag(detail.testTag)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = padding),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        ) {
            detail.message?.let {
                Text(
                    text = it,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
            detail.content(this)
        }
    }
}
