package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.dnsProviderById
import com.poyka.ripdpi.data.protocolDisplayName
import com.poyka.ripdpi.platform.StringResolver

/**
 * The one user-visible resolver summary ("Encrypted DNS · Cloudflare (DoH)"). Every screen uses it;
 * `ActiveDnsSettings.summary()` in core stays English for non-UI strategy labels.
 */
internal fun StringResolver.dnsSummaryLabel(
    dnsMode: String,
    dnsProviderId: String,
    encryptedDnsProtocol: String,
    dnsIp: String,
): String =
    if (dnsMode == DnsModeEncrypted) {
        val provider = dnsProviderById(dnsProviderId)?.displayName ?: getString(R.string.dns_custom_title)
        "${getString(R.string.dns_mode_doh)} · $provider (${protocolDisplayName(encryptedDnsProtocol)})"
    } else {
        "${getString(R.string.dns_mode_plain)} · $dnsIp"
    }

internal fun StringResolver.dnsSummaryLabel(draft: ConfigDraft): String =
    dnsSummaryLabel(draft.dnsMode, draft.dnsProviderId, draft.encryptedDnsProtocol, draft.dnsIp)
