package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationLadderUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun DiagnosticsRemediationLadderCard(
    ladder: DiagnosticsRemediationLadderUiModel,
    onAction: (DiagnosticsRemediationActionUiModel) -> Unit,
    modifier: Modifier = Modifier,
    cardTestTag: String? = null,
    actionTestTag: String? = null,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(
        modifier =
            modifier.then(
                if (cardTestTag != null) {
                    Modifier.ripDpiTestTag(cardTestTag)
                } else {
                    Modifier
                },
            ),
        variant = RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(
            label = ladder.title,
            tone =
                when (ladder.tone) {
                    DiagnosticsTone.Negative -> StatusIndicatorTone.Error
                    DiagnosticsTone.Warning -> StatusIndicatorTone.Warning
                    DiagnosticsTone.Positive -> StatusIndicatorTone.Active
                    else -> StatusIndicatorTone.Idle
                },
        )
        Text(
            text = ladder.summary,
            style = RipDpiThemeTokens.type.body,
            color = colors.foreground,
        )
        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            ladder.steps.forEach { step ->
                Text(
                    text = "\u2022 ${step.text}",
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
        }
        RipDpiButton(
            text = ladder.primaryAction.label,
            onClick = { onAction(ladder.primaryAction) },
            variant = RipDpiButtonVariant.Secondary,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs)
                    .then(
                        if (actionTestTag != null) {
                            Modifier.ripDpiTestTag(actionTestTag)
                        } else {
                            Modifier
                        },
                    ),
        )
    }
}
