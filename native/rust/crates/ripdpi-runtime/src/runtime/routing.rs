use std::io::{self, Read, Write};
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::time::{SystemTime, UNIX_EPOCH};

use ciadpi_config::{
    DETECT_CONNECT, DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT, DETECT_SILENT_DROP, DETECT_TCP_RESET,
    DETECT_TLS_ALERT, DETECT_TLS_HANDSHAKE_FAILURE,
};
use ciadpi_session::{
    detect_response_trigger, TriggerEvent, S_ATP_I4, S_ATP_I6, S_AUTH_NONE, S_CMD_CONN, S_ER_GEN, S_VER5,
};
use ripdpi_dns_resolver::{
    extract_ip_answers, EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport,
};
use ripdpi_failure_classifier::{
    classify_http_blockpage, classify_redirect_failure, classify_tls_alert, classify_tls_handshake_failure,
    classify_transport_error, confirm_dns_tampering, ClassifiedFailure, FailureAction, FailureClass, FailureStage,
};
use ripdpi_proxy_config::ProxyEncryptedDnsContext;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::platform;
use crate::runtime_policy::{select_initial_group, ConnectionRoute, RouteAdvance, TransportProtocol};

use super::adaptive::{note_adaptive_fake_ttl_failure, note_adaptive_tcp_failure};
use super::retry::{build_retry_selection_penalties, maybe_emit_candidate_diversification, note_retry_failure};
use super::state::{flush_autolearn_updates, RuntimeState};

pub(super) fn select_route(
    state: &RuntimeState,
    target: SocketAddr,
    payload: Option<&[u8]>,
    host: Option<&str>,
    allow_unknown_payload: bool,
) -> io::Result<ConnectionRoute> {
    select_route_for_transport(state, target, payload, host, allow_unknown_payload, TransportProtocol::Tcp)
}

pub(super) fn select_route_for_transport(
    state: &RuntimeState,
    target: SocketAddr,
    payload: Option<&[u8]>,
    host: Option<&str>,
    allow_unknown_payload: bool,
    transport: TransportProtocol,
) -> io::Result<ConnectionRoute> {
    let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
    select_initial_group(&state.config, &mut cache, target, payload, host, allow_unknown_payload, transport)
        .ok_or_else(|| io::Error::new(io::ErrorKind::PermissionDenied, "no matching desync group"))
}

pub(super) fn connect_target(
    target: SocketAddr,
    state: &RuntimeState,
    payload: Option<&[u8]>,
    allow_unknown_payload: bool,
    host: Option<String>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    let route = select_route(state, target, payload, host.as_deref(), allow_unknown_payload)?;
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_route_selected(target, route.group_index, host.as_deref(), "initial");
    }
    connect_target_with_route(target, state, route, payload, host)
}

pub(super) fn connect_target_with_route(
    target: SocketAddr,
    state: &RuntimeState,
    mut route: ConnectionRoute,
    payload: Option<&[u8]>,
    host: Option<String>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    loop {
        match connect_target_via_group(target, state, route.group_index) {
            Ok(stream) => return Ok((stream, route)),
            Err(err) => {
                let failure = classify_transport_error(FailureStage::Connect, &err);
                emit_failure_classified(state, target, &failure, host.as_deref());
                let next = advance_route_for_failure(state, target, &route, host.clone(), payload, &failure)?;
                let Some(next) = next else {
                    return Err(err);
                };
                route = next;
            }
        }
    }
}

pub(super) fn failure_trigger_mask(failure: &ClassifiedFailure) -> u32 {
    match failure.class {
        FailureClass::DnsTampering => DETECT_DNS_TAMPER,
        FailureClass::TcpReset => DETECT_TCP_RESET,
        FailureClass::SilentDrop => DETECT_SILENT_DROP,
        FailureClass::TlsAlert => DETECT_TLS_ALERT,
        FailureClass::HttpBlockpage => DETECT_HTTP_BLOCKPAGE,
        FailureClass::QuicBreakage => 0,
        FailureClass::Redirect => DETECT_HTTP_LOCAT,
        FailureClass::TlsHandshakeFailure => DETECT_TLS_HANDSHAKE_FAILURE,
        FailureClass::ConnectFailure => DETECT_CONNECT,
        FailureClass::Unknown => 0,
    }
}

