use std::io;
use std::net::{IpAddr, SocketAddr};
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_dns_resolver::{
    extract_ip_answers, EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver,
    EncryptedDnsTransport,
};
use ripdpi_proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};
use ripdpi_ws_tunnel::TelegramDc;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::platform;

const DNS_RECORD_TYPE_A: u16 = 1;
const DNS_RECORD_TYPE_AAAA: u16 = 28;
const DEFAULT_DOH_URL: &str = "https://cloudflare-dns.com/dns-query";
const DEFAULT_DOH_HOST: &str = "cloudflare-dns.com";
const DEFAULT_DOH_BOOTSTRAP_IPS: &[&str] = &["1.1.1.1", "1.0.0.1"];
const WS_TUNNEL_PORT: u16 = 443;

/// Resolve `kws{dc}.web.telegram.org` through the configured encrypted DNS
/// endpoint and return the first socket address suitable for WS bootstrap.
pub fn resolve_ws_tunnel_addr(
    dc: TelegramDc,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
) -> io::Result<SocketAddr> {
    resolve_ws_tunnel_addr_with_default(dc, runtime_context, protect_path, default_encrypted_dns_context)
}

fn resolve_ws_tunnel_addr_with_default(
    dc: TelegramDc,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
    default_context: impl FnOnce() -> ProxyEncryptedDnsContext,
) -> io::Result<SocketAddr> {
    let host = ws_tunnel_host(dc);
    let resolved =
        resolve_host_via_encrypted_dns_with_default(&host, runtime_context, protect_path, false, default_context)?;
    Ok(SocketAddr::new(resolved.ip(), WS_TUNNEL_PORT))
}

fn ws_tunnel_host(dc: TelegramDc) -> String {
    ripdpi_ws_tunnel::ws_host(dc).expect("WS bootstrap only resolves tunnelable Telegram DCs")
}

pub(crate) fn default_encrypted_dns_context() -> ProxyEncryptedDnsContext {
    ProxyEncryptedDnsContext {
        resolver_id: Some("cloudflare".to_string()),
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

pub(crate) fn runtime_encrypted_dns_context(runtime_context: Option<&ProxyRuntimeContext>) -> ProxyEncryptedDnsContext {
    runtime_context.and_then(|context| context.encrypted_dns.clone()).unwrap_or_else(default_encrypted_dns_context)
}

pub(crate) fn encrypted_dns_endpoint(context: &ProxyEncryptedDnsContext) -> io::Result<EncryptedDnsEndpoint> {
    let protocol = match context.protocol.trim().to_ascii_lowercase().as_str() {
        "dot" => EncryptedDnsProtocol::Dot,
        "doq" => EncryptedDnsProtocol::Doq,
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

pub(crate) fn encrypted_dns_label(context: &ProxyEncryptedDnsContext) -> String {
    context
        .doh_url
        .clone()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| format!("{}:{}", context.host, context.port))
}

pub(crate) fn build_encrypted_dns_resolver(
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
) -> io::Result<EncryptedDnsResolver> {
    let resolver_context = runtime_encrypted_dns_context(runtime_context);
    let connect_hooks = build_direct_connect_hooks(protect_path);
    EncryptedDnsResolver::with_connect_hooks(
        encrypted_dns_endpoint(&resolver_context)?,
        EncryptedDnsTransport::Direct,
        connect_hooks,
    )
    .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))
}

pub(crate) fn resolve_host_via_encrypted_dns(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
    ipv6_enabled: bool,
) -> io::Result<SocketAddr> {
    resolve_host_via_encrypted_dns_with_default(
        host,
        runtime_context,
        protect_path,
        ipv6_enabled,
        default_encrypted_dns_context,
    )
}

fn resolve_host_via_encrypted_dns_with_default(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
    ipv6_enabled: bool,
    default_context: impl FnOnce() -> ProxyEncryptedDnsContext,
) -> io::Result<SocketAddr> {
    let resolver_context =
        runtime_context.and_then(|context| context.encrypted_dns.clone()).unwrap_or_else(default_context);
    let resolver = EncryptedDnsResolver::with_connect_hooks(
        encrypted_dns_endpoint(&resolver_context)?,
        EncryptedDnsTransport::Direct,
        build_direct_connect_hooks(protect_path),
    )
    .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))?;

    if let Some(ip) = resolve_first_ip(&resolver, host, DNS_RECORD_TYPE_A, |ip| ip.is_ipv4())? {
        return Ok(SocketAddr::new(ip, 0));
    }
    if ipv6_enabled {
        if let Some(ip) = resolve_first_ip(&resolver, host, DNS_RECORD_TYPE_AAAA, |ip| ip.is_ipv6())? {
            return Ok(SocketAddr::new(ip, 0));
        }
    }

    Err(io::Error::new(io::ErrorKind::AddrNotAvailable, "encrypted DNS resolved no usable socket address"))
}

