package com.poyka.ripdpi.ui.screens.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.indicators.LogRow
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiDetailPaneEmptyState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground

/** The selected log entry, shown beside the stream on wide windows. */
@Composable
internal fun LogsDetailPane(
    entry: LogEntry?,
    modifier: Modifier = Modifier,
) {
    val padding = RipDpiThemeTokens.layout.horizontalPadding
    Column(modifier = modifier.fillMaxSize().ripDpiScreenBackground()) {
        if (entry == null) {
            RipDpiDetailPaneEmptyState(
                body = stringResource(R.string.history_detail_placeholder),
                testTag = RipDpiTestTags.LogsDetailPlaceholder,
            )
            return@Column
        }
        RipDpiTopAppBar(title = subsystemLabel(entry.subsystem))
        Column(
            modifier =
                Modifier
                    .ripDpiTestTag(RipDpiTestTags.LogsDetailPane)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = padding),
        ) {
            LogRow(
                timestamp = entry.timestamp,
                type = subsystemLabel(entry.subsystem),
                message = entry.message,
                tone = logRowTone(entry),
                metadataChips = metadataChips(entry),
            )
        }
    }
}
