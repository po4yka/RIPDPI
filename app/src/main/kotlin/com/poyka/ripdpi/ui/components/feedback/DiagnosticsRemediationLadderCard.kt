package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationLadderUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.components.chrome.RipDpiRemediationCard
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone

@Composable
internal fun DiagnosticsRemediationLadderCard(
    ladder: DiagnosticsRemediationLadderUiModel,
    onAction: (DiagnosticsRemediationActionUiModel) -> Unit,
    modifier: Modifier = Modifier,
    cardTestTag: String? = null,
    actionTestTag: String? = null,
) {
    RipDpiRemediationCard(
        title = ladder.title,
        summary = ladder.summary,
        steps = ladder.steps.map { it.text },
        tone =
            when (ladder.tone) {
                DiagnosticsTone.Negative -> StatusIndicatorTone.Error
                DiagnosticsTone.Warning -> StatusIndicatorTone.Warning
                DiagnosticsTone.Positive -> StatusIndicatorTone.Active
                else -> StatusIndicatorTone.Idle
            },
        modifier = modifier,
        cardTestTag = cardTestTag,
        actionLabel = ladder.primaryAction.label,
        onAction = { onAction(ladder.primaryAction) },
        actionTestTag = actionTestTag,
    )
}
