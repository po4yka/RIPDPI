package com.poyka.ripdpi.ui.components.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.zIndex
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.theme.RipDpiContentGrouping
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiScaffoldWidth {
    Content,
    Form,
    Dashboard,
    Intro,
}

@Composable
fun RipDpiScreenScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors

    Scaffold(
        modifier = modifier.ripDpiAutomationTreeRoot(),
        containerColor = colors.background,
        topBar = topBar,
        snackbarHost = snackbarHost,
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
fun RipDpiContentScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    navigationContentDescription: String? = null,
    contentWidth: RipDpiScaffoldWidth = RipDpiScaffoldWidth.Content,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing

    RipDpiScreenScaffold(
        modifier = modifier,
        topBar = {
            RipDpiTopAppBar(
                title = title,
                navigationIcon = navigationIcon,
                onNavigationClick = onNavigationClick,
                navigationContentDescription = navigationContentDescription,
                actions = actions,
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(RipDpiThemeTokens.colors.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = ripDpiScaffoldMaxWidth(contentWidth))
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = layout.horizontalPadding,
                            top = spacing.sm,
                            end = layout.horizontalPadding,
                            bottom = spacing.xxl,
                        ),
                verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
                content = content,
            )
        }
    }
}

@Composable
fun RipDpiSettingsScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    navigationContentDescription: String? = null,
    navigationEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing

    RipDpiScreenScaffold(
        modifier = modifier,
        topBar = {
            RipDpiTopAppBar(
                title = title,
                navigationIcon = navigationIcon,
                onNavigationClick = onNavigationClick,
                navigationContentDescription = navigationContentDescription,
                navigationEnabled = navigationEnabled,
                actions = actions,
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(RipDpiThemeTokens.colors.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .widthIn(max = ripDpiScaffoldMaxWidth(RipDpiScaffoldWidth.Form))
                        .fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = layout.horizontalPadding,
                        top = spacing.sm,
                        end = layout.horizontalPadding,
                        bottom = spacing.xxl,
                    ),
                verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
                content = content,
            )
        }
    }
}

@Composable
fun RipDpiDashboardScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val scrollState = rememberScrollState()

    RipDpiScreenScaffold(
        modifier = modifier,
        topBar = {
            Column {
                topBar()
                // Hairline appears only once content scrolls beneath the top bar,
                // so the bar reads as a surface over scrollable content instead of
                // clipping it abruptly at rest.
                if (scrollState.canScrollBackward) {
                    HorizontalDivider(thickness = RipDpiStroke.Hairline, color = colors.border)
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(colors.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = ripDpiScaffoldMaxWidth(RipDpiScaffoldWidth.Dashboard))
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(
                            start = layout.horizontalPadding,
                            top = spacing.sm,
                            end = layout.horizontalPadding,
                            // No bottom gap: content scrolls flush to the bottom nav
                            // bar (innerPadding already reserves the bar height), so
                            // there is no empty grey band above it.
                        ),
                verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
                content = content,
            )
        }
    }
}

@Composable
fun RipDpiIntroScaffold(
    modifier: Modifier = Modifier,
    topAction: @Composable BoxScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layout = RipDpiThemeTokens.layout
    val colors = RipDpiThemeTokens.colors

    Box(
        modifier =
            modifier
                .ripDpiAutomationTreeRoot()
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = layout.horizontalPadding),
    ) {
        Box(
            modifier =
                Modifier
                    .statusBarsPadding()
                    .widthIn(max = ripDpiScaffoldMaxWidth(RipDpiScaffoldWidth.Intro))
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .zIndex(1f),
            content = topAction,
        )
        Column(
            modifier =
                Modifier
                    .widthIn(max = ripDpiScaffoldMaxWidth(RipDpiScaffoldWidth.Intro))
                    .fillMaxWidth()
                    .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        Column(
            modifier =
                Modifier
                    .widthIn(max = ripDpiScaffoldMaxWidth(RipDpiScaffoldWidth.Intro))
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = footer,
        )
    }
}

@Composable
fun RipDpiAdaptiveColumns(
    modifier: Modifier = Modifier,
    primaryWeight: Float = 0.55f,
    spacing: androidx.compose.ui.unit.Dp = RipDpiThemeTokens.spacing.lg,
    primary: @Composable ColumnScope.() -> Unit,
    secondary: @Composable ColumnScope.() -> Unit,
) {
    val grouping = RipDpiThemeTokens.layout.contentGrouping
    if (grouping == RipDpiContentGrouping.SplitColumns) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            Column(
                modifier = Modifier.weight(primaryWeight).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.layout.sectionGap),
                content = primary,
            )
            Column(
                modifier = Modifier.weight(1f - primaryWeight).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.layout.sectionGap),
                content = secondary,
            )
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.layout.sectionGap),
        ) {
            primary()
            secondary()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiScreenScaffoldPreview() {
    RipDpiComponentPreview {
        RipDpiScreenScaffold(
            topBar = {
                RipDpiTopAppBar(title = "Screen")
            },
        ) {
            Text(text = "Content", modifier = Modifier.padding(it))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiSettingsScaffoldPreview() {
    RipDpiComponentPreview {
        RipDpiSettingsScaffold(
            title = "Settings",
            navigationIcon = RipDpiIcons.Back,
            onNavigationClick = {},
        ) {
            item { Text(text = "Setting row") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiDashboardScaffoldPreview() {
    RipDpiComponentPreview {
        RipDpiDashboardScaffold(
            topBar = {
                RipDpiTopAppBar(title = "Dashboard")
            },
        ) {
            Text(text = "Dashboard content")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiContentScreenScaffoldPreview() {
    RipDpiComponentPreview {
        RipDpiContentScreenScaffold(
            title = "Details",
            navigationIcon = RipDpiIcons.Back,
            onNavigationClick = {},
        ) {
            Text(text = "Detail content")
        }
    }
}

/**
 * Content ceiling for a scaffold body.
 *
 * Apply this *before* `fillMaxWidth()`/`fillMaxSize()` in the modifier chain.
 * A fill modifier pins `min == max` to the parent width, and `widthIn` can only
 * constrain within the range it is handed, so the cap silently evaporates when
 * it comes second — the tiers below then have no effect at any window size.
 */
@Composable
private fun ripDpiScaffoldMaxWidth(width: RipDpiScaffoldWidth) =
    when (width) {
        RipDpiScaffoldWidth.Content -> {
            RipDpiThemeTokens.layout.contentMaxWidth
        }

        RipDpiScaffoldWidth.Form -> {
            RipDpiThemeTokens.layout.formMaxWidth
        }

        RipDpiScaffoldWidth.Dashboard -> {
            RipDpiThemeTokens.layout.contentMaxWidth
        }

        RipDpiScaffoldWidth.Intro -> {
            RipDpiThemeTokens.layout.formMaxWidth.coerceAtMost(
                RipDpiThemeTokens.layout.contentMaxWidth,
            )
        }
    }
