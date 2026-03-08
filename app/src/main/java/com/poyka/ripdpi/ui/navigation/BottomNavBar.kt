package com.poyka.ripdpi.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.card)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(
            color = colors.border,
            thickness = RipDpiStroke.Hairline,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.bottomBarHeight)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Route.topLevel.forEach { destination ->
                BottomNavItem(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    onClick = { onNavigate(destination) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(
    destination: Route,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .weight(1f)
            .padding(vertical = 3.dp)
            .clickable(
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 28.dp)
                .background(
                    color = if (selected) colors.inputBackground else Color.Transparent,
                    shape = RipDpiThemeTokens.shapes.xxl,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = requireNotNull(destination.icon),
                contentDescription = null,
                tint = if (selected) colors.foreground else colors.mutedForeground,
                modifier = Modifier.size(RipDpiIconSizes.Default),
            )
        }
        Text(
            text = androidx.compose.ui.res.stringResource(destination.titleRes),
            style = type.navLabel,
            color = if (selected) colors.foreground else colors.mutedForeground,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BottomNavBarLightPreview() {
    RipDpiTheme(themePreference = "light") {
        BottomNavBar(
            currentRoute = Route.Home.route,
            onNavigate = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BottomNavBarDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        BottomNavBar(
            currentRoute = Route.Settings.route,
            onNavigate = {},
        )
    }
}
