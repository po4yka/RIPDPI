use std::net::IpAddr;
use std::str::FromStr;

use crate::{
    Cidr, ConfigError, DesyncGroup, RuntimeConfig, TcpChainStep, TcpChainStepKind, WsizeConfig, DETECT_CONNECT,
    DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT, DETECT_QUIC_BREAKAGE, DETECT_RECONN,
    DETECT_SILENT_DROP, DETECT_TCP_RESET, DETECT_TLS_ALERT, DETECT_TLS_ERR, DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TORST,
};

use super::super::fake_profiles::lower_host_char;

pub(super) fn parse_auto_detect_token(token: &str) -> Option<u32> {
    match token.trim().to_ascii_lowercase().as_str() {
        "t" | "torst" => Some(DETECT_TORST),
        "tcp_reset" => Some(DETECT_TCP_RESET),
        "silent_drop" => Some(DETECT_SILENT_DROP),
        "r" | "redirect" => Some(DETECT_HTTP_LOCAT),
        "http_blockpage" => Some(DETECT_HTTP_BLOCKPAGE),
        "a" | "s" | "ssl_err" => Some(DETECT_TLS_ERR),
        "tls_handshake_failure" => Some(DETECT_TLS_HANDSHAKE_FAILURE),
        "tls_alert" => Some(DETECT_TLS_ALERT),
        "k" | "reconn" => Some(DETECT_RECONN),
        "c" | "connect" => Some(DETECT_CONNECT),
        "dns_tamper" => Some(DETECT_DNS_TAMPER),
        "quic_breakage" => Some(DETECT_QUIC_BREAKAGE),
        "n" | "none" => Some(0),
        _ => None,
    }
}

pub fn parse_hosts_spec(spec: &str) -> Result<Vec<String>, ConfigError> {
    let mut out = Vec::new();
    for token in spec.split_whitespace() {
        let mut normalized = String::with_capacity(token.len());
        let mut valid = true;
        for ch in token.chars() {
            match lower_host_char(ch) {
                Some(lower) => normalized.push(lower),
                None => {
                    valid = false;
                    break;
                }
            }
        }
        if valid && !normalized.is_empty() {
            out.push(normalized);
        }
    }
    Ok(out)
}

fn parse_ip_token(token: &str) -> Result<Cidr, ConfigError> {
    let (addr_str, bits) = match token.split_once('/') {
        Some((addr, bits_str)) => {
            let bits = bits_str.parse::<u16>().map_err(|_| ConfigError::invalid("--ipset", Some(token)))?;
            if bits == 0 {
                return Err(ConfigError::invalid("--ipset", Some(token)));
            }
            (addr, bits)
        }
        None => (token, 0),
    };
    let addr = IpAddr::from_str(addr_str).map_err(|_| ConfigError::invalid("--ipset", Some(token)))?;
    let max_bits = match addr {
        IpAddr::V4(_) => 32,
        IpAddr::V6(_) => 128,
    };
    let bits = if bits == 0 || bits > max_bits { max_bits } else { bits };
    Ok(Cidr { addr, bits: bits as u8 })
}

pub fn parse_ipset_spec(spec: &str) -> Result<Vec<Cidr>, ConfigError> {
    let mut out = Vec::new();
    for token in spec.split_whitespace() {
        out.push(parse_ip_token(token)?);
    }
    Ok(out)
}

pub(crate) fn parse_numeric_addr(spec: &str) -> Result<(IpAddr, Option<u16>), ConfigError> {
    let (host, port) = if let Some(rest) = spec.strip_prefix('[') {
        let end = rest.find(']').ok_or_else(|| ConfigError::invalid("address", Some(spec)))?;
        let host = &rest[..end];
        let suffix = &rest[end + 1..];
        let port = if let Some(port_str) = suffix.strip_prefix(':') {
            Some(port_str.parse::<u16>().map_err(|_| ConfigError::invalid("address", Some(spec)))?)
        } else if suffix.is_empty() {
            None
        } else {
            return Err(ConfigError::invalid("address", Some(spec)));
        };
        (host, port)
    } else {
        let colon_count = spec.bytes().filter(|&byte| byte == b':').count();
        if colon_count == 1 {
            match spec.rsplit_once(':') {
                Some((host, port_str)) if !port_str.is_empty() && port_str.as_bytes()[0].is_ascii_digit() => {
                    let port = port_str.parse::<u16>().map_err(|_| ConfigError::invalid("address", Some(spec)))?;
                    (host, Some(port))
                }
                _ => (spec, None),
            }
        } else {
            (spec, None)
        }
    };
    let ip = IpAddr::from_str(host).map_err(|_| ConfigError::invalid("address", Some(spec)))?;
    Ok((ip, port))
}

