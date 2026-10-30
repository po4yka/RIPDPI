package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val TopAppBarStackFontScale = 1.5f

// Material hides the expanded title semantics past this collapse fraction.
private const val CollapsedTitleFraction = 0.5f

// Material places the first and last glyph 16dp in from the bar edge (4dp slot padding + 12dp icon inset).
private val MaterialGlyphInset = 16.dp

/**
 * Screen top app bar on Material 3. The small bar grows to fit a wrapped title; at large font with
 * navigation or actions the title moves to the full-width expanded row of a two-row bar, so it
 * never shares a narrow slot with the controls. Wire [scrollBehavior] to the screen's scroll
 * container with `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)`.
 *
 * The background spans the full width; the controls and title keep to the content column, so on
 * wide windows they line up with the capped, centered body instead of the screen edges.
 */
@Composable
fun RipDpiTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    navigationContentDescription: String? = null,
    navigationEnabled: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    // Beside its list the detail pane needs no back arrow: the list is the way back.
    val hasNavigation = navigationIcon != null && onNavigationClick != null && !LocalShownBesideList.current
    val stackTitle =
        LocalDensity.current.fontScale >= TopAppBarStackFontScale && (hasNavigation || actions != null)
    val titleContent: @Composable () -> Unit = { Text(text = title, modifier = Modifier.semantics { heading() }) }
    val navigationContent: @Composable () -> Unit = {
        if (hasNavigation && navigationIcon != null && onNavigationClick != null) {
            RipDpiIconButton(
                icon = navigationIcon,
                contentDescription = navigationContentDescription ?: stringResource(R.string.navigation_back),
                onClick = onNavigationClick,
                style = RipDpiIconButtonStyle.Ghost,
                enabled = navigationEnabled,
            )
        }
    }
    val actionsContent: @Composable RowScope.() -> Unit = actions ?: {}
    val colors = ripDpiTopAppBarColors()
    val layout = RipDpiThemeTokens.layout
    val barModifier =
        Modifier
            .widthIn(max = layout.contentMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = (layout.horizontalPadding - MaterialGlyphInset).coerceAtLeast(0.dp))

    Box(
        modifier = modifier.fillMaxWidth().background(colors.containerColor),
        contentAlignment = Alignment.TopCenter,
    ) {
        RipDpiTopAppBarContent(
            stackTitle = stackTitle,
            barModifier = barModifier,
            titleContent = titleContent,
            navigationContent = navigationContent,
            actionsContent = actionsContent,
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
private fun RipDpiTopAppBarContent(
    stackTitle: Boolean,
    barModifier: Modifier,
    titleContent: @Composable () -> Unit,
    navigationContent: @Composable () -> Unit,
    actionsContent: @Composable RowScope.() -> Unit,
    colors: TopAppBarColors,
    scrollBehavior: TopAppBarScrollBehavior?,
) {
    if (stackTitle) {
        // The collapsed row shares width with the controls, so it shows the title only once the bar
        // has collapsed; otherwise its hidden copy would wrap there and inflate the expanded bar.
        val collapsed by remember(scrollBehavior) {
            derivedStateOf { (scrollBehavior?.state?.collapsedFraction ?: 0f) >= CollapsedTitleFraction }
        }
        TwoRowsTopAppBar(
            title = { expanded -> if (expanded || collapsed) titleContent() },
            modifier = barModifier,
            navigationIcon = navigationContent,
            actions = actionsContent,
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
    } else {
        TopAppBar(
            title = titleContent,
            modifier = barModifier,
            navigationIcon = navigationContent,
            actions = actionsContent,
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
private fun ripDpiTopAppBarColors(): TopAppBarColors {
    val colors = RipDpiThemeTokens.colors
    // One color at rest and scrolled: the shell consumes the status-bar inset above the bar, so a
    // scrolled tint could not reach that strip and would leave a two-tone band.
    return TopAppBarDefaults.topAppBarColors(
        containerColor = colors.background,
        scrolledContainerColor = colors.background,
        navigationIconContentColor = colors.foreground,
        titleContentColor = colors.foreground,
        actionIconContentColor = colors.foreground,
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
