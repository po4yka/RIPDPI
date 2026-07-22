package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class LogRowTone {
    Dns,
    Connection,
    Warning,
    Error,
}

@Composable
fun LogRow(
    timestamp: String,
    type: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: LogRowTone = LogRowTone.Connection,
    metadataChips: ImmutableList<String> = persistentListOf(),
) {
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val palette = logRowPalette(tone)
    val stacksMessage = LocalDensity.current.fontScale >= StackedMessageFontScale

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = components.feedback.decorativeBadgeSize)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$timestamp $type $message ${metadataChips.joinToString(" ")}"
                },
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        if (stacksMessage) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                LogRowHeader(timestamp = timestamp, type = type, palette = palette)
                LogRowMessage(message = message, palette = palette, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                LogRowHeader(timestamp = timestamp, type = type, palette = palette)
                LogRowMessage(message = message, palette = palette, modifier = Modifier.weight(1f))
            }
        }
        if (metadataChips.isNotEmpty()) {
            LogMetadataChips(metadataChips)
        }
    }
}

@Composable
private fun LogRowHeader(
    timestamp: String,
    type: String,
    palette: LogRowPalette,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val typeScale = RipDpiThemeTokens.type
    val locale = LocalConfiguration.current.locales[0]

    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.material3.Text(
            text = timestamp,
            style = typeScale.monoLog,
            color = colors.mutedForeground,
        )
        Box(
            modifier =
                Modifier
                    .background(palette.badgeContainer, RipDpiThemeTokens.shapes.xxl)
                    .padding(
                        horizontal = components.rows.compactPillHorizontalPadding,
                        vertical = components.rows.compactPillVerticalPadding,
                    ),
        ) {
            androidx.compose.material3.Text(
                text = type.uppercase(locale),
                style = typeScale.smallLabel,
                color = palette.badgeContent,
            )
        }
    }
}

@Composable
private fun LogRowMessage(
    message: String,
    palette: LogRowPalette,
    modifier: Modifier,
) {
    androidx.compose.material3.Text(
        text = message,
        modifier = modifier,
        style = RipDpiThemeTokens.type.monoInline,
        color = palette.message,
    )
}

@Composable
private fun LogMetadataChips(metadataChips: ImmutableList<String>) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val typeScale = RipDpiThemeTokens.type

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val chipMaxWidth = maxWidth
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            metadataChips.forEach { chip ->
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = chipMaxWidth)
                            .background(colors.inputBackground, RipDpiThemeTokens.shapes.xxl)
                            .padding(
                                horizontal = components.rows.compactPillHorizontalPadding,
                                vertical = components.rows.compactPillVerticalPadding,
                            ),
                ) {
                    androidx.compose.material3.Text(
                        text = chip,
                        style = typeScale.smallLabel,
                        color = colors.mutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Immutable
private data class LogRowPalette(
    val badgeContainer: Color,
    val badgeContent: Color,
    val message: Color,
)

@Composable
private fun logRowPalette(tone: LogRowTone): LogRowPalette {
    val colors = RipDpiThemeTokens.colors

    return when (tone) {
        LogRowTone.Dns -> {
            LogRowPalette(
                badgeContainer = colors.infoContainer,
                badgeContent = colors.infoContainerForeground,
                message = colors.foreground,
            )
        }

        LogRowTone.Connection -> {
            LogRowPalette(
                badgeContainer = colors.inputBackground,
                badgeContent = colors.foreground,
                message = colors.foreground,
            )
        }

        LogRowTone.Warning -> {
            LogRowPalette(
                badgeContainer = colors.warningContainer,
                badgeContent = colors.warningContainerForeground,
                message = colors.foreground,
            )
        }

        LogRowTone.Error -> {
            LogRowPalette(
                badgeContainer = colors.destructiveContainer,
                badgeContent = colors.destructiveContainerForeground,
                message = colors.foreground,
            )
        }
    }
}

private const val StackedMessageFontScale = 1.5f

@Preview(showBackground = true)
@Composable
private fun LogRowPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            LogRow(
                timestamp = "02:14:38",
                type = "dns",
                message = "DNS resolver switched to 1.1.1.1",
                tone = LogRowTone.Dns,
                metadataChips = persistentListOf("runtime:vpn-1", "scan:diag-7"),
            )
            LogRow(
                timestamp = "02:14:42",
                type = "conn",
                message = "VPN service started on 127.0.0.1:1080",
                tone = LogRowTone.Connection,
            )
            LogRow(
                timestamp = "02:14:47",
                type = "warn",
                message = "Fallback DNS is active for this network",
                tone = LogRowTone.Warning,
            )
            LogRow(
                timestamp = "02:14:51",
                type = "error",
                message = "VPN permission was denied by the system",
                tone = LogRowTone.Error,
            )
        }
    }
}
