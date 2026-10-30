package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground

/** The selected entry of the current History section, shown beside the list on wide windows instead of a sheet. */
@Composable
internal fun HistoryDetailPane(
    uiState: HistoryUiState,
    modifier: Modifier = Modifier,
) {
    val connection = uiState.selectedConnectionDetail.takeIf { uiState.selectedSection == HistorySection.Connections }
    val diagnostics = uiState.selectedDiagnosticsDetail.takeIf { uiState.selectedSection == HistorySection.Diagnostics }
    val event = uiState.selectedEvent.takeIf { uiState.selectedSection == HistorySection.Events }
    val title = connection?.session?.title ?: diagnostics?.session?.title ?: event?.source
    val subtitle = connection?.session?.subtitle ?: diagnostics?.session?.subtitle ?: event?.createdAtLabel
    Column(modifier = modifier.fillMaxSize().ripDpiScreenBackground()) {
        if (title == null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .ripDpiTestTag(RipDpiTestTags.HistoryDetailPlaceholder)
                        .padding(RipDpiThemeTokens.layout.horizontalPadding),
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
        RipDpiTopAppBar(title = title)
        Column(
            modifier =
                Modifier
                    .ripDpiTestTag(RipDpiTestTags.HistoryDetailPane)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = RipDpiThemeTokens.layout.horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = RipDpiThemeTokens.layout.contentMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
            ) {
                subtitle?.let {
                    Text(
                        text = it,
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
                when {
                    connection != null -> ConnectionDetailContent(connection)
                    diagnostics != null -> DiagnosticsDetailContent(diagnostics)
                    event != null -> EventDetailContent(event)
                }
            }
        }
    }
}
