package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val TopAppBarStackFontScale = 1.5f

@Composable
fun RipDpiTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    navigationContentDescription: String? = null,
    navigationEnabled: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    val containerMaxWidth = layout.contentMaxWidth + layout.horizontalPadding + layout.horizontalPadding
    val separateTitle = LocalDensity.current.fontScale >= TopAppBarStackFontScale
    val hasControls = (navigationIcon != null && onNavigationClick != null) || actions != null

    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = layout.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = containerMaxWidth).fillMaxWidth()) {
            if (!separateTitle || hasControls) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = layout.appBarMinHeight),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopAppBarNavigation(
                        navigationIcon,
                        onNavigationClick,
                        navigationContentDescription,
                        navigationEnabled,
                    )
                    if (separateTitle) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        TopAppBarTitle(title, Modifier.weight(1f))
                    }
                    if (actions != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions,
                        )
                    }
                }
            }
            if (separateTitle) {
                TopAppBarTitle(title, Modifier.fillMaxWidth().padding(bottom = spacing.sm))
            }
        }
    }
}

@Composable
private fun TopAppBarNavigation(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: (() -> Unit)?,
    contentDescription: String?,
    enabled: Boolean,
) {
    if (icon != null && onClick != null) {
        RipDpiIconButton(
            icon = icon,
            contentDescription = contentDescription ?: stringResource(R.string.navigation_back),
            onClick = onClick,
            style = RipDpiIconButtonStyle.Ghost,
            enabled = enabled,
        )
    }
}

@Composable
private fun TopAppBarTitle(
    title: String,
    modifier: Modifier,
) {
    Text(
        text = title,
        modifier = modifier.semantics { heading() },
        style = RipDpiThemeTokens.type.appBarTitle,
        color = RipDpiThemeTokens.colors.foreground,
    )
}

@Preview(showBackground = true)
@Composable
private fun RipDpiTopAppBarPreview() {
    RipDpiComponentPreview {
        RipDpiTopAppBar(
            title = "Logs",
            navigationIcon = RipDpiIcons.Back,
            onNavigationClick = {},
            actions = {
                RipDpiIconButton(
                    icon = RipDpiIcons.Search,
                    contentDescription = "Search",
                    onClick = {},
                )
                RipDpiIconButton(
                    icon = RipDpiIcons.Overflow,
                    contentDescription = "More",
                    onClick = {},
                )
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiTopAppBarDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiTopAppBar(
            title = "Mode editor",
            navigationIcon = RipDpiIcons.Back,
            onNavigationClick = {},
        )
    }
}
