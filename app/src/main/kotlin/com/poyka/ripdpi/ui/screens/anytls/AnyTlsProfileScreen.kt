package com.poyka.ripdpi.ui.screens.anytls

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.inputs.RipDpiAutofillPolicy
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons

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
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.anytls_editor_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.AnyTlsProfile)),
    ) {
        uiState.errorMessage?.let { errorRes ->
            RipDpiCard {
                RipDpiPanelHeader(title = stringResource(errorRes))
            }
        }
        EndpointSection(uiState.editor, onFieldChanged)
        TlsSection(uiState.editor, onFieldChanged)
        RipDpiButton(
            text = stringResource(R.string.anytls_save_action),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.editor.isComplete && !uiState.saving,
        )
    }
}

@Composable
private fun EndpointSection(
    editor: AnyTlsProfileEditorState,
    onFieldChanged: (AnyTlsEditorField, String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.anytls_section_endpoint))
        PlainField(AnyTlsEditorField.DISPLAY_NAME, R.string.anytls_field_display_name, editor, onFieldChanged)
        PlainField(AnyTlsEditorField.SERVER, R.string.anytls_field_server, editor, onFieldChanged)
        PlainField(
            AnyTlsEditorField.SERVER_PORT,
            R.string.anytls_field_server_port,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
        )
        PlainField(
            AnyTlsEditorField.PASSWORD,
            R.string.anytls_field_password,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Password,
            autofillPolicy = RipDpiAutofillPolicy.Password,
        )
    }
}

@Composable
private fun TlsSection(
    editor: AnyTlsProfileEditorState,
    onFieldChanged: (AnyTlsEditorField, String) -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.anytls_section_tls),
            supporting = stringResource(R.string.anytls_section_tls_body),
        )
        PlainField(AnyTlsEditorField.SNI, R.string.anytls_field_sni, editor, onFieldChanged)
    }
}

@Composable
private fun PlainField(
    field: AnyTlsEditorField,
    labelRes: Int,
    editor: AnyTlsProfileEditorState,
    onFieldChanged: (AnyTlsEditorField, String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    autofillPolicy: RipDpiAutofillPolicy = RipDpiAutofillPolicy.Disabled,
) {
    val hasError = editor.hasFieldError(field)
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = editor.rawText(field),
                onValueChange = { onFieldChanged(field, it) },
            ),
        modifier = Modifier.fillMaxWidth(),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(labelRes),
                errorText = if (hasError) stringResource(R.string.anytls_field_invalid) else null,
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            ),
        autofillPolicy = autofillPolicy,
    )
}
