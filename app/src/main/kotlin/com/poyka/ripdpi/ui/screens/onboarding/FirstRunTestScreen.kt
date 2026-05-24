package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class FirstRunTargetStatus { Queued, Running, Ok, Warn }

data class FirstRunTarget(
    val name: String,
    val status: FirstRunTargetStatus,
    val detail: String,
)

@Composable
fun FirstRunTestScreen(
    targets: ImmutableList<FirstRunTarget>,
    onSkip: () -> Unit,
    onApplyRecommendation: () -> Unit,
    modifier: Modifier = Modifier,
    applyEnabled: Boolean = false,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiContentScreenScaffold(
        title = stringResource(R.string.title_first_run_test),
        modifier = modifier,
    ) {
        RipDpiCard(variant = RipDpiCardVariant.Outlined) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = RipDpiIcons.Shield,
                    contentDescription = null,
                    tint = colors.foreground,
                    modifier = Modifier.size(RipDpiIconSizes.Large),
                )
                Text(
                    text = stringResource(R.string.vpn_first_run_test_title),
                    style = type.introTitle,
                    color = colors.foreground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.vpn_first_run_test_caption),
                    style = type.caption,
                    color = colors.mutedForeground,
                    textAlign = TextAlign.Center,
                )
            }
        }
        RipDpiCard(variant = RipDpiCardVariant.Outlined) {
            targets.forEachIndexed { index, target ->
                FirstRunTargetRow(target = target)
                if (index < targets.lastIndex) {
                    HorizontalDivider(
                        thickness = RipDpiStroke.Thin,
                        color = colors.divider,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RipDpiButton(
                text = stringResource(R.string.vpn_first_run_test_skip),
                onClick = onSkip,
                variant = RipDpiButtonVariant.Outline,
            )
            RipDpiButton(
                text = stringResource(R.string.vpn_first_run_test_apply),
                onClick = onApplyRecommendation,
                modifier = Modifier.weight(1f),
                variant = RipDpiButtonVariant.Primary,
                enabled = applyEnabled,
            )
        }
    }
}

@Composable
private fun FirstRunTargetRow(target: FirstRunTarget) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val icon: ImageVector =
        when (target.status) {
            FirstRunTargetStatus.Ok -> RipDpiIcons.Check
            FirstRunTargetStatus.Warn -> RipDpiIcons.Warning
            FirstRunTargetStatus.Running -> RipDpiIcons.Refresh
            FirstRunTargetStatus.Queued -> RipDpiIcons.ChevronRight
        }
    val tint =
        when (target.status) {
            FirstRunTargetStatus.Ok -> colors.success
            FirstRunTargetStatus.Warn -> colors.warning
            FirstRunTargetStatus.Running -> colors.info
            FirstRunTargetStatus.Queued -> colors.mutedForeground
        }
    val detailColor =
        when (target.status) {
            FirstRunTargetStatus.Ok -> colors.foreground
            FirstRunTargetStatus.Warn -> colors.warning
            FirstRunTargetStatus.Running -> colors.mutedForeground
            FirstRunTargetStatus.Queued -> colors.mutedForeground
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(RipDpiIconSizes.Medium),
        )
        Text(
            text = target.name,
            style = type.monoValue,
            color = colors.foreground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = target.detail,
            style = type.monoSmall,
            color = detailColor,
        )
    }
}

private val sampleTargets =
    persistentListOf(
        FirstRunTarget(name = "cloudflare-dns.com", status = FirstRunTargetStatus.Ok, detail = "OK · 12 ms"),
        FirstRunTarget(name = "discord.com", status = FirstRunTargetStatus.Warn, detail = "QUIC blocked"),
        FirstRunTarget(name = "youtube.com", status = FirstRunTargetStatus.Running, detail = "…"),
        FirstRunTarget(name = "github.com", status = FirstRunTargetStatus.Queued, detail = ""),
        FirstRunTarget(name = "raw.githubusercontent.com", status = FirstRunTargetStatus.Queued, detail = ""),
    )

@Preview(showBackground = true)
@Composable
private fun FirstRunTestScreenLightPreview() {
    RipDpiComponentPreview {
        FirstRunTestScreen(
            targets = sampleTargets,
            onSkip = {},
            onApplyRecommendation = {},
            applyEnabled = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FirstRunTestScreenDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        FirstRunTestScreen(
            targets = sampleTargets,
            onSkip = {},
            onApplyRecommendation = {},
            applyEnabled = true,
        )
    }
}
