package com.poyka.ripdpi.ui.screens.awg

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import com.poyka.ripdpi.security.AppPinCheck
import com.poyka.ripdpi.security.SecretRevealPinViewModel
import com.poyka.ripdpi.security.rememberSecretRevealGate
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.relayeditor.RelayAdvancedSection
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorScaffold
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorStatus
import com.poyka.ripdpi.ui.screens.relayeditor.RelayHostField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayNumberField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayPortField
import com.poyka.ripdpi.ui.screens.relayeditor.RelaySecretField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayUrlField
import com.poyka.ripdpi.ui.screens.relayeditor.RelayValueField
import com.poyka.ripdpi.ui.screens.relayeditor.fieldErrorText
import com.poyka.ripdpi.ui.testing.AwgTestTags
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
 * key, peer endpoint, allowed IPs, preshared key, persistent keepalive) plus the legacy and v3
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
    profileId: String = "",
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(profileId, importToken) {
        // The saved profile loads first so a staged .conf is applied on top of it.
        if (profileId.isNotEmpty()) viewModel.onLoadProfile(profileId)
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
    LifecycleEventEffect(viewModel.profileDeleted) { onBack() }
    // Revealed secrets hide again when the app goes to the background or the screen closes.
    LifecycleStartEffect(viewModel) { onStopOrDispose { viewModel.onSecretsRelocked() } }
    val revealPrivateKey =
        rememberSecretRevealGate({ viewModel.onSecretRevealAuthorized(AwgSecret.PRIVATE_KEY) }, appPin)
    val revealPresharedKey =
        rememberSecretRevealGate({ viewModel.onSecretRevealAuthorized(AwgSecret.PRESHARED_KEY) }, appPin)
    val revealHeaderProtectionKey =
        rememberSecretRevealGate({ viewModel.onSecretRevealAuthorized(AwgSecret.HEADER_PROTECTION_KEY) }, appPin)
    AmneziaWgProfileScreen(
        uiState = uiState,
        onBack = onBack,
        onFieldChanged = viewModel::onFieldChanged,
        onNameChanged = viewModel::onNameChanged,
        onGenerateKeyPair = viewModel::onGenerateKeyPair,
        onCohortSelected = viewModel::onCohortSelected,
        onResolveCohortReplacement = viewModel::onCohortReplacementResolved,
        onCarrierSelected = viewModel::onCarrierSelected,
        onPasteConf = viewModel::onPasteConf,
        onRevealSecret = { secret ->
            when (secret) {
                AwgSecret.PRIVATE_KEY -> (onRequestPrivateKeyReveal ?: revealPrivateKey)()
                AwgSecret.PRESHARED_KEY -> (onRequestPresharedKeyReveal ?: revealPresharedKey)()
                AwgSecret.HEADER_PROTECTION_KEY -> revealHeaderProtectionKey()
            }
        },
        onConnect = viewModel::onConnect,
        onSave = viewModel::onSave,
        onDelete = viewModel::onDelete,
        modifier = modifier,
    )
}

