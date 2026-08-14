package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun ModeEditorScreen(
    uiState: ConfigUiState,
    snackbarHostState: SnackbarHostState,
    actions: ModeEditorActions,
    modifier: Modifier = Modifier,
) {
    val layout = RipDpiThemeTokens.layout

    RipDpiScreenScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.ModeEditor))
                .fillMaxSize(),
        topBar = {
            RipDpiTopAppBar(
                title = stringResource(R.string.title_mode_editor),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = actions.onBack,
                navigationEnabled = !uiState.isEditorSaving,
            )
        },
        snackbarHost = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                RipDpiSnackbarHost(
                    hostState = snackbarHostState,
                    modifier =
                        Modifier
                            .widthIn(max = layout.formMaxWidth)
                            .fillMaxWidth()
                            .padding(horizontal = layout.horizontalPadding),
                )
            }
        },
        bottomBar = {
            if (!uiState.isEditorLoading) {
                ModeEditorBottomBar(uiState = uiState, actions = actions)
            }
        },
    ) { innerPadding ->
        if (uiState.isEditorLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .ripDpiTestTag(RipDpiTestTags.ModeEditorLoading),
                contentAlignment = Alignment.Center,
            ) {
                RipDpiSpinner()
            }
        } else {
            ModeEditorBody(
                uiState = uiState,
                actions = actions,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
