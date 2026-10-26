package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.dnsSummaryLabel
import com.poyka.ripdpi.platform.AndroidStringResolver

@Composable
internal fun DnsUiState.localizedSummary(): String =
    AndroidStringResolver(LocalContext.current).dnsSummaryLabel(dnsMode, dnsProviderId, encryptedDnsProtocol, dnsIp)

@Composable
internal fun ConfigDraft.localizedDnsSummary(): String =
    AndroidStringResolver(LocalContext.current).dnsSummaryLabel(this)

/** One subtitle for every DNS settings row, so the same destination reads the same on each screen. */
@Composable
internal fun dnsSettingsRowSubtitle(vpn: Boolean): String =
    stringResource(if (vpn) R.string.settings_connectivity_dns_body else R.string.settings_connectivity_dns_body_proxy)
