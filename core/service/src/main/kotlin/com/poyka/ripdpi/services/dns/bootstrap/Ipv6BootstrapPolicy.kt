package com.poyka.ripdpi.services.dns.bootstrap

import com.poyka.ripdpi.data.Ipv6Policy

/**
 * Translates an [Ipv6Policy] into a bootstrap-resolution AAAA permission.
 *
 * AAAA queries are permitted only for dual-stack modes ([Ipv6Policy.ALLOW],
 * [Ipv6Policy.PREFER]). All other modes suppress AAAA at bootstrap time.
 *
 * @param mode The active IPv6 policy for the current profile.
 */
class Ipv6BootstrapPolicy(
    private val mode: Ipv6Policy,
) {
    /**
     * Returns true when AAAA bootstrap queries are permitted under [mode].
     *
     * Only [Ipv6Policy.ALLOW] and [Ipv6Policy.PREFER] return true; every
     * other policy value returns false.
     */
    fun isAaaaPermitted(): Boolean =
        when (mode) {
            Ipv6Policy.ALLOW, Ipv6Policy.PREFER -> true
            Ipv6Policy.IPV4_ONLY, Ipv6Policy.BLOCK, Ipv6Policy.NATIVE, Ipv6Policy.TRANSLATED -> false
        }
}