pub(super) fn failure_penalizes_strategy(failure: &ClassifiedFailure) -> bool {
    matches!(
        failure.class,
        FailureClass::TcpReset
            | FailureClass::SilentDrop
            | FailureClass::TlsAlert
            | FailureClass::HttpBlockpage
            | FailureClass::Redirect
            | FailureClass::TlsHandshakeFailure
    )
}

pub(super) fn emit_failure_classified(
    state: &RuntimeState,
    target: SocketAddr,
    failure: &ClassifiedFailure,
    host: Option<&str>,
) {
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_failure_classified(target, failure, host);
    }
}

pub(super) fn advance_route_for_failure(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
    failure: &ClassifiedFailure,
) -> io::Result<Option<ConnectionRoute>> {
    let trigger = failure_trigger_mask(failure);
    if failure.action != FailureAction::RetryWithMatchingGroup
        || trigger == 0
        || !runtime_supports_trigger(state, trigger)?
    {
        return Ok(None);
    }

    let _ = note_retry_failure(state, target, route.group_index, host.as_deref(), payload, TransportProtocol::Tcp)?;
    let penalize = failure_penalizes_strategy(failure);
    if penalize {
        if let Some(payload) = payload {
            note_adaptive_tcp_failure(state, target, route.group_index, host.as_deref(), payload)?;
        }
        note_adaptive_fake_ttl_failure(state, target, route.group_index, host.as_deref())?;
    }

    let retry_penalties =
        build_retry_selection_penalties(state, target, host.as_deref(), payload, TransportProtocol::Tcp)?;
    let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
    let next = cache.advance_route(
        &state.config,
        route,
        RouteAdvance {
            dest: target,
            payload,
            transport: TransportProtocol::Tcp,
            trigger,
            can_reconnect: true,
            host: host.clone(),
            penalize_strategy_failure: penalize,
            retry_penalties: Some(&retry_penalties),
        },
    )?;
    flush_autolearn_updates(state, &mut cache);
    drop(cache);
    if let Some(next_route) = next.as_ref() {
        maybe_emit_candidate_diversification(state, target, next_route, &retry_penalties);
    }
    if let (Some(telemetry), Some(next_route)) = (&state.telemetry, next.as_ref()) {
        telemetry.on_route_advanced(target, route.group_index, next_route.group_index, trigger, host.as_deref());
    }
    Ok(next)
}

pub(super) fn classify_response_failure(
    state: &RuntimeState,
    target: SocketAddr,
    request: &[u8],
    response: &[u8],
    host: Option<&str>,
) -> Option<ClassifiedFailure> {
    if response.starts_with(b"HTTP/1.") && ciadpi_packets::is_tls_client_hello(request) {
        if let Some(host) = host {
            if let Some(dns_tampering) = confirm_dns_tampering_for_host(state, host, target.ip()) {
                return Some(dns_tampering);
            }
        }
    }

    if matches!(detect_response_trigger(request, response), Some(TriggerEvent::Redirect)) {
        return Some(classify_redirect_failure("HTTP redirect during first response"));
    }
    if let Some(alert) = classify_tls_alert(response) {
        return Some(alert);
    }
    if let Some(blockpage) = classify_http_blockpage(response) {
        return Some(blockpage);
    }
    if matches!(detect_response_trigger(request, response), Some(TriggerEvent::SslErr)) {
        return Some(classify_tls_handshake_failure("TLS handshake failed before ServerHello"));
    }
    None
}

