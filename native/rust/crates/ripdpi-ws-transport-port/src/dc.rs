use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// Date of the last review of the IPv4 DC range table below.
///
/// Reviewed quarterly per `docs/strategy-pack-operations.md` section
/// "Telegram DC table review". When updating the table, bump this constant and
/// update the `dc_ipv4_table_provenance` test.
pub const TELEGRAM_DC_IPV4_TABLE_LAST_REVIEWED: &str = "2026-05-15";

/// Authoritative source for the Telegram DC IP table.
pub const TELEGRAM_DC_IPV4_TABLE_SOURCE: &str = "https://core.telegram.org";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TelegramDcClass {
    Production,
    Test,
    MediaOrCdn,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TelegramDc {
    number: u8,
    raw: i32,
    class: TelegramDcClass,
}

impl TelegramDc {
    pub const fn production(number: u8) -> Self {
        Self { number, raw: number as i32, class: TelegramDcClass::Production }
    }

    pub const fn number(self) -> u8 {
        self.number
    }

    pub const fn raw(self) -> i32 {
        self.raw
    }

    pub const fn class(self) -> TelegramDcClass {
        self.class
    }

    pub const fn is_tunnelable(self) -> bool {
        !matches!(self.class, TelegramDcClass::MediaOrCdn)
    }

    pub fn ws_host(self) -> Option<String> {
        match self.class {
            TelegramDcClass::Production => Some(format!("kws{}.web.telegram.org", self.number)),
            TelegramDcClass::Test => Some(format!("kws{}-test.web.telegram.org", self.number)),
            TelegramDcClass::MediaOrCdn => None,
        }
    }

    pub fn ws_url(self) -> Option<String> {
        self.ws_host().map(|host| format!("wss://{host}/apiws"))
    }

    pub fn from_raw(raw: i32) -> Option<Self> {
        if (1..=5).contains(&raw) {
            return Some(Self { number: raw as u8, raw, class: TelegramDcClass::Production });
        }
        if (10_001..=10_005).contains(&raw) {
            return Some(Self { number: (raw - 10_000) as u8, raw, class: TelegramDcClass::Test });
        }
        if (-5..=-1).contains(&raw) {
            return Some(Self { number: raw.unsigned_abs() as u8, raw, class: TelegramDcClass::MediaOrCdn });
        }
        None
    }
}

/// Map a known Telegram IPv4 address to its production DC.
pub fn dc_from_ip(ip: Ipv4Addr) -> Option<TelegramDc> {
    let octets = ip.octets();
    let number = match (octets[0], octets[1]) {
        (149, 154) => Some(match octets[2] {
            160..=163 => 1,
            164..=167 => 2,
            168..=171 => 3,
            172..=175 => 1,
            _ => 2,
        }),
        (91, 108) => Some(match octets[2] {
            56..=59 => 5,
            8..=11 => 3,
            12..=15 => 4,
            _ => 2,
        }),
        (91, 105) | (185, 76) => Some(2),
        _ => None,
    }?;
    Some(TelegramDc::production(number))
}

/// Map a known Telegram IPv6 address to its production DC.
///
/// The recognised supernets come from Telegram's published infrastructure
/// documentation at `https://core.telegram.org`:
///
/// - `2001:67c:4e8::/48` - Amsterdam (DC2/DC4 prefix)
/// - `2001:b28:f23c::/46` - Miami/Singapore (DC1/DC3/DC5 family)
///
/// Mapping is intentionally conservative: a Telegram-owned supernet is mapped
/// to one representative production DC, matching the IPv4 fallback for
/// unmatched sub-prefixes. IPv4 and IPv6 share the quarterly review obligation
/// tracked by [`TELEGRAM_DC_IPV4_TABLE_LAST_REVIEWED`].
pub fn dc_from_ipv6(ip: Ipv6Addr) -> Option<TelegramDc> {
    let segments = ip.segments();
    if segments[0] == 0x2001 && segments[1] == 0x067c && segments[2] == 0x04e8 {
        return Some(TelegramDc::production(2));
    }
    if segments[0] == 0x2001 && segments[1] == 0x0b28 && (0xf23c..=0xf23f).contains(&segments[2]) {
        return Some(TelegramDc::production(3));
    }
    None
}

/// Check whether an IP address belongs to a known Telegram DC range.
pub fn is_telegram_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => dc_from_ip(ip).is_some(),
        IpAddr::V6(ip) => dc_from_ipv6(ip).is_some(),
    }
}

pub fn ws_host(dc: TelegramDc) -> Option<String> {
    dc.ws_host()
}

