//! Strict numeric CIDR input for configuration boundaries.

use std::fmt;
use std::net::IpAddr;

use ipnet::IpNet;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InvalidCidr;

impl fmt::Display for InvalidCidr {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("invalid numeric address or CIDR prefix")
    }
}

impl std::error::Error for InvalidCidr {}

/// Parse without DNS or trimming. Defaults apply only when the slash is absent.
/// The returned address retains host bits; use `network()` for routing rules.
pub fn parse_cidr(value: &str, defaults: Option<(u8, u8)>) -> Result<IpNet, InvalidCidr> {
    let (address, prefix) = value.split_once('/').map_or((value, None), |(address, prefix)| (address, Some(prefix)));
    let address: IpAddr = address.parse().map_err(|_| InvalidCidr)?;
    let prefix = match prefix {
        Some(prefix) => {
            if prefix.is_empty()
                || !prefix.bytes().all(|byte| byte.is_ascii_digit())
                || (prefix.len() > 1 && prefix.starts_with('0'))
            {
                return Err(InvalidCidr);
            }
            prefix.parse().map_err(|_| InvalidCidr)?
        }
        None => {
            let (v4, v6) = defaults.ok_or(InvalidCidr)?;
            if address.is_ipv4() { v4 } else { v6 }
        }
    };
    IpNet::new(address, prefix).map_err(|_| InvalidCidr)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_host_bits_and_family_with_separate_network_projection() {
        let net = parse_cidr("192.0.2.129/24", None).unwrap();
        assert_eq!(net.addr().to_string(), "192.0.2.129");
        assert_eq!(net.network().to_string(), "192.0.2.0");
        let mapped = parse_cidr("::ffff:192.0.2.129/120", None).unwrap();
        assert!(mapped.addr().is_ipv6());
        assert!(mapped.contains(&"::ffff:192.0.2.1".parse::<IpAddr>().unwrap()));
        assert!(!mapped.contains(&"192.0.2.129".parse::<IpAddr>().unwrap()));
    }

    #[test]
    fn rejects_noncanonical_syntax_and_out_of_range_prefixes() {
        for value in [
            "192.0.2.1/",
            "192.0.2.1/+24",
            "192.0.2.1/024",
            "192.0.2.1/-1",
            "192.0.2.1/33",
            "::1/129",
            "::1/256",
            "::1/64/64",
            "::1/６４",
            "01.2.3.4/24",
            "127.1/8",
            "example.com/24",
            "fe80::1%eth0/64",
            ":1::2/64",
            "1::2:/64",
            " ::1/64",
            "::1/64 ",
        ] {
            assert!(parse_cidr(value, Some((24, 128))).is_err(), "{value}");
        }
        assert!(parse_cidr("::1", None).is_err());
    }

    #[test]
    fn defaults_and_zero_full_width_and_partial_prefixes() {
        for (value, prefix) in [
            ("192.0.2.129", 24),
            ("2001:db8::1", 128),
            ("0.0.0.0/0", 0),
            ("::/0", 0),
            ("192.0.2.1/32", 32),
            ("::1/128", 128),
        ] {
            assert_eq!(parse_cidr(value, Some((24, 128))).unwrap().prefix_len(), prefix);
        }
        let net = parse_cidr("192.0.2.129/25", None).unwrap();
        assert!(net.contains(&"192.0.2.255".parse::<IpAddr>().unwrap()));
        assert!(!net.contains(&"192.0.2.127".parse::<IpAddr>().unwrap()));
        assert!(!net.contains(&"::ffff:192.0.2.129".parse::<IpAddr>().unwrap()));
    }
}
