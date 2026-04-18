package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.normalizeDnsBootstrapIps
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.net.URI

@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun CustomEncryptedDnsSection(
    uiState: SettingsUiState,
    dohUrl: String,
    onDohUrlChange: (String) -> Unit,
    dotHost: String,
    onDotHostChange: (String) -> Unit,
    dnscryptHost: String,
    onDnscryptHostChange: (String) -> Unit,
    portInput: String,
    onPortInputChange: (String) -> Unit,
    tlsServerNameInput: String,
    onTlsServerNameChange: (String) -> Unit,
    bootstrapInput: String,
    onBootstrapInputChange: (String) -> Unit,
    dnscryptProviderInput: String,
    onDnscryptProviderChange: (String) -> Unit,
    dnscryptPublicKeyInput: String,
    onDnscryptPublicKeyChange: (String) -> Unit,
    customDohValid: Boolean,
    customDohDirty: Boolean,
    customDotValid: Boolean,
    customDotDirty: Boolean,
    customDnsCryptValid: Boolean,
    customDnsCryptDirty: Boolean,
    dnscryptPublicKeyValid: Boolean,
    bootstrapIpsValid: Boolean,
    onSaveCustomDoh: () -> Unit,
    onSaveCustomDot: () -> Unit,
    onSaveCustomDnsCrypt: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    when (uiState.dns.encryptedDnsProtocol) {
        EncryptedDnsProtocolDot -> {
            Text(
                text = stringResource(R.string.dns_custom_dot_title),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.dns_custom_dot_body),
                style = type.body,
                color = colors.mutedForeground,
            )
            CommonEndpointFields(
                host = dotHost,
                onHostChange = onDotHostChange,
                portInput = portInput,
                onPortInputChange = onPortInputChange,
                bootstrapInput = bootstrapInput,
                onBootstrapInputChange = onBootstrapInputChange,
                hostLabel = stringResource(R.string.dns_custom_host_label),
                hostHelper = stringResource(R.string.dns_custom_dot_host_helper),
                bootstrapError =
                    if (bootstrapInput.isNotBlank() && !bootstrapIpsValid) {
                        stringResource(R.string.dns_custom_bootstrap_error)
                    } else {
                        null
                    },
            )
            RipDpiTextField(
                value = tlsServerNameInput,
                onValueChange = onTlsServerNameChange,
                decoration =
                    RipDpiTextFieldDecoration(
                        testTag = RipDpiTestTags.DnsCustomTlsServerName,
                        label = stringResource(R.string.dns_custom_tls_server_name_label),
                        placeholder = stringResource(R.string.dns_placeholder_resolver_example),
                        helperText = stringResource(R.string.dns_custom_dot_tls_helper),
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = {
                                    if (customDotValid && customDotDirty) {
                                        onSaveCustomDot()
                                    }
                                },
                            ),
                    ),
            )
            RipDpiButton(
                text = stringResource(R.string.config_save),
                onClick = onSaveCustomDot,
                enabled = customDotValid && customDotDirty,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.DnsCustomSave),
            )
        }

        EncryptedDnsProtocolDnsCrypt -> {
            Text(
                text = stringResource(R.string.dns_custom_dnscrypt_title),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.dns_custom_dnscrypt_body),
                style = type.body,
                color = colors.mutedForeground,
            )
            CommonEndpointFields(
                host = dnscryptHost,
                onHostChange = onDnscryptHostChange,
                portInput = portInput,
                onPortInputChange = onPortInputChange,
                bootstrapInput = bootstrapInput,
                onBootstrapInputChange = onBootstrapInputChange,
                hostLabel = stringResource(R.string.dns_custom_host_label),
                hostHelper = stringResource(R.string.dns_custom_dnscrypt_host_helper),
                bootstrapError =
                    if (bootstrapInput.isNotBlank() && !bootstrapIpsValid) {
                        stringResource(R.string.dns_custom_bootstrap_error)
                    } else {
                        null
                    },
            )
            RipDpiTextField(
                value = dnscryptProviderInput,
                onValueChange = onDnscryptProviderChange,
                decoration =
                    RipDpiTextFieldDecoration(
                        testTag = RipDpiTestTags.DnsCustomDnsCryptProvider,
                        label = stringResource(R.string.dns_custom_dnscrypt_provider_label),
                        placeholder = stringResource(R.string.dns_placeholder_dnscrypt_cert),
                        helperText = stringResource(R.string.dns_custom_dnscrypt_provider_helper),
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next,
                            ),
                    ),
            )
            RipDpiTextField(
                value = dnscryptPublicKeyInput,
                onValueChange = onDnscryptPublicKeyChange,
                decoration =
                    RipDpiTextFieldDecoration(
                        testTag = RipDpiTestTags.DnsCustomDnsCryptPublicKey,
                        label = stringResource(R.string.dns_custom_dnscrypt_public_key_label),
                        placeholder = stringResource(R.string.dns_placeholder_public_key),
                        helperText = stringResource(R.string.dns_custom_dnscrypt_public_key_helper),
                        errorText =
                            if (dnscryptPublicKeyInput.isNotBlank() && !dnscryptPublicKeyValid) {
                                stringResource(R.string.dns_custom_dnscrypt_public_key_error)
                            } else {
                                null
                            },
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = {
                                    if (customDnsCryptValid && customDnsCryptDirty) {
                                        onSaveCustomDnsCrypt()
                                    }
                                },
                            ),
                    ),
            )
            RipDpiButton(
                text = stringResource(R.string.config_save),
                onClick = onSaveCustomDnsCrypt,
                enabled = customDnsCryptValid && customDnsCryptDirty,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.DnsCustomSave),
            )
        }

        else -> {
            Text(
                text = stringResource(R.string.dns_custom_doh_title),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.dns_custom_doh_body),
                style = type.body,
                color = colors.mutedForeground,
            )
            RipDpiTextField(
                value = dohUrl,
                onValueChange = onDohUrlChange,
                decoration =
                    RipDpiTextFieldDecoration(
                        testTag = RipDpiTestTags.DnsCustomDohUrl,
                        label = stringResource(R.string.dns_custom_doh_url_label),
                        placeholder = stringResource(R.string.dns_placeholder_doh_url),
                        helperText = stringResource(R.string.dns_custom_doh_url_helper),
                        errorText =
                            if (dohUrl.isNotBlank() && !isValidHttpsUrl(dohUrl.trim())) {
                                stringResource(R.string.dns_custom_doh_url_error)
                            } else {
                                null
                            },
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            ),
                    ),
            )
            RipDpiTextField(
                value = bootstrapInput,
                onValueChange = onBootstrapInputChange,
                decoration =
                    RipDpiTextFieldDecoration(
                        testTag = RipDpiTestTags.DnsCustomBootstrap,
                        label = stringResource(R.string.dns_custom_bootstrap_label),
                        placeholder = stringResource(R.string.dns_placeholder_bootstrap_ips),
                        helperText = stringResource(R.string.dns_custom_bootstrap_helper),
                        errorText =
                            if (bootstrapInput.isNotBlank() && !bootstrapIpsValid) {
                                stringResource(R.string.dns_custom_bootstrap_error)
                            } else {
                                null
                            },
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = {
                                    if (customDohValid && customDohDirty) {
                                        onSaveCustomDoh()
                                    }
                                },
                            ),
                    ),
            )
            RipDpiButton(
                text = stringResource(R.string.config_save),
                onClick = onSaveCustomDoh,
                enabled = customDohValid && customDohDirty,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.DnsCustomSave),
            )
        }
    }
}

