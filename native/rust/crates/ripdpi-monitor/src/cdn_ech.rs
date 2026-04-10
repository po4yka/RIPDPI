//! Opportunistic ECH (Encrypted Client Hello) configs for known CDN providers.
//!
//! When DNS HTTPS record resolution fails (e.g. because encrypted DNS is
//! blocked), we fall back to hardcoded ECH configs for CDN providers that are
//! known to support ECH.  The configs are best-effort: if the server rejects
//! them (key rotation, etc.) the caller falls back to normal TLS.
//!
//! # Maintenance
//!
//! Cloudflare rotates ECH keys periodically.  The hardcoded config below was
//! captured from `dig +short type65 cloudflare.com` and should be refreshed
//! when the VPN app catalog is updated (see `VpnAppCatalogUpdater`).
//!
//! To obtain a fresh config:
//! ```text
//! dig +short type65 cloudflare.com | grep -oP 'ech=\K[^ ]+'
//! ```
//! Then base64-decode the value and replace `CLOUDFLARE_ECH_CONFIG_LIST`.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// A hardcoded ECH configuration for a CDN provider.
#[derive(Debug)]
pub(crate) struct CdnEchConfig {
    /// Human-readable CDN name (for logging).
    pub(crate) provider: &'static str,
    /// IPv4 CIDR prefixes owned by this CDN.
    ipv4_ranges: &'static [Ipv4Cidr],
    /// IPv6 CIDR prefixes owned by this CDN.
    ipv6_ranges: &'static [Ipv6Cidr],
    /// Raw ECHConfigList bytes (wire format, as returned by DNS HTTPS records).
    pub(crate) ech_config_list: &'static [u8],
}

#[derive(Debug)]
struct Ipv4Cidr {
    network: u32,
    prefix_len: u8,
}

#[derive(Debug)]
struct Ipv6Cidr {
    network: u128,
    prefix_len: u8,
}

impl Ipv4Cidr {
    const fn new(a: u8, b: u8, c: u8, d: u8, prefix_len: u8) -> Self {
        let network = ((a as u32) << 24) | ((b as u32) << 16) | ((c as u32) << 8) | (d as u32);
        Self { network, prefix_len }
    }

    fn contains(&self, addr: Ipv4Addr) -> bool {
        let bits: u32 = addr.into();
        let mask = if self.prefix_len == 0 { 0 } else { !0u32 << (32 - self.prefix_len) };
        (bits & mask) == (self.network & mask)
    }
}

impl Ipv6Cidr {
    const fn new(segments: [u16; 8], prefix_len: u8) -> Self {
        let mut network: u128 = 0;
        let mut i = 0;
        while i < 8 {
            network |= (segments[i] as u128) << (112 - 16 * i);
            i += 1;
        }
        Self { network, prefix_len }
    }

    fn contains(&self, addr: Ipv6Addr) -> bool {
        let bits: u128 = addr.into();
        let mask = if self.prefix_len == 0 { 0 } else { !0u128 << (128 - self.prefix_len) };
        (bits & mask) == (self.network & mask)
    }
}

impl CdnEchConfig {
    /// Returns true if the given IP address falls within one of this CDN's
    /// known address ranges.
    pub(crate) fn contains_ip(&self, ip: IpAddr) -> bool {
        match ip {
            IpAddr::V4(v4) => self.ipv4_ranges.iter().any(|cidr| cidr.contains(v4)),
            IpAddr::V6(v6) => self.ipv6_ranges.iter().any(|cidr| cidr.contains(v6)),
        }
    }
}

// ---------------------------------------------------------------------------
// Cloudflare ECH configuration
// ---------------------------------------------------------------------------

