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
import androidx.compose.foundation.shape.ZeroCornerSize
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
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RipDpiBottomSheet(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    message: String? = null,
    icon: ImageVector? = null,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryActionVariant: RipDpiButtonVariant = RipDpiButtonVariant.Primary,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
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
            message = message,
            icon = icon,
            primaryActionLabel = primaryActionLabel,
            onPrimaryAction = onPrimaryAction,
            primaryActionVariant = primaryActionVariant,
            secondaryActionLabel = secondaryActionLabel,
            onSecondaryAction = onSecondaryAction,
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
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryActionVariant: RipDpiButtonVariant = RipDpiButtonVariant.Primary,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = colors.foreground,
        border = BorderStroke(RipDpiStroke.Thin, colors.cardBorder),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
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
                primaryActionLabel = primaryActionLabel,
                onPrimaryAction = onPrimaryAction,
                primaryActionVariant = primaryActionVariant,
                secondaryActionLabel = secondaryActionLabel,
                onSecondaryAction = onSecondaryAction,
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
    primaryActionLabel: String?,
    onPrimaryAction: (() -> Unit)?,
    primaryActionVariant: RipDpiButtonVariant,
    secondaryActionLabel: String?,
    onSecondaryAction: (() -> Unit)?,
) {
    val spacing = RipDpiThemeTokens.spacing
    val hasPrimaryAction = primaryActionLabel != null && onPrimaryAction != null
    val hasSecondaryAction = secondaryActionLabel != null && onSecondaryAction != null

    if (!hasPrimaryAction && !hasSecondaryAction) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (hasPrimaryAction) {
            RipDpiButton(
                text = primaryActionLabel.orEmpty(),
                onClick = { onPrimaryAction?.invoke() },
                modifier = Modifier.fillMaxWidth(),
                variant = primaryActionVariant,
            )
        }
        if (hasSecondaryAction) {
            RipDpiButton(
                text = secondaryActionLabel.orEmpty(),
                onClick = { onSecondaryAction?.invoke() },
                modifier = Modifier.fillMaxWidth(),
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 640)
@Composable
private fun RipDpiBottomSheetLightPreview() {
    RipDpiBottomSheetPreview(themePreference = "light")
}

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
                primaryActionLabel = "Apply icon",
                onPrimaryAction = {},
                secondaryActionLabel = "Not now",
                onSecondaryAction = {},
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