@Composable
internal fun AmneziaWgProfileScreen(
    uiState: AmneziaWgProfileUiState,
    onBack: () -> Unit,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onNameChanged: (String) -> Unit,
    onGenerateKeyPair: () -> Unit,
    onCohortSelected: (String) -> Unit,
    onCarrierSelected: (String) -> Unit,
    onPasteConf: () -> Unit,
    onRevealSecret: (AwgSecret) -> Unit,
    onConnect: () -> Unit,
    onSave: () -> Unit,
    onResolveCohortReplacement: (confirmed: Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.pendingCohortId != null) {
        AwgCohortReplacementDialog({ onResolveCohortReplacement(true) }, { onResolveCohortReplacement(false) })
    }
    val connectFailed = uiState.activationStatus == AwgActivationStatus.Failed
    RelayEditorScaffold(
        actionTag = AwgTestTags.AwgConnectAction,
        route = Route.AmneziaWgProfile(),
        title = stringResource(R.string.awg_editor_title),
        saveLabel = stringResource(R.string.awg_connect_action),
        status =
            RelayEditorStatus(
                complete = uiState.canActivate,
                saving = uiState.activationStatus == AwgActivationStatus.Connecting,
                loading = uiState.deleting || uiState.saving,
                failed = connectFailed,
            ),
        onBack = onBack,
        onSave = onConnect,
        modifier = modifier,
        // The connect hint sits at the top; it also reports a failed attempt.
        hint =
            when {
                connectFailed -> stringResource(R.string.awg_connect_error)
                uiState.saveFailed -> stringResource(R.string.awg_save_error)
                uiState.keyGenerationFailed -> stringResource(R.string.awg_key_generation_error)
                uiState.deleteFailed -> stringResource(R.string.awg_delete_failed)
                uiState.subscriptionServerChanged -> stringResource(R.string.awg_conf_subscription_server_mismatch_body)
                uiState.saved -> stringResource(R.string.awg_saved_hint)
                uiState.canActivate -> stringResource(R.string.awg_connect_ready_hint)
                else -> connectBlockedHint(uiState.editor)
            },
        hintIsError =
            connectFailed || uiState.saveFailed || uiState.keyGenerationFailed || uiState.deleteFailed ||
                uiState.subscriptionServerChanged,
    ) {
        // A saved profile replaces the whole form when it loads, so nothing is shown to edit before then.
        if (uiState.loadingProfile) return@RelayEditorScaffold
        // Import comes first: a pasted .conf fills most of the fields below.
        RipDpiButton(
            text = stringResource(R.string.awg_paste_conf_action),
            onClick = onPasteConf,
            modifier = Modifier.fillMaxWidth(),
            variant = RipDpiButtonVariant.Secondary,
            leadingIcon = RipDpiIcons.Paste,
        )
        AwgConfImportFeedback(uiState)
        InterfaceSection(uiState, onFieldChanged, onNameChanged, onGenerateKeyPair) {
            onRevealSecret(AwgSecret.PRIVATE_KEY)
        }
        PeerSection(uiState, onFieldChanged)
        AwgAdvancedSection(
            uiState = uiState,
            onFieldChanged = onFieldChanged,
            onCohortSelected = onCohortSelected,
            onCarrierSelected = onCarrierSelected,
            onRevealPresharedKey = { onRevealSecret(AwgSecret.PRESHARED_KEY) },
            onRevealHeaderProtectionKey = { onRevealSecret(AwgSecret.HEADER_PROTECTION_KEY) },
        )
        RipDpiButton(
            text = stringResource(R.string.awg_save_action),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(AwgTestTags.AwgSaveAction),
            variant = RipDpiButtonVariant.Secondary,
            enabled =
                uiState.canActivate && !uiState.deleting &&
                    uiState.activationStatus != AwgActivationStatus.Connecting,
            loading = uiState.saving,
        )
        if (uiState.deletable) AwgDeleteProfileAction(uiState, onDelete)
    }
}

/** Why the last pasted `.conf` was refused, and what it carried that the editor ignores. */
@Composable
private fun AwgConfImportFeedback(uiState: AmneziaWgProfileUiState) {
    uiState.confImportFailure?.let { failure ->
        WarningBanner(
            title = stringResource(R.string.awg_conf_import_failed_title),
            message =
                when (failure.reason) {
                    AwgConfImportFailure.UNREADABLE -> {
                        stringResource(R.string.awg_conf_import_unreadable_body)
                    }

                    AwgConfImportFailure.INVALID,
                    AwgConfImportFailure.UNSUPPORTED_OPTION,
                    -> {
                        stringResource(R.string.awg_conf_import_failed_body)
                    }

                    AwgConfImportFailure.UNSUPPORTED_LISTEN_PORT -> {
                        stringResource(R.string.awg_conf_unsupported_option_body, "ListenPort")
                    }

                    AwgConfImportFailure.SUBSCRIPTION_SERVER_MISMATCH -> {
                        stringResource(R.string.awg_conf_subscription_server_mismatch_body)
                    }
                },
            testTag = AwgTestTags.AwgConfImportFailed,
        )
    }
    if (uiState.ignoredPeerCount > 0) {
        WarningBanner(
            title = stringResource(R.string.awg_conf_extra_peers_title),
            message = stringResource(R.string.awg_conf_extra_peers_body, uiState.ignoredPeerCount),
            tone = WarningBannerTone.Info,
        )
    }
    if (uiState.ignoredConfOptions.isNotEmpty()) {
        WarningBanner(
            title = stringResource(R.string.awg_conf_ignored_options_title),
            message = uiState.ignoredConfOptions.joinToString(),
            tone = WarningBannerTone.Info,
        )
    }
}

