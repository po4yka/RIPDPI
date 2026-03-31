use std::fs;
use std::net::IpAddr;

use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};

use crate::{ConfigError, DesyncGroup, QuicFakeProfile, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI};

pub(crate) fn lower_host_char(ch: char) -> Option<char> {
    if ch.is_ascii_uppercase() {
        Some(ch.to_ascii_lowercase())
    } else if ('-'..='9').contains(&ch) || ch.is_ascii_lowercase() {
        Some(ch)
    } else {
        None
    }
}

fn host_template_char(ch: char) -> Option<char> {
    match ch {
        '.' => Some('.'),
        _ => lower_host_char(ch),
    }
}

fn is_ip_literal(value: &str) -> bool {
    value.parse::<IpAddr>().is_ok()
}

fn normalize_domain_host(spec: &str, option: &str) -> Result<String, ConfigError> {
    let trimmed = spec.trim().trim_end_matches('.');
    if trimmed.is_empty() {
        return Err(ConfigError::invalid(option, Some(spec)));
    }
    if trimmed.contains(':') || is_ip_literal(trimmed) {
        return Err(ConfigError::invalid(option, Some(spec)));
    }

    let mut normalized = String::with_capacity(trimmed.len());
    for ch in trimmed.chars() {
        let Some(lower) = host_template_char(ch) else {
            return Err(ConfigError::invalid(option, Some(spec)));
        };
        normalized.push(lower);
    }

    if normalized.starts_with('.') || normalized.ends_with('.') || normalized.contains("..") {
        return Err(ConfigError::invalid(option, Some(spec)));
    }
    for label in normalized.split('.') {
        if label.is_empty() || label.starts_with('-') || label.ends_with('-') {
            return Err(ConfigError::invalid(option, Some(spec)));
        }
    }
    Ok(normalized)
}

pub fn normalize_fake_host_template(spec: &str) -> Result<String, ConfigError> {
    normalize_domain_host(spec, "hostfake-template")
}

pub fn normalize_quic_fake_host(spec: &str) -> Result<String, ConfigError> {
    normalize_domain_host(spec, "fake-quic-host")
}

pub fn parse_quic_fake_profile(spec: &str) -> Result<QuicFakeProfile, ConfigError> {
    match spec.trim().to_ascii_lowercase().as_str() {
        "disabled" => Ok(QuicFakeProfile::Disabled),
        "compat_default" => Ok(QuicFakeProfile::CompatDefault),
        "realistic_initial" => Ok(QuicFakeProfile::RealisticInitial),
        _ => Err(ConfigError::invalid("--fake-quic-profile", Some(spec))),
    }
}

pub fn parse_http_fake_profile(spec: &str) -> Result<HttpFakeProfile, ConfigError> {
    match spec.trim().to_ascii_lowercase().as_str() {
        "" | "compat_default" => Ok(HttpFakeProfile::CompatDefault),
        "iana_get" => Ok(HttpFakeProfile::IanaGet),
        "cloudflare_get" => Ok(HttpFakeProfile::CloudflareGet),
        _ => Err(ConfigError::invalid("--fake-http-profile", Some(spec))),
    }
}

pub fn parse_tls_fake_profile(spec: &str) -> Result<TlsFakeProfile, ConfigError> {
    match spec.trim().to_ascii_lowercase().as_str() {
        "" | "compat_default" => Ok(TlsFakeProfile::CompatDefault),
        "iana_firefox" => Ok(TlsFakeProfile::IanaFirefox),
        "google_chrome" => Ok(TlsFakeProfile::GoogleChrome),
        "vk_chrome" => Ok(TlsFakeProfile::VkChrome),
        "sberbank_chrome" => Ok(TlsFakeProfile::SberbankChrome),
        "rutracker_kyber" => Ok(TlsFakeProfile::RutrackerKyber),
        "bigsize_iana" => Ok(TlsFakeProfile::BigsizeIana),
        _ => Err(ConfigError::invalid("--fake-tls-profile", Some(spec))),
    }
}

