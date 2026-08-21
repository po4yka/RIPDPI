//! Client -> relay spoof-intent signaling (AC2).
//!
//! The on-device client cannot perform the raw-socket injection itself
//! (non-rooted Android has no `CAP_NET_RAW`/`CAP_NET_ADMIN`), so it instead
//! tells the relay hop *what* to spoof. [`SpoofRequest`] is the wire model for
//! that intent. It is transport-agnostic serde — the relay control channel
//! (whatever carries it) is out of scope here.

use std::net::IpAddr;

use serde::{Deserialize, Serialize};

use crate::config::{SpoofMethod, is_valid_dns_hostname, validate_decoy_sni};
use crate::error::SpoofError;

/// A client's request that the relay spoof a decoy ClientHello in front of the
/// real handshake to `destination`.
///
/// `destination` is kept as a `host:port` / address string (the relay resolves
/// and connects); modelling it as a plain `String` avoids forcing the client to
/// pre-resolve and keeps the signaling layer dependency-light.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SpoofRequest {
    /// Real upstream the relay should connect to, as `host:port`. The host is
    /// either a DNS name or a bracketed/plain IP literal; IP literals must be
    /// globally routable (loopback, private, and link-local targets are
    /// rejected so the relay cannot be aimed at its own network).
    pub destination: String,
    /// Decoy hostname the relay injects into the forged SNI.
    pub decoy_sni: String,
    /// Wire-corruption method for the forged decoy segment.
    pub method: SpoofMethod,
}

impl SpoofRequest {
    /// Validate the request: the decoy SNI (non-empty, not an IP literal,
    /// valid hostname) and the destination (valid `host:port`, globally
    /// routable when the host is an IP literal). Errors never echo the
    /// offending values (AC7 redaction).
    ///
    /// Note the limit of this gate: a *hostname* destination is only checked
    /// syntactically here — where it resolves to is a relay-side resolution
    /// policy (DNS rebinding is out of scope for the wire model).
    pub fn validate(&self) -> Result<(), SpoofError> {
        validate_decoy_sni(&self.decoy_sni)?;
        validate_destination(&self.destination)
    }
}

/// Validate a `host:port` destination: non-empty, explicit non-zero port,
/// host is a bracketed IPv6 literal, a plain IPv4 literal, or a syntactically
/// valid DNS name; IP literals must be globally routable.
fn validate_destination(destination: &str) -> Result<(), SpoofError> {
    let (host, port) = split_host_port(destination).ok_or(SpoofError::InvalidDestination)?;
    let port: u16 = port.parse().map_err(|_| SpoofError::InvalidDestination)?;
    if port == 0 {
        return Err(SpoofError::InvalidDestination);
    }
    if let Some(v6) = host.strip_prefix('[').and_then(|h| h.strip_suffix(']')) {
        require_global_ip(v6)?;
    } else if host.parse::<IpAddr>().is_ok() {
        require_global_ip(host)?;
    } else if !is_valid_dns_hostname(host) {
        return Err(SpoofError::InvalidDestination);
    }
    Ok(())
}

/// Split a `host:port` destination. Bracketed IPv6 (`[h]:p`) is the only form
/// allowed to contain multiple colons; a bare IPv6 with port is rejected.
fn split_host_port(destination: &str) -> Option<(&str, &str)> {
    if let Some(rest) = destination.strip_prefix('[') {
        let close = rest.find(']')?;
        let port = rest[close + 1..].strip_prefix(':')?;
        Some((&rest[..close], port))
    } else {
        let (host, port) = destination.rsplit_once(':')?;
        (!host.contains(':')).then_some((host, port))
    }
}

/// IP-literal destinations must be globally routable: the relay connects on
/// its own network, and a client-supplied loopback/private/link-local target
/// is an SSRF vector against the relay's infrastructure.
///
/// Hand-rolled because `IpAddr::is_global` is still unstable on the pinned
/// toolchain; mirrors the IANA IPv4/IPv6 special-purpose registries.
fn require_global_ip(host: &str) -> Result<(), SpoofError> {
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V4(v4)) if is_global_ipv4(v4.octets()) => Ok(()),
        Ok(IpAddr::V6(v6)) if is_global_ipv6(v6.segments()) => Ok(()),
        _ => Err(SpoofError::InvalidDestination),
    }
}

/// Globally routable unicast IPv4: everything outside the IANA
/// special-purpose and non-routable ranges.
fn is_global_ipv4(o: [u8; 4]) -> bool {
    !(o[0] == 0                                        // "this network" / unspecified
        || o[0] == 10                                  // 10/8 private
        || o[0] == 127                                 // 127/8 loopback
        || (o[0] == 100 && (o[1] & 0xC0) == 128)       // 100.64/10 CGNAT
        || (o[0] == 169 && o[1] == 254)                // 169.254/16 link-local
        || (o[0] == 172 && (o[1] & 0xF0) == 16)        // 172.16/12 private
        || (o[0] == 192 && o[1] == 0 && o[2] == 0)     // 192.0.0/24 IETF protocol
        || (o[0] == 192 && o[1] == 0 && o[2] == 2)     // 192.0.2/24 TEST-NET-1
        || (o[0] == 198 && (o[1] & 0xFE) == 18)        // 198.18/15 benchmarking
        || (o[0] == 198 && o[1] == 51 && o[2] == 100)  // 198.51.100/24 TEST-NET-2
        || (o[0] == 203 && o[1] == 0 && o[2] == 113)   // 203.0.113/24 TEST-NET-3
        || (o[0] == 192 && o[1] == 168)                // 192.168/16 private
        || o[0] >= 224) // multicast, reserved, broadcast
}