fn resolve_first_ip(
    resolver: &EncryptedDnsResolver,
    host: &str,
    record_type: u16,
    predicate: impl Fn(IpAddr) -> bool,
) -> io::Result<Option<IpAddr>> {
    let query = build_dns_query(host, record_type, current_query_id())?;
    let response = resolver.exchange_blocking(&query).map_err(|err| io::Error::other(err.to_string()))?;
    let answers =
        extract_ip_answers(&response).map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err.to_string()))?;
    Ok(answers.into_iter().filter_map(|answer| answer.parse::<IpAddr>().ok()).find(|ip| predicate(*ip)))
}

fn build_direct_connect_hooks(protect_path: Option<&str>) -> EncryptedDnsConnectHooks {
    let Some(protect_path) = protect_path else {
        return EncryptedDnsConnectHooks::default();
    };
    let tcp_protect_path = protect_path.to_string();
    let udp_protect_path = tcp_protect_path.clone();

    EncryptedDnsConnectHooks::new()
        .with_direct_tcp_connector(move |target, timeout| {
            connect_protected_tcp_socket(target, &tcp_protect_path, timeout)
        })
        .with_direct_udp_binder(move |bind_addr| bind_protected_udp_socket(bind_addr, &udp_protect_path))
}

fn connect_protected_tcp_socket(
    target: SocketAddr,
    protect_path: &str,
    timeout: std::time::Duration,
) -> io::Result<std::net::TcpStream> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    platform::protect_socket(&socket, protect_path)?;
    socket.connect_timeout(&SockAddr::from(target), timeout)?;
    let stream: std::net::TcpStream = socket.into();
    stream.set_nodelay(true)?;
    Ok(stream)
}

fn bind_protected_udp_socket(bind_addr: SocketAddr, protect_path: &str) -> io::Result<std::net::UdpSocket> {
    let domain = match bind_addr {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    platform::protect_socket(&socket, protect_path)?;
    socket.bind(&SockAddr::from(bind_addr))?;
    let socket: std::net::UdpSocket = socket.into();
    socket.set_nonblocking(true)?;
    Ok(socket)
}

fn build_dns_query(domain: &str, record_type: u16, query_id: u16) -> io::Result<Vec<u8>> {
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
    packet.extend(record_type.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    Ok(packet)
}

fn current_query_id() -> u16 {
    (((SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos() as u64) & 0xffff) as u16).max(1)
}

#[cfg(test)]
mod tests {
    use super::*;

    use local_network_fixture::{FixtureConfig, FixtureStack};
    #[test]
    fn resolve_ws_tunnel_addr_uses_runtime_context_when_present() {
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture");
        let runtime_context = fixture_runtime_context(stack.manifest().dns_http_port);

        let addr = resolve_ws_tunnel_addr(TelegramDc::production(3), Some(&runtime_context), None)
            .expect("resolve ws tunnel addr");

        assert_eq!(addr, SocketAddr::new(stack.manifest().dns_answer_ipv4.parse().expect("fixture ip"), 443));
    }

    #[test]
    fn resolve_ws_tunnel_addr_uses_default_context_when_runtime_context_is_absent() {
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture");

        let addr = resolve_ws_tunnel_addr_with_default(TelegramDc::production(2), None, None, || {
            fixture_encrypted_dns_context(stack.manifest().dns_http_port)
        })
        .expect("resolve ws tunnel addr");

        assert_eq!(addr, SocketAddr::new(stack.manifest().dns_answer_ipv4.parse().expect("fixture ip"), 443));
    }

    #[test]
    fn default_encrypted_dns_context_uses_cloudflare_doh() {
        let context = default_encrypted_dns_context();

        assert_eq!(context.resolver_id.as_deref(), Some("cloudflare"));
        assert_eq!(context.host, "cloudflare-dns.com");
        assert_eq!(context.doh_url.as_deref(), Some("https://cloudflare-dns.com/dns-query"));
        assert_eq!(context.bootstrap_ips, vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()]);
    }

    #[test]
    fn build_direct_connect_hooks_only_installs_protected_connectors_when_path_present() {
        let empty = build_direct_connect_hooks(None);
        assert!(empty.direct_tcp_connector.is_none());
        assert!(empty.direct_udp_binder.is_none());

        let protected = build_direct_connect_hooks(Some("/tmp/ripdpi-protect.sock"));
        assert!(protected.direct_tcp_connector.is_some());
        assert!(protected.direct_udp_binder.is_some());
    }

    #[test]
    fn resolve_host_via_encrypted_dns_uses_runtime_context_for_regular_runtime_resolution() {
        let stack = FixtureStack::start(dynamic_fixture_config()).expect("start fixture");
        let runtime_context = fixture_runtime_context(stack.manifest().dns_http_port);

        let addr =
            resolve_host_via_encrypted_dns("fixture.test", Some(&runtime_context), None, false).expect("resolve host");

        assert_eq!(addr.ip(), stack.manifest().dns_answer_ipv4.parse::<IpAddr>().expect("fixture ip"));
    }

    #[test]
    fn ws_tunnel_host_supports_test_gateways() {
        assert_eq!(ws_tunnel_host(TelegramDc::from_raw(10_004).expect("test dc")), "kws4-test.web.telegram.org");
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
            dns_dot_port: 0,
            dns_dnscrypt_port: 0,
            dns_doq_port: 0,
            socks5_port: 0,
            control_port: 0,
            ..FixtureConfig::default()
        }
    }
}