/// Cloudflare ECHConfigList captured 2026-04-08.
///
/// Wire-format bytes from the `ech` SvcParam of `_dns.resolver.arpa` /
/// `cloudflare.com` HTTPS record.  This is the raw ECHConfigList (not
/// base64-encoded).
///
/// Structure (RFC 9460 / draft-ietf-tls-esni):
///   - 2 bytes: total length of the config list
///   - ECHConfig entries (version 0xfe0d, KEM x25519, HKDF-SHA256, AES-128-GCM)
///
/// This config is Cloudflare's public ECH key and is not secret.  It is
/// published in DNS for anyone to use.
// TODO(npochaev): add a periodic refresh mechanism (see VpnAppCatalogUpdater)
// so the hardcoded config stays current after key rotations.
static CLOUDFLARE_ECH_CONFIG_LIST: &[u8] = &[
    // ECHConfigList length: 0x0045 (69 bytes)
    0x00, 0x45, // ECHConfig version: 0xfe0d (draft/RFC)
    0xfe, 0x0d, // ECHConfig length: 0x0041 (65 bytes)
    0x00, 0x41, // config_id: 0x20
    0x20, // KEM id: 0x0020 (DHKEM(X25519, HKDF-SHA256))
    0x00, 0x20, // public_key length: 0x0020 (32 bytes)
    0x00, 0x20, // X25519 public key (Cloudflare's published key, 32 bytes)
    0x6b, 0x84, 0x16, 0x6c, 0xb2, 0xdc, 0x0a, 0xd0, 0x8a, 0x4b, 0x12, 0x0e, 0x1b, 0x4f, 0xe8, 0x85, 0x8a, 0xcd, 0xf7,
    0x05, 0xfa, 0xfe, 0x55, 0x32, 0x48, 0x71, 0xe9, 0x3e, 0x12, 0xb5, 0x5a, 0x3c,
    // cipher_suites length: 0x0004 (4 bytes = 1 suite)
    0x00, 0x04, // HKDF-SHA256 (0x0001) + AES-128-GCM (0x0001)
    0x00, 0x01, 0x00, 0x01, // maximum_name_length: 0x00 (0)
    0x00, // public_name length: 0x12 (18 bytes = "cloudflare-ech.com")
    0x12, // public_name: "cloudflare-ech.com"
    b'c', b'l', b'o', b'u', b'd', b'f', b'l', b'a', b'r', b'e', b'-', b'e', b'c', b'h', b'.', b'c', b'o', b'm',
    // extensions length: 0x0000
    0x00, 0x00,
];

/// Cloudflare IPv4 ranges (major allocations, not exhaustive).
/// Source: <https://www.cloudflare.com/ips-v4/>
static CLOUDFLARE_IPV4_RANGES: &[Ipv4Cidr] = &[
    Ipv4Cidr::new(103, 21, 244, 0, 22),
    Ipv4Cidr::new(103, 22, 200, 0, 22),
    Ipv4Cidr::new(103, 31, 4, 0, 22),
    Ipv4Cidr::new(104, 16, 0, 0, 13),
    Ipv4Cidr::new(104, 24, 0, 0, 14),
    Ipv4Cidr::new(108, 162, 192, 0, 18),
    Ipv4Cidr::new(131, 0, 72, 0, 22),
    Ipv4Cidr::new(141, 101, 64, 0, 18),
    Ipv4Cidr::new(162, 158, 0, 0, 15),
    Ipv4Cidr::new(172, 64, 0, 0, 13),
    Ipv4Cidr::new(173, 245, 48, 0, 20),
    Ipv4Cidr::new(188, 114, 96, 0, 20),
    Ipv4Cidr::new(190, 93, 240, 0, 20),
    Ipv4Cidr::new(197, 234, 240, 0, 22),
    Ipv4Cidr::new(198, 41, 128, 0, 17),
];

/// Cloudflare IPv6 ranges (major allocations).
/// Source: <https://www.cloudflare.com/ips-v6/>
static CLOUDFLARE_IPV6_RANGES: &[Ipv6Cidr] = &[
    Ipv6Cidr::new([0x2400, 0xcb00, 0, 0, 0, 0, 0, 0], 32),
    Ipv6Cidr::new([0x2405, 0x8100, 0, 0, 0, 0, 0, 0], 32),
    Ipv6Cidr::new([0x2405, 0xb500, 0, 0, 0, 0, 0, 0], 32),
    Ipv6Cidr::new([0x2606, 0x4700, 0, 0, 0, 0, 0, 0], 32),
    Ipv6Cidr::new([0x2803, 0xf800, 0, 0, 0, 0, 0, 0], 32),
    Ipv6Cidr::new([0x2a06, 0x98c0, 0, 0, 0, 0, 0, 0], 29),
    Ipv6Cidr::new([0x2c0f, 0xf248, 0, 0, 0, 0, 0, 0], 32),
];

static CLOUDFLARE_ECH: CdnEchConfig = CdnEchConfig {
    provider: "Cloudflare",
    ipv4_ranges: CLOUDFLARE_IPV4_RANGES,
    ipv6_ranges: CLOUDFLARE_IPV6_RANGES,
    ech_config_list: CLOUDFLARE_ECH_CONFIG_LIST,
};

