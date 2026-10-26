package com.poyka.ripdpi.ui.screens.awg

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.security.AppPinCheck
import com.poyka.ripdpi.security.SecretRevealPinViewModel
import com.poyka.ripdpi.security.rememberSecretRevealGate
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.profiles.RelayEditorActionRow
import com.poyka.ripdpi.ui.screens.profiles.RelaySecretField
import com.poyka.ripdpi.ui.screens.profiles.relayPortRangeHelper
import com.poyka.ripdpi.ui.security.SecureWindowEffect
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * AmneziaWG profile editor destination.
 *
 * Surfaces every standard WireGuard field (private key, address, DNS, MTU, peer public
 * key, peer endpoint, allowed IPs, preshared key, persistent keepalive) plus all 16
 * AmneziaWG obfuscation fields in one labeled "Obfuscation" section — the field ordering
 * follows amnezia-vpn/amneziawg-android's editor (Apache-2.0). A cohort-preset picker fills
 * and locks the obfuscation group and collapses it behind a "Show values" toggle; "Custom"
 * frees and shows it. Secret fields stay behind the shared reveal control.
 */
@Composable
fun AmneziaWgProfileRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AmneziaWgProfileViewModel = hiltViewModel(),
    appPin: AppPinCheck? = hiltViewModel<SecretRevealPinViewModel>(),
    onRequestPrivateKeyReveal: (() -> Unit)? = null,
    onRequestPresharedKeyReveal: (() -> Unit)? = null,
    importToken: String = "",
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(importToken) {
        if (importToken.isNotEmpty()) viewModel.onImportToken(importToken)
    }
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
    // Revealed secrets hide again when the app goes to the background or the screen closes.
    LifecycleStartEffect(viewModel) { onStopOrDispose { viewModel.onSecretsRelocked() } }
    val revealPrivateKey = rememberSecretRevealGate(viewModel::onPrivateKeyRevealAuthorized, appPin)
    val revealPresharedKey = rememberSecretRevealGate(viewModel::onPresharedKeyRevealAuthorized, appPin)
    AmneziaWgProfileScreen(
        uiState = uiState,
        onBack = onBack,
        onFieldChanged = viewModel::onFieldChanged,
        onCohortSelected = viewModel::onCohortSelected,
        onCarrierSelected = viewModel::onCarrierSelected,
        onPasteConf = viewModel::onPasteConf,
        onRevealPrivateKey = onRequestPrivateKeyReveal ?: revealPrivateKey,
        onRevealPresharedKey = onRequestPresharedKeyReveal ?: revealPresharedKey,
        onConnect = viewModel::onConnect,
        modifier = modifier,
    )
}

@Composable
internal fun AmneziaWgProfileScreen(
    uiState: AmneziaWgProfileUiState,
    onBack: () -> Unit,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onCohortSelected: (String) -> Unit,
    onCarrierSelected: (String) -> Unit,
    onPasteConf: () -> Unit,
    onRevealPrivateKey: () -> Unit,
    onRevealPresharedKey: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureWindowEffect()
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.awg_editor_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.AmneziaWgProfile())),
    ) {
        // Import comes first: a pasted .conf fills most of the fields below.
        RipDpiButton(
            text = stringResource(R.string.awg_paste_conf_action),
            onClick = onPasteConf,
            modifier = Modifier.fillMaxWidth(),
            variant = RipDpiButtonVariant.Secondary,
            leadingIcon = RipDpiIcons.Paste,
        )
        uiState.confImportFailure?.let { failure ->
            WarningBanner(
                title = stringResource(R.string.awg_conf_import_failed_title),
                message =
                    when (failure.reason) {
                        AwgConfImportFailure.UNREADABLE -> {
                            stringResource(R.string.awg_conf_import_unreadable_body)
                        }

                        AwgConfImportFailure.INVALID -> {
                            stringResource(R.string.awg_conf_import_failed_body)
                        }

                        AwgConfImportFailure.UNSUPPORTED_OPTION -> {
                            stringResource(R.string.awg_conf_unsupported_option_body, failure.key)
                        }
                    },
                testTag = RipDpiTestTags.AwgConfImportFailed,
            )
        }
        if (uiState.ignoredPeerCount > 0) {
            WarningBanner(
                title = stringResource(R.string.awg_conf_extra_peers_title),
                message = stringResource(R.string.awg_conf_extra_peers_body, uiState.ignoredPeerCount),
                tone = WarningBannerTone.Info,
            )
        }
        InterfaceSection(uiState, onFieldChanged, onRevealPrivateKey)
        PeerSection(uiState, onFieldChanged, onRevealPresharedKey)
        CarrierSection(uiState, onFieldChanged, onCarrierSelected)
        ObfuscationSection(uiState, onFieldChanged, onCohortSelected)
        ConnectSection(
            canActivate = uiState.canActivate,
            activationStatus = uiState.activationStatus,
            onConnect = onConnect,
        )
    }
}

