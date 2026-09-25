package com.poyka.ripdpi.ui.screens.dns

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.LegacyOdohConfigSourceCustom
import com.poyka.ripdpi.activities.OdohResolverFields
import com.poyka.ripdpi.activities.hasFreshConfig
import com.poyka.ripdpi.activities.hasSupportedConfigWire
import com.poyka.ripdpi.activities.isValid
import com.poyka.ripdpi.data.EncryptedDnsOdohConfigSourceBundled
import com.poyka.ripdpi.data.EncryptedDnsOdohConfigSourceCustomBytes
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
@Suppress("LongMethod")
internal fun OdohDnsEditor(
    current: OdohResolverFields,
    protocolChanged: Boolean,
    currentBootstrapIps: List<String>,
    bootstrapInput: String,
    onBootstrapInputChange: (String) -> Unit,
    bootstrapIpsValid: Boolean,
    onSave: (OdohResolverFields) -> Unit,
) {
    var proxyUrl by rememberSaveable(current.proxyUrl) { mutableStateOf(current.proxyUrl) }
    var proxyOperator by rememberSaveable(current.proxyOperatorId) { mutableStateOf(current.proxyOperatorId) }
    var targetHost by rememberSaveable(current.targetHost) { mutableStateOf(current.targetHost) }
    var targetPath by rememberSaveable(current.targetPath) { mutableStateOf(current.targetPath) }
    var targetOperator by rememberSaveable(current.targetOperatorId) { mutableStateOf(current.targetOperatorId) }
    var configSource by rememberSaveable(current.configSource) {
        mutableStateOf(current.configSource.ifBlank { EncryptedDnsOdohConfigSourceCustomBytes })
    }
    var configsHex by rememberSaveable(current.configsHex) { mutableStateOf(current.configsHex) }
    var retrievedAt by rememberSaveable(current.configsRetrievedAtSecs) {
        mutableStateOf(
            current.configsRetrievedAtSecs
                .takeIf { it > 0 }
                ?.toString()
                .orEmpty(),
        )
    }
    var ttl by rememberSaveable(current.configsTtlSecs) {
        mutableStateOf(
            current.configsTtlSecs
                .takeIf { it > 0 }
                ?.toString()
                .orEmpty(),
        )
    }
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val candidate =
        OdohResolverFields(
            proxyUrl = proxyUrl.trim(),
            proxyOperatorId = proxyOperator.trim(),
            targetHost = targetHost.trim(),
            targetPath = targetPath.trim(),
            targetOperatorId = targetOperator.trim(),
            configSource = configSource,
            configsHex = configsHex.trim(),
            configsRetrievedAtSecs = retrievedAt.toLongOrNull() ?: 0,
            configsTtlSecs = ttl.toLongOrNull() ?: 0,
        )
    val canSave =
        candidate.isValid() && bootstrapIpsValid &&
            (protocolChanged || candidate != current || parseBootstrapIps(bootstrapInput) != currentBootstrapIps)

    Text(stringResource(R.string.dns_custom_odoh_title), style = type.bodyEmphasis, color = colors.foreground)
    Text(stringResource(R.string.dns_custom_odoh_body), style = type.body, color = colors.mutedForeground)
    OdohTextField(proxyUrl, {
        proxyUrl = it
    }, R.string.dns_custom_odoh_proxy_url_label, RipDpiTestTags.DnsOdohProxyUrl, KeyboardType.Uri)
    OdohTextField(proxyOperator, {
        proxyOperator = it
    }, R.string.dns_custom_odoh_proxy_operator_label, RipDpiTestTags.DnsOdohProxyOperator)
    OdohTextField(targetHost, {
        targetHost = it
    }, R.string.dns_custom_odoh_target_host_label, RipDpiTestTags.DnsOdohTargetHost)
    OdohTextField(targetPath, {
        targetPath = it
    }, R.string.dns_custom_odoh_target_path_label, RipDpiTestTags.DnsOdohTargetPath)
    OdohTextField(targetOperator, {
        targetOperator = it
    }, R.string.dns_custom_odoh_target_operator_label, RipDpiTestTags.DnsOdohTargetOperator)

    Text(stringResource(R.string.dns_custom_odoh_source_label), style = type.bodyEmphasis, color = colors.foreground)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        DnsOptionCard(
            icon = RipDpiIcons.Lock,
            title = stringResource(R.string.dns_custom_odoh_source_custom),
            body = stringResource(R.string.dns_custom_odoh_source_custom_body),
            selected =
                configSource == EncryptedDnsOdohConfigSourceCustomBytes || configSource == LegacyOdohConfigSourceCustom,
            badges = emptyList(),
            onClick = { configSource = EncryptedDnsOdohConfigSourceCustomBytes },
        )
        DnsOptionCard(
            icon = RipDpiIcons.Lock,
            title = stringResource(R.string.dns_custom_odoh_source_bundled),
            body = stringResource(R.string.dns_custom_odoh_source_bundled_body),
            selected = configSource == EncryptedDnsOdohConfigSourceBundled,
            badges = emptyList(),
            onClick = { configSource = EncryptedDnsOdohConfigSourceBundled },
        )
    }
    OdohTextField(configsHex, {
        configsHex = it
    }, R.string.dns_custom_odoh_configs_hex_label, RipDpiTestTags.DnsOdohConfigsHex)
    if (configsHex.isNotBlank() && !candidate.hasSupportedConfigWire()) {
        Text(stringResource(R.string.dns_custom_odoh_config_invalid), style = type.caption, color = colors.destructive)
    }
    OdohTextField(retrievedAt, {
        retrievedAt = it
    }, R.string.dns_custom_odoh_retrieved_at_label, RipDpiTestTags.DnsOdohRetrievedAt, KeyboardType.Number)
    OdohTextField(ttl, { ttl = it }, R.string.dns_custom_odoh_ttl_label, RipDpiTestTags.DnsOdohTtl, KeyboardType.Number)
    if (candidate.configsRetrievedAtSecs > 0 && candidate.configsTtlSecs > 0 && !candidate.hasFreshConfig()) {
        Text(stringResource(R.string.dns_custom_odoh_config_expired), style = type.caption, color = colors.destructive)
    }
    OdohTextField(
        bootstrapInput,
        onBootstrapInputChange,
        R.string.dns_custom_bootstrap_label,
        RipDpiTestTags.DnsCustomBootstrap,
    )
    RipDpiButton(
        text = stringResource(R.string.config_save),
        onClick = { onSave(candidate) },
        enabled = canSave,
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.DnsCustomSave),
    )
    if (!canSave) {
        Text(
            stringResource(R.string.dns_custom_save_requirements_hint),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun OdohTextField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    testTag: String,
    keyboardType: KeyboardType = KeyboardType.Ascii,
) {
    RipDpiTextField(
        value = value,
        onValueChange = onValueChange,
        decoration = RipDpiTextFieldDecoration(testTag = testTag, label = stringResource(label)),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions =
                    androidx.compose.foundation.text
                        .KeyboardOptions(keyboardType = keyboardType),
            ),
    )
}
