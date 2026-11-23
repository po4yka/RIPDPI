package com.poyka.ripdpi.diagnostics

import android.net.LinkProperties
import android.os.Build

/**
 * Tri-state describing whether the Android system-level "Private DNS" feature
 * (Settings -> Network -> Private DNS) is currently configured with a resolver.
 *
 * This status is computed entirely Kotlin-side and is informational: it is NOT a
 * policy source for the VPN DNS interceptor. It does not prove that the interceptor
 * sees every lookup. Android pushes the Private DNS mode to the VPN network too
 * (AOSP DnsManager), so in strict mode the system stub resolver sends DNS-over-TLS
 * to the configured hostname through the tunnel and never queries the interceptor
 * address; domain-based rules and host mapping then do not apply to those lookups.
 * The wire values below are persisted and keep their historical names.
 */
enum class SystemPrivateDnsStatus(
    val wireValue: String,
) {
    /** A system Private DNS resolver hostname is active on the current network. */
    PRESENT("system_private_dns_present"),

    /** No system Private DNS resolver is active on the current network. */
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
 * A resolver hostname or `"strict"` signals strict Private DNS.
 * Non-strict modes do not carry a configured resolver hostname.
 *
 * @param privateDnsMode the captured value, e.g. `"system"`, `"dns.example.org"`, or `""`.
 */
fun mapPrivateDnsModeToStatus(privateDnsMode: String?): SystemPrivateDnsStatus =
    when (privateDnsMode?.trim()?.lowercase()) {
        null, "", "unknown", "unavailable" -> SystemPrivateDnsStatus.UNKNOWN
        "system", "off", "none", "opportunistic" -> SystemPrivateDnsStatus.ABSENT
        else -> SystemPrivateDnsStatus.PRESENT
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
 * @param apiSupported `false` when the running platform is below API 28 (the level
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
 * Reads `getPrivateDnsServerName()` (API 28+) and never logs the resolver
 * hostname. Any unexpected failure degrades to [SystemPrivateDnsStatus.UNKNOWN]
 * rather than throwing into the diagnostics capture path.
 */
class AndroidSystemPrivateDnsStatusProvider : SystemPrivateDnsStatusProvider {
    override fun detect(linkProperties: LinkProperties?): SystemPrivateDnsStatus =
        if (linkProperties == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            SystemPrivateDnsStatus.UNKNOWN
        } else {
            runCatching {
                mapLinkPropertiesToStatus(linkProperties.privateDnsServerName, apiSupported = true)
            }.getOrDefault(SystemPrivateDnsStatus.UNKNOWN)
        }
}