@Composable
internal fun CommonEndpointFields(
    host: String,
    onHostChange: (String) -> Unit,
    portInput: String,
    onPortInputChange: (String) -> Unit,
    bootstrapInput: String,
    onBootstrapInputChange: (String) -> Unit,
    hostLabel: String,
    hostHelper: String,
    bootstrapError: String?,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
        RipDpiTextField(
            value = host,
            onValueChange = onHostChange,
            modifier = Modifier.weight(1f),
            decoration =
                RipDpiTextFieldDecoration(
                    testTag = RipDpiTestTags.DnsCustomHost,
                    label = hostLabel,
                    placeholder = stringResource(R.string.dns_placeholder_resolver_example),
                    helperText = hostHelper,
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                        ),
                ),
        )
        RipDpiTextField(
            value = portInput,
            onValueChange = onPortInputChange,
            modifier = Modifier.weight(dnsPortWeightFraction),
            decoration =
                RipDpiTextFieldDecoration(
                    testTag = RipDpiTestTags.DnsCustomPort,
                    label = stringResource(R.string.dns_custom_port_label),
                    placeholder = stringResource(R.string.dns_placeholder_port),
                    helperText = stringResource(R.string.dns_custom_port_helper),
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                ),
        )
    }
    RipDpiTextField(
        value = bootstrapInput,
        onValueChange = onBootstrapInputChange,
        decoration =
            RipDpiTextFieldDecoration(
                testTag = RipDpiTestTags.DnsCustomBootstrap,
                label = stringResource(R.string.dns_custom_bootstrap_label),
                placeholder = stringResource(R.string.dns_placeholder_bootstrap_ips),
                helperText = stringResource(R.string.dns_custom_bootstrap_helper),
                errorText = bootstrapError,
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
            ),
    )
}

internal fun parseBootstrapIps(value: String): List<String> = normalizeDnsBootstrapIps(listOf(value))

internal fun activeEndpointSummary(uiState: SettingsUiState): String =
    when {
        uiState.dns.dnsMode != com.poyka.ripdpi.data.DnsModeEncrypted -> {
            uiState.dns.dnsIp
        }

        uiState.dns.encryptedDnsProtocol == com.poyka.ripdpi.data.EncryptedDnsProtocolDoh -> {
            uiState.dns.encryptedDnsDohUrl.ifBlank {
                "${uiState.dns.encryptedDnsHost}:${uiState.dns.encryptedDnsPort}"
            }
        }

        else -> {
            "${uiState.dns.encryptedDnsHost}:${uiState.dns.encryptedDnsPort}"
        }
    }

internal fun formatBootstrapPreview(values: List<String>): String =
    when {
        values.isEmpty() -> ""
        values.size <= 2 -> values.joinToString(" · ")
        else -> values.take(2).joinToString(" · ") + " · +${values.size - 2}"
    }

internal fun isValidHttpsUrl(value: String): Boolean =
    runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
