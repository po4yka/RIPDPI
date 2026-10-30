package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

// Material 3 list-detail preferred list pane width.
private val ListPaneWidth = 360.dp

/**
 * True while the content shows as the detail pane beside its list. The list stays visible, so
 * [RipDpiTopAppBar] drops its back arrow there.
 */
val LocalShownBesideList = staticCompositionLocalOf { false }

/**
 * Material 3 list-detail layout. With [showList] the list pane sits at the start and the detail
 * pane fills the rest; without it only the detail pane shows. The detail pane keeps its place in
 * composition, so its content keeps its state when the list appears or leaves.
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
) {
    Row(modifier = modifier) {
        if (showList) {
            Box(Modifier.width(ListPaneWidth).fillMaxHeight()) { listPane() }
            Spacer(Modifier.width(RipDpiThemeTokens.layout.groupGap))
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CompositionLocalProvider(LocalShownBesideList provides showList, content = detailPane)
        }
    }
}
