use std::io;
use std::net::{IpAddr, SocketAddr};
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_dns_resolver::{
    extract_ip_answers, EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport,
};
use ripdpi_proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};

const WS_TUNNEL_PORT: u16 = 443;
const DEFAULT_DOH_URL: &str = "https://dns.google/dns-query";
const DEFAULT_DOH_HOST: &str = "dns.google";
const DEFAULT_DOH_BOOTSTRAP_IPS: &[&str] = &["8.8.8.8", "8.8.4.4"];

/// Resolve `kws{dc}.web.telegram.org` through the configured encrypted DNS
/// endpoint and return the first socket address suitable for WS bootstrap.
pub fn resolve_ws_tunnel_addr(dc: u8, runtime_context: Option<&ProxyRuntimeContext>) -> io::Result<SocketAddr> {
    resolve_ws_tunnel_addr_with_default(dc, runtime_context, default_ws_tunnel_encrypted_dns_context)
}

fn resolve_ws_tunnel_addr_with_default(
    dc: u8,
    runtime_context: Option<&ProxyRuntimeContext>,
    default_context: impl FnOnce() -> ProxyEncryptedDnsContext,
) -> io::Result<SocketAddr> {
    let host = ws_tunnel_host(dc);
    let resolver_context =
        runtime_context.and_then(|context| context.encrypted_dns.clone()).unwrap_or_else(default_context);
    let resolver = EncryptedDnsResolver::new(encrypted_dns_endpoint(&resolver_context)?, EncryptedDnsTransport::Direct)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))?;
    let query = build_dns_query(&host, current_query_id())?;
    let response = resolver.exchange_blocking(&query).map_err(|err| io::Error::other(err.to_string()))?;
    let answers =
        extract_ip_answers(&response).map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err.to_string()))?;
    select_first_resolved_addr(&answers, WS_TUNNEL_PORT)
}

fn ws_tunnel_host(dc: u8) -> String {
    format!("kws{dc}.web.telegram.org")
}

fn default_ws_tunnel_encrypted_dns_context() -> ProxyEncryptedDnsContext {
    ProxyEncryptedDnsContext {
        resolver_id: Some("google".to_string()),
        protocol: "doh".to_string(),
        host: DEFAULT_DOH_HOST.to_string(),
        port: WS_TUNNEL_PORT,
        tls_server_name: Some(DEFAULT_DOH_HOST.to_string()),
        bootstrap_ips: DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect(),
        doh_url: Some(DEFAULT_DOH_URL.to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}

fn encrypted_dns_endpoint(context: &ProxyEncryptedDnsContext) -> io::Result<EncryptedDnsEndpoint> {
    let protocol = match context.protocol.trim().to_ascii_lowercase().as_str() {
        "dot" => EncryptedDnsProtocol::Dot,
        "dnscrypt" => EncryptedDnsProtocol::DnsCrypt,
        _ => EncryptedDnsProtocol::Doh,
    };
    let bootstrap_ips = context
        .bootstrap_ips
        .iter()
        .map(|value| value.parse::<IpAddr>())
        .collect::<Result<Vec<_>, _>>()
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))?;
    if bootstrap_ips.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "encrypted DNS bootstrap requires at least one bootstrap IP",
        ));
    }

    Ok(EncryptedDnsEndpoint {
        protocol,
        resolver_id: context.resolver_id.clone(),
        host: context.host.clone(),
        port: context.port,
        tls_server_name: context.tls_server_name.clone(),
        bootstrap_ips,
        doh_url: context.doh_url.clone(),
        dnscrypt_provider_name: context.dnscrypt_provider_name.clone(),
        dnscrypt_public_key: context.dnscrypt_public_key.clone(),
    })
}

fn build_dns_query(domain: &str, query_id: u16) -> io::Result<Vec<u8>> {
    let mut packet = Vec::with_capacity(512);
    packet.extend(query_id.to_be_bytes());
    packet.extend(0x0100u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    for label in domain.split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid dns name"));
        }
        packet.push(label.len() as u8);
        packet.extend(label.as_bytes());
    }
    packet.push(0);
    packet.extend(1u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    Ok(packet)
}

fn current_query_id() -> u16 {
    (((SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos() as u64) & 0xffff) as u16).max(1)
}

fn select_first_resolved_addr(answers: &[String], port: u16) -> io::Result<SocketAddr> {
    answers
        .iter()
        .find_map(|answer| answer.parse::<IpAddr>().ok())
        .map(|ip| SocketAddr::new(ip, port))
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "encrypted DNS resolved no socket address"))
}

#[cfg(test)]
mod tests {
    use super::*;

    use local_network_fixture::{FixtureConfig, FixtureStack};
    use std::net::Ipv4Addr;

    #[test]
    fn resolve_ws_tunnel_addr_uses_runtime_context_when_present() {
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture");
        let runtime_context = fixture_runtime_context(stack.manifest().dns_http_port);

        let addr = resolve_ws_tunnel_addr(3, Some(&runtime_context)).expect("resolve ws tunnel addr");

        assert_eq!(addr, SocketAddr::new(stack.manifest().dns_answer_ipv4.parse().expect("fixture ip"), 443));
    }

    #[test]
    fn resolve_ws_tunnel_addr_uses_default_context_when_runtime_context_is_absent() {
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture");

        let addr = resolve_ws_tunnel_addr_with_default(2, None, || {
            fixture_encrypted_dns_context(stack.manifest().dns_http_port)
        })
        .expect("resolve ws tunnel addr");

        assert_eq!(addr, SocketAddr::new(stack.manifest().dns_answer_ipv4.parse().expect("fixture ip"), 443));
    }

    #[test]
    fn select_first_resolved_addr_prefers_first_valid_ip() {
        let answers = vec!["invalid".to_string(), "198.18.0.10".to_string(), "198.18.0.11".to_string()];

        let addr = select_first_resolved_addr(&answers, 443).expect("select address");

        assert_eq!(addr, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 443));
    }

    fn fixture_runtime_context(dns_http_port: u16) -> ProxyRuntimeContext {
        ProxyRuntimeContext { encrypted_dns: Some(fixture_encrypted_dns_context(dns_http_port)) }
    }

    fn fixture_encrypted_dns_context(dns_http_port: u16) -> ProxyEncryptedDnsContext {
        ProxyEncryptedDnsContext {
            resolver_id: Some("fixture-doh".to_string()),
            protocol: "doh".to_string(),
            host: "127.0.0.1".to_string(),
            port: dns_http_port,
            tls_server_name: None,
            bootstrap_ips: vec!["127.0.0.1".to_string()],
            doh_url: Some(format!("http://127.0.0.1:{dns_http_port}/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }
    }

    fn dynamic_fixture_config() -> FixtureConfig {
        FixtureConfig {
            tcp_echo_port: 0,
            udp_echo_port: 0,
            tls_echo_port: 0,
            dns_udp_port: 0,
            dns_http_port: 0,
            socks5_port: 0,
            control_port: 0,
            ..FixtureConfig::default()
        }
    }
}
