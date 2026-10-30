package com.poyka.ripdpi.ui.screens.logs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.ui.components.indicators.LogRow
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
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
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .ripDpiTestTag(RipDpiTestTags.LogsDetailPlaceholder)
                        .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.history_detail_placeholder),
                    style = RipDpiThemeTokens.type.body,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                    textAlign = TextAlign.Center,
                )
            }
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
