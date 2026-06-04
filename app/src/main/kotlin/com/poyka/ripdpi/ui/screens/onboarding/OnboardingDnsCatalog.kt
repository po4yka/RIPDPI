package com.poyka.ripdpi.ui.screens.onboarding

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.DnsProviderAdGuard
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderMullvad
import com.poyka.ripdpi.data.DnsProviderQuad9
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.EncryptedDnsProtocolOdoh
import com.poyka.ripdpi.data.dnsProviderById

/**
 * Onboarding-only sentinel id for the "use the system / device DNS" choice. It is intentionally NOT
 * a [com.poyka.ripdpi.data.BuiltInDnsProviders] id: selecting it means "no built-in resolver is
 * pinned", and it has no technical metadata line.
 */
internal const val OnboardingDnsSystemId: String = "system"

/**
 * A single curated entry in the onboarding DNS picker. The data layer ([BuiltInDnsProviders]) is
 * test-pinned and contains IP-variant duplicates we deliberately hide here; this is the trimmed,
 * human-facing subset keyed by [providerId]. The full catalog stays reachable via Advanced DNS
 * settings.
 */
internal data class OnboardingDnsOption(
    /** [OnboardingDnsSystemId] or a [com.poyka.ripdpi.data.BuiltInDnsProviders] provider id. */
    val providerId: String,
    @StringRes val descriptionRes: Int,
    /** Recommended badge for the default option; null for the rest. */
    @StringRes val badgeRes: Int? = null,
)

/**
 * Curated onboarding order: System default, AdGuard (Recommended / default), Cloudflare, Quad9,
 * Mullvad. Cloudflare uses the canonical [DnsProviderCloudflare] id (1.1.1.1, DoH host
 * `cloudflare-dns.com`) — NOT the bare-IP `cloudflare_ip` variant.
 */
internal val OnboardingDnsOptions: List<OnboardingDnsOption> =
    listOf(
        OnboardingDnsOption(
            providerId = OnboardingDnsSystemId,
            descriptionRes = R.string.onboarding_setup_dns_system_body,
        ),
        OnboardingDnsOption(
            providerId = DnsProviderAdGuard,
            descriptionRes = R.string.onboarding_setup_dns_adguard_body,
            badgeRes = R.string.onboarding_badge_recommended,
        ),
        OnboardingDnsOption(
            providerId = DnsProviderCloudflare,
            descriptionRes = R.string.onboarding_setup_dns_cloudflare_body,
        ),
        OnboardingDnsOption(
            providerId = DnsProviderQuad9,
            descriptionRes = R.string.onboarding_setup_dns_quad9_body,
        ),
        OnboardingDnsOption(
            providerId = DnsProviderMullvad,
            descriptionRes = R.string.onboarding_setup_dns_mullvad_body,
        ),
    )

/**
 * Precise, unambiguous encryption-protocol label for a curated DNS option (e.g. "DNS-over-HTTPS"),
 * resolved from the existing data layer — or `null` for the system option, which pins no resolver.
 *
 * Onboarding cards deliberately show ONLY this protocol label, never a resolver host/endpoint: a
 * bare host implies an endpoint it is not, and low-level host metadata over-loads the main list. The
 * full per-resolver address detail stays reachable via Advanced DNS settings. The names are
 * locale-invariant protocol proper nouns, so — like the data layer's
 * [com.poyka.ripdpi.data.protocolDisplayName] — they live in code, not string resources.
 */
internal fun onboardingDnsProtocolLabel(providerId: String): String? {
    if (providerId == OnboardingDnsSystemId) return null
    return dnsProviderById(providerId)?.let { def ->
        when (def.protocol) {
            EncryptedDnsProtocolDot -> "DNS-over-TLS"
            EncryptedDnsProtocolDoq -> "DNS-over-QUIC"
            EncryptedDnsProtocolDnsCrypt -> "DNSCrypt"
            EncryptedDnsProtocolOdoh -> "Oblivious DoH"
            else -> "DNS-over-HTTPS"
        }
    }
}
