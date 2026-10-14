package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.dnsProviderById
import com.poyka.ripdpi.data.protocolDisplayName

@Composable
internal fun DnsUiState.localizedSummary(): String =
    if (dnsMode == DnsModeEncrypted) {
        val mode = stringResource(R.string.dns_mode_doh)
        val provider = dnsProviderById(dnsProviderId)?.displayName ?: stringResource(R.string.dns_custom_title)
        "$mode · $provider (${protocolDisplayName(encryptedDnsProtocol)})"
    } else {
        "${stringResource(R.string.dns_mode_plain)} · $dnsIp"
    }
