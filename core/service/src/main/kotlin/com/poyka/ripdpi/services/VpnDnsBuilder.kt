package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DnsModeEncrypted

/**
 * DNS address injected into the VPN tunnel for encrypted-mode profiles.
 *
 * This is the internal map-DNS intercept address that tun2socks forwards
 * all DNS queries through; the interceptor then applies [SplitStrictDnsPolicy]
 * routing before forwarding to the appropriate resolver plane.
 */
internal const val VpnInterceptorDnsAddress = "198.18.0.53"

/**
 * Returns the DNS address that must be programmed into the VPN tunnel.
 *
 * For secure (encrypted) profiles the interceptor address is always returned
 * so DNS cannot leak to the underlying network resolver. For plain-text
 * profiles the configured DNS IP is used directly.
 *
 * The result is guaranteed to be non-blank for encrypted profiles so that
 * callers can always call [android.net.VpnService.Builder.addDnsServer].
 */
internal fun vpnDnsAddressForProfile(
    dnsMode: String,
    plainDnsIp: String,
): String = if (dnsMode == DnsModeEncrypted) VpnInterceptorDnsAddress else plainDnsIp

/**
 * Returns true when [dnsMode] identifies a secure (encrypted) VPN profile
 * that must use the interceptor DNS and may never fall back to the system resolver.
 */
internal fun isSecureVpnDnsProfile(dnsMode: String): Boolean = dnsMode == DnsModeEncrypted
