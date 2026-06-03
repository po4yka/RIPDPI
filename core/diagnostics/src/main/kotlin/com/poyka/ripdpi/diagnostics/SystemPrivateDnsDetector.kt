package com.poyka.ripdpi.diagnostics

import android.net.LinkProperties
import android.os.Build

/**
 * Tri-state describing whether the Android system-level "Private DNS" feature
 * (Settings -> Network -> Private DNS) is currently configured with a resolver.
 *
 * This status is computed entirely Kotlin-side and is purely informational: it
 * is NOT a policy source for the VPN DNS interceptor. RIPDPI always programs its
 * own interceptor DNS for encrypted profiles, so system Private DNS never changes
 * how the VPN resolves names. The status only helps the user understand a possible
 * mismatch between what Android reports and what RIPDPI actually does.
 */
enum class SystemPrivateDnsStatus(
    val wireValue: String,
) {
    /** A system Private DNS resolver hostname is active on the current network. */
    PRESENT("system_private_dns_present"),

    /** No system Private DNS resolver is active; it is ignored for VPN policy regardless. */
    ABSENT("ignored_for_vpn_policy"),

    /** The system Private DNS state could not be read (missing API level, permission, or error). */
    UNKNOWN("unknown"),
    ;

    companion object {
        /** Resolves a status from its persisted [wireValue], falling back to [UNKNOWN]. */
        fun fromWireValue(value: String?): SystemPrivateDnsStatus =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * Maps a captured `privateDnsMode` string (as produced by the network snapshot /
 * fingerprint pipeline) to a [SystemPrivateDnsStatus].
 *
 * The pipeline reports `"system"` when no strict Private DNS resolver is active
 * (off / opportunistic), a resolver hostname when strict Private DNS is set, and
 * an empty string when the value is unavailable.
 *
 * @param privateDnsMode the captured value, e.g. `"system"`, `"dns.example.org"`, or `""`.
 */
fun mapPrivateDnsModeToStatus(privateDnsMode: String?): SystemPrivateDnsStatus {
    val normalized = privateDnsMode?.trim()
    return when {
        normalized.isNullOrBlank() -> SystemPrivateDnsStatus.UNKNOWN
        normalized.equals("system", ignoreCase = true) -> SystemPrivateDnsStatus.ABSENT
        else -> SystemPrivateDnsStatus.PRESENT
    }
}

/**
 * Injectable seam over the Android APIs that report system Private DNS state.
 *
 * Implemented by [AndroidSystemPrivateDnsStatusProvider] in production; the pure
 * mapping in [mapLinkPropertiesToStatus] is what the unit tests exercise so the
 * status logic can be verified without a device.
 */
interface SystemPrivateDnsStatusProvider {
    fun detect(linkProperties: LinkProperties?): SystemPrivateDnsStatus
}

/**
 * Maps the raw `LinkProperties.getPrivateDnsServerName()` value (and the absence
 * of API support) to a [SystemPrivateDnsStatus].
 *
 * @param privateDnsServerName the strict Private DNS hostname Android reports, or
 *   `null` when there is none / the value is unavailable.
 * @param apiSupported `false` when the running platform is below API 29 (the level
 *   at which `getPrivateDnsServerName()` is available); the status is [UNKNOWN] then.
 */
fun mapLinkPropertiesToStatus(
    privateDnsServerName: String?,
    apiSupported: Boolean,
): SystemPrivateDnsStatus =
    when {
        !apiSupported -> SystemPrivateDnsStatus.UNKNOWN
        privateDnsServerName.isNullOrBlank() -> SystemPrivateDnsStatus.ABSENT
        else -> SystemPrivateDnsStatus.PRESENT
    }

/**
 * Production [SystemPrivateDnsStatusProvider] backed by [LinkProperties].
 *
 * Reads `getPrivateDnsServerName()` (API 29+) and never logs the resolver
 * hostname. Any unexpected failure degrades to [SystemPrivateDnsStatus.UNKNOWN]
 * rather than throwing into the diagnostics capture path.
 */
class AndroidSystemPrivateDnsStatusProvider : SystemPrivateDnsStatusProvider {
    override fun detect(linkProperties: LinkProperties?): SystemPrivateDnsStatus =
        runCatching {
            val apiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val serverName =
                if (apiSupported) {
                    linkProperties?.privateDnsServerName
                } else {
                    null
                }
            mapLinkPropertiesToStatus(serverName, apiSupported)
        }.getOrDefault(SystemPrivateDnsStatus.UNKNOWN)
}
