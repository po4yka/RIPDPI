@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.indicators

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
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.logBadge
import com.poyka.ripdpi.ui.theme.metadataChip
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private const val CollapsedMessageMaxChars = 120

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
    action: (@Composable () -> Unit)? = null,
) {
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val palette = logRowPalette(tone)
    val collapsible = message.length > CollapsedMessageMaxChars
    var expanded by rememberSaveable(message) { mutableStateOf(false) }

    // The merged semantics read the visible texts, so a collapsed entry is announced by its summary only.
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = components.feedback.decorativeBadgeSize)
                .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        LogRowHeader(timestamp = timestamp, type = type, palette = palette, tone = tone, action = action)
        LogRowMessage(
            message = if (collapsible && !expanded) logMessageSummary(message) else message,
            palette = palette,
            modifier = Modifier.fillMaxWidth(),
        )
        if (collapsible) {
            androidx.compose.material3.Text(
                text = stringResource(if (expanded) R.string.action_show_less else R.string.logs_entry_details),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = RipDpiThemeTokens.colors.info,
                modifier = Modifier.ripDpiClickable(role = Role.Button, onClick = { expanded = !expanded }),
            )
        }
        if (metadataChips.isNotEmpty()) {
            LogMetadataChips(metadataChips)
        }
    }
}

/** First words of a long entry, cut at a space, so one diagnostics record no longer fills the screen. */
internal fun logMessageSummary(message: String): String {
    if (message.length <= CollapsedMessageMaxChars) return message
    val head = message.take(CollapsedMessageMaxChars)
    val cut = head.lastIndexOf(' ').takeIf { it > 0 } ?: head.length
    return head.take(cut).trimEnd() + "\u2026"
}

/** Lets long `key=value` tokens wrap after `=` and `_` instead of in the middle of a word. */
internal fun logMessageWithTokenBreaks(message: String): String =
    message.replace("=", "=\u200B").replace("_", "_\u200B")

@Composable
private fun LogRowHeader(
    timestamp: String,
    type: String,
    palette: LogRowPalette,
    tone: LogRowTone,
    action: (@Composable () -> Unit)?,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val typeScale = RipDpiThemeTokens.type
    val locale = LocalConfiguration.current.locales[0]
    val styleState = rememberUpdatedStyleState(null)
    val badgeResolver = rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.logBadge(tone), CommonStyle, styleState)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Text(
                text = timestamp,
                style = typeScale.monoLog,
                color = colors.mutedForeground,
            )
            Box(
                modifier =
                    Modifier
                        .ripDpiStyleSurface(badgeResolver, shape = RipDpiThemeTokens.shapes.xxl)
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
        action?.invoke()
    }
}

@Composable
private fun LogRowMessage(
    message: String,
    palette: LogRowPalette,
    modifier: Modifier,
) {
    androidx.compose.material3.Text(
        text = logMessageWithTokenBreaks(message),
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

    val styleState = rememberUpdatedStyleState(null)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val chipMaxWidth = maxWidth
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            metadataChips.forEach { chip ->
                val resolver =
                    rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.metadataChip, CommonStyle, styleState)
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = chipMaxWidth)
                            .ripDpiStyleSurface(resolver, shape = RipDpiThemeTokens.shapes.xxl)
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
    val badgeContent: Color,
    val message: Color,
)

@Composable
private fun logRowPalette(tone: LogRowTone): LogRowPalette {
    val colors = RipDpiThemeTokens.colors

    return when (tone) {
        LogRowTone.Dns -> {
            LogRowPalette(
                badgeContent = colors.infoContainerForeground,
                message = colors.foreground,
            )
        }

        LogRowTone.Connection -> {
            LogRowPalette(
                badgeContent = colors.foreground,
                message = colors.foreground,
            )
        }

        LogRowTone.Warning -> {
            LogRowPalette(
                badgeContent = colors.warningContainerForeground,
                message = colors.foreground,
            )
        }

        LogRowTone.Error -> {
            LogRowPalette(
                badgeContent = colors.destructiveContainerForeground,
                message = colors.foreground,
            )
        }
    }
}

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
