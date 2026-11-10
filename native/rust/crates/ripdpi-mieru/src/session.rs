//! Target-address helpers for the in-tunnel SOCKS5 CONNECT that
//! [`crate::MieruMuxConnection`] sends. See `PROTOCOL.md` §5.

use std::net::{Ipv4Addr, Ipv6Addr};

use crate::error::{MieruError, Result};

pub(crate) fn split_host_port(target: &str) -> Result<(&str, u16)> {
    let (host, port) =
        target.rsplit_once(':').ok_or_else(|| MieruError::Socks5(format!("target `{target}` missing port")))?;
    if !host.starts_with('[') && host.contains(':') {
        return Err(MieruError::Socks5(format!("unbracketed IPv6 target `{target}` must use bracketed form")));
    }
    let port: u16 = port.parse().map_err(|_| MieruError::Socks5(format!("bad port in `{target}`")))?;
    let host = host.strip_prefix('[').and_then(|h| h.strip_suffix(']')).unwrap_or(host);
    Ok((host, port))
}

pub(crate) fn encode_socks5_address(out: &mut Vec<u8>, host: &str, port: u16) -> Result<()> {
    if let Ok(v4) = host.parse::<Ipv4Addr>() {
        out.push(0x01);
        out.extend_from_slice(&v4.octets());
    } else if let Ok(v6) = host.parse::<Ipv6Addr>() {
        out.push(0x04);
        out.extend_from_slice(&v6.octets());
    } else {
        let bytes = host.as_bytes();
        let len = u8::try_from(bytes.len()).map_err(|_| MieruError::Socks5("domain too long".to_owned()))?;
        out.push(0x03);
        out.push(len);
        out.extend_from_slice(bytes);
    }
    out.extend_from_slice(&port.to_be_bytes());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_port_split_handles_v6_brackets() {
        assert_eq!(split_host_port("example.com:443").unwrap(), ("example.com", 443));
        assert_eq!(split_host_port("[::1]:8080").unwrap(), ("::1", 8080));
        assert!(split_host_port("noport").is_err());
    }

    #[test]
    fn socks5_address_encodes_each_atyp() {
        let mut v4 = Vec::new();
        encode_socks5_address(&mut v4, "127.0.0.1", 80).unwrap();
        assert_eq!(v4, vec![0x01, 127, 0, 0, 1, 0x00, 80]);

        let mut domain = Vec::new();
        encode_socks5_address(&mut domain, "ex.com", 443).unwrap();
        assert_eq!(domain[0], 0x03);
        assert_eq!(domain[1], 6);
        assert_eq!(&domain[2..8], b"ex.com");
        assert_eq!(&domain[8..], &[0x01, 0xbb]);
    }
}

#[cfg(test)]
mod bare_ipv6_rejection_tests {
    use super::split_host_port;

    /// Regression test (audit H4 siblings): a bare IPv6 literal must be
    /// rejected instead of being silently split into a corrupted host
    /// (`"2001:db8:"`) with a bogus port.
    #[test]
    fn split_host_port_rejects_bare_ipv6_target() {
        assert!(split_host_port("2001:db8::1").is_err(), "bare IPv6 target must be rejected");
        assert!(split_host_port("2001:db8::1:443").is_err(), "unbracketed IPv6 with port must be rejected");
    }

    #[test]
    fn split_host_port_accepts_bracketed_ipv6_and_domain() {
        let bracketed = split_host_port("[2001:db8::1]:443").expect("bracketed IPv6 target parses");
        assert_eq!(bracketed, ("2001:db8::1", 443));
        let domain = split_host_port("example.com:443").expect("domain target parses");
        assert_eq!(domain, ("example.com", 443));
    }
}
