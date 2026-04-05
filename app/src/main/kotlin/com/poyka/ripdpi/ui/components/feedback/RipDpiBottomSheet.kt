package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiSurfaceRole
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiSurfaceStyle

data class RipDpiSheetAction(
    val label: String,
    val onClick: () -> Unit,
    val testTag: String? = null,
    val variant: RipDpiButtonVariant = RipDpiButtonVariant.Primary,
    val enabled: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RipDpiBottomSheet(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    message: String? = null,
    icon: ImageVector? = null,
    testTag: String? = null,
    primaryAction: RipDpiSheetAction? = null,
    secondaryAction: RipDpiSheetAction? = null,
    actionLayout: RipDpiActionLayout = RipDpiActionLayout.Adaptive,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = Dp.Unspecified,
        shape =
            MaterialTheme.shapes.extraLarge.copy(
                bottomStart = ZeroCornerSize,
                bottomEnd = ZeroCornerSize,
            ),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = RipDpiThemeTokens.colors.foreground,
        scrimColor = MaterialTheme.colorScheme.scrim,
        dragHandle = { RipDpiBottomSheetHandle() },
    ) {
        RipDpiBottomSheetCard(
            title = title,
            modifier = Modifier.ripDpiTestTag(testTag),
            message = message,
            icon = icon,
            primaryAction = primaryAction,
            secondaryAction = secondaryAction,
            actionLayout = actionLayout,
            content = content,
        )
    }
}

@Composable
fun RipDpiBottomSheetCard(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    primaryAction: RipDpiSheetAction? = null,
    secondaryAction: RipDpiSheetAction? = null,
    actionLayout: RipDpiActionLayout = RipDpiActionLayout.Adaptive,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type
    val surfaceStyle = ripDpiSurfaceStyle(RipDpiSurfaceRole.Sheet)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = surfaceStyle.container,
        contentColor = surfaceStyle.content,
        border = BorderStroke(RipDpiStroke.Thin, surfaceStyle.border),
        shadowElevation = surfaceStyle.shadowElevation,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = layout.horizontalPadding,
                        end = layout.horizontalPadding,
                        top = spacing.sm,
                        bottom = spacing.xxl,
                    ),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            icon?.let {
                RipDpiBottomSheetIconBadge(icon = it)
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = title,
                    style = type.sheetTitle,
                    color = colors.foreground,
                )
                message?.let {
                    Text(
                        text = it,
                        style = type.body,
                        color = colors.mutedForeground,
                    )
                }
            }

            content()

            BottomSheetActionColumn(
                primaryAction = primaryAction,
                secondaryAction = secondaryAction,
                actionLayout = actionLayout,
            )
        }
    }
}

@Composable
private fun RipDpiBottomSheetHandle() {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components

    Box(
        modifier =
            Modifier
                .padding(top = 12.dp)
                .size(width = components.sheetHandleWidth, height = components.sheetHandleHeight)
                .background(color = colors.border, shape = RipDpiThemeTokens.shapes.full),
    )
}

@Composable
private fun RipDpiBottomSheetIconBadge(icon: ImageVector) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components

    Box(
        modifier =
            Modifier
                .size(components.dialogIconSize)
                .background(color = colors.inputBackground, shape = RipDpiThemeTokens.shapes.full),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.foreground,
            modifier = Modifier.size(RipDpiIconSizes.Default),
        )
    }
}

@Composable
private fun BottomSheetActionColumn(
    primaryAction: RipDpiSheetAction?,
    secondaryAction: RipDpiSheetAction?,
    actionLayout: RipDpiActionLayout,
) {
    val spacing = RipDpiThemeTokens.spacing
    val hasPrimaryAction = primaryAction != null
    val hasSecondaryAction = secondaryAction != null
    val resolvedActionLayout = actionLayout.resolvedActionLayout()

    if (!hasPrimaryAction && !hasSecondaryAction) {
        return
    }

    if (resolvedActionLayout == RipDpiActionLayout.Stacked) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            primaryAction?.let { action ->
                RipDpiButton(
                    text = action.label,
                    onClick = action.onClick,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(action.testTag),
                    variant = action.variant,
                    enabled = action.enabled,
                )
            }
            secondaryAction?.let { action ->
                RipDpiButton(
                    text = action.label,
                    onClick = action.onClick,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(action.testTag),
                    variant = RipDpiButtonVariant.Outline,
                    enabled = action.enabled,
                )
            }
        }
    } else {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            secondaryAction?.let { action ->
                RipDpiButton(
                    text = action.label,
                    onClick = action.onClick,
                    modifier = Modifier.ripDpiTestTag(action.testTag),
                    variant = RipDpiButtonVariant.Outline,
                    enabled = action.enabled,
                    density = RipDpiControlDensity.Compact,
                )
            }
            primaryAction?.let { action ->
                RipDpiButton(
                    text = action.label,
                    onClick = action.onClick,
                    modifier = Modifier.ripDpiTestTag(action.testTag),
                    variant = action.variant,
                    enabled = action.enabled,
                    density = RipDpiControlDensity.Compact,
                )
            }
        }
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true, heightDp = 640)
@Composable
private fun RipDpiBottomSheetLightPreview() {
    RipDpiBottomSheetPreview(themePreference = "light")
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true, heightDp = 640)
@Composable
private fun RipDpiBottomSheetDarkPreview() {
    RipDpiBottomSheetPreview(themePreference = "dark")
}

@Composable
private fun RipDpiBottomSheetPreview(themePreference: String) {
    RipDpiTheme(themePreference = themePreference) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(560.dp)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            RipDpiBottomSheetCard(
                title = "Launcher icon options",
                message = "Changes apply immediately, but some launchers cache icon masks until the next refresh.",
                icon = RipDpiIcons.Info,
                primaryAction = RipDpiSheetAction(label = "Apply icon", onClick = {}),
                secondaryAction = RipDpiSheetAction(label = "Not now", onClick = {}),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
                ) {
                    Text(
                        text = "- Monochrome icon aliases only affect Android 13+ themed icons.",
                        style = RipDpiThemeTokens.type.body,
                        color = RipDpiThemeTokens.colors.foreground,
                    )
                    Text(
                        text = "- Adaptive shape stays controlled by the current launcher.",
                        style = RipDpiThemeTokens.type.body,
                        color = RipDpiThemeTokens.colors.foreground,
                    )
                }
            }
        }
    }
}
