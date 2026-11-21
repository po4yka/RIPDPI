package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.SupportedShadowsocksMethods
import com.poyka.ripdpi.data.isSupportedShadowsocksMethod
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.screens.relayeditor.RelayPasswordField
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun VlessPlainRelayFields(
    draft: ConnectionDraft,
    actions: RelayVlessActions,
) {
    // The plain TLS backend supports xHTTP only; the Reality transport selector does not apply.
    VlessUuidField(draft, actions)
    VlessFlowFields(draft, actions)
    XhttpFields(draft, actions)
}

@Composable
internal fun TrojanRelayFields(
    draft: ConnectionDraft,
    actions: RelayAdditionalKindActions,
) {
    RelayPasswordField(
        label = stringResource(R.string.config_relay_trojan_password),
        value = draft.relayTrojanPassword,
        onValueChange = actions.onTrojanPasswordChanged,
    )
    RipDpiTextField(
        value = draft.relayTrojanRootCertificatePem,
        onValueChange = actions.onTrojanRootCertificatePemChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_tuic_root_certificate)),
        behavior = RipDpiTextFieldBehavior(singleLine = false),
    )
}

@Composable
internal fun ShadowsocksRelayFields(
    draft: ConnectionDraft,
    actions: RelayAdditionalKindActions,
) {
    val method = draft.relayShadowsocksMethod.trim().lowercase()
    RipDpiDropdown(
        options = remember { SupportedShadowsocksMethods.map { RipDpiDropdownOption(it, it) }.toImmutableList() },
        selectedValue = method.takeIf(::isSupportedShadowsocksMethod),
        onValueSelected = actions.onShadowsocksMethodChanged,
        label = stringResource(R.string.config_relay_shadowsocks_method),
        // An imported stream cipher has no entry in the list; the field names it until another method is picked.
        placeholder = draft.relayShadowsocksMethod.takeUnless { isSupportedShadowsocksMethod(it) },
        errorText =
            stringResource(R.string.config_error_unsupported).takeIf {
                method.isNotEmpty() && !isSupportedShadowsocksMethod(method)
            },
    )
    RelayPasswordField(
        label = stringResource(R.string.config_relay_shadowsocks_password),
        value = draft.relayShadowsocksPassword,
        onValueChange = actions.onShadowsocksPasswordChanged,
    )
}

@Composable
internal fun AppsScriptRelayFields(
    draft: ConnectionDraft,
    actions: RelayAdditionalKindActions,
) {
    AppsScriptCaExport()
    RelayPlainField(
        draft.relayAppsScriptScriptIds,
        R.string.config_relay_apps_script_ids,
        actions.onAppsScriptScriptIdsChanged,
    )
    RelayPlainField(
        draft.relayAppsScriptGoogleIp,
        R.string.config_relay_apps_script_google_ip,
        actions.onAppsScriptGoogleIpChanged,
    )
    RelayPlainField(
        draft.relayAppsScriptFrontDomain,
        R.string.config_relay_apps_script_front_domain,
        actions.onAppsScriptFrontDomainChanged,
    )
    RelayPlainField(
        draft.relayAppsScriptSniHosts,
        R.string.config_relay_apps_script_sni_hosts,
        actions.onAppsScriptSniHostsChanged,
    )
    RelayPlainField(
        draft.relayAppsScriptDirectHosts,
        R.string.config_relay_apps_script_direct_hosts,
        actions.onAppsScriptDirectHostsChanged,
    )
    RelayPasswordField(
        label = stringResource(R.string.config_relay_apps_script_auth_key),
        value = draft.relayAppsScriptAuthKey,
        onValueChange = actions.onAppsScriptAuthKeyChanged,
    )
    RipDpiSwitch(
        checked = draft.relayAppsScriptVerifySsl,
        onCheckedChange = actions.onAppsScriptVerifySslChanged,
        label = stringResource(R.string.config_relay_apps_script_verify_ssl),
        enabled = !draft.relayAppsScriptVerifySsl,
    )
}

@Composable
private fun RelayPlainField(
    value: String,
    labelRes: Int,
    onChanged: (String) -> Unit,
) {
    RipDpiTextField(
        value = value,
        onValueChange = onChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(labelRes)),
    )
}
