package com.poyka.ripdpi.ui.screens.config

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldBufferSize
import com.poyka.ripdpi.activities.ConfigFieldDefaultTtl
import com.poyka.ripdpi.activities.ConfigFieldDnsIp
import com.poyka.ripdpi.activities.ConfigFieldMaxConnections
import com.poyka.ripdpi.activities.ConfigFieldProxyIp
import com.poyka.ripdpi.activities.ConfigFieldProxyPort
import com.poyka.ripdpi.activities.ConfigFieldRelayCredentials
import com.poyka.ripdpi.activities.ConfigFieldRelayLocalSocksPort
import com.poyka.ripdpi.activities.ConfigFieldRelayServer
import com.poyka.ripdpi.activities.ConfigFieldRelayServerPort
import com.poyka.ripdpi.activities.ConfigFieldStrategyChain
import com.poyka.ripdpi.activities.ConfigPreset
import com.poyka.ripdpi.activities.ConfigPresetKind
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.activities.MasqueImportAction
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.security.SecureWindowEffect
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.launch

internal data class MasqueImportRequest(
    val action: MasqueImportAction,
    val sessionId: Long,
)

internal data class PendingMasquePkcs12Import(
    val uri: Uri,
    val sessionId: Long,
)

internal data class PendingMasqueDocumentResult(
    val action: MasqueImportAction,
    val uri: Uri,
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
    var pkcs12Password by remember { mutableStateOf("") }
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
    ModeEditorHydrationFailureDialog(
        visible = hydrationFailurePending,
        onDismiss = onBack,
    )
    val requestCoarseLocationPermission = rememberModeEditorCoarseLocationPermissionAction(viewModel)

    ModeEditorUnsavedChangesDialog(
        visible = showUnsavedChangesDialog,
        onKeepEditing = { showUnsavedChangesDialog = false },
        onDiscard = {
            showUnsavedChangesDialog = false
            discardAndNavigate()
        },
    )

    val clearPkcs12 = {
        viewModel.masqueImports.dismissPkcs12()
        pkcs12Password = ""
    }
    ModeEditorPkcs12Dialog(
        uri = masqueImportState.pendingPkcs12Uri,
        password = pkcs12Password,
        onPasswordChanged = { pkcs12Password = it },
        onImport = { _, password ->
            viewModel.masqueImports.importPendingPkcs12(password)
            pkcs12Password = ""
        },
        onDismiss = clearPkcs12,
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
                            viewModel.masqueImports.begin(request.action, request.sessionId)
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
private fun rememberModeEditorMasqueImportAction(viewModel: ConfigViewModel): (MasqueImportRequest) -> Unit {
    var pendingRequest by rememberMasqueImportRequestState()
    var pendingResult by rememberPendingMasqueDocumentResultState()
    var pendingPkcs12 by rememberPendingMasquePkcs12ImportState()
    var pkcs12Password by remember { mutableStateOf("") }
    val editorSessionId = viewModel.currentEditorSessionId
    LaunchedEffect(pendingRequest, editorSessionId) {
        val request = pendingRequest ?: return@LaunchedEffect
        if (request.sessionId != editorSessionId) pendingRequest = null
    }
    val documentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val request = pendingRequest
            pendingRequest = null
            pendingResult =
                if (request == null || uri == null) {
                    null
                } else {
                    PendingMasqueDocumentResult(request.action, uri, request.sessionId)
                }
        }

    val clearPkcs12 = {
        pendingPkcs12 = null
        pkcs12Password = ""
    }
    ModeEditorPendingDocumentResultEffect(
        pendingResult = pendingResult,
        editorSessionId = editorSessionId,
        onReady = { result ->
            pendingResult = null
            handleMasqueDocumentResult(
                viewModel = viewModel,
                request = MasqueImportRequest(result.action, result.sessionId),
                uri = result.uri,
                onPkcs12Selected = { pendingPkcs12 = it },
            )
        },
        onDiscard = { pendingResult = null },
    )
    LaunchedEffect(pendingPkcs12, editorSessionId) {
        val pending = pendingPkcs12 ?: return@LaunchedEffect
        if (pending.sessionId != editorSessionId) clearPkcs12()
    }
    val activePkcs12 = pendingPkcs12?.takeIf { it.sessionId == editorSessionId }
    ModeEditorPkcs12Dialog(
        uri = activePkcs12?.uri,
        password = pkcs12Password,
        onPasswordChanged = { pkcs12Password = it },
        onImport = { selectedUri, password ->
            activePkcs12?.let { pending ->
                viewModel.importRelayMasquePkcs12(selectedUri, password, pending.sessionId)
                clearPkcs12()
            }
        },
        onDismiss = clearPkcs12,
    )
    return { request ->
        pendingRequest = request
        documentLauncher.launch(arrayOf("*/*"))
    }
}

@Composable
internal fun rememberMasqueImportRequestState(): MutableState<MasqueImportRequest?> =
    rememberSaveable(
        stateSaver =
            mapSaver(
                save = { request ->
                    if (request == null) {
                        emptyMap()
                    } else {
                        mapOf(
                            "action" to request.action.name,
                            "sessionId" to request.sessionId,
                        )
                    }
                },
                restore = { values ->
                    if (values.isEmpty()) {
                        null
                    } else {
                        MasqueImportRequest(
                            action = MasqueImportAction.valueOf(values.getValue("action") as String),
                            sessionId = values.getValue("sessionId") as Long,
                        )
                    }
                },
            ),
    ) { mutableStateOf(null) }

@Composable
internal fun rememberPendingMasquePkcs12ImportState(): MutableState<PendingMasquePkcs12Import?> =
    rememberSaveable(
        stateSaver =
            mapSaver(
                save = { pending ->
                    if (pending == null) {
                        emptyMap()
                    } else {
                        mapOf(
                            "uri" to pending.uri.toString(),
                            "sessionId" to pending.sessionId,
                        )
                    }
                },
                restore = { values ->
                    if (values.isEmpty()) {
                        null
                    } else {
                        PendingMasquePkcs12Import(
                            uri = Uri.parse(values.getValue("uri") as String),
                            sessionId = values.getValue("sessionId") as Long,
                        )
                    }
                },
            ),
    ) { mutableStateOf(null) }

@Composable
internal fun rememberPendingMasqueDocumentResultState(): MutableState<PendingMasqueDocumentResult?> =
    rememberSaveable(
        stateSaver =
            mapSaver(
                save = { result ->
                    if (result == null) {
                        emptyMap()
                    } else {
                        mapOf(
                            "action" to result.action.name,
                            "uri" to result.uri.toString(),
                            "sessionId" to result.sessionId,
                        )
                    }
                },
                restore = { values ->
                    if (values.isEmpty()) {
                        null
                    } else {
                        PendingMasqueDocumentResult(
                            action = MasqueImportAction.valueOf(values.getValue("action") as String),
                            uri = Uri.parse(values.getValue("uri") as String),
                            sessionId = values.getValue("sessionId") as Long,
                        )
                    }
                },
            ),
    ) { mutableStateOf(null) }

@Composable
internal fun ModeEditorPendingDocumentResultEffect(
    pendingResult: PendingMasqueDocumentResult?,
    editorSessionId: Long?,
    onReady: (PendingMasqueDocumentResult) -> Unit,
    onDiscard: () -> Unit,
) {
    LaunchedEffect(pendingResult, editorSessionId) {
        val result = pendingResult ?: return@LaunchedEffect
        if (result.sessionId == editorSessionId) onReady(result) else onDiscard()
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