pub(super) fn parse_timeout(spec: &str, config: &mut RuntimeConfig) -> Result<(), ConfigError> {
    let mut parts = spec.split(':');
    config.timeouts.timeout_ms =
        seconds_to_millis(parts.next().ok_or_else(|| ConfigError::invalid("--timeout", Some(spec)))?)?;
    if let Some(value) = parts.next() {
        config.timeouts.partial_timeout_ms = seconds_to_millis(value)?;
    }
    if let Some(value) = parts.next() {
        config.timeouts.timeout_count_limit =
            value.parse::<i32>().map_err(|_| ConfigError::invalid("--timeout", Some(spec)))?;
    }
    if let Some(value) = parts.next() {
        config.timeouts.timeout_bytes_limit =
            value.parse::<i32>().map_err(|_| ConfigError::invalid("--timeout", Some(spec)))?;
    }
    if parts.next().is_some() {
        return Err(ConfigError::invalid("--timeout", Some(spec)));
    }
    Ok(())
}

pub(crate) fn seconds_to_millis(spec: &str) -> Result<u32, ConfigError> {
    let seconds = spec.parse::<f32>().map_err(|_| ConfigError::invalid("--timeout", Some(spec)))?;
    if seconds < 0.0 {
        return Err(ConfigError::invalid("--timeout", Some(spec)));
    }
    Ok((seconds * 1000.0) as u32)
}

pub(super) fn split_plugin_options(spec: &str) -> Vec<String> {
    spec.split(' ').filter(|token| !token.is_empty()).map(ToOwned::to_owned).collect()
}

pub(super) fn next_value<'a>(args: &'a [String], idx: &mut usize, option: &str) -> Result<&'a str, ConfigError> {
    *idx += 1;
    args.get(*idx).map(String::as_str).ok_or_else(|| ConfigError::invalid(option, Option::<String>::None))
}

pub(super) fn parse_wsize(arg: &str, value: &str) -> Result<WsizeConfig, ConfigError> {
    let invalid = || ConfigError::invalid(arg, Some(value.to_string()));
    let (win_str, scale_str) = value.split_once(':').map_or((value, None), |(w, s)| (w, Some(s)));
    let window = win_str.parse::<u32>().map_err(|_| invalid())?;
    let scale = scale_str.map(|s| s.parse::<u8>().map_err(|_| invalid())).transpose()?;
    if scale.is_some_and(|s| s > 14) {
        return Err(invalid());
    }
    Ok(WsizeConfig { window, scale })
}

pub(super) fn add_group(groups: &mut Vec<DesyncGroup>) -> Result<&mut DesyncGroup, ConfigError> {
    if groups.len() >= 64 {
        return Err(ConfigError::invalid("groups", Some("too many groups")));
    }
    groups.push(DesyncGroup::new(groups.len()));
    Ok(groups.last_mut().expect("new group"))
}

pub(super) fn seqovl_step_mut(group: &mut DesyncGroup) -> Option<&mut TcpChainStep> {
    group.actions.tcp_chain.iter_mut().rev().find(|step| step.kind == TcpChainStepKind::SeqOverlap)
}

pub(super) fn parse_ttl_byte(arg: &str, value: &str) -> Result<u8, ConfigError> {
    let ttl = value.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
    if ttl == 0 || ttl > 255 {
        return Err(ConfigError::invalid(arg, Some(value)));
    }
    Ok(ttl as u8)
}