@Composable
private fun AwgAdvancedSection(
    uiState: AmneziaWgProfileUiState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onCohortSelected: (String) -> Unit,
    onCarrierSelected: (String) -> Unit,
    onRevealPresharedKey: () -> Unit,
    onRevealHeaderProtectionKey: () -> Unit,
) {
    val editor = uiState.editor
    RelayAdvancedSection(
        hasError =
            editor.runtimeInputField() in AwgAdvancedFields ||
                AwgAdvancedFields.any { editor.hasFieldError(it) || editor.conflictingField(it) != null },
    ) {
        AdvancedConnectionSection(uiState, onFieldChanged, onRevealPresharedKey)
        CarrierSection(uiState, onFieldChanged, onCarrierSelected)
        ObfuscationSection(uiState, onFieldChanged, onCohortSelected, onRevealHeaderProtectionKey)
    }
}

/** The interface and peer settings most profiles take from the imported `.conf` unchanged. */
@Composable
private fun AdvancedConnectionSection(
    uiState: AmneziaWgProfileUiState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onRevealPresharedKey: () -> Unit,
) {
    val editor = uiState.editor
    RipDpiCard {
        PlainField(
            AwgEditorField.DNS,
            editor,
            onFieldChanged,
            helperText = stringResource(R.string.awg_example_helper, stringResource(R.string.awg_dns_example)),
        )
        PlainField(
            AwgEditorField.MTU,
            editor,
            onFieldChanged,
            placeholder = AwgActivationRequest.DEFAULT_MTU.toString(),
        )
        PlainField(AwgEditorField.PEER_ENDPOINT, editor, onFieldChanged)
        PlainField(
            AwgEditorField.ALLOWED_IPS,
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
            readOnly = editor.isManaged(AwgEditorField.PRESHARED_KEY),
            helperText =
                stringResource(R.string.awg_field_from_subscription)
                    .takeIf { editor.isManaged(AwgEditorField.PRESHARED_KEY) },
            errorText =
                fieldErrorText(
                    editor.rawText(AwgEditorField.PRESHARED_KEY),
                    editor.hasFieldError(AwgEditorField.PRESHARED_KEY),
                ),
        )
        PlainField(
            AwgEditorField.PERSISTENT_KEEPALIVE,
            editor,
            onFieldChanged,
            helperText = stringResource(R.string.awg_keepalive_helper),
        )
    }
}

/** The fields the editor shows under Advanced; the rest stay in view. */
private val AwgAdvancedFields =
    AwgEditorField.entries -
        setOf(
            AwgEditorField.INTERFACE_PRIVATE_KEY,
            AwgEditorField.SERVER,
            AwgEditorField.SERVER_PORT,
            AwgEditorField.ADDRESS,
            AwgEditorField.PEER_PUBLIC_KEY,
        )

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
            testTag = AwgTestTags.AwgCarrierPicker,
        )
        if (editor.form.carrier == AwgActivationRequest.CARRIER_WS) {
            PlainField(
                AwgEditorField.CARRIER_WS_URL,
                editor,
                onFieldChanged,
            )
        }
    }
}

