use std::net::IpAddr;

use crate::connectivity::adapters::transport::{TargetAddress, throughput_connect_targets};

use super::types::ParsedHttpTarget;

pub(super) fn parse_http_target(
    url: &str,
    connect_ip: Option<&str>,
    connect_ips: &[String],
    port_override: Option<u16>,
) -> Result<ParsedHttpTarget, String> {
    if url.bytes().any(|byte| byte.is_ascii_whitespace() || byte.is_ascii_control() || byte == b'\\') {
        return Err("invalid_url_character".to_string());
    }
    let secure = url.starts_with("https://");
    let without_scheme = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))
        .ok_or_else(|| "unsupported_url_scheme".to_string())?;
    let without_fragment = without_scheme.split('#').next().unwrap_or_default();
    let boundary = without_fragment.find(['/', '?']).unwrap_or(without_fragment.len());
    let (authority, suffix) = without_fragment.split_at(boundary);
    let path = if suffix.starts_with('/') { suffix.to_string() } else { format!("/{suffix}") };
    let (host, parsed_port) = split_host_and_port(authority)?;
    let port = port_override.or(parsed_port).unwrap_or(if secure { 443 } else { 80 });
    if port == 0 {
        return Err("invalid_url_port".to_string());
    }
    let connect_targets = throughput_connect_targets(Some(host.as_str()), connect_ip, connect_ips);
    Ok(ParsedHttpTarget { host, path, port, secure, connect_targets })
}

pub(super) fn connect_target_from_parts(host: Option<&str>, connect_ip: Option<&str>) -> Option<TargetAddress> {
    connect_ip
        .and_then(|value| value.parse::<IpAddr>().ok())
        .map(TargetAddress::Ip)
        .or_else(|| host.filter(|value| !value.is_empty()).map(|value| TargetAddress::Host(value.to_string())))
}

fn split_host_and_port(authority: &str) -> Result<(String, Option<u16>), String> {
    let (host, port) = if let Some(bracketed) = authority.strip_prefix('[') {
        let (host, suffix) = bracketed.split_once(']').ok_or("invalid_url_host")?;
        host.parse::<std::net::Ipv6Addr>().map_err(|_| "invalid_url_host")?;
        let port = if suffix.is_empty() { None } else { Some(suffix.strip_prefix(':').ok_or("invalid_url_port")?) };
        (host, port)
    } else {
        let (host, port) = authority.split_once(':').map_or((authority, None), |(host, port)| (host, Some(port)));
        if host.contains(['[', ']', '@']) {
            return Err("invalid_url_host".to_string());
        }
        (host, port)
    };
    if host.is_empty() {
        return Err("missing_url_host".to_string());
    }
    let port = port.map(|value| value.parse::<u16>().map_err(|_| "invalid_url_port".to_string())).transpose()?;
    if port == Some(0) {
        return Err("invalid_url_port".to_string());
    }
    Ok((host.to_string(), port))
}

#[cfg(test)]
mod url_regressions {
    use super::parse_http_target;

    #[test]
    fn parses_ipv6_authority_and_query_without_slash() {
        let parsed = parse_http_target("http://[::1]:8080?probe=1#ignored", None, &[], None).unwrap();
        assert_eq!(parsed.host, "::1");
        assert_eq!(parsed.port, 8080);
        assert_eq!(parsed.path, "/?probe=1");
    }

    #[test]
    fn rejects_malformed_authorities() {
        for url in
            ["http://host:bad/", "http://host:0/", "http://[::1/", "http://user@host/", "http://host/\r\nInjected: yes"]
        {
            assert!(parse_http_target(url, None, &[], None).is_err(), "{url:?}");
        }
    }
}
