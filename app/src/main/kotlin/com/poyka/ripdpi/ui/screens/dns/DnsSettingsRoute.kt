package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsProviderCloudflare

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
                if (uiState.dns.dnsMode != DnsModeEncrypted) {
                    val providerId =
                        resolverOptions.firstOrNull { it.providerId == uiState.dns.dnsProviderId }?.providerId
                            ?: DnsProviderCloudflare
                    viewModel.selectBuiltInDnsProvider(providerId)
                }
            } else {
                viewModel.setPlainDnsServer(uiState.dns.dnsIp)
            }
        },
        onProtocolSelected = { _ ->
            val providerId =
                resolverOptions.firstOrNull { it.providerId == uiState.dns.dnsProviderId }?.providerId
                    ?: DnsProviderCloudflare
            viewModel.selectBuiltInDnsProvider(providerId)
        },
        onResolverSelected = { resolver ->
            viewModel.selectBuiltInDnsProvider(resolver.providerId)
        },
        onSaveCustomDoh = { dohUrl, bootstrapIps ->
            viewModel.setCustomDohResolver(dohUrl, bootstrapIps)
        },
        onSaveCustomDot = { protocol, host, port, tlsServerName, bootstrapIps ->
            viewModel.setCustomDotResolver(protocol, host, port, tlsServerName, bootstrapIps)
        },
        onSaveCustomDnsCrypt = { host, port, providerName, publicKey, bootstrapIps ->
            viewModel.setCustomDnsCryptResolver(host, port, providerName, publicKey, bootstrapIps)
        },
        onSaveCustomOdoh = { fields, bootstrapIps ->
            viewModel.setCustomOdohResolver(fields, bootstrapIps)
        },
        onSavePlainDns = { dnsAddress ->
            viewModel.setPlainDnsServer(dnsAddress)
        },
        onIpv6Changed = { enabled ->
            viewModel.setDnsIpv6Enabled(enabled)
        },
        modifier = modifier,
    )
}