@Composable
private fun InterfaceSection(
    uiState: AmneziaWgProfileUiState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onNameChanged: (String) -> Unit,
    onGenerateKeyPair: () -> Unit,
    onRevealPrivateKey: () -> Unit,
) {
    val editor = uiState.editor
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.awg_section_interface))
        RipDpiTextField(
            value = uiState.name,
            onValueChange = onNameChanged,
            modifier = Modifier.fillMaxWidth(),
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.awg_field_name),
                    testTag = AwgTestTags.AwgNameField,
                ),
        )
        RelaySecretField(
            testTag = RipDpiTestTags.awgField(AwgEditorField.INTERFACE_PRIVATE_KEY.name),
            label = stringResource(R.string.awg_field_private_key),
            value = editor.rawText(AwgEditorField.INTERFACE_PRIVATE_KEY),
            revealed = uiState.privateKeyRevealed,
            onValueChange = { onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, it) },
            onReveal = onRevealPrivateKey,
            errorText =
                fieldErrorText(
                    editor.rawText(AwgEditorField.INTERFACE_PRIVATE_KEY),
                    editor.hasFieldError(AwgEditorField.INTERFACE_PRIVATE_KEY),
                ),
        )
        if (editor.form.interfacePrivateKey.isBlank()) {
            RipDpiButton(
                text = stringResource(R.string.awg_generate_key_action),
                onClick = onGenerateKeyPair,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(AwgTestTags.AwgGenerateKeyAction),
                variant = RipDpiButtonVariant.Secondary,
            )
        }
        uiState.generatedPublicKey?.let { AwgPublicKeyField(it) }
        PlainField(AwgEditorField.SERVER, editor, onFieldChanged)
        PlainField(
            AwgEditorField.SERVER_PORT,
            editor,
            onFieldChanged,
        )
        PlainField(
            AwgEditorField.ADDRESS,
            editor,
            onFieldChanged,
            helperText = stringResource(R.string.awg_example_helper, stringResource(R.string.awg_address_example)),
        )
    }
}

@Composable
private fun AwgPublicKeyField(publicKey: String) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val label = stringResource(R.string.awg_field_public_key)
    RipDpiTextField(
        value = publicKey,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        decoration = RipDpiTextFieldDecoration(label = label, testTag = AwgTestTags.AwgPublicKeyField),
        behavior = RipDpiTextFieldBehavior(readOnly = true),
        trailingContent = {
            RipDpiIconButton(
                icon = RipDpiIcons.Copy,
                contentDescription = stringResource(R.string.awg_copy_public_key_action),
                onClick = {
                    clipboard?.setPrimaryClip(ClipData.newPlainText(label, publicKey))
                    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.ripDpiTestTag(AwgTestTags.AwgCopyPublicKeyAction),
            )
        },
    )
}

@Composable
private fun PeerSection(
    uiState: AmneziaWgProfileUiState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
) {
    val editor = uiState.editor
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.awg_section_peer))
        PlainField(AwgEditorField.PEER_PUBLIC_KEY, editor, onFieldChanged)
    }
}

@Composable
private fun ObfuscationSection(
    uiState: AmneziaWgProfileUiState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    onCohortSelected: (String) -> Unit,
    onRevealHeaderProtectionKey: () -> Unit,
) {
    val editor = uiState.editor
    val customLabel = stringResource(R.string.awg_cohort_custom)
    // A preset owns these values, so its fields stay collapsed until the user asks to see them.
    var expanded by rememberSaveable { mutableStateOf(false) }
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.awg_section_obfuscation),
            supporting = if (editor.obfuscationLocked) stringResource(R.string.awg_cohort_locked_hint) else null,
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
            readOnly = editor.subscriptionOwned,
            helperText = stringResource(R.string.awg_field_from_subscription).takeIf { editor.subscriptionOwned },
            label = stringResource(R.string.awg_cohort_picker_label),
            testTag = AwgTestTags.AwgCohortPicker,
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
            AwgV3Fields(uiState, onFieldChanged, onRevealHeaderProtectionKey)
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
            AwgEditorField.JC,
            AwgEditorField.JMIN,
            AwgEditorField.JMAX,
            AwgEditorField.S1,
            AwgEditorField.S2,
            AwgEditorField.S3,
            AwgEditorField.S4,
            AwgEditorField.H1,
            AwgEditorField.H2,
            AwgEditorField.H3,
            AwgEditorField.H4,
            AwgEditorField.I1,
            AwgEditorField.I2,
            AwgEditorField.I3,
            AwgEditorField.I4,
            AwgEditorField.I5,
        )
    val spacing = RipDpiThemeTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        obfuscationFields.forEach { field ->
            PlainField(
                field = field,
                editor = editor,
                onFieldChanged = onFieldChanged,
                enabled = !editor.obfuscationLocked,
            )
        }
    }
}

