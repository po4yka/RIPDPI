package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class ReplayStepStatus { Success, Failure, Pending }

data class ReplayStep(
    val whenLabel: String,
    val name: String,
    val detail: String,
    val status: ReplayStepStatus,
)

@Composable
fun ReplayFailureScreen(
    timestampLabel: String,
    probeSummary: String,
    steps: ImmutableList<ReplayStep>,
    recommendation: String,
    onReplay: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiContentScreenScaffold(
        title = stringResource(R.string.title_replay_failure),
        modifier = modifier,
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        RipDpiCard(variant = RipDpiCardVariant.Outlined) {
            Text(
                text =
                    stringResource(
                        R.string.vpn_replay_failure_header_format,
                        timestampLabel,
                    ),
                style = type.sectionTitle,
                color = colors.foreground,
            )
            Text(
                text = probeSummary,
                style = type.body,
                color = colors.mutedForeground,
            )
            Spacer(modifier = Modifier.height(spacing.lg))
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                steps.forEachIndexed { index, step ->
                    ReplayStepRow(step = step, isLast = index == steps.lastIndex)
                }
            }
            Spacer(modifier = Modifier.height(spacing.lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                RipDpiButton(
                    text = stringResource(R.string.vpn_replay_failure_replay),
                    onClick = onReplay,
                    variant = RipDpiButtonVariant.Outline,
                    leadingIcon = RipDpiIcons.Refresh,
                )
            }
        }
        WarningBanner(
            title = stringResource(R.string.vpn_replay_failure_recommendation_title),
            message = recommendation,
            tone = WarningBannerTone.Info,
        )
    }
}

@Composable
private fun ReplayStepRow(
    step: ReplayStep,
    isLast: Boolean,
) {
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val statusColor =
        when (step.status) {
            ReplayStepStatus.Success -> colors.success
            ReplayStepStatus.Failure -> colors.destructive
            ReplayStepStatus.Pending -> colors.mutedForeground
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = step.whenLabel,
            style = type.monoSmall,
            color = colors.mutedForeground,
            modifier = Modifier.width(spacing.xxxl * 2),
        )
        Column(
            modifier = Modifier.width(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(spacing.md)
                        .background(statusColor, shapes.full),
            )
            if (!isLast) {
                Box(
                    modifier =
                        Modifier
                            .width(RipDpiStroke.Thin)
                            .height(spacing.xxl)
                            .background(colors.divider),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = step.name,
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = step.detail,
                style = type.secondaryBody,
                color = colors.mutedForeground,
            )
            if (!isLast) {
                Spacer(modifier = Modifier.height(spacing.sm))
            }
        }
    }
}

private val sampleSteps =
    persistentListOf(
        ReplayStep(
            whenLabel = "12:18:43.013",
            name = "DNS resolve",
            detail = "answers in 8 ms",
            status = ReplayStepStatus.Success,
        ),
        ReplayStep(
            whenLabel = "12:18:43.022",
            name = "TCP open",
            detail = "SYN+ACK in 31 ms",
            status = ReplayStepStatus.Success,
        ),
        ReplayStep(
            whenLabel = "12:18:43.060",
            name = "TLS ClientHello",
            detail = "sent 517 B",
            status = ReplayStepStatus.Success,
        ),
        ReplayStep(
            whenLabel = "12:18:43.144",
            name = "TLS reset",
            detail = "RST received in 84 ms",
            status = ReplayStepStatus.Failure,
        ),
    )

@Preview(showBackground = true)
@Composable
private fun ReplayFailureScreenLightPreview() {
    RipDpiComponentPreview {
        ReplayFailureScreen(
            timestampLabel = "12:18:43",
            probeSummary = "Probe replay · youtube.com · TLS ClientHello",
            steps = sampleSteps,
            recommendation = "Possible RST + SNI inspection · try tlsrec_split_host instead",
            onReplay = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ReplayFailureScreenDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        ReplayFailureScreen(
            timestampLabel = "12:18:43",
            probeSummary = "Probe replay · youtube.com · TLS ClientHello",
            steps = sampleSteps,
            recommendation = "Possible RST + SNI inspection · try tlsrec_split_host instead",
            onReplay = {},
            onBack = {},
        )
    }
}
