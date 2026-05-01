use std::net::IpAddr;

use crate::transport::{throughput_connect_targets, TargetAddress};

use super::types::ParsedHttpTarget;

pub(super) fn parse_http_target(
    url: &str,
    connect_ip: Option<&str>,
    connect_ips: &[String],
    port_override: Option<u16>,
) -> Result<ParsedHttpTarget, String> {
    let secure = url.starts_with("https://");
    let without_scheme = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))
        .ok_or_else(|| "unsupported_url_scheme".to_string())?;
    let (authority, path) = match without_scheme.split_once('/') {
        Some((authority, suffix)) => (authority, format!("/{suffix}")),
        None => (without_scheme, "/".to_string()),
    };
    let (host, parsed_port) = split_host_and_port(authority);
    if host.is_empty() {
        return Err("missing_url_host".to_string());
    }
    let port = port_override.or(parsed_port).unwrap_or(if secure { 443 } else { 80 });
    let connect_targets = throughput_connect_targets(Some(host.as_str()), connect_ip, connect_ips);
    Ok(ParsedHttpTarget { host, path, port, secure, connect_targets })
}

pub(super) fn connect_target_from_parts(host: Option<&str>, connect_ip: Option<&str>) -> Option<TargetAddress> {
    connect_ip
        .and_then(|value| value.parse::<IpAddr>().ok())
        .map(TargetAddress::Ip)
        .or_else(|| host.filter(|value| !value.is_empty()).map(|value| TargetAddress::Host(value.to_string())))
}

fn split_host_and_port(authority: &str) -> (String, Option<u16>) {
    if authority.starts_with('[') {
        return (authority.to_string(), None);
    }
    match authority.rsplit_once(':') {
        Some((host, port)) => match port.parse::<u16>() {
            Ok(parsed_port) => (host.to_string(), Some(parsed_port)),
            Err(_) => (authority.to_string(), None),
        },
        None => (authority.to_string(), None),
    }
}
