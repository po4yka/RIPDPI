package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.ui.screens.settings.SettingsViewModel

@Composable
fun DnsSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val notice by viewModel.dnsNotice.collectAsStateWithLifecycle()

    DnsSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        notice = notice,
        onDismissNotice = { notice?.let(viewModel::acknowledgeDnsNotice) },
        onModeSelected = { mode ->
            if (mode == DnsModeEncrypted) {
                if (uiState.dns.dnsMode != DnsModeEncrypted) {
                    val providerId =
                        resolverOptions.firstOrNull { it.providerId == uiState.dns.dnsProviderId }?.providerId
                            ?: DnsProviderCloudflare
                    viewModel.dnsActions.selectBuiltInDnsProvider(providerId)
                }
            } else {
                viewModel.dnsActions.setPlainDnsServer(uiState.dns.dnsIp)
            }
        },
        onProtocolSelected = { _ ->
            val providerId =
                resolverOptions.firstOrNull { it.providerId == uiState.dns.dnsProviderId }?.providerId
                    ?: DnsProviderCloudflare
            viewModel.dnsActions.selectBuiltInDnsProvider(providerId)
        },
        onResolverSelected = { resolver ->
            viewModel.dnsActions.selectBuiltInDnsProvider(resolver.providerId)
        },
        onSaveCustomDoh = { dohUrl, bootstrapIps ->
            viewModel.dnsActions.setCustomDohResolver(dohUrl, bootstrapIps)
        },
        onSaveCustomDot = { protocol, host, port, tlsServerName, bootstrapIps ->
            viewModel.dnsActions.setCustomDotResolver(
                protocol,
                uiState.selectedMode,
                host,
                port,
                tlsServerName,
                bootstrapIps,
            )
        },
        onSaveCustomDnsCrypt = { host, port, providerName, publicKey, bootstrapIps ->
            viewModel.dnsActions.setCustomDnsCryptResolver(host, port, providerName, publicKey, bootstrapIps)
        },
        onSaveCustomOdoh = { fields, bootstrapIps ->
            viewModel.dnsActions.setCustomOdohResolver(fields, bootstrapIps)
        },
        onSavePlainDns = { dnsAddress ->
            viewModel.dnsActions.setPlainDnsServer(dnsAddress)
        },
        onIpv6Changed = { enabled ->
            viewModel.dnsActions.setIpv6Enabled(enabled)
        },
        modifier = modifier,
    )
}
