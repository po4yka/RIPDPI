package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val typeScale = RipDpiThemeTokens.type
    val palette = logRowPalette(tone)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = components.decorativeBadgeSize)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$timestamp ${type.uppercase()} $message ${metadataChips.joinToString(" ")}"
                },
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth(),
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
                            horizontal = components.compactPillHorizontalPadding,
                            vertical = components.compactPillVerticalPadding,
                        ),
            ) {
                androidx.compose.material3.Text(
                    text = type.uppercase(),
                    style = typeScale.smallLabel,
                    color = palette.badgeContent,
                )
            }
            androidx.compose.material3.Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = typeScale.monoInline,
                color = palette.message,
            )
        }
        if (metadataChips.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                metadataChips.forEach { chip ->
                    Box(
                        modifier =
                            Modifier
                                .wrapContentWidth()
                                .background(colors.inputBackground, RipDpiThemeTokens.shapes.xxl)
                                .padding(
                                    horizontal = components.compactPillHorizontalPadding,
                                    vertical = components.compactPillVerticalPadding,
                                ),
                    ) {
                        androidx.compose.material3.Text(
                            text = chip,
                            style = typeScale.smallLabel,
                            color = colors.mutedForeground,
                        )
                    }
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

@Suppress("UnusedPrivateMember")
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
