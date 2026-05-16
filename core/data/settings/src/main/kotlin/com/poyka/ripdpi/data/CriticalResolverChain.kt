package com.poyka.ripdpi.data

/**
 * A single entry in the critical resolver chain.
 *
 * Only the fields needed for chain construction and Cloudflare-endpoint detection
 * are carried here; the full [DnsProviderDefinition] remains in core:data:model.
 */
data class CriticalResolverEntry(
    val resolverId: String,
    val host: String,
    val tlsServerName: String,
    val bootstrapIps: List<String>,
    val dohUrl: String?,
)

/**
 * Profile that controls whether Cloudflare DNS is permitted in the critical chain.
 *
 * [Default] is the safe baseline: Cloudflare DNS is excluded.
 * [CloudflareAllowed] is an explicit opt-in for callers that need it.
 */
enum class CriticalResolverProfile {
    Default,
    CloudflareAllowed,
}

private val cloudflareProviderIds = setOf(DnsProviderCloudflare, DnsProviderCloudflareIp)

private val DnsProviderDefinition.isCloudflareDns: Boolean
    get() = providerId in cloudflareProviderIds

/**
 * Builds and filters the critical resolver chain for a given [CriticalResolverProfile].
 */
object CriticalResolverChainBuilder {
    /**
     * Returns the ordered list of [CriticalResolverEntry] items for the given [profile].
     *
     * For [CriticalResolverProfile.Default] Cloudflare DNS entries are removed.
     * For [CriticalResolverProfile.CloudflareAllowed] all built-in providers are included.
     */
    fun build(profile: CriticalResolverProfile): List<CriticalResolverEntry> {
        val allowCloudflare = profile == CriticalResolverProfile.CloudflareAllowed
        val all = BuiltInDnsProviders.map { it.toCriticalResolverEntry() }
        return filterForCriticalPath(all, allowCloudflare = allowCloudflare)
    }

    /**
     * Filters [entries] for use on the critical DNS path.
     *
     * When [allowCloudflare] is false, any entry whose [CriticalResolverEntry.resolverId]
     * belongs to a Cloudflare provider is removed.
     */
    fun filterForCriticalPath(
        entries: List<CriticalResolverEntry>,
        allowCloudflare: Boolean,
    ): List<CriticalResolverEntry> =
        if (allowCloudflare) {
            entries
        } else {
            entries.filter { it.resolverId !in cloudflareProviderIds }
        }
}

private fun DnsProviderDefinition.toCriticalResolverEntry(): CriticalResolverEntry =
    CriticalResolverEntry(
        resolverId = providerId,
        host = host,
        tlsServerName = tlsServerName,
        bootstrapIps = bootstrapIps,
        dohUrl = dohUrl,
    )
