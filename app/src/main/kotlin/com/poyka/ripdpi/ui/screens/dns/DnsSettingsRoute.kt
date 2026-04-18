package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh

@Composable
fun DnsSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DnsSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onModeSelected = { mode ->
            if (mode == DnsModeEncrypted) {
                if (uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh &&
                    uiState.dns.dnsProviderId != DnsProviderCustom
                ) {
                    viewModel.selectBuiltInDnsProvider(uiState.dns.dnsProviderId)
                } else {
                    viewModel.setEncryptedDnsProtocol(
                        uiState.dns.encryptedDnsProtocol.ifBlank { EncryptedDnsProtocolDoh },
                    )
                }
            } else {
                viewModel.setPlainDnsServer(uiState.dns.dnsIp)
            }
        },
        onProtocolSelected = { protocol ->
            when (protocol) {
                EncryptedDnsProtocolDoh -> {
                    val providerId =
                        resolverOptions.firstOrNull { it.providerId == uiState.dns.dnsProviderId }?.providerId
                            ?: DnsProviderCloudflare
                    viewModel.selectBuiltInDnsProvider(providerId)
                }

                else -> {
                    viewModel.setEncryptedDnsProtocol(protocol)
                }
            }
        },
        onResolverSelected = { resolver ->
            viewModel.selectBuiltInDnsProvider(resolver.providerId)
        },
        onSaveCustomDoh = { dohUrl, bootstrapIps ->
            viewModel.setCustomDohResolver(dohUrl, bootstrapIps)
        },
        onSaveCustomDot = { host, port, tlsServerName, bootstrapIps ->
            viewModel.setCustomDotResolver(host, port, tlsServerName, bootstrapIps)
        },
        onSaveCustomDnsCrypt = { host, port, providerName, publicKey, bootstrapIps ->
            viewModel.setCustomDnsCryptResolver(host, port, providerName, publicKey, bootstrapIps)
        },
        onSavePlainDns = { dnsAddress ->
            viewModel.setPlainDnsServer(dnsAddress)
        },
        onIpv6Changed = { enabled ->
            viewModel.updateSetting(
                key = "ipv6Enable",
                value = enabled.toString(),
            ) {
                setIpv6Enable(enabled)
            }
        },
        modifier = modifier,
    )
}