pub fn ws_url(dc: TelegramDc) -> Option<String> {
    dc.ws_url()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_raw_dc_encodings() {
        assert_eq!(TelegramDc::from_raw(3), Some(TelegramDc::production(3)));
        assert_eq!(
            TelegramDc::from_raw(10_002),
            Some(TelegramDc { number: 2, raw: 10_002, class: TelegramDcClass::Test }),
        );
        assert_eq!(
            TelegramDc::from_raw(-4),
            Some(TelegramDc { number: 4, raw: -4, class: TelegramDcClass::MediaOrCdn }),
        );
        assert_eq!(TelegramDc::from_raw(0), None);
        assert_eq!(TelegramDc::from_raw(6), None);
        assert_eq!(TelegramDc::from_raw(-6), None);
    }

    #[test]
    fn tunnelability_matches_dc_class() {
        assert!(TelegramDc::production(2).is_tunnelable());
        assert!(TelegramDc::from_raw(10_004).expect("test dc").is_tunnelable());
        assert!(!TelegramDc::from_raw(-5).expect("media dc").is_tunnelable());
    }

    #[test]
    fn websocket_host_mapping_matches_dc_class() {
        assert_eq!(TelegramDc::production(1).ws_host().as_deref(), Some("kws1.web.telegram.org"));
        assert_eq!(
            TelegramDc::from_raw(10_005).expect("test dc").ws_host().as_deref(),
            Some("kws5-test.web.telegram.org"),
        );
        assert_eq!(TelegramDc::from_raw(-2).expect("media dc").ws_host(), None);
    }

    #[test]
    fn websocket_url_mapping_matches_dc_class() {
        assert_eq!(TelegramDc::production(4).ws_url().as_deref(), Some("wss://kws4.web.telegram.org/apiws"));
        assert_eq!(
            TelegramDc::from_raw(10_001).expect("test dc").ws_url().as_deref(),
            Some("wss://kws1-test.web.telegram.org/apiws"),
        );
        assert_eq!(TelegramDc::from_raw(-1).expect("media dc").ws_url(), None);
    }

    #[test]
    fn dc_from_known_ips() {
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 160, 1)), Some(TelegramDc::production(1)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 165, 10)), Some(TelegramDc::production(2)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 170, 5)), Some(TelegramDc::production(3)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 108, 56, 100)), Some(TelegramDc::production(5)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 108, 13, 1)), Some(TelegramDc::production(4)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(185, 76, 151, 1)), Some(TelegramDc::production(2)));
    }

    #[test]
    fn non_telegram_ips_return_none() {
        assert_eq!(dc_from_ip(Ipv4Addr::new(8, 8, 8, 8)), None);
        assert_eq!(dc_from_ip(Ipv4Addr::new(1, 1, 1, 1)), None);
        assert_eq!(dc_from_ip(Ipv4Addr::new(192, 168, 0, 1)), None);
    }

    #[test]
    fn dc_from_ip_falls_back_to_dc2_for_unmatched_telegram_ranges() {
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 200, 1)), Some(TelegramDc::production(2)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 108, 100, 1)), Some(TelegramDc::production(2)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 105, 1, 1)), Some(TelegramDc::production(2)));
    }

    #[test]
    fn is_telegram_ip_v6_recognises_known_supernets() {
        for ip in ["2001:67c:4e8::1", "2001:b28:f23c::1", "2001:b28:f23f::dead"] {
            assert!(is_telegram_ip(IpAddr::V6(ip.parse().expect("parse Telegram v6"))));
        }
        for ip in ["::1", "2001:4860:4860::8888", "2606:4700:4700::1111"] {
            assert!(!is_telegram_ip(IpAddr::V6(ip.parse().expect("parse non-Telegram v6"))));
        }
    }

    #[test]
    fn dc_from_ipv6_returns_none_for_unrelated_supernets() {
        let unrelated: Ipv6Addr = "2001:b28:f240::1".parse().expect("parse v6");
        assert_eq!(dc_from_ipv6(unrelated), None);
    }

    #[test]
    fn dc_from_ip_boundary_first_and_last_in_range() {
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 160, 0)), Some(TelegramDc::production(1)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 163, 255)), Some(TelegramDc::production(1)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 164, 0)), Some(TelegramDc::production(2)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 167, 255)), Some(TelegramDc::production(2)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 168, 0)), Some(TelegramDc::production(3)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 171, 255)), Some(TelegramDc::production(3)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 108, 56, 0)), Some(TelegramDc::production(5)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(91, 108, 59, 255)), Some(TelegramDc::production(5)));
    }

    #[test]
    fn dc_from_ip_supports_alternate_dc1_range() {
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 172, 0)), Some(TelegramDc::production(1)));
        assert_eq!(dc_from_ip(Ipv4Addr::new(149, 154, 175, 255)), Some(TelegramDc::production(1)));
    }

    #[test]
    fn dc_ipv4_table_provenance() {
        assert!(TELEGRAM_DC_IPV4_TABLE_LAST_REVIEWED.starts_with("202"));
        assert_eq!(TELEGRAM_DC_IPV4_TABLE_LAST_REVIEWED.len(), 10, "review date must be YYYY-MM-DD");
        assert!(TELEGRAM_DC_IPV4_TABLE_SOURCE.starts_with("https://core.telegram.org"));
    }
}
