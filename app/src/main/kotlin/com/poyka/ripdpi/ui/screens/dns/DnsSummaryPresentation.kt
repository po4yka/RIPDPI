package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.dnsSummaryLabel
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.ui.screens.connection.ConnectionDraft

@Composable
internal fun DnsUiState.localizedSummary(): String =
    AndroidStringResolver(LocalContext.current).dnsSummaryLabel(dnsMode, dnsProviderId, encryptedDnsProtocol, dnsIp)

@Composable
internal fun ConnectionDraft.localizedDnsSummary(): String =
    AndroidStringResolver(LocalContext.current).dnsSummaryLabel(this)
