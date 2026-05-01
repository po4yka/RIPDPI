use std::net::IpAddr;

use crate::cdn_ech::range_match::{Ipv4Cidr, Ipv6Cidr};

/// A hardcoded ECH configuration for a CDN provider.
#[derive(Debug)]
pub struct CdnEchConfig {
    /// Human-readable CDN name (for logging).
    pub provider: &'static str,
    /// IPv4 CIDR prefixes owned by this CDN.
    ipv4_ranges: &'static [Ipv4Cidr],
    /// IPv6 CIDR prefixes owned by this CDN.
    ipv6_ranges: &'static [Ipv6Cidr],
    /// Raw ECHConfigList bytes (wire format, as returned by DNS HTTPS records).
    pub ech_config_list: &'static [u8],
}

impl CdnEchConfig {
    /// Returns true if the given IP address falls within one of this CDN's
    /// known address ranges.
    pub fn contains_ip(&self, ip: IpAddr) -> bool {
        match ip {
            IpAddr::V4(v4) => self.ipv4_ranges.iter().any(|cidr| cidr.contains(v4)),
            IpAddr::V6(v6) => self.ipv6_ranges.iter().any(|cidr| cidr.contains(v6)),
        }
    }
}

/// Cloudflare ECHConfigList captured 2026-04-08.
///
/// Wire-format bytes from the `ech` SvcParam of `_dns.resolver.arpa` /
/// `cloudflare.com` HTTPS record. This config is Cloudflare's public ECH key
/// and is not secret. Runtime refresh uses `RemoteEchConfigSource`; rotate the
/// bundled fallback manually using the instructions in `cdn_ech.rs`.
pub(crate) static CLOUDFLARE_ECH_CONFIG_LIST: &[u8] = &[
    0x00, 0x45, 0xfe, 0x0d, 0x00, 0x41, 0x20, 0x00, 0x20, 0x00, 0x20, 0x6b, 0x84, 0x16, 0x6c, 0xb2, 0xdc, 0x0a, 0xd0,
    0x8a, 0x4b, 0x12, 0x0e, 0x1b, 0x4f, 0xe8, 0x85, 0x8a, 0xcd, 0xf7, 0x05, 0xfa, 0xfe, 0x55, 0x32, 0x48, 0x71, 0xe9,
    0x3e, 0x12, 0xb5, 0x5a, 0x3c, 0x00, 0x04, 0x00, 0x01, 0x00, 0x01, 0x00, 0x12, b'c', b'l', b'o', b'u', b'd', b'f',
    b'l', b'a', b'r', b'e', b'-', b'e', b'c', b'h', b'.', b'c', b'o', b'm', 0x00, 0x00,
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

static CDN_ECH_CONFIGS: &[&CdnEchConfig] = &[&CLOUDFLARE_ECH];

/// Look up a hardcoded ECH config for a domain that resolves to a known CDN IP.
pub fn opportunistic_ech_config_for_ip(ip: IpAddr) -> Option<&'static CdnEchConfig> {
    CDN_ECH_CONFIGS.iter().find(|config| config.contains_ip(ip)).copied()
}

pub fn opportunistic_ech_provider_for_ip(ip: IpAddr) -> Option<&'static str> {
    opportunistic_ech_config_for_ip(ip).map(|config| config.provider)
}
