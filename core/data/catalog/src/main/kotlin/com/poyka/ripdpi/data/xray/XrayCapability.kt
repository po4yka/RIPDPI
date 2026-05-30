package com.poyka.ripdpi.data.xray

/**
 * User-facing capability labels for an Xray profile.
 *
 * The product surface never shows protocol jargon ("VLESS", "REALITY",
 * "xtls-rprx-vision", "xhttp") to the user. Instead an [XrayProfile] is
 * summarised into a small, stable set of capability claims phrased in terms of
 * what the connection *does for the user* — VPN privacy, relaying, tunnel
 * scope, anti-DPI masquerading, DNS protection.
 *
 * Each enum entry carries a [titleKey] / [descriptionKey] string-resource name
 * (resolved by the UI layer against `:app`'s `strings.xml`, all 7 locales) so
 * this module stays free of Android resource dependencies and remains unit
 * testable offline.
 *
 * This is a presentation contract, not a security guarantee: the labels are
 * derived purely from the *shape* of the profile RIPDPI knows how to render and
 * validate. A profile only earns a capability label when the underlying field
 * is actually present and validated.
 */
enum class XrayCapability(
    val titleKey: String,
    val descriptionKey: String,
) {
    /** Full-tunnel VPN privacy: all device traffic is carried through the tunnel. */
    VPN_PRIVACY(
        titleKey = "xray_capability_vpn_privacy_title",
        descriptionKey = "xray_capability_vpn_privacy_desc",
    ),

    /** Traffic is relayed through a remote endpoint rather than sent directly. */
    RELAY(
        titleKey = "xray_capability_relay_title",
        descriptionKey = "xray_capability_relay_desc",
    ),

    /** The connection masquerades to slip past deep-packet-inspection censors. */
    ANTI_DPI(
        titleKey = "xray_capability_anti_dpi_title",
        descriptionKey = "xray_capability_anti_dpi_desc",
    ),

    /** DNS lookups are resolved through the protected tunnel, not the local network. */
    DNS_PROTECTION(
        titleKey = "xray_capability_dns_protection_title",
        descriptionKey = "xray_capability_dns_protection_desc",
    ),

    /** UDP/QUIC traffic is carried, enabling modern apps and real-time media. */
    REALTIME_MEDIA(
        titleKey = "xray_capability_realtime_media_title",
        descriptionKey = "xray_capability_realtime_media_desc",
    ),
    ;

    companion object {
        /**
         * Derives the ordered, de-duplicated set of capability labels a
         * [profile] advertises. Order matches enum declaration order so the UI
         * renders a deterministic list.
         *
         * Rules (all derived from already-validated profile shape):
         * - [VPN_PRIVACY] and [RELAY] are always present: an Xray profile always
         *   relays through its remote outbound and is offered as a full-tunnel
         *   VPN mode.
         * - [ANTI_DPI] when the profile uses REALITY masquerading or the XHTTP
         *   masquerading transport.
         * - [DNS_PROTECTION] when the profile declares at least one upstream DNS
         *   server (so resolution rides the tunnel).
         * - [REALTIME_MEDIA] when the local inbound relays UDP.
         */
        fun forProfile(profile: XrayProfile): List<XrayCapability> {
            val result = mutableListOf(VPN_PRIVACY, RELAY)
            val o = profile.outbound
            val antiDpi =
                o.security == XrayProfile.Security.REALITY ||
                    o.network == XrayProfile.Network.XHTTP
            if (antiDpi) result += ANTI_DPI
            if (profile.dns.servers.isNotEmpty()) result += DNS_PROTECTION
            if (profile.inbound.udpEnabled) result += REALTIME_MEDIA
            return result
        }
    }
}
