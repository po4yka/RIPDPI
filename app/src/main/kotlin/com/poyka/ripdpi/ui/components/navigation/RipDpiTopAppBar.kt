package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun RipDpiTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    navigationContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val containerMaxWidth = layout.contentMaxWidth + layout.horizontalPadding + layout.horizontalPadding

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = layout.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = containerMaxWidth)
                    .heightIn(min = layout.appBarMinHeight),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null && onNavigationClick != null) {
                RipDpiIconButton(
                    icon = navigationIcon,
                    contentDescription = navigationContentDescription ?: stringResource(R.string.navigation_back),
                    onClick = onNavigationClick,
                    style = RipDpiIconButtonStyle.Ghost,
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = type.appBarTitle,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
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