pub fn parse_udp_fake_profile(spec: &str) -> Result<UdpFakeProfile, ConfigError> {
    match spec.trim().to_ascii_lowercase().as_str() {
        "" | "compat_default" => Ok(UdpFakeProfile::CompatDefault),
        "zero_256" => Ok(UdpFakeProfile::Zero256),
        "zero_512" => Ok(UdpFakeProfile::Zero512),
        "dns_query" => Ok(UdpFakeProfile::DnsQuery),
        "stun_binding" => Ok(UdpFakeProfile::StunBinding),
        "wireguard_initiation" => Ok(UdpFakeProfile::WireGuardInitiation),
        "dht_get_peers" => Ok(UdpFakeProfile::DhtGetPeers),
        _ => Err(ConfigError::invalid("--fake-udp-profile", Some(spec))),
    }
}

fn cform_byte(ch: char) -> Option<u8> {
    Some(match ch {
        'r' => b'\r',
        'n' => b'\n',
        't' => b'\t',
        '\\' => b'\\',
        'f' => 0x0c,
        'b' => 0x08,
        'v' => 0x0b,
        'a' => 0x07,
        _ => return None,
    })
}

pub fn data_from_str(spec: &str) -> Result<Vec<u8>, ConfigError> {
    if spec.is_empty() {
        return Err(ConfigError::invalid("inline-data", Some(spec)));
    }
    let bytes = spec.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut idx = 0;
    while idx < bytes.len() {
        if bytes[idx] != b'\\' {
            out.push(bytes[idx]);
            idx += 1;
            continue;
        }
        idx += 1;
        if idx >= bytes.len() {
            out.push(b'\\');
            break;
        }
        let ch = bytes[idx] as char;
        if let Some(mapped) = cform_byte(ch) {
            out.push(mapped);
            idx += 1;
            continue;
        }
        if ch == 'x' && idx + 2 < bytes.len() {
            let hex = &spec[idx + 1..idx + 3];
            if let Ok(value) = u8::from_str_radix(hex, 16) {
                out.push(value);
                idx += 3;
                continue;
            }
        }
        let mut oct_end = idx;
        while oct_end < bytes.len() && oct_end < idx + 3 && (b'0'..=b'7').contains(&bytes[oct_end]) {
            oct_end += 1;
        }
        if oct_end > idx {
            if let Ok(value) = u8::from_str_radix(&spec[idx..oct_end], 8) {
                out.push(value);
                idx = oct_end;
                continue;
            }
        }
        out.push(ch as u8);
        idx += 1;
    }
    if out.is_empty() {
        return Err(ConfigError::invalid("inline-data", Some(spec)));
    }
    Ok(out)
}

pub fn file_or_inline_bytes(spec: &str) -> Result<Vec<u8>, ConfigError> {
    if let Some(inline) = spec.strip_prefix(':') {
        return data_from_str(inline);
    }
    let data = fs::read(spec).map_err(|_| ConfigError::invalid("file", Some(spec)))?;
    if data.is_empty() {
        return Err(ConfigError::invalid("file", Some(spec)));
    }
    Ok(data)
}

