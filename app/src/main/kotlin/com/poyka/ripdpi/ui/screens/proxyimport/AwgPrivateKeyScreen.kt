package com.poyka.ripdpi.ui.screens.proxyimport

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.security.AppPinCheck
import com.poyka.ripdpi.security.SecretRevealPinViewModel
import com.poyka.ripdpi.security.rememberSecretRevealGate
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.awg.AmneziaWgProfileUiState
import com.poyka.ripdpi.ui.screens.awg.AmneziaWgProfileViewModel
import com.poyka.ripdpi.ui.screens.awg.AwgActivationStatus
import com.poyka.ripdpi.ui.screens.awg.AwgConfImportFailure
import com.poyka.ripdpi.ui.screens.awg.AwgEditorField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorScaffold
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorStatus
import com.poyka.ripdpi.ui.screens.relayeditor.RelaySecretField
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiIcons

/**
 * Asks only for the private key of the saved AmneziaWG profile [profileId]: a bundle delivers such a
 * profile complete except for the key. It drives the editor's own view model, so the key is checked,
 * saved and connected exactly as in the full editor.
 */
@Composable
fun AwgPrivateKeyRoute(
    profileId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AmneziaWgProfileViewModel = hiltViewModel(),
    appPin: AppPinCheck? = hiltViewModel<SecretRevealPinViewModel>(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(profileId) { viewModel.onLoadProfile(profileId) }
    var consentRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val consentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val requestId = consentRequestId
            consentRequestId = null
            requestId?.let { viewModel.onVpnConsentResult(it, granted = result.resultCode == Activity.RESULT_OK) }
        }
    LifecycleEventEffect(viewModel.vpnConsentRequests) { request ->
        consentRequestId = request.id
        try {
            consentLauncher.launch(request.intent)
        } catch (_: ActivityNotFoundException) {
            consentRequestId = null
            viewModel.onVpnConsentResult(request.id, granted = false)
        } catch (_: SecurityException) {
            consentRequestId = null
            viewModel.onVpnConsentResult(request.id, granted = false)
        }
    }
    LifecycleStartEffect(viewModel) { onStopOrDispose { viewModel.onSecretsRelocked() } }
    AwgPrivateKeyScreen(
        uiState = uiState,
        onBack = onBack,
        onKeyChanged = { viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, it) },
        onPasteConf = viewModel::onPasteConf,
        onRevealKey = rememberSecretRevealGate(viewModel::onPrivateKeyRevealAuthorized, appPin),
        onConnect = viewModel::onConnect,
        modifier = modifier,
    )
}

@Composable
internal fun AwgPrivateKeyScreen(
    uiState: AmneziaWgProfileUiState,
    onBack: () -> Unit,
    onKeyChanged: (String) -> Unit,
    onPasteConf: () -> Unit,
    onRevealKey: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connectFailed = uiState.activationStatus == AwgActivationStatus.Failed
    RelayEditorScaffold(
        actionTag = RipDpiTestTags.AwgConnectAction,
        route = Route.AmneziaWgProfile(),
        title = stringResource(R.string.awg_editor_title),
        saveLabel = stringResource(R.string.awg_connect_action),
        status =
            RelayEditorStatus(
                complete = uiState.canActivate,
                saving = uiState.activationStatus == AwgActivationStatus.Connecting,
                failed = connectFailed,
            ),
        onBack = onBack,
        onSave = onConnect,
        modifier = modifier,
        hintRes =
            when {
                connectFailed -> R.string.awg_connect_error
                uiState.subscriptionServerChanged -> R.string.awg_conf_subscription_server_mismatch_body
                else -> null
            },
        hintIsError = true,
    ) {
        if (uiState.loadingProfile) return@RelayEditorScaffold
        val editor = uiState.editor
        RelaySecretField(
            testTag = RipDpiTestTags.awgField(AwgEditorField.INTERFACE_PRIVATE_KEY.name),
            label = stringResource(R.string.awg_field_private_key),
            value = editor.rawText(AwgEditorField.INTERFACE_PRIVATE_KEY),
            revealed = uiState.privateKeyRevealed,
            onValueChange = onKeyChanged,
            onReveal = onRevealKey,
            errorText =
                stringResource(R.string.field_error_invalid)
                    .takeIf { editor.hasFieldError(AwgEditorField.INTERFACE_PRIVATE_KEY) },
        )
        // The provider's .conf for this server carries the key; the editor keeps the bundle's server.
        RipDpiButton(
            text = stringResource(R.string.awg_paste_conf_action),
            onClick = onPasteConf,
            modifier = Modifier.fillMaxWidth(),
            variant = RipDpiButtonVariant.Secondary,
            leadingIcon = RipDpiIcons.Paste,
        )
        uiState.confImportFailure?.let { failure ->
            val mismatch = failure.reason == AwgConfImportFailure.SUBSCRIPTION_SERVER_MISMATCH
            WarningBanner(
                title = stringResource(R.string.awg_conf_import_failed_title),
                message =
                    stringResource(
                        if (mismatch) {
                            R.string.awg_conf_subscription_server_mismatch_body
                        } else {
                            R.string.awg_conf_import_failed_body
                        },
                    ),
                testTag = RipDpiTestTags.AwgConfImportFailed,
            )
        }
    }
}
