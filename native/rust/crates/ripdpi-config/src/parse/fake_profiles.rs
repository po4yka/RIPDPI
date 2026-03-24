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
        "rand" => group.fake_mod |= FM_RAND,
        "orig" => group.fake_mod |= FM_ORIG,
        "rndsni" => group.fake_mod |= FM_RNDSNI,
        "dupsid" => group.fake_mod |= FM_DUPSID,
        "padencap" => group.fake_mod |= FM_PADENCAP,
        _ => {
            let Some((name, value)) = token.split_once('=') else {
                return Err(ConfigError::invalid(arg, Some(raw_value)));
            };
            match name {
                "m" | "msize" => {
                    group.fake_tls_size =
                        value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(raw_value)))?;
                }
                _ => return Err(ConfigError::invalid(arg, Some(raw_value))),
            }
        }
    }
    Ok(())
}
