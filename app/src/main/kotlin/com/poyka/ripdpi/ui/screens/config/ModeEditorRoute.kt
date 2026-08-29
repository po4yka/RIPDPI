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

private enum class ModeEditorExitTarget {
    Back,
    XrayImport,
    ;

    fun navigate(
        onBack: () -> Unit,
        onOpenXrayImport: () -> Unit,
    ) {
        when (this) {
            Back -> onBack()
            XrayImport -> onOpenXrayImport()
        }
    }
}

@Composable
fun ModeEditorRoute(
    onBack: () -> Unit,
    onOpenXrayImport: () -> Unit,
    viewModel: ConfigViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val masqueImportState by viewModel.masqueImportState.collectAsStateWithLifecycle()
    SecureWindowEffect()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    var pendingExitTarget by remember { mutableStateOf<ModeEditorExitTarget?>(null) }
    var hydrationFailurePending by rememberSaveable { mutableStateOf(false) }
    val discardAndNavigate: () -> Unit = {
        val target = pendingExitTarget ?: ModeEditorExitTarget.Back
        scope.launch {
            if (viewModel.cancelEditing()) {
                pendingExitTarget = null
                target.navigate(onBack, onOpenXrayImport)
            }
        }
    }
    val requestExit: (ModeEditorExitTarget) -> Unit = { target ->
        scope.launch {
            handleModeEditorExitDecision(
                decision = viewModel.requestEditorExit(),
                onBack = { target.navigate(onBack, onOpenXrayImport) },
                onConfirmDiscard = {
                    pendingExitTarget = target
                    showUnsavedChangesDialog = true
                },
            )
        }
    }
    val requestBack = modeEditorBackAction { requestExit(ModeEditorExitTarget.Back) }

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

    ModeEditorExitDialogs(
        hydrationFailureVisible = hydrationFailurePending,
        onHydrationFailureDismiss = onBack,
        unsavedChangesVisible = showUnsavedChangesDialog,
        onKeepEditing = {
            pendingExitTarget = null
            showUnsavedChangesDialog = false
        },
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
                onOpenXrayImport = { requestExit(ModeEditorExitTarget.XrayImport) },
                externalActions = rememberModeEditorExternalActions(viewModel),
            ),
        modifier = modifier,
    )
}

@Composable
private fun rememberModeEditorExternalActions(viewModel: ConfigViewModel): ModeEditorExternalActions {
    val documentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            viewModel.masqueImports.onDocumentPicked(uri)
        }
    val requestCoarseLocationPermission = rememberModeEditorCoarseLocationPermissionAction(viewModel)
    return createModeEditorExternalActions(
        viewModel = viewModel,
        context = LocalContext.current,
        requestCoarseLocationPermission = requestCoarseLocationPermission,
        requestDocument = { request ->
            viewModel.masqueImports.begin(request.action, request.sessionId, viewModel.currentEditorRecoveryOwnerId)
            documentLauncher.launch(arrayOf("*/*"))
        },
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
private fun modeEditorBackAction(requestBack: () -> Unit): () -> Unit {
    BackHandler(onBack = requestBack)
    return requestBack
}
