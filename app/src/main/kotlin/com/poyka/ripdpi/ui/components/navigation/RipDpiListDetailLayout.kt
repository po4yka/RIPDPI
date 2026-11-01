package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.RipDpiWidthClass

// Material 3 list-detail preferred list pane width, kept proportional within this range.
private val MinListPaneWidth = 360.dp
private val MaxListPaneWidth = 520.dp
private const val ListPaneFraction = 0.4f

// The narrowest detail pane that still reads as a pane rather than a sliver.
private val MinDetailPaneWidth = 320.dp
private val CollapsedRailWidth = 96.dp
private val ExpandedRailWidth = 260.dp

/**
 * True while the content shows as the detail pane beside its list. The list stays visible, so
 * [RipDpiTopAppBar] drops its back arrow there.
 */
val LocalShownBesideList = staticCompositionLocalOf { false }

/**
 * Whether a list and a detail pane both fit beside each other: the window is Expanded or wider and
 * the width left after the navigation rail holds the minimum list and detail panes at the current
 * font scale. When this is false the screen falls back to one pane, as on compact windows.
 */
@Composable
fun rememberListDetailTwoPane(): Boolean {
    val layout = RipDpiThemeTokens.layout
    if (layout.widthClass < RipDpiWidthClass.Expanded) return false
    val fontScale = LocalDensity.current.fontScale
    val rail =
        if (ripDpiNavigationSuiteType(layout.widthClass, fontScale) == NavigationSuiteType.WideNavigationRailExpanded) {
            ExpandedRailWidth
        } else {
            CollapsedRailWidth
        }
    val available = LocalConfiguration.current.screenWidthDp.dp - rail
    val needed = (MinListPaneWidth + layout.groupGap + MinDetailPaneWidth) * fontScale.coerceAtLeast(1f)
    return available >= needed
}

/**
 * Material 3 list-detail layout. With [showList] the list pane sits at the start and the detail
 * pane fills the rest; without it only the detail pane shows. With [showDetail] false the list
 * pane fills the width alone. Both panes keep their place in composition, so their content keeps
 * its state when a pane appears or leaves.
 *
 * shortcut: a stable Row instead of ListDetailPaneScaffold, which is still
 * ExperimentalMaterial3AdaptiveApi in adaptive-layout 1.3.0; switch once it is stable.
 */
@Composable
fun RipDpiListDetailLayout(
    showList: Boolean,
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    showDetail: Boolean = true,
) {
    BoxWithConstraints(modifier = modifier) {
        val listWidth: Dp = (maxWidth * ListPaneFraction).coerceIn(MinListPaneWidth, MaxListPaneWidth)
        Row(modifier = Modifier.fillMaxSize()) {
            if (showList) {
                val listModifier =
                    if (showDetail) Modifier.width(listWidth) else Modifier.weight(1f)
                Box(listModifier.fillMaxHeight()) { listPane() }
                if (showDetail) Spacer(Modifier.width(RipDpiThemeTokens.layout.groupGap))
            }
            if (showDetail) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    CompositionLocalProvider(LocalShownBesideList provides showList, content = detailPane)
                }
            }
        }
    }
}
