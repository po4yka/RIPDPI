package com.poyka.ripdpi.ui.components.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.chrome.RipDpiEmptyState
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/** Empty state card for a detail pane while no list entry is selected. */
@Composable
fun RipDpiDetailPaneEmptyState(
    body: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .ripDpiTestTag(testTag)
                .padding(RipDpiThemeTokens.layout.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        RipDpiEmptyState(
            icon = RipDpiIcons.Info,
            title = stringResource(R.string.detail_pane_empty_title),
            body = body,
            modifier = Modifier.widthIn(max = RipDpiThemeTokens.layout.formMaxWidth),
        )
    }
}