/**
 * Transport carrier section: a UDP/WebSocket picker plus a WS request-URL field
 * that only appears (and is only required by [AmneziaWgEditorState.isActivatable])
 * when the WebSocket carrier is selected. See `vpnservice-protect-invariant.md` --
 * the WS carrier still egresses through a protected socket in the native runtime;
 * this section only chooses which carrier that socket uses.
 */
@Composable
private fun CarrierSection(
    uiState: AmneziaWgProfileUiState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onCarrierSelected: (String) -> Unit,
) {
    val editor = uiState.editor
    val udpLabel = stringResource(R.string.awg_carrier_udp)
    val wsLabel = stringResource(R.string.awg_carrier_ws)
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.awg_section_carrier),
            supporting = stringResource(R.string.awg_section_carrier_body),
        )
        RipDpiDropdown(
            options =
                persistentListOf(
                    RipDpiDropdownOption(value = AwgActivationRequest.CARRIER_UDP, label = udpLabel),
                    RipDpiDropdownOption(value = AwgActivationRequest.CARRIER_WS, label = wsLabel),
                ),
            selectedValue = editor.form.carrier,
            onValueSelected = onCarrierSelected,
            label = stringResource(R.string.awg_carrier_picker_label),
            testTag = RipDpiTestTags.AwgCarrierPicker,
        )
        if (editor.form.carrier == AwgActivationRequest.CARRIER_WS) {
            PlainField(
                AwgEditorField.CARRIER_WS_URL,
                R.string.awg_field_carrier_ws_url,
                editor,
                onFieldChanged,
            )
        }
    }
}

@Composable
private fun ConnectSection(
    canActivate: Boolean,
    activationStatus: AwgActivationStatus,
    onConnect: () -> Unit,
) {
    val failed = activationStatus == AwgActivationStatus.Failed
    RelayEditorActionRow(
        text = stringResource(R.string.awg_connect_action),
        onClick = onConnect,
        enabled = canActivate && activationStatus != AwgActivationStatus.Connecting,
        hint =
            stringResource(
                when {
                    failed -> R.string.awg_connect_error
                    canActivate -> R.string.awg_connect_ready_hint
                    else -> R.string.awg_connect_incomplete_hint
                },
            ),
        hintIsError = failed,
        leadingIcon = RipDpiIcons.Vpn,
        testTag = RipDpiTestTags.AwgConnectAction,
    )
}

@Composable
private fun InterfaceSection(
    uiState: AmneziaWgProfileUiState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onRevealPrivateKey: () -> Unit,
) {
    val editor = uiState.editor
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.awg_section_interface))
        RelaySecretField(
            testTag = RipDpiTestTags.awgField(AwgEditorField.INTERFACE_PRIVATE_KEY.name),
            label = stringResource(R.string.awg_field_private_key),
            value = editor.rawText(AwgEditorField.INTERFACE_PRIVATE_KEY),
            revealed = uiState.privateKeyRevealed,
            onValueChange = { onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, it) },
            onReveal = onRevealPrivateKey,
        )
        PlainField(AwgEditorField.SERVER, R.string.awg_field_server, editor, onFieldChanged)
        PlainField(
            AwgEditorField.SERVER_PORT,
            R.string.awg_field_server_port,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
            helperText = relayPortRangeHelper(),
        )
        PlainField(
            AwgEditorField.ADDRESS,
            R.string.awg_field_address,
            editor,
            onFieldChanged,
            placeholder = stringResource(R.string.awg_address_placeholder),
        )
        PlainField(
            AwgEditorField.DNS,
            R.string.awg_field_dns,
            editor,
            onFieldChanged,
            placeholder = stringResource(R.string.awg_dns_placeholder),
        )
        PlainField(
            AwgEditorField.MTU,
            R.string.awg_field_mtu,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
            placeholder = AwgActivationRequest.DEFAULT_MTU.toString(),
        )
    }
}

