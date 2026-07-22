package com.poyka.ripdpi.ui.screens.config

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
