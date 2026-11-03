package com.poyka.ripdpi.ui.screens.anytls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorScaffold
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorStatus
import com.poyka.ripdpi.ui.screens.relayeditor.RelayFieldBinding

/**
 * AnyTLS profile editor destination.
 *
 * AnyTLS is a **current** outbound that runs over a single TLS session. The
 * editor validates the password as non-blank, the port against `1..65535`, and
 * exposes the optional TLS SNI override; a blank SNI falls back to the server
 * host.
 */
@Composable
fun AnyTlsProfileRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnyTlsProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(viewModel.savedEvents) { onBack() }
    AnyTlsProfileScreen(
        uiState = uiState,
        onBack = onBack,
        onFieldChanged = viewModel::onFieldChanged,
        onSave = viewModel::onSave,
        modifier = modifier,
    )
}

@Composable
internal fun AnyTlsProfileScreen(
    uiState: AnyTlsProfileUiState,
    onBack: () -> Unit,
    onFieldChanged: (AnyTlsEditorField, String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor = uiState.editor
    val fields =
        remember(editor, onFieldChanged) {
            RelayFieldBinding(
                kind = RelayKindAnyTls,
                invalidRes = R.string.anytls_field_invalid,
                rawText = editor::rawText,
                hasError = editor::hasFieldError,
                onChanged = onFieldChanged,
            )
        }
    RelayEditorScaffold(
        kind = RelayKindAnyTls,
        route = Route.AnyTlsProfile,
        title = stringResource(R.string.anytls_editor_title),
        saveLabel = stringResource(R.string.anytls_save_action),
        status =
            RelayEditorStatus(
                complete = editor.isComplete,
                saving = uiState.saving,
                errorMessage = uiState.errorMessage,
            ),
        onBack = onBack,
        onSave = onSave,
        modifier = modifier,
    ) {
        fields.EndpointCard(
            AnyTlsEditorField.DISPLAY_NAME,
            AnyTlsEditorField.SERVER,
            AnyTlsEditorField.SERVER_PORT,
            R.string.anytls_section_endpoint,
            R.string.anytls_field_display_name,
            R.string.anytls_field_server,
            R.string.anytls_field_server_port,
        )
        RipDpiCard {
            RipDpiPanelHeader(title = stringResource(R.string.relay_editor_section_credentials))
            fields.Password(AnyTlsEditorField.PASSWORD, R.string.anytls_field_password)
        }
        RipDpiCard {
            RipDpiPanelHeader(title = stringResource(R.string.anytls_section_tls))
            fields.Text(AnyTlsEditorField.SNI, R.string.anytls_field_sni)
        }
    }
}
