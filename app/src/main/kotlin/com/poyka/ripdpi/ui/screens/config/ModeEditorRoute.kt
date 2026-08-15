package com.poyka.ripdpi.ui.screens.config

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.activities.MasqueImportAction
import com.poyka.ripdpi.ui.security.SecureWindowEffect
import kotlinx.coroutines.launch

internal data class MasqueImportRequest(
    val action: MasqueImportAction,
    val sessionId: Long,
)

@Composable
fun ModeEditorRoute(
    onBack: () -> Unit,
    viewModel: ConfigViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val masqueImportState by viewModel.masqueImportState.collectAsStateWithLifecycle()
    SecureWindowEffect()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    var hydrationFailurePending by rememberSaveable { mutableStateOf(false) }
    val discardAndNavigate: () -> Unit = {
        scope.launch {
            if (viewModel.cancelEditing()) {
                onBack()
            }
        }
    }
    val requestBack =
        modeEditorBackAction(viewModel, onBack) {
            showUnsavedChangesDialog = true
        }

    val documentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            viewModel.masqueImports.onDocumentPicked(uri)
        }
    ModeEditorStartEffect(
        viewModel = viewModel,
        isLoading = uiState.isLoading,
        editingPresetId = uiState.editingPreset?.id,
        enabled = !hydrationFailurePending,
    )
    ModeEditorEffects(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        onHydrationFailure = { hydrationFailurePending = true },
        onBack = onBack,
    )
    val requestCoarseLocationPermission = rememberModeEditorCoarseLocationPermissionAction(viewModel)

    ModeEditorExitDialogs(
        hydrationFailureVisible = hydrationFailurePending,
        onHydrationFailureDismiss = onBack,
        unsavedChangesVisible = showUnsavedChangesDialog,
        onKeepEditing = { showUnsavedChangesDialog = false },
        onDiscard = {
            showUnsavedChangesDialog = false
            discardAndNavigate()
        },
    )
    ModeEditorRetainedPkcs12Dialog(
        viewModel = viewModel,
        uri = masqueImportState.pendingPkcs12Uri.takeIf { masqueImportState.sessionReady },
    )
    ModeEditorScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        actions =
            createModeEditorActions(
                viewModel = viewModel,
                onBack = requestBack,
                onCancel = discardAndNavigate,
                externalActions =
                    createModeEditorExternalActions(
                        viewModel = viewModel,
                        context = LocalContext.current,
                        requestCoarseLocationPermission = requestCoarseLocationPermission,
                        requestDocument = { request ->
                            viewModel.masqueImports.begin(
                                request.action,
                                request.sessionId,
                                viewModel.currentEditorRecoveryOwnerId,
                            )
                            documentLauncher.launch(arrayOf("*/*"))
                        },
                    ),
            ),
        modifier = modifier,
    )
}

@Composable
private fun rememberModeEditorCoarseLocationPermissionAction(viewModel: ConfigViewModel): (String) -> Unit {
    var permissionRequestSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingEnableSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editorSessionId = viewModel.currentEditorSessionId
    LaunchedEffect(permissionRequestSessionId, editorSessionId) {
        val requestSessionId = permissionRequestSessionId ?: return@LaunchedEffect
        if (requestSessionId != editorSessionId) permissionRequestSessionId = null
    }
    ModeEditorPendingDraftUpdateEffect(
        pendingSessionId = pendingEnableSessionId,
        editorSessionId = editorSessionId,
        onReady = { readySessionId ->
            viewModel.updateDraft(expectedSessionId = readySessionId) {
                copy(relayMasqueCloudflareGeohashEnabled = true)
            }
            pendingEnableSessionId = null
        },
        onDiscard = { pendingEnableSessionId = null },
    )
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val requestSessionId = permissionRequestSessionId
            permissionRequestSessionId = null
            pendingEnableSessionId = requestSessionId.takeIf { granted }
        }
    return { permission ->
        viewModel.currentEditorSessionId?.let { sessionId ->
            permissionRequestSessionId = sessionId
            launcher.launch(permission)
        }
    }
}

@Composable
internal fun ModeEditorPendingDraftUpdateEffect(
    pendingSessionId: Long?,
    editorSessionId: Long?,
    onReady: (Long) -> Unit,
    onDiscard: () -> Unit,
) {
    LaunchedEffect(pendingSessionId, editorSessionId) {
        val expectedSessionId = pendingSessionId ?: return@LaunchedEffect
        if (expectedSessionId == editorSessionId) onReady(expectedSessionId) else onDiscard()
    }
}

@Composable
private fun modeEditorBackAction(
    viewModel: ConfigViewModel,
    onBack: () -> Unit,
    onConfirmDiscard: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val requestBack: () -> Unit = {
        scope.launch {
            handleModeEditorExitDecision(
                decision = viewModel.requestEditorExit(),
                onBack = onBack,
                onConfirmDiscard = onConfirmDiscard,
            )
        }
    }
    BackHandler(onBack = requestBack)
    return requestBack
}
