package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiContentGrouping
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

// Material 3 supporting pane preferred width.
private val SupportingPaneWidth = 360.dp

/**
 * Home's primary and supporting panes. The connection control, advisories and measurements stay in the
 * primary pane; the supporting pane takes the secondary cards. Below Expanded both stack in order inside
 * the caller's column, so narrower layouts render identically.
 *
 * shortcut: a stable Row instead of SupportingPaneScaffold, which is still experimental in
 * adaptive-layout 1.3.0; switch once it is stable.
 */
@Composable
internal fun ColumnScope.HomeSupportingPaneLayout(
    primary: @Composable ColumnScope.() -> Unit,
    supporting: @Composable ColumnScope.() -> Unit,
) {
    val layout = RipDpiThemeTokens.layout
    if (layout.contentGrouping == RipDpiContentGrouping.SplitColumns) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(layout.groupGap),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
                content = primary,
            )
            Column(
                modifier = Modifier.width(SupportingPaneWidth),
                verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
                content = supporting,
            )
        }
    } else {
        primary()
        supporting()
    }
}