@Composable
internal fun PlainField(
    field: AwgEditorField,
    editor: AmneziaWgEditorState,
    onFieldChanged: (AwgEditorField, String) -> Unit,
    placeholder: String? = null,
    helperText: String? = null,
    enabled: Boolean = true,
) {
    val hasError = editor.hasFieldError(field)
    val conflict = editor.conflictingField(field)
    val managed = editor.isManaged(field)
    val value = editor.rawText(field)
    val valueField =
        RelayValueField(
            label = stringResource(field.label),
            placeholder = placeholder,
            helperText = if (managed) stringResource(R.string.awg_field_from_subscription) else helperText,
            errorText =
                when {
                    conflict != null -> {
                        stringResource(R.string.config_error_conflicts_with, stringResource(conflict.label))
                    }

                    editor.hasHeaderProtectionPrefixError(field) -> {
                        stringResource(
                            R.string.awg_hp_prefix_min,
                            field.name,
                            AmneziaWgV3Parameters.HeaderNonceBytes,
                        )
                    }

                    hasError -> {
                        fieldErrorText(value, hasError = true)
                    }

                    // An empty field the runtime requires, inside Advanced where the user may not have looked.
                    field in AwgAdvancedFields && field == editor.runtimeInputField() && value.isBlank() -> {
                        stringResource(R.string.config_error_required)
                    }

                    else -> {
                        null
                    }
                },
            testTag = RipDpiTestTags.awgField(field.name),
            readOnly = managed,
            enabled = enabled,
        )
    val onValueChange: (String) -> Unit = { onFieldChanged(field, it) }
    when (field) {
        AwgEditorField.SERVER -> {
            RelayHostField(valueField, value, onValueChange)
        }

        AwgEditorField.SERVER_PORT -> {
            RelayPortField(valueField, value, onValueChange)
        }

        AwgEditorField.CARRIER_WS_URL -> {
            RelayUrlField(valueField, value, onValueChange)
        }

        in AwgNumberFields -> {
            RelayNumberField(valueField, value, onValueChange)
        }

        // Addresses, keys, routes, header ranges and signature programs are free text.
        else -> {
            RipDpiTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                decoration =
                    RipDpiTextFieldDecoration(
                        label = valueField.label,
                        placeholder = placeholder,
                        helperText = valueField.helperText,
                        errorText = valueField.errorText,
                        testTag = valueField.testTag,
                    ),
                behavior = RipDpiTextFieldBehavior(enabled = enabled, readOnly = managed),
            )
        }
    }
}

/** Fields that hold one whole number; the editor state owns their range checks. */
private val AwgNumberFields =
    setOf(
        AwgEditorField.MTU,
        AwgEditorField.PERSISTENT_KEEPALIVE,
        AwgEditorField.JC,
        AwgEditorField.JMIN,
        AwgEditorField.JMAX,
        AwgEditorField.S1,
        AwgEditorField.S2,
        AwgEditorField.S3,
        AwgEditorField.S4,
    )

@Composable
private fun connectBlockedHint(editor: AmneziaWgEditorState): String {
    val labels = editor.blockingFields().map { stringResource(it.label) }
    return if (labels.isEmpty()) {
        stringResource(R.string.awg_connect_incomplete_hint)
    } else {
        stringResource(R.string.awg_connect_blocked_hint, labels.joinToString())
    }
}
