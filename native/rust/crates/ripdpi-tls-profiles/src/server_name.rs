use std::net::{IpAddr, Ipv6Addr};

use crate::Error;

/// Validate a TLS DNS or IP identity before it reaches a TLS backend.
///
/// DNS names retain their case and lose one optional root dot. Unicode names
/// must already be encoded as IDNA A-labels. URL IPv6 brackets are removed;
/// the caller must format its HTTP authority separately.
///
/// # Errors
///
/// Returns an error for an empty name, a raw Unicode name, or invalid DNS labels.
pub fn normalize_server_name(name: &str) -> Result<String, Error> {
    if let Ok(ip) = name.parse::<IpAddr>() {
        return Ok(ip.to_string());
    }
    if let Some(ip) = name.strip_prefix('[').and_then(|name| name.strip_suffix(']'))
        && let Ok(ip) = ip.parse::<Ipv6Addr>()
    {
        return Ok(ip.to_string());
    }

    let name = name.strip_suffix('.').unwrap_or(name);
    let valid = !name.is_empty()
        && name.len() <= 253
        && rustls::pki_types::DnsName::try_from(name).is_ok()
        && name.split('.').all(|label| {
            let bytes = label.as_bytes();
            bytes.len() <= 63
                && bytes.first().is_some_and(u8::is_ascii_alphanumeric)
                && bytes.last().is_some_and(u8::is_ascii_alphanumeric)
                && bytes.iter().all(|byte| byte.is_ascii_alphanumeric() || *byte == b'-')
        });
    if !valid {
        return Err(Error::InvalidServerName {
            reason: "expected an IP address or ASCII DNS labels of 1..63 bytes, at most 253 bytes in total",
        });
    }
    Ok(name.to_owned())
}

#[cfg(test)]
mod tests {
    use super::normalize_server_name;

    #[test]
    fn tls_identity_accepts_valid_dns_and_ip_names() {
        for (input, expected) in [
            ("Example.TEST", "Example.TEST"),
            ("example.test.", "example.test"),
            ("xn--bcher-kva.test", "xn--bcher-kva.test"),
            ("localhost", "localhost"),
            ("127.0.0.1", "127.0.0.1"),
            ("2001:db8::1", "2001:db8::1"),
            ("[2001:db8::1]", "2001:db8::1"),
        ] {
            assert_eq!(normalize_server_name(input).expect(input), expected);
        }
        let max = format!("{}.{}.{}.{}", "a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61));
        assert_eq!(max.len(), 253);
        assert_eq!(normalize_server_name(&format!("{max}.")).expect("maximum DNS name"), max);
    }

    #[test]
    fn tls_identity_rejects_invalid_names() {
        for input in [
            "".to_owned(),
            ".".into(),
            "bücher.test".into(),
            "bad name.test".into(),
            "bad\0name.test".into(),
            "bad..test".into(),
            "bad.test..".into(),
            "-bad.test".into(),
            "bad-.test".into(),
            "bad_name.test".into(),
            "127.1".into(),
            "cover.123".into(),
            "[127.0.0.1]".into(),
            "[::1].".into(),
            format!("{}.test", "a".repeat(64)),
            format!("{}.{}.{}.{}", "a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(62)),
        ] {
            assert!(normalize_server_name(&input).is_err(), "{input:?}");
        }
    }
}
