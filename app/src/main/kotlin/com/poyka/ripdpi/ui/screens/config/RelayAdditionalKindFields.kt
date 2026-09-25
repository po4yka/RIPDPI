package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

@Composable
internal fun VlessPlainRelayFields(
    draft: ConfigDraft,
    actions: RelayVlessActions,
) {
    // The plain TLS backend supports xHTTP only; the Reality transport selector does not apply.
    VlessUuidField(draft, actions)
    XhttpFields(draft, actions)
}

@Composable
internal fun TrojanRelayFields(
    draft: ConfigDraft,
    actions: RelayAdditionalKindActions,
) {
    RelaySecretField(draft.relayTrojanPassword, R.string.config_relay_trojan_password, actions.onTrojanPasswordChanged)
}

@Composable
internal fun ShadowsocksRelayFields(
    draft: ConfigDraft,
    actions: RelayAdditionalKindActions,
) {
    RelayPlainField(
        draft.relayShadowsocksMethod,
        R.string.config_relay_shadowsocks_method,
        actions.onShadowsocksMethodChanged,
    )
    RelaySecretField(
        draft.relayShadowsocksPassword,
        R.string.config_relay_shadowsocks_password,
        actions.onShadowsocksPasswordChanged,
    )
}

@Composable
internal fun AppsScriptRelayFields(
    draft: ConfigDraft,
    actions: RelayAdditionalKindActions,
) {
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
    RelaySecretField(
        draft.relayAppsScriptAuthKey,
        R.string.config_relay_apps_script_auth_key,
        actions.onAppsScriptAuthKeyChanged,
    )
    RipDpiSwitch(
        checked = draft.relayAppsScriptVerifySsl,
        onCheckedChange = actions.onAppsScriptVerifySslChanged,
        label = stringResource(R.string.config_relay_apps_script_verify_ssl),
    )
    RipDpiSwitch(
        checked = draft.relayAppsScriptParallelRelay,
        onCheckedChange = actions.onAppsScriptParallelRelayChanged,
        label = stringResource(R.string.config_relay_apps_script_parallel),
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

@Composable
private fun RelaySecretField(
    value: String,
    labelRes: Int,
    onChanged: (String) -> Unit,
) {
    RipDpiTextField(
        value = value,
        onValueChange = onChanged,
        decoration = RipDpiTextFieldDecoration(label = stringResource(labelRes)),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            ),
    )
}