/// Globally routable unicast IPv6: everything outside the IANA
/// special-purpose and non-routable ranges.
fn is_global_ipv6(s: [u16; 8]) -> bool {
    if s[0] == 0 {
        // ::/8: unspecified, loopback, and other reserved or IPv4-embedded
        // forms. Only IPv4-mapped addresses can be global, judged by the
        // IPv4 policy; everything else in this block is non-routable.
        if s[5] == 0xffff {
            return is_global_ipv4([(s[6] >> 8) as u8, s[6] as u8, (s[7] >> 8) as u8, s[7] as u8]);
        }
        return false;
    }
    (s[0] & 0xFE00) != 0xFC00                   // fc00::/7 unique-local
        && (s[0] & 0xFFC0) != 0xFE80            // fe80::/10 link-local
        && (s[0] & 0xFF00) != 0xFF00            // ff00::/8 multicast
        && !(s[0] == 0x2001 && s[1] == 0x0DB8) // 2001:db8::/32 documentation
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serde_json_round_trip() {
        let req = SpoofRequest {
            destination: "example.com:443".to_string(),
            decoy_sni: "www.wikipedia.org".to_string(),
            method: SpoofMethod::WrongMd5,
        };
        let json = serde_json::to_string(&req).unwrap();
        let back: SpoofRequest = serde_json::from_str(&json).unwrap();
        assert_eq!(req, back);
        // method serializes by its enum variant name.
        assert!(json.contains("WrongMd5"));
    }

    #[test]
    fn validate_rejects_ip_literal_decoy() {
        let req = SpoofRequest {
            destination: "10.0.0.5:443".to_string(),
            decoy_sni: "10.0.0.5".to_string(),
            method: SpoofMethod::WrongAck,
        };
        assert_eq!(req.validate(), Err(SpoofError::SniIsIpLiteral));
    }

    #[test]
    fn validate_accepts_hostname_decoy() {
        let req = SpoofRequest {
            destination: "[2001:4860:4860::8888]:443".to_string(),
            decoy_sni: "decoy.example.com".to_string(),
            method: SpoofMethod::WrongTimestamp,
        };
        assert_eq!(req.validate(), Ok(()));
    }

    #[test]
    fn valid_destinations_accepted() {
        let decoy = "decoy.example.com".to_string();
        for destination in [
            "example.com:443",
            "93.184.216.34:443",
            "[2001:4860:4860::8888]:443",
            "upstream.example.co.uk.:8443", // trailing-dot FQDN
        ] {
            let req = SpoofRequest {
                destination: destination.to_string(),
                decoy_sni: decoy.clone(),
                method: SpoofMethod::WrongAck,
            };
            assert_eq!(req.validate(), Ok(()), "{destination}");
        }
    }

    #[test]
    fn malformed_destinations_rejected() {
        let decoy = "decoy.example.com".to_string();
        for destination in [
            "",
            "example.com",              // no port
            "example.com:",             // empty port
            "example.com:0",            // port zero
            "example.com:99999",        // port out of range
            "2001:4860:4860::8888:443", // bare IPv6 with port (needs brackets)
            "[2001:4860:4860::8888]",   // missing port
            "under_score.example.com:443",
        ] {
            let req = SpoofRequest {
                destination: destination.to_string(),
                decoy_sni: decoy.clone(),
                method: SpoofMethod::WrongAck,
            };
            assert_eq!(req.validate(), Err(SpoofError::InvalidDestination), "{destination}");
        }
    }

    #[test]
    fn non_global_ip_destinations_rejected() {
        let decoy = "decoy.example.com".to_string();
        for destination in [
            "127.0.0.1:443",      // loopback
            "10.0.0.5:443",       // RFC1918 private
            "192.168.1.1:443",    // RFC1918 private
            "169.254.169.254:80", // link-local metadata endpoint
            "0.0.0.0:443",        // unspecified
            "[::1]:443",          // v6 loopback
            "[fe80::1]:443",      // v6 link-local
            "[fc00::1]:443",      // v6 unique-local
            "[2001:db8::1]:443",  // documentation range
        ] {
            let req = SpoofRequest {
                destination: destination.to_string(),
                decoy_sni: decoy.clone(),
                method: SpoofMethod::WrongAck,
            };
            assert_eq!(req.validate(), Err(SpoofError::InvalidDestination), "{destination}");
        }
    }
}
