package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class RipDpiContextMenuAction(
    val label: String,
    val icon: ImageVector,
    val shortcut: String? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Long-press / overflow-triggered context menu surface. Long-press
 * detection (e.g. `Modifier.combinedClickable(onLongClick = ...)`) and
 * the "lift" animation belong at the call site; this composable renders
 * only the popup surface, ordered actions, and the destructive
 * separator. Tap-scrim dismissal is handled by [Popup] when
 * [PopupProperties.focusable] is true.
 *
 * Matches `docs/design/rds/preview/gesture-long-press-menu.html`.
 */
@Composable
fun RipDpiContextMenu(
    visible: Boolean,
    actions: ImmutableList<RipDpiContextMenuAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) {
        return
    }
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing
    val regular = actions.filter { !it.destructive }
    val destructive = actions.filter { it.destructive }

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = modifier,
            shape = shapes.lg,
            color = colors.card,
            contentColor = colors.cardForeground,
            border = BorderStroke(RipDpiStroke.Thin, colors.border),
            shadowElevation = spacing.lg,
        ) {
            Column(
                modifier = Modifier.padding(vertical = spacing.xs),
            ) {
                regular.forEach { action ->
                    RipDpiContextMenuItem(
                        action = action,
                        onDismiss = onDismiss,
                    )
                }
                if (destructive.isNotEmpty() && regular.isNotEmpty()) {
                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                horizontal = spacing.sm,
                                vertical = spacing.xs,
                            ),
                        thickness = RipDpiStroke.Hairline,
                        color = colors.divider,
                    )
                }
                destructive.forEach { action ->
                    RipDpiContextMenuItem(
                        action = action,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun RipDpiContextMenuItem(
    action: RipDpiContextMenuAction,
    onDismiss: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val contentColor = if (action.destructive) colors.destructive else colors.foreground

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiClickable {
                    action.onClick()
                    onDismiss()
                }.padding(horizontal = spacing.md, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(RipDpiIconSizes.Small),
        )
        Text(
            modifier = Modifier.weight(1f),
            text = action.label,
            style = type.body,
            color = contentColor,
        )
        if (action.shortcut != null) {
            Text(
                text = action.shortcut,
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
        }
    }
}

private val SampleActions =
    persistentListOf(
        RipDpiContextMenuAction(
            label = "View pcap",
            icon = RipDpiIcons.Search,
            onClick = {},
        ),
        RipDpiContextMenuAction(
            label = "Copy as JSON",
            icon = RipDpiIcons.Copy,
            shortcut = "⌘C",
            onClick = {},
        ),
        RipDpiContextMenuAction(
            label = "Share replay link",
            icon = RipDpiIcons.Share,
            onClick = {},
        ),
        RipDpiContextMenuAction(
            label = "Archive",
            icon = RipDpiIcons.Archive,
            onClick = {},
        ),
        RipDpiContextMenuAction(
            label = "Delete",
            icon = RipDpiIcons.Delete,
            shortcut = "⌘⌫",
            destructive = true,
            onClick = {},
        ),
    )

@Preview(showBackground = true, name = "RipDpiContextMenu (light)")
@Composable
private fun RipDpiContextMenuLightPreview() {
    RipDpiComponentPreview {
        RipDpiContextMenu(
            visible = true,
            actions = SampleActions,
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, name = "RipDpiContextMenu (dark)")
@Composable
private fun RipDpiContextMenuDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiContextMenu(
            visible = true,
            actions = SampleActions,
            onDismiss = {},
        )
    }
}