fn confirm_dns_tampering_for_host(state: &RuntimeState, host: &str, target_ip: IpAddr) -> Option<ClassifiedFailure> {
    let resolver_context = state
        .runtime_context
        .as_ref()
        .and_then(|context| context.encrypted_dns.as_ref())
        .cloned()
        .or_else(default_encrypted_dns_context);
    let resolver_context = resolver_context?;
    let resolver =
        EncryptedDnsResolver::new(encrypted_dns_endpoint(&resolver_context)?, EncryptedDnsTransport::Direct).ok()?;
    let query_id = ((SystemTime::now().duration_since(UNIX_EPOCH).ok()?.as_nanos() as u64) & 0xffff) as u16;
    let query = build_dns_query(host, query_id.max(1)).ok()?;
    let response = resolver.exchange_blocking(&query).ok()?;
    let answers = extract_ip_answers(&response)
        .ok()?
        .into_iter()
        .filter_map(|answer| answer.parse::<IpAddr>().ok())
        .collect::<Vec<_>>();
    confirm_dns_tampering(host, target_ip, &answers, &encrypted_dns_label(&resolver_context))
}

fn encrypted_dns_endpoint(context: &ProxyEncryptedDnsContext) -> Option<EncryptedDnsEndpoint> {
    let protocol = match context.protocol.trim().to_ascii_lowercase().as_str() {
        "dot" => EncryptedDnsProtocol::Dot,
        "dnscrypt" => EncryptedDnsProtocol::DnsCrypt,
        _ => EncryptedDnsProtocol::Doh,
    };
    let bootstrap_ips =
        context.bootstrap_ips.iter().filter_map(|value| value.parse::<IpAddr>().ok()).collect::<Vec<_>>();
    if bootstrap_ips.is_empty() {
        return None;
    }
    Some(EncryptedDnsEndpoint {
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

fn encrypted_dns_label(context: &ProxyEncryptedDnsContext) -> String {
    context
        .doh_url
        .clone()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| format!("{}:{}", context.host, context.port))
}

fn default_encrypted_dns_context() -> Option<ProxyEncryptedDnsContext> {
    Some(ProxyEncryptedDnsContext {
        resolver_id: Some("cloudflare".to_string()),
        protocol: "doh".to_string(),
        host: "cloudflare-dns.com".to_string(),
        port: 443,
        tls_server_name: Some("cloudflare-dns.com".to_string()),
        bootstrap_ips: vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()],
        doh_url: Some("https://cloudflare-dns.com/dns-query".to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    })
}

fn build_dns_query(domain: &str, query_id: u16) -> Result<Vec<u8>, io::Error> {
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

pub(super) fn note_route_success(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<&str>,
) -> io::Result<()> {
    note_route_success_for_transport(state, target, route, host, TransportProtocol::Tcp)
}

pub(super) fn note_route_success_for_transport(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<&str>,
    transport: TransportProtocol,
) -> io::Result<()> {
    let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
    cache.note_route_success_for_transport(&state.config, target, route, host, transport)?;
    flush_autolearn_updates(state, &mut cache);
    Ok(())
}

pub(super) fn connect_target_via_group(
    target: SocketAddr,
    state: &RuntimeState,
    group_index: usize,
) -> io::Result<TcpStream> {
    let group = state
        .config
        .groups
        .get(group_index)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let stream = if let Some(upstream) = group.ext_socks {
        connect_via_socks(
            target,
            upstream.addr,
            state.config.listen.bind_ip,
            state.config.protect_path.as_deref(),
            state.config.tfo,
        )
    } else {
        connect_socket(target, state.config.listen.bind_ip, state.config.protect_path.as_deref(), state.config.tfo)
    }?;

    if group.drop_sack {
        platform::attach_drop_sack(&stream)?;
    }
    if let Some(telemetry) = &state.telemetry {
        let upstream_addr = stream.peer_addr().unwrap_or(target);
        let upstream_rtt_ms = platform::tcp_round_trip_time_ms(&stream).ok().flatten();
        telemetry.on_upstream_connected(upstream_addr, upstream_rtt_ms);
    }
    Ok(stream)
}

fn connect_via_socks(
    target: SocketAddr,
    upstream: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
) -> io::Result<TcpStream> {
    let mut stream = connect_socket(upstream, bind_ip, protect_path, tfo)?;
    stream.write_all(&[S_VER5, 1, S_AUTH_NONE])?;
    let mut auth = [0u8; 2];
    stream.read_exact(&mut auth)?;
    if auth != [S_VER5, S_AUTH_NONE] {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "upstream socks auth failed"));
    }

    let request = encode_upstream_socks_connect(target);
    stream.write_all(&request)?;
    let reply = read_upstream_socks_reply(&mut stream)?;
    if reply.get(1).copied().unwrap_or(S_ER_GEN) != 0 {
        return Err(io::Error::new(io::ErrorKind::ConnectionRefused, "upstream socks connect failed"));
    }
    Ok(stream)
}

pub(super) fn encode_upstream_socks_connect(target: SocketAddr) -> Vec<u8> {
    let mut out = vec![S_VER5, S_CMD_CONN, 0];
    match target {
        SocketAddr::V4(addr) => {
            out.push(S_ATP_I4);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            out.push(S_ATP_I6);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    out
}

pub(super) fn read_upstream_socks_reply(stream: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    stream.read_exact(&mut header)?;
    let mut out = header.to_vec();
    match header[3] {
        S_ATP_I4 => {
            let mut tail = [0u8; 6];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        S_ATP_I6 => {
            let mut tail = [0u8; 18];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len)?;
            out.extend_from_slice(&len);
            let mut tail = vec![0u8; len[0] as usize + 2];
            stream.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid upstream socks reply")),
    }
    Ok(out)
}

pub(super) fn connect_socket(
    target: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
) -> io::Result<TcpStream> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    if let Some(path) = protect_path {
        platform::protect_socket(&socket, path)?;
    }
    if tfo {
        platform::enable_tcp_fastopen_connect(&socket)?;
    }
    bind_socket(&socket, bind_ip, target)?;
    socket.connect(&SockAddr::from(target))?;
    let stream: TcpStream = socket.into();
    stream.set_nodelay(true)?;
    Ok(stream)
}

fn bind_socket(socket: &Socket, bind_ip: IpAddr, target: SocketAddr) -> io::Result<()> {
    if is_unspecified(bind_ip) {
        return Ok(());
    }
    let bind_addr = match (bind_ip, target) {
        (IpAddr::V4(ip), SocketAddr::V4(_)) => SocketAddr::new(IpAddr::V4(ip), 0),
        (IpAddr::V6(ip), SocketAddr::V6(_)) => SocketAddr::new(IpAddr::V6(ip), 0),
        _ => return Err(io::Error::new(io::ErrorKind::InvalidInput, "bind ip family does not match target family")),
    };
    socket.bind(&SockAddr::from(bind_addr))
}

fn is_unspecified(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => ip.is_unspecified(),
        IpAddr::V6(ip) => ip.is_unspecified(),
    }
}

pub(super) fn runtime_supports_trigger(state: &RuntimeState, trigger: u32) -> io::Result<bool> {
    let cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
    Ok(cache.supports_trigger(trigger))
}

pub(super) fn reconnect_target(
    target: SocketAddr,
    state: &RuntimeState,
    mut route: ConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    loop {
        super::retry::apply_retry_pacing_before_connect(state, target, &route, host.as_deref(), payload)?;
        match connect_target_via_group(target, state, route.group_index) {
            Ok(stream) => return Ok((stream, route)),
            Err(err) => {
                let failure = classify_transport_error(FailureStage::Connect, &err);
                emit_failure_classified(state, target, &failure, host.as_deref());
                let next = advance_route_for_failure(state, target, &route, host.clone(), payload, &failure)?;
                let Some(next) = next else {
                    return Err(err);
                };
                route = next;
            }
        }
    }
}

#[cfg(test)]
pub(super) fn trigger_flag(trigger: TriggerEvent) -> u32 {
    match trigger {
        TriggerEvent::Redirect => DETECT_HTTP_LOCAT,
        TriggerEvent::SslErr => DETECT_TLS_HANDSHAKE_FAILURE,
        TriggerEvent::Connect => DETECT_CONNECT,
        TriggerEvent::Torst => ciadpi_config::DETECT_TORST,
    }
}
