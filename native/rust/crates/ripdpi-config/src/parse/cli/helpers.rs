use crate::{
    ConfigError, DETECT_CONNECT, DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT, DETECT_QUIC_BREAKAGE,
    DETECT_RECONN, DETECT_SILENT_DROP, DETECT_TCP_RESET, DETECT_TLS_ALERT, DETECT_TLS_ERR,
    DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TORST, DesyncGroup, RuntimeConfig, TcpChainStep, TcpChainStepKind,
    WsizeConfig,
};

mod address;
mod filters;

pub(crate) use address::parse_numeric_addr;
pub use filters::{parse_host_filter_spec, parse_hosts_spec, parse_ipset_spec};

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
    group.actions.tcp_chain.iter_mut().rev().find(|step| step.kind() == TcpChainStepKind::SeqOverlap)
}

pub(super) fn parse_ttl_byte(arg: &str, value: &str) -> Result<u8, ConfigError> {
    let ttl = value.parse::<u16>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
    if ttl == 0 || ttl > 255 {
        return Err(ConfigError::invalid(arg, Some(value)));
    }
    Ok(ttl as u8)
}