@Composable
private fun PeerSection(
    uiState: AmneziaWgProfileUiState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onRevealPresharedKey: () -> Unit,
) {
    val editor = uiState.editor
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.awg_section_peer))
        PlainField(AwgEditorField.PEER_PUBLIC_KEY, R.string.awg_field_peer_public_key, editor, onFieldChanged)
        PlainField(AwgEditorField.PEER_ENDPOINT, R.string.awg_field_peer_endpoint, editor, onFieldChanged)
        PlainField(
            AwgEditorField.ALLOWED_IPS,
            R.string.awg_field_allowed_ips,
            editor,
            onFieldChanged,
            placeholder = stringResource(R.string.awg_allowed_ips_placeholder),
        )
        RelaySecretField(
            testTag = RipDpiTestTags.awgField(AwgEditorField.PRESHARED_KEY.name),
            label = stringResource(R.string.awg_field_preshared_key),
            value = editor.rawText(AwgEditorField.PRESHARED_KEY),
            revealed = uiState.presharedKeyRevealed,
            onValueChange = { onFieldChanged(AwgEditorField.PRESHARED_KEY, it) },
            onReveal = onRevealPresharedKey,
        )
        PlainField(
            AwgEditorField.PERSISTENT_KEEPALIVE,
            R.string.awg_field_persistent_keepalive,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
            placeholder = stringResource(R.string.awg_keepalive_placeholder),
            helperText = stringResource(R.string.awg_keepalive_helper),
        )
    }
}

@Composable
private fun ObfuscationSection(
    uiState: AmneziaWgProfileUiState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onCohortSelected: (String) -> Unit,
) {
    val editor = uiState.editor
    val customLabel = stringResource(R.string.awg_cohort_custom)
    // A preset owns these values, so its fields stay collapsed until the user asks to see them.
    var expanded by rememberSaveable { mutableStateOf(false) }
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.awg_section_obfuscation),
            supporting =
                stringResource(
                    if (editor.obfuscationLocked) {
                        R.string.awg_cohort_locked_hint
                    } else {
                        R.string.awg_section_obfuscation_body
                    },
                ),
        )
        RipDpiDropdown(
            options =
                uiState.cohortOptions
                    .map { option ->
                        RipDpiDropdownOption(
                            value = option.id,
                            label = if (option.id == AwgProfileForm.CUSTOM_COHORT_ID) customLabel else option.id,
                        )
                    }.toImmutableList(),
            selectedValue = editor.form.cohortId,
            onValueSelected = onCohortSelected,
            label = stringResource(R.string.awg_cohort_picker_label),
            testTag = RipDpiTestTags.AwgCohortPicker,
        )
        if (editor.obfuscationLocked) {
            RipDpiButton(
                text =
                    stringResource(
                        if (expanded) R.string.awg_obfuscation_hide_values else R.string.awg_obfuscation_show_values,
                    ),
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                variant = RipDpiButtonVariant.Ghost,
            )
        }
        if (!editor.obfuscationLocked || expanded) {
            ObfuscationFields(editor, onFieldChanged)
        }
    }
}

@Composable
private fun ObfuscationFields(
    editor: AmneziaWgEditorState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
) {
    val obfuscationFields =
        listOf(
            AwgEditorField.JC to R.string.awg_field_jc,
            AwgEditorField.JMIN to R.string.awg_field_jmin,
            AwgEditorField.JMAX to R.string.awg_field_jmax,
            AwgEditorField.S1 to R.string.awg_field_s1,
            AwgEditorField.S2 to R.string.awg_field_s2,
            AwgEditorField.S3 to R.string.awg_field_s3,
            AwgEditorField.S4 to R.string.awg_field_s4,
            AwgEditorField.H1 to R.string.awg_field_h1,
            AwgEditorField.H2 to R.string.awg_field_h2,
            AwgEditorField.H3 to R.string.awg_field_h3,
            AwgEditorField.H4 to R.string.awg_field_h4,
            AwgEditorField.I1 to R.string.awg_field_i1,
            AwgEditorField.I2 to R.string.awg_field_i2,
            AwgEditorField.I3 to R.string.awg_field_i3,
            AwgEditorField.I4 to R.string.awg_field_i4,
            AwgEditorField.I5 to R.string.awg_field_i5,
        )
    val spacing = RipDpiThemeTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        obfuscationFields.forEach { (field, labelRes) ->
            // Headers accept ranges; signatures accept tag programs.
            val isTextField = field.name.startsWith("I") || field.name.startsWith("H")
            PlainField(
                field = field,
                labelRes = labelRes,
                editor = editor,
                onFieldChanged = onFieldChanged,
                keyboardType = if (isTextField) KeyboardType.Text else KeyboardType.Number,
                enabled = !editor.obfuscationLocked,
            )
        }
    }
}

@Composable
private fun PlainField(
    field: AwgEditorField,
    labelRes: Int,
    editor: AmneziaWgEditorState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    helperText: String? = null,
    enabled: Boolean = true,
) {
    val hasError = editor.hasFieldError(field)
    RipDpiTextField(
        value = editor.rawText(field),
        onValueChange = { onFieldChanged(field, it) },
        modifier = Modifier.fillMaxWidth(),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(labelRes),
                placeholder = placeholder,
                helperText = helperText,
                errorText = if (hasError) stringResource(R.string.awg_field_invalid) else null,
                testTag = RipDpiTestTags.awgField(field.name),
            ),
        behavior =
            RipDpiTextFieldBehavior(
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            ),
    )
}
