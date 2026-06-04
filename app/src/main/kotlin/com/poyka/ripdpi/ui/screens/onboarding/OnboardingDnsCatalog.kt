package com.poyka.ripdpi.ui.screens.onboarding

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.DnsProviderAdGuard
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderMullvad
import com.poyka.ripdpi.data.DnsProviderQuad9
import com.poyka.ripdpi.data.dnsProviderById
import com.poyka.ripdpi.data.protocolDisplayName
import java.net.URI

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
 * Resolves the technical metadata for a curated DNS option from the existing data layer (nothing is
 * hardcoded). Returns a `(protocol, endpointHost)` pair, e.g. `("DoH", "cloudflare-dns.com")`, or
 * `null` for the system option (which has no resolver definition).
 *
 * The endpoint host is parsed from the provider's [dohUrl] (the DoH endpoint host, NOT a bare IP),
 * falling back to the provider's [host] when no DoH URL is present.
 */
private fun onboardingDnsMetadataParts(providerId: String): Pair<String, String>? {
    if (providerId == OnboardingDnsSystemId) return null
    val def = dnsProviderById(providerId) ?: return null
    val endpointHost = def.dohUrl?.let { hostFromUrl(it) }?.takeIf { it.isNotBlank() } ?: def.host
    return protocolDisplayName(def.protocol) to endpointHost
}

private fun hostFromUrl(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

/**
 * Builds the formatted technical metadata line (`protocol · endpoint host`) for a curated DNS option
 * using the `onboarding_dns_meta` template, or `null` for the system option.
 *
 * The caller supplies a [getString] bridge (typically `context::getString` or a `stringResource`
 * lambda) so this stays free of an Android `Context` dependency and is unit-testable.
 */
internal fun onboardingDnsMetadataLine(
    providerId: String,
    getString: (Int, String, String) -> String,
): String? {
    val (protocol, host) = onboardingDnsMetadataParts(providerId) ?: return null
    return getString(R.string.onboarding_dns_meta, protocol, host)
}
