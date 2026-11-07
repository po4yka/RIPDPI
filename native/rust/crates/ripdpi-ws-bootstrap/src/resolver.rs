use std::io;
use std::net::{IpAddr, SocketAddr};

use ripdpi_dns_resolver::{EncryptedDnsResolver, EncryptedDnsTransport, extract_ip_answers_for_query};
use ripdpi_proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};
use ripdpi_ws_transport_port::{TelegramDc, ws_host};

use crate::catalog::{WS_TUNNEL_PORT, default_encrypted_dns_context};
use crate::endpoint::encrypted_dns_endpoint;
use crate::policy::{
    record_successful_resolver, runtime_encrypted_dns_context_for_host,
    runtime_encrypted_dns_context_for_host_with_default,
};
use crate::protect_hooks::build_direct_connect_hooks;
use crate::query::{DNS_RECORD_TYPE_A, DNS_RECORD_TYPE_AAAA, build_dns_query, current_query_id};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncryptedDnsIpAnswers {
    pub label: String,
    pub answers: Vec<IpAddr>,
}

/// Resolve `kws{dc}.web.telegram.org` through the configured encrypted DNS
/// endpoint and return the first socket address suitable for WS bootstrap.
pub fn resolve_ws_tunnel_addr(
    dc: TelegramDc,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
) -> io::Result<SocketAddr> {
    resolve_ws_tunnel_addr_with_default(dc, runtime_context, protect_path, default_encrypted_dns_context)
}

pub fn build_encrypted_dns_resolver_for_host(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
) -> io::Result<EncryptedDnsResolver> {
    let resolver_context = runtime_encrypted_dns_context_for_host(host, runtime_context);
    let connect_hooks = build_direct_connect_hooks(protect_path);
    EncryptedDnsResolver::with_connect_hooks(
        encrypted_dns_endpoint(&resolver_context)?,
        EncryptedDnsTransport::Direct,
        connect_hooks,
    )
    .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))
}

pub fn resolve_host_via_encrypted_dns(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
    ipv6_enabled: bool,
) -> io::Result<SocketAddr> {
    resolve_host_via_encrypted_dns_with_cancel(host, runtime_context, protect_path, ipv6_enabled, &|| false)
}

/// Resolve until the caller cancels. The callback must return quickly and stay true after cancellation.
pub fn resolve_host_via_encrypted_dns_with_cancel(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
    ipv6_enabled: bool,
    should_cancel: &dyn Fn() -> bool,
) -> io::Result<SocketAddr> {
    resolve_host_via_encrypted_dns_with_default_and_cancel(
        host,
        runtime_context,
        protect_path,
        ipv6_enabled,
        default_encrypted_dns_context,
        should_cancel,
    )
}

pub fn encrypted_dns_ip_answers_for_host(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
) -> io::Result<EncryptedDnsIpAnswers> {
    let resolver_context = runtime_encrypted_dns_context_for_host(host, runtime_context);
    let resolver = build_encrypted_dns_resolver_for_host(host, runtime_context, protect_path)?;
    let answers = query_ip_answers(&resolver, host, DNS_RECORD_TYPE_A, &|| false)?;
    Ok(EncryptedDnsIpAnswers { label: crate::encrypted_dns_label(&resolver_context), answers })
}

pub(crate) fn resolve_ws_tunnel_addr_with_default(
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

pub(crate) fn resolve_host_via_encrypted_dns_with_default(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
    ipv6_enabled: bool,
    default_context: impl FnOnce() -> ProxyEncryptedDnsContext,
) -> io::Result<SocketAddr> {
    resolve_host_via_encrypted_dns_with_default_and_cancel(
        host,
        runtime_context,
        protect_path,
        ipv6_enabled,
        default_context,
        &|| false,
    )
}

fn resolve_host_via_encrypted_dns_with_default_and_cancel(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
    ipv6_enabled: bool,
    default_context: impl FnOnce() -> ProxyEncryptedDnsContext,
    should_cancel: &dyn Fn() -> bool,
) -> io::Result<SocketAddr> {
    let resolver_context = runtime_encrypted_dns_context_for_host_with_default(host, runtime_context, default_context);
    let resolver = EncryptedDnsResolver::with_connect_hooks(
        encrypted_dns_endpoint(&resolver_context)?,
        EncryptedDnsTransport::Direct,
        build_direct_connect_hooks(protect_path),
    )
    .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))?;

    if let Some(ip) = resolve_first_ip(&resolver, host, DNS_RECORD_TYPE_A, |ip| ip.is_ipv4(), should_cancel)? {
        record_successful_resolver(host, &resolver_context, ip);
        return Ok(SocketAddr::new(ip, 0));
    }
    if ipv6_enabled
        && let Some(ip) = resolve_first_ip(&resolver, host, DNS_RECORD_TYPE_AAAA, |ip| ip.is_ipv6(), should_cancel)?
    {
        record_successful_resolver(host, &resolver_context, ip);
        return Ok(SocketAddr::new(ip, 0));
    }

    Err(io::Error::new(io::ErrorKind::AddrNotAvailable, "encrypted DNS resolved no usable socket address"))
}

pub(crate) fn ws_tunnel_host(dc: TelegramDc) -> String {
    ws_host(dc).expect("WS bootstrap only resolves tunnelable Telegram DCs")
}

fn resolve_first_ip(
    resolver: &EncryptedDnsResolver,
    host: &str,
    record_type: u16,
    predicate: impl Fn(IpAddr) -> bool,
    should_cancel: &dyn Fn() -> bool,
) -> io::Result<Option<IpAddr>> {
    let answers = query_ip_answers(resolver, host, record_type, should_cancel)?;
    Ok(answers.into_iter().find(|ip| predicate(*ip)))
}

fn query_ip_answers(
    resolver: &EncryptedDnsResolver,
    host: &str,
    record_type: u16,
    should_cancel: &dyn Fn() -> bool,
) -> io::Result<Vec<IpAddr>> {
    let query = build_dns_query(host, record_type, current_query_id())?;
    let response = resolver.exchange_blocking_with_cancel(&query, should_cancel).map_err(|err| {
        let kind = if should_cancel() { io::ErrorKind::Interrupted } else { io::ErrorKind::Other };
        io::Error::new(kind, err.to_string())
    })?;
    if should_cancel() {
        return Err(io::Error::new(io::ErrorKind::Interrupted, "encrypted DNS lookup cancelled"));
    }
    extract_ip_answers_for_query(&query, &response)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err.to_string()))
}