pub(crate) fn apply_fake_tls_mod_token(
    group: &mut DesyncGroup,
    token: &str,
    arg: &str,
    raw_value: &str,
) -> Result<(), ConfigError> {
    let token = token.trim();
    if token.is_empty() {
        return Err(ConfigError::invalid(arg, Some(raw_value)));
    }
    match token {
        "rand" => group.actions.fake_mod |= FM_RAND,
        "orig" => group.actions.fake_mod |= FM_ORIG,
        "rndsni" => group.actions.fake_mod |= FM_RNDSNI,
        "dupsid" => group.actions.fake_mod |= FM_DUPSID,
        "padencap" => group.actions.fake_mod |= FM_PADENCAP,
        _ => {
            let Some((name, value)) = token.split_once('=') else {
                return Err(ConfigError::invalid(arg, Some(raw_value)));
            };
            match name {
                "m" | "msize" => {
                    group.actions.fake_tls_size =
                        value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(raw_value)))?;
                }
                _ => return Err(ConfigError::invalid(arg, Some(raw_value))),
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};

    #[test]
    fn data_from_str_all_cform_branches() {
        assert_eq!(data_from_str("\\r").unwrap(), vec![b'\r']);
        assert_eq!(data_from_str("\\n").unwrap(), vec![b'\n']);
        assert_eq!(data_from_str("\\t").unwrap(), vec![b'\t']);
        assert_eq!(data_from_str("\\\\").unwrap(), vec![b'\\']);
        assert_eq!(data_from_str("\\f").unwrap(), vec![0x0c]);
        assert_eq!(data_from_str("\\b").unwrap(), vec![0x08]);
        assert_eq!(data_from_str("\\v").unwrap(), vec![0x0b]);
        assert_eq!(data_from_str("\\a").unwrap(), vec![0x07]);
        // hex escape
        assert_eq!(data_from_str("\\x41").unwrap(), vec![0x41]);
        // octal escape
        assert_eq!(data_from_str("\\101").unwrap(), vec![0x41]);
    }

    #[test]
    fn data_from_str_trailing_backslash() {
        // Trailing backslash is emitted as literal backslash
        assert_eq!(data_from_str("abc\\").unwrap(), vec![97, 98, 99, b'\\']);
    }

    #[test]
    fn data_from_str_empty_string_rejected() {
        assert!(data_from_str("").is_err());
    }

    #[test]
    fn data_from_str_plain_ascii() {
        assert_eq!(data_from_str("hello").unwrap(), b"hello".to_vec());
    }

    #[test]
    fn data_from_str_mixed_escapes_and_text() {
        let result = data_from_str("A\\x42C").unwrap();
        assert_eq!(result, vec![b'A', 0x42, b'C']);
    }

    #[test]
    fn file_or_inline_bytes_inline_data() {
        let result = file_or_inline_bytes(":hello").unwrap();
        assert_eq!(result, b"hello".to_vec());
    }

    #[test]
    fn file_or_inline_bytes_missing_file() {
        assert!(file_or_inline_bytes("/nonexistent/path/to/file").is_err());
    }

    #[test]
    fn lower_host_char_range_boundaries() {
        // '-' is the low bound of the range
        assert_eq!(lower_host_char('-'), Some('-'));
        // '9' is the high bound
        assert_eq!(lower_host_char('9'), Some('9'));
        // Just below range: ','
        assert_eq!(lower_host_char(','), None);
        // Uppercase converted to lowercase
        assert_eq!(lower_host_char('A'), Some('a'));
    }

    #[test]
    fn normalize_quic_fake_host_rejects_invalid_values() {
        assert_eq!(normalize_quic_fake_host(" Example.COM. ").unwrap(), "example.com");
        assert!(normalize_quic_fake_host("127.0.0.1").is_err());
        assert!(normalize_quic_fake_host("::1").is_err());
        assert!(normalize_quic_fake_host("bad..host").is_err());
    }

    #[test]
    fn parse_quic_fake_profile_accepts_known_values() {
        assert_eq!(parse_quic_fake_profile("disabled").unwrap(), QuicFakeProfile::Disabled);
        assert_eq!(parse_quic_fake_profile("compat_default").unwrap(), QuicFakeProfile::CompatDefault);
        assert_eq!(parse_quic_fake_profile("realistic_initial").unwrap(), QuicFakeProfile::RealisticInitial);
        assert!(parse_quic_fake_profile("bogus").is_err());
    }

    #[test]
    fn parse_fake_payload_profiles_accepts_known_values() {
        assert_eq!(parse_http_fake_profile("compat_default").unwrap(), HttpFakeProfile::CompatDefault);
        assert_eq!(parse_http_fake_profile("iana_get").unwrap(), HttpFakeProfile::IanaGet);
        assert_eq!(parse_http_fake_profile("cloudflare_get").unwrap(), HttpFakeProfile::CloudflareGet);
        assert!(parse_http_fake_profile("bogus").is_err());

        assert_eq!(parse_tls_fake_profile("compat_default").unwrap(), TlsFakeProfile::CompatDefault);
        assert_eq!(parse_tls_fake_profile("google_chrome").unwrap(), TlsFakeProfile::GoogleChrome);
        assert_eq!(parse_tls_fake_profile("rutracker_kyber").unwrap(), TlsFakeProfile::RutrackerKyber);
        assert!(parse_tls_fake_profile("bogus").is_err());

        assert_eq!(parse_udp_fake_profile("compat_default").unwrap(), UdpFakeProfile::CompatDefault);
        assert_eq!(parse_udp_fake_profile("dns_query").unwrap(), UdpFakeProfile::DnsQuery);
        assert_eq!(parse_udp_fake_profile("wireguard_initiation").unwrap(), UdpFakeProfile::WireGuardInitiation);
        assert!(parse_udp_fake_profile("bogus").is_err());
    }

    #[test]
    fn normalize_fake_host_template_valid() {
        assert_eq!(normalize_fake_host_template("Example.COM.").unwrap(), "example.com");
        assert_eq!(normalize_fake_host_template("sub.HOST.test").unwrap(), "sub.host.test");
    }

    #[test]
    fn normalize_fake_host_template_rejects_invalid() {
        assert!(normalize_fake_host_template("").is_err());
        assert!(normalize_fake_host_template("bad..host").is_err());
        assert!(normalize_fake_host_template("-start.com").is_err());
        assert!(normalize_fake_host_template("end-.com").is_err());
        assert!(normalize_fake_host_template("127.0.0.1").is_err());
    }

    // --- New coverage gap tests ---

    #[test]
    fn data_from_str_hex_boundary_00_ff() {
        assert_eq!(data_from_str("\\x00").unwrap(), vec![0x00]);
        assert_eq!(data_from_str("\\xff").unwrap(), vec![0xff]);
    }

    #[test]
    fn data_from_str_octal_boundary_000_377() {
        assert_eq!(data_from_str("\\000").unwrap(), vec![0x00]);
        assert_eq!(data_from_str("\\377").unwrap(), vec![0xff]);
    }

    #[test]
    fn apply_fake_tls_mod_all_tokens() {
        use crate::{DesyncGroup, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI};

        let mut group = DesyncGroup::new(0);
        apply_fake_tls_mod_token(&mut group, "rand", "--fake-tls-mod", "rand").unwrap();
        assert_eq!(group.actions.fake_mod, FM_RAND);

        apply_fake_tls_mod_token(&mut group, "orig", "--fake-tls-mod", "orig").unwrap();
        assert_eq!(group.actions.fake_mod, FM_RAND | FM_ORIG);

        apply_fake_tls_mod_token(&mut group, "rndsni", "--fake-tls-mod", "rndsni").unwrap();
        assert_eq!(group.actions.fake_mod, FM_RAND | FM_ORIG | FM_RNDSNI);

        apply_fake_tls_mod_token(&mut group, "dupsid", "--fake-tls-mod", "dupsid").unwrap();
        assert_eq!(group.actions.fake_mod, FM_RAND | FM_ORIG | FM_RNDSNI | FM_DUPSID);

        apply_fake_tls_mod_token(&mut group, "padencap", "--fake-tls-mod", "padencap").unwrap();
        assert_eq!(group.actions.fake_mod, FM_RAND | FM_ORIG | FM_RNDSNI | FM_DUPSID | FM_PADENCAP);
    }

    #[test]
    fn apply_fake_tls_mod_rejects_unknown_token() {
        use crate::DesyncGroup;
        let mut group = DesyncGroup::new(0);
        assert!(apply_fake_tls_mod_token(&mut group, "bogus", "--fake-tls-mod", "bogus").is_err());
    }

    #[test]
    fn apply_fake_tls_mod_msize_key() {
        use crate::DesyncGroup;
        let mut group = DesyncGroup::new(0);
        apply_fake_tls_mod_token(&mut group, "m=256", "--fake-tls-mod", "m=256").unwrap();
        assert_eq!(group.actions.fake_tls_size, 256);

        let mut group2 = DesyncGroup::new(0);
        apply_fake_tls_mod_token(&mut group2, "msize=512", "--fake-tls-mod", "msize=512").unwrap();
        assert_eq!(group2.actions.fake_tls_size, 512);
    }

    #[test]
    fn file_or_inline_bytes_with_escapes() {
        let result = file_or_inline_bytes(":A\\x42C").unwrap();
        assert_eq!(result, vec![b'A', 0x42, b'C']);
    }
}
