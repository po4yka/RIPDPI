package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiTopBarAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.launch

@Composable
internal fun ModeEditorScreen(
    uiState: ConfigUiState,
    snackbarHostState: SnackbarHostState,
    actions: ModeEditorActions,
    modifier: Modifier = Modifier,
) {
    val topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val bodyScrollState = rememberScrollState()
    val noticesScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // The validation banner is the first item of the form; show it whether or not a field is in view.
    val onSave: () -> Unit = {
        // The banner is in the form column, or in the supporting pane when the window is wide.
        scope.launch { bodyScrollState.animateScrollTo(0) }
        scope.launch { noticesScrollState.animateScrollTo(0) }
        actions.onSave()
    }
    RipDpiScreenScaffold(
        scrollBehavior = topBarScrollBehavior,
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.ModeEditor))
                .fillMaxSize(),
        topBar = {
            RipDpiTopAppBar(
                scrollBehavior = topBarScrollBehavior,
                title = stringResource(R.string.title_mode_editor),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = actions.onBack,
                navigationEnabled = !uiState.isEditorSaving,
                actions =
                    if (uiState.isEditorLoading) {
                        null
                    } else {
                        {
                            RipDpiTopBarAction(
                                text = stringResource(R.string.config_save),
                                onClick = onSave,
                                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ModeEditorSave),
                                enabled = !uiState.isEditorSaving && !uiState.isEditorImporting,
                            )
                        }
                    },
            )
        },
        snackbarHost = { ModeEditorSnackbarHost(snackbarHostState) },
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
                scrollState = bodyScrollState,
                noticesScrollState = noticesScrollState,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun ModeEditorSnackbarHost(snackbarHostState: SnackbarHostState) {
    val layout = RipDpiThemeTokens.layout
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
}
