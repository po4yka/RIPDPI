package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    brandGlyph: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val layout = RipDpiThemeTokens.layout

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(layout.appBarHeight)
                .padding(horizontal = layout.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(if (brandGlyph != null) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                brandGlyph != null -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .size(28.dp)
                                    .background(colors.foreground, CircleShape),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = brandGlyph,
                                style = type.brandGlyph.copy(fontSize = 13.sp, lineHeight = 13.sp),
                                color = colors.background,
                            )
                        }
                        Text(text = title, style = type.brandMark, color = colors.foreground)
                    }
                }

                navigationIcon != null && onNavigationClick != null -> {
                    RipDpiIconButton(
                        icon = navigationIcon,
                        contentDescription = navigationContentDescription ?: stringResource(R.string.navigation_back),
                        onClick = onNavigationClick,
                        style = RipDpiIconButtonStyle.Ghost,
                    )
                    Text(text = title, style = type.appBarTitle, color = colors.foreground)
                }

                else -> {
                    Text(text = title, style = type.appBarTitle, color = colors.foreground)
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiTopAppBarPreview() {
    RipDpiComponentPreview {
        RipDpiTopAppBar(
            title = "RIPDPI",
            brandGlyph = "R",
            actions = {
                RipDpiIconButton(
                    icon = RipDpiIcons.Overflow,
                    contentDescription = "More",
                    onClick = {},
                    style = RipDpiIconButtonStyle.Ghost,
                )
            },
        )
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