/// All known CDN ECH configs.
static CDN_ECH_CONFIGS: &[&CdnEchConfig] = &[&CLOUDFLARE_ECH];

/// Look up a hardcoded ECH config for a domain that resolves to a known CDN IP.
///
/// Returns `Some` if `ip` falls within a recognised CDN range that publishes
/// ECH configs.  The caller should attempt ECH with the returned config and
/// fall back to normal TLS if the handshake is rejected (the hardcoded config
/// may be stale after a key rotation).
pub(crate) fn opportunistic_ech_config_for_ip(ip: IpAddr) -> Option<&'static CdnEchConfig> {
    CDN_ECH_CONFIGS.iter().find(|cfg| cfg.contains_ip(ip)).copied()
}

pub(crate) fn opportunistic_ech_provider_for_ip(ip: IpAddr) -> Option<&'static str> {
    opportunistic_ech_config_for_ip(ip).map(|config| config.provider)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cloudflare_ipv4_match() {
        let ip: IpAddr = "104.16.1.1".parse().unwrap();
        let config = opportunistic_ech_config_for_ip(ip);
        assert!(config.is_some(), "expected Cloudflare match for 104.16.1.1");
        assert_eq!(config.unwrap().provider, "Cloudflare");
    }

    #[test]
    fn cloudflare_ipv4_boundary() {
        // 104.16.0.0/13 covers 104.16.0.0 - 104.23.255.255
        let inside: IpAddr = "104.23.255.255".parse().unwrap();
        assert!(opportunistic_ech_config_for_ip(inside).is_some());

        let outside: IpAddr = "104.24.128.0".parse().unwrap();
        // 104.24.0.0/14 covers 104.24.0.0 - 104.27.255.255, so this is inside
        assert!(opportunistic_ech_config_for_ip(outside).is_some());
    }

    #[test]
    fn cloudflare_ipv6_match() {
        let ip: IpAddr = "2606:4700::1".parse().unwrap();
        let config = opportunistic_ech_config_for_ip(ip);
        assert!(config.is_some(), "expected Cloudflare match for 2606:4700::1");
    }

    #[test]
    fn non_cloudflare_ip_returns_none() {
        let ip: IpAddr = "8.8.8.8".parse().unwrap();
        assert!(opportunistic_ech_config_for_ip(ip).is_none());
    }

    #[test]
    fn non_cloudflare_ipv6_returns_none() {
        let ip: IpAddr = "2001:4860:4860::8888".parse().unwrap();
        assert!(opportunistic_ech_config_for_ip(ip).is_none());
    }

    #[test]
    fn ech_config_list_has_valid_prefix() {
        // First two bytes are the list length
        assert!(CLOUDFLARE_ECH_CONFIG_LIST.len() >= 4);
        let list_len = u16::from_be_bytes([CLOUDFLARE_ECH_CONFIG_LIST[0], CLOUDFLARE_ECH_CONFIG_LIST[1]]) as usize;
        assert_eq!(list_len + 2, CLOUDFLARE_ECH_CONFIG_LIST.len(), "ECHConfigList length mismatch");
        // Version should be 0xfe0d
        assert_eq!(&CLOUDFLARE_ECH_CONFIG_LIST[2..4], &[0xfe, 0x0d]);
    }

    #[test]
    fn ipv4_cidr_contains_basic() {
        let cidr = Ipv4Cidr::new(10, 0, 0, 0, 8);
        assert!(cidr.contains(Ipv4Addr::new(10, 0, 0, 1)));
        assert!(cidr.contains(Ipv4Addr::new(10, 255, 255, 255)));
        assert!(!cidr.contains(Ipv4Addr::new(11, 0, 0, 1)));
    }

    #[test]
    fn ipv6_cidr_contains_basic() {
        let cidr = Ipv6Cidr::new([0x2606, 0x4700, 0, 0, 0, 0, 0, 0], 32);
        assert!(cidr.contains(Ipv6Addr::new(0x2606, 0x4700, 0, 0, 0, 0, 0, 1)));
        assert!(cidr.contains(Ipv6Addr::new(0x2606, 0x4700, 0xffff, 0, 0, 0, 0, 0)));
        assert!(!cidr.contains(Ipv6Addr::new(0x2606, 0x4701, 0, 0, 0, 0, 0, 0)));
    }
}
