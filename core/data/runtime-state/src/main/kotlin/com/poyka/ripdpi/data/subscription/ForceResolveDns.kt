package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Outcome of a single host lookup performed by [ForceResolveDns]. */
sealed interface HostResolution {
    /**
     * The host resolved to one or more [addresses]. Address order is the
     * resolver's; [ForceResolveDns] rewrites the profile to the first entry. An
     * empty list is treated as a [Failed] lookup.
     */
    data class Resolved(
        val addresses: List<String>,
    ) : HostResolution

    /** The host could not be resolved; [reason] is a short, non-secret diagnostic string. */
    data class Failed(
        val reason: String,
    ) : HostResolution
}

/**
 * Force-resolves subscription profile hostnames to IP literals at refresh time.
 *
 * This is NekoBox's `GroupUpdater.forceResolve()` ported to Kotlin coroutines:
 * NekoBox uses `Executors.newFixedThreadPool(5)`; RIPDPI uses a coroutine
 * [Semaphore] with 5 permits so at most 5 lookups run in parallel. Pre-resolving
 * avoids relying on the runtime DNS path for nodes whose DNS is flaky.
 *
 * Per profile:
 * - A profile whose `server` is already an IP literal (v4 or v6) is left
 *   untouched and is **not** looked up.
 * - A [HostResolution.Resolved] with a non-empty address list rewrites the
 *   profile's server (and any SNI-ish field) to the first resolved address.
 * - A [HostResolution.Failed] — or a `Resolved` with an empty address list —
 *   leaves the profile unchanged; a flaky lookup never drops a node.
 * - Profiles with no addressable host (e.g. [ProxyProfile.RawConfig]) pass
 *   through untouched.
 */
object ForceResolveDns {
    /** NekoBox-equivalent of `newFixedThreadPool(5)`. */
    private const val MAX_PARALLEL_LOOKUPS = 5

    private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * Resolves every profile in [profiles] concurrently (bounded to
     * [MAX_PARALLEL_LOOKUPS] in-flight lookups) using [resolve], and returns the
     * profile list with resolvable hostnames rewritten to IP literals. Order is
     * preserved. An empty input is a no-op.
     */
    suspend fun resolveAll(
        profiles: List<ProxyProfile>,
        resolve: suspend (host: String) -> HostResolution,
    ): List<ProxyProfile> {
        if (profiles.isEmpty()) return emptyList()
        val gate = Semaphore(MAX_PARALLEL_LOOKUPS)
        return coroutineScope {
            profiles
                .map { profile ->
                    async {
                        val host = addressableHost(profile)
                        if (host == null || isIpLiteral(host)) {
                            profile
                        } else {
                            val resolution = gate.withPermit { resolve(host) }
                            applyResolution(profile, resolution)
                        }
                    }
                }.awaitAll()
        }
    }

    /** The hostname a force-resolve should rewrite, or `null` for non-addressed profiles. */
    private fun addressableHost(profile: ProxyProfile): String? =
        when (profile) {
            is ProxyProfile.Vless -> profile.server
            is ProxyProfile.VlessReality -> profile.server
            is ProxyProfile.Shadowsocks -> profile.server
            is ProxyProfile.Trojan -> profile.server
            is ProxyProfile.Hysteria2 -> profile.server
            is ProxyProfile.AnyTls -> profile.server
            is ProxyProfile.TrojanGo -> profile.server
            is ProxyProfile.Mieru -> profile.server
            is ProxyProfile.HysteriaV1 -> profile.server
            is ProxyProfile.Vmess -> profile.server
            is ProxyProfile.RawConfig -> null
        }

    private fun applyResolution(
        profile: ProxyProfile,
        resolution: HostResolution,
    ): ProxyProfile {
        val resolvedAddress =
            when (resolution) {
                is HostResolution.Resolved -> resolution.addresses.firstOrNull()
                is HostResolution.Failed -> null
            } ?: return profile
        return when (profile) {
            is ProxyProfile.Vless -> profile.copy(server = resolvedAddress)
            is ProxyProfile.VlessReality -> profile.copy(server = resolvedAddress)
            is ProxyProfile.Shadowsocks -> profile.copy(server = resolvedAddress)
            is ProxyProfile.Trojan -> profile.copy(server = resolvedAddress)
            is ProxyProfile.Hysteria2 -> profile.copy(server = resolvedAddress)
            is ProxyProfile.AnyTls -> profile.copy(server = resolvedAddress)
            is ProxyProfile.TrojanGo -> profile.copy(server = resolvedAddress)
            is ProxyProfile.Mieru -> profile.copy(server = resolvedAddress)
            is ProxyProfile.HysteriaV1 -> profile.copy(server = resolvedAddress)
            is ProxyProfile.Vmess -> profile.copy(server = resolvedAddress)
            is ProxyProfile.RawConfig -> profile
        }
    }

    /** `true` when [host] is already an IPv4 or IPv6 literal — no lookup needed. */
    private fun isIpLiteral(host: String): Boolean = IPV4_REGEX.matches(host) || host.contains(':')
}
