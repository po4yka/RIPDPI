package com.poyka.ripdpi.ui.screens.awg

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileForm
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
 * AmneziaWG obfuscation fields *inline* in one labeled "Obfuscation" section — the field
 * ordering follows amnezia-vpn/amneziawg-android's editor (Apache-2.0). Obfuscation fields
 * are not hidden behind an "Advanced" toggle because they are server-coordinated and
 * pasted verbatim. A cohort-preset picker fills and locks the obfuscation group; "Custom"
 * frees it. Secret fields stay behind the biometric-gated reveal pattern.
 */
@Composable
fun AmneziaWgProfileRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AmneziaWgProfileViewModel = hiltViewModel(),
    onRequestPrivateKeyReveal: (() -> Unit)? = null,
    onRequestPresharedKeyReveal: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
    AmneziaWgProfileScreen(
        uiState = uiState,
        onBack = onBack,
        onFieldChanged = viewModel::onFieldChanged,
        onCohortSelected = viewModel::onCohortSelected,
        onCarrierSelected = viewModel::onCarrierSelected,
        onPasteConf = viewModel::onPasteConf,
        onRevealPrivateKey = onRequestPrivateKeyReveal ?: viewModel::onPrivateKeyRevealAuthorized,
        onRevealPresharedKey = onRequestPresharedKeyReveal ?: viewModel::onPresharedKeyRevealAuthorized,
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
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.awg_editor_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.AmneziaWgProfile)),
    ) {
        InterfaceSection(uiState, onFieldChanged, onRevealPrivateKey)
        PeerSection(uiState, onFieldChanged, onRevealPresharedKey)
        CarrierSection(uiState, onFieldChanged, onCarrierSelected)
        ObfuscationSection(uiState, onFieldChanged, onCohortSelected, onPasteConf)
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
    val spacing = RipDpiThemeTokens.spacing
    val failed = activationStatus == AwgActivationStatus.Failed
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text =
                    stringResource(
                        when {
                            failed -> R.string.awg_connect_error
                            canActivate -> R.string.awg_connect_ready_hint
                            else -> R.string.awg_connect_incomplete_hint
                        },
                    ),
                style = RipDpiThemeTokens.type.caption,
                color =
                    if (failed) {
                        RipDpiThemeTokens.colors.destructive
                    } else {
                        RipDpiThemeTokens.colors.mutedForeground
                    },
            )
            RipDpiButton(
                text = stringResource(R.string.awg_connect_action),
                onClick = onConnect,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.AwgConnectAction),
                variant = RipDpiButtonVariant.Primary,
                enabled = canActivate && activationStatus != AwgActivationStatus.Connecting,
                leadingIcon = RipDpiIcons.Vpn,
            )
        }
    }
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
        SecretField(
            field = AwgEditorField.INTERFACE_PRIVATE_KEY,
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
        )
        PlainField(AwgEditorField.ADDRESS, R.string.awg_field_address, editor, onFieldChanged)
        PlainField(AwgEditorField.DNS, R.string.awg_field_dns, editor, onFieldChanged)
        PlainField(
            AwgEditorField.MTU,
            R.string.awg_field_mtu,
            editor,
            onFieldChanged,
            keyboardType = KeyboardType.Number,
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
        PlainField(AwgEditorField.ALLOWED_IPS, R.string.awg_field_allowed_ips, editor, onFieldChanged)
        SecretField(
            field = AwgEditorField.PRESHARED_KEY,
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
        )
    }
}

@Composable
private fun ObfuscationSection(
    uiState: AmneziaWgProfileUiState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onCohortSelected: (String) -> Unit,
    onPasteConf: () -> Unit,
) {
    val editor = uiState.editor
    val customLabel = stringResource(R.string.awg_cohort_custom)
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.awg_section_obfuscation),
            supporting = stringResource(R.string.awg_section_obfuscation_body),
            trailingContent = {
                RipDpiButton(
                    text = stringResource(R.string.awg_paste_conf_action),
                    onClick = onPasteConf,
                    variant = RipDpiButtonVariant.Secondary,
                    leadingIcon = RipDpiIcons.Copy,
                )
            },
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
            WarningBanner(
                title = stringResource(R.string.awg_cohort_locked_hint),
                message = stringResource(R.string.awg_section_obfuscation_body),
                tone = WarningBannerTone.Info,
            )
        }
        ObfuscationFields(editor, onFieldChanged)
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
            // I1-I5 carry hex strings; Jc/Jmin/Jmax/S1-S4/H1-H4 are numeric.
            val isHexField = field.name.startsWith("I")
            PlainField(
                field = field,
                labelRes = labelRes,
                editor = editor,
                onFieldChanged = onFieldChanged,
                keyboardType = if (isHexField) KeyboardType.Text else KeyboardType.Number,
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

@Composable
private fun SecretField(
    field: AwgEditorField,
    label: String,
    value: String,
    revealed: Boolean,
    onValueChange: (String) -> Unit,
    onReveal: () -> Unit,
) {
    RipDpiTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        decoration =
            RipDpiTextFieldDecoration(
                label = label,
                placeholder = if (revealed) null else stringResource(R.string.awg_secret_hidden),
                testTag = RipDpiTestTags.awgField(field.name),
            ),
        behavior =
            RipDpiTextFieldBehavior(
                visualTransformation =
                    if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            ),
        trailingContent = {
            RipDpiButton(
                text = stringResource(R.string.awg_reveal_secret_action),
                onClick = onReveal,
                variant = RipDpiButtonVariant.Secondary,
                leadingIcon = RipDpiIcons.Visibility,
            )
        },
    )
}
