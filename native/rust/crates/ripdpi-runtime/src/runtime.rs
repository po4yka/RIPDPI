mod handshake;
mod listeners;
mod state;

use std::collections::{BTreeMap, HashMap};
use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, Shutdown, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::platform;
use crate::retry_stealth::{adaptive_signature_hash, target_key, RetryDecision, RetryLane, RetrySignature};
use crate::runtime_policy::{
    extract_host, extract_host_info, select_initial_group, ConnectionRoute, HostSource, RetrySelectionPenalty,
    RouteAdvance, TransportProtocol,
};
use crate::EmbeddedProxyControl;
use ciadpi_config::{
    DesyncGroup, QuicInitialMode, RuntimeConfig, TcpChainStepKind, DETECT_CONNECT, DETECT_DNS_TAMPER,
    DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT, DETECT_SILENT_DROP, DETECT_TCP_RESET, DETECT_TLS_ALERT,
    DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TORST,
};
use ciadpi_desync::{
    activation_filter_matches, build_fake_packet, build_fake_region_bytes, build_hostfake_bytes, plan_tcp, plan_udp,
    resolve_hostfake_span, ActivationContext, ActivationTransport, AdaptivePlannerHints, DesyncAction, DesyncPlan,
};
use ciadpi_packets::parse_quic_initial;
use ciadpi_session::{
    detect_response_trigger, OutboundProgress, SessionState, SocketType, TriggerEvent, S_ATP_I4, S_ATP_I6, S_AUTH_NONE,
    S_CMD_CONN, S_ER_GEN, S_VER5,
};
use ripdpi_dns_resolver::{
    extract_ip_answers, EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport,
};
use ripdpi_failure_classifier::{
    classify_http_blockpage, classify_redirect_failure, classify_tls_alert, classify_tls_handshake_failure,
    classify_transport_error, confirm_dns_tampering, ClassifiedFailure, FailureAction, FailureClass, FailureStage,
};
use ripdpi_proxy_config::ProxyEncryptedDnsContext;
use socket2::{Domain, Protocol, SockAddr, SockRef, Socket, Type};

use self::listeners::{build_listener, run_proxy_with_listener_internal};
use self::state::{flush_autolearn_updates, RuntimeState, DESYNC_SEED_BASE, UDP_FLOW_IDLE_TIMEOUT};

#[derive(Debug, Clone)]
struct UdpFlowActivationState {
    session: SessionState,
    last_used: Instant,
    route: ConnectionRoute,
    host: Option<String>,
    payload: Vec<u8>,
    awaiting_response: bool,
}

pub fn run_proxy(config: RuntimeConfig) -> io::Result<()> {
    let listener = create_listener(&config)?;
    run_proxy_with_listener(config, listener)
}

pub fn create_listener(config: &RuntimeConfig) -> io::Result<TcpListener> {
    build_listener(config)
}

pub fn run_proxy_with_listener(config: RuntimeConfig, listener: TcpListener) -> io::Result<()> {
    run_proxy_with_listener_internal(config, listener, None)
}

pub fn run_proxy_with_embedded_control(
    config: RuntimeConfig,
    listener: TcpListener,
    control: Arc<EmbeddedProxyControl>,
) -> io::Result<()> {
    run_proxy_with_listener_internal(config, listener, Some(control))
}

fn select_route(
    state: &RuntimeState,
    target: SocketAddr,
    payload: Option<&[u8]>,
    host: Option<&str>,
    allow_unknown_payload: bool,
) -> io::Result<ConnectionRoute> {
    select_route_for_transport(state, target, payload, host, allow_unknown_payload, TransportProtocol::Tcp)
}

fn select_route_for_transport(
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

fn connect_target(
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

fn connect_target_with_route(
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

fn failure_trigger_mask(failure: &ClassifiedFailure) -> u32 {
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

fn failure_penalizes_strategy(failure: &ClassifiedFailure) -> bool {
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

fn emit_failure_classified(state: &RuntimeState, target: SocketAddr, failure: &ClassifiedFailure, host: Option<&str>) {
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_failure_classified(target, failure, host);
    }
}

fn advance_route_for_failure(
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

fn classify_response_failure(
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

fn note_route_success(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<&str>,
) -> io::Result<()> {
    note_route_success_for_transport(state, target, route, host, TransportProtocol::Tcp)
}

fn note_route_success_for_transport(
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

fn note_adaptive_fake_ttl_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.lock().map_err(|_| io::Error::other("adaptive fake ttl mutex poisoned"))?;
    resolver.note_success(group_index, target, host);
    Ok(())
}

fn note_adaptive_fake_ttl_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.lock().map_err(|_| io::Error::other("adaptive fake ttl mutex poisoned"))?;
    resolver.note_failure(group_index, target, host);
    Ok(())
}

fn note_server_ttl_for_route(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    observed_ttl: u8,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.lock().map_err(|_| io::Error::other("adaptive fake ttl mutex poisoned"))?;
    resolver.note_server_ttl(group_index, target, host, observed_ttl);
    Ok(())
}

fn resolve_adaptive_fake_ttl(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
) -> io::Result<Option<u8>> {
    let Some(auto_ttl) = group.auto_ttl else {
        return Ok(None);
    };
    let mut resolver =
        state.adaptive_fake_ttl.lock().map_err(|_| io::Error::other("adaptive fake ttl mutex poisoned"))?;
    Ok(Some(resolver.resolve(group_index, target, host, auto_ttl, group.ttl)))
}

fn resolve_adaptive_tcp_hints(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    Ok(resolver.resolve_tcp_hints(network_scope_key(&state.config), group_index, target, host, group, payload))
}

fn resolve_adaptive_udp_hints(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    group: &DesyncGroup,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<AdaptivePlannerHints> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    Ok(resolver.resolve_udp_hints(network_scope_key(&state.config), group_index, target, host, group, payload))
}

fn note_adaptive_tcp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    resolver.note_tcp_success(network_scope_key(&state.config), group_index, target, host, payload);
    Ok(())
}

fn note_adaptive_tcp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    resolver.note_tcp_failure(network_scope_key(&state.config), group_index, target, host, payload);
    Ok(())
}

fn note_adaptive_udp_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    resolver.note_udp_success(network_scope_key(&state.config), group_index, target, host, payload);
    Ok(())
}

fn note_adaptive_udp_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: &[u8],
) -> io::Result<()> {
    let mut resolver = state.adaptive_tuning.lock().map_err(|_| io::Error::other("adaptive tuning mutex poisoned"))?;
    resolver.note_udp_failure(network_scope_key(&state.config), group_index, target, host, payload);
    Ok(())
}

fn build_retry_signature(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
) -> io::Result<Option<RetrySignature>> {
    let Some(group) = state.config.groups.get(group_index) else {
        return Ok(None);
    };
    let resolved_fake_ttl = resolve_adaptive_fake_ttl(state, target, group_index, group, host)?;
    let adaptive_hints = match (transport, payload) {
        (TransportProtocol::Tcp, Some(bytes)) => {
            resolve_adaptive_tcp_hints(state, target, group_index, group, host, bytes)?
        }
        (TransportProtocol::Udp, Some(bytes)) => {
            resolve_adaptive_udp_hints(state, target, group_index, group, host, bytes)?
        }
        _ => AdaptivePlannerHints::default(),
    };
    Ok(Some(RetrySignature::new(
        network_scope_key(&state.config).unwrap_or("default"),
        retry_lane(transport, payload),
        target_key(host, target),
        group_index,
        adaptive_signature_hash(resolved_fake_ttl, &adaptive_hints),
    )))
}

fn retry_lane(transport: TransportProtocol, payload: Option<&[u8]>) -> RetryLane {
    match transport {
        TransportProtocol::Tcp if payload.is_some_and(ciadpi_packets::is_tls_client_hello) => RetryLane::TcpTls,
        TransportProtocol::Tcp => RetryLane::TcpOther,
        TransportProtocol::Udp if payload.is_some_and(|bytes| parse_quic_initial(bytes).is_some()) => {
            RetryLane::UdpQuic
        }
        TransportProtocol::Udp => RetryLane::UdpOther,
    }
}

fn note_retry_success(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
) -> io::Result<()> {
    let Some(signature) = build_retry_signature(state, target, group_index, host, payload, transport)? else {
        return Ok(());
    };
    let mut pacer = state.retry_stealth.lock().map_err(|_| io::Error::other("retry pacing mutex poisoned"))?;
    pacer.clear_success(&signature);
    Ok(())
}

fn note_retry_failure(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
) -> io::Result<Option<RetryDecision>> {
    let Some(signature) = build_retry_signature(state, target, group_index, host, payload, transport)? else {
        return Ok(None);
    };
    let mut pacer = state.retry_stealth.lock().map_err(|_| io::Error::other("retry pacing mutex poisoned"))?;
    Ok(Some(pacer.record_failure(&signature, now_millis())))
}

fn build_retry_selection_penalties(
    state: &RuntimeState,
    target: SocketAddr,
    host: Option<&str>,
    payload: Option<&[u8]>,
    transport: TransportProtocol,
) -> io::Result<BTreeMap<usize, RetrySelectionPenalty>> {
    let now_ms = now_millis();
    let mut penalties = BTreeMap::new();
    let pacer = state.retry_stealth.lock().map_err(|_| io::Error::other("retry pacing mutex poisoned"))?;
    for group_index in 0..state.config.groups.len() {
        if let Some(signature) = build_retry_signature(state, target, group_index, host, payload, transport)? {
            penalties.insert(group_index, pacer.penalty_for(&signature, now_ms));
        }
    }
    Ok(penalties)
}

fn maybe_emit_candidate_diversification(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    penalties: &BTreeMap<usize, RetrySelectionPenalty>,
) {
    let Some(selected_penalty) = penalties.get(&route.group_index).copied() else {
        return;
    };
    let cooled_alternative_exists = penalties.iter().any(|(group_index, penalty)| {
        *group_index != route.group_index && (penalty.same_signature_cooldown_ms > 0 || penalty.family_cooldown_ms > 0)
    });
    if !cooled_alternative_exists
        || (selected_penalty.same_signature_cooldown_ms > 0 && selected_penalty.family_cooldown_ms > 0)
    {
        return;
    }
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_retry_paced(target, route.group_index, "candidate_order_diversified", 0);
    }
}

fn apply_retry_pacing_before_connect(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<&str>,
    payload: Option<&[u8]>,
) -> io::Result<()> {
    let Some(signature) =
        build_retry_signature(state, target, route.group_index, host, payload, TransportProtocol::Tcp)?
    else {
        return Ok(());
    };
    let decision = {
        let pacer = state.retry_stealth.lock().map_err(|_| io::Error::other("retry pacing mutex poisoned"))?;
        pacer.retry_delay_for(&signature, now_millis())
    };
    let Some(decision) = decision.filter(|value| value.backoff_ms > 0) else {
        return Ok(());
    };
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_retry_paced(target, route.group_index, decision.reason, decision.backoff_ms);
    }
    thread::sleep(Duration::from_millis(decision.backoff_ms));
    Ok(())
}

fn network_scope_key(config: &RuntimeConfig) -> Option<&str> {
    config.network_scope_key.as_deref().map(str::trim).filter(|value| !value.is_empty())
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .map_or(0, |value| value.as_millis().min(u128::from(u64::MAX)) as u64)
}

fn connect_target_via_group(target: SocketAddr, state: &RuntimeState, group_index: usize) -> io::Result<TcpStream> {
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

fn encode_upstream_socks_connect(target: SocketAddr) -> Vec<u8> {
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

fn read_upstream_socks_reply(stream: &mut TcpStream) -> io::Result<Vec<u8>> {
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

fn build_udp_relay_socket(ip: IpAddr, protect_path: Option<&str>) -> io::Result<UdpSocket> {
    let bind_addr = SocketAddr::new(ip, 0);
    let domain = match bind_addr {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_reuse_address(true)?;
    if let Some(path) = protect_path {
        platform::protect_socket(&socket, path)?;
    }
    socket.bind(&SockAddr::from(bind_addr))?;
    let socket: UdpSocket = socket.into();
    socket.set_read_timeout(Some(Duration::from_millis(250)))?;
    socket.set_write_timeout(Some(Duration::from_secs(5)))?;
    Ok(socket)
}

fn udp_associate_loop(relay: UdpSocket, state: RuntimeState, running: Arc<AtomicBool>) -> io::Result<()> {
    let mut udp_client_addr = None;
    let mut buffer = [0u8; 65_535];
    let mut flow_state = HashMap::<(SocketAddr, SocketAddr), UdpFlowActivationState>::new();

    while running.load(Ordering::Relaxed) {
        expire_udp_flows(&state, &mut flow_state, Instant::now())?;
        match relay.recv_from(&mut buffer) {
            Ok((n, sender)) => {
                let now = Instant::now();
                let known_client = udp_client_addr;
                if known_client.is_none() || known_client == Some(sender) {
                    udp_client_addr = Some(sender);
                    let Some((target, payload)) = parse_socks5_udp_packet(&buffer[..n], &state.config) else {
                        continue;
                    };
                    let host_info = extract_host_info(&state.config, payload);
                    let host = host_info.as_ref().map(|value| value.host.clone());
                    let route = match select_route_for_transport(
                        &state,
                        target,
                        Some(payload),
                        host.as_deref(),
                        false,
                        TransportProtocol::Udp,
                    ) {
                        Ok(route) => route,
                        Err(_) => continue,
                    };
                    if let Some(telemetry) = &state.telemetry {
                        telemetry.on_route_selected(target, route.group_index, host.as_deref(), "initial");
                    }
                    if let Some(host) =
                        host.clone().filter(|_| should_cache_udp_host(&state.config, host_info.as_ref()))
                    {
                        if let Ok(mut cache) = state.cache.lock() {
                            let _ =
                                cache.store(&state.config, target, route.group_index, route.attempted_mask, Some(host));
                        }
                    }
                    let Some(group) = state.config.groups.get(route.group_index) else {
                        continue;
                    };
                    let activation = {
                        let adaptive_hints = resolve_adaptive_udp_hints(
                            &state,
                            target,
                            route.group_index,
                            group,
                            host.as_deref(),
                            payload,
                        )?;
                        let entry = flow_state.entry((sender, target)).or_insert_with(|| UdpFlowActivationState {
                            session: SessionState::default(),
                            last_used: now,
                            route: route.clone(),
                            host: host.clone(),
                            payload: payload.to_vec(),
                            awaiting_response: true,
                        });
                        entry.last_used = now;
                        entry.route = route.clone();
                        entry.host = host.clone();
                        entry.payload.clear();
                        entry.payload.extend_from_slice(payload);
                        entry.awaiting_response = true;
                        let progress = entry.session.observe_datagram_outbound(payload);
                        activation_context_from_progress(progress, ActivationTransport::Udp, None, None, adaptive_hints)
                    };
                    let actions = plan_udp(group, payload, state.config.default_ttl, activation);
                    execute_udp_actions(&relay, target, &actions)?;
                } else if let Some(client_addr) = udp_client_addr {
                    if let Some(entry) = flow_state.get_mut(&(client_addr, sender)) {
                        entry.last_used = now;
                        entry.session.observe_inbound(&buffer[..n]);
                        if entry.awaiting_response {
                            note_adaptive_udp_success(
                                &state,
                                sender,
                                entry.route.group_index,
                                entry.host.as_deref(),
                                &entry.payload,
                            )?;
                            note_retry_success(
                                &state,
                                sender,
                                entry.route.group_index,
                                entry.host.as_deref(),
                                Some(&entry.payload),
                                TransportProtocol::Udp,
                            )?;
                            note_route_success_for_transport(
                                &state,
                                sender,
                                &entry.route,
                                entry.host.as_deref(),
                                TransportProtocol::Udp,
                            )?;
                            entry.awaiting_response = false;
                        }
                    }
                    let packet = encode_socks5_udp_packet(sender, &buffer[..n]);
                    relay.send_to(&packet, client_addr)?;
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(err) => return Err(err),
        }
    }

    expire_udp_flows(&state, &mut flow_state, Instant::now())?;
    Ok(())
}

fn expire_udp_flows(
    state: &RuntimeState,
    flow_state: &mut HashMap<(SocketAddr, SocketAddr), UdpFlowActivationState>,
    now: Instant,
) -> io::Result<()> {
    let expired = flow_state
        .iter()
        .filter(|(_, value)| now.duration_since(value.last_used) >= UDP_FLOW_IDLE_TIMEOUT)
        .map(|(key, value)| (*key, value.clone()))
        .collect::<Vec<_>>();

    for ((client_addr, target), entry) in expired {
        flow_state.remove(&(client_addr, target));
        if !entry.awaiting_response {
            continue;
        }
        let _ = note_retry_failure(
            state,
            target,
            entry.route.group_index,
            entry.host.as_deref(),
            Some(entry.payload.as_slice()),
            TransportProtocol::Udp,
        )?;
        note_adaptive_udp_failure(state, target, entry.route.group_index, entry.host.as_deref(), &entry.payload)?;
        let retry_penalties = build_retry_selection_penalties(
            state,
            target,
            entry.host.as_deref(),
            Some(entry.payload.as_slice()),
            TransportProtocol::Udp,
        )?;
        let next = {
            let mut cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
            let next = cache.advance_route(
                &state.config,
                &entry.route,
                RouteAdvance {
                    dest: target,
                    payload: Some(entry.payload.as_slice()),
                    transport: TransportProtocol::Udp,
                    trigger: DETECT_CONNECT,
                    can_reconnect: true,
                    host: entry.host.clone(),
                    penalize_strategy_failure: false,
                    retry_penalties: Some(&retry_penalties),
                },
            )?;
            flush_autolearn_updates(state, &mut cache);
            next
        };
        if let Some(next_route) = next.as_ref() {
            maybe_emit_candidate_diversification(state, target, next_route, &retry_penalties);
        }
        if let (Some(telemetry), Some(next)) = (&state.telemetry, next) {
            telemetry.on_route_advanced(
                target,
                entry.route.group_index,
                next.group_index,
                DETECT_CONNECT,
                entry.host.as_deref(),
            );
        }
    }
    Ok(())
}

fn parse_socks5_udp_packet<'a>(packet: &'a [u8], config: &RuntimeConfig) -> Option<(SocketAddr, &'a [u8])> {
    if packet.len() < 4 || packet[2] != 0 {
        return None;
    }
    let atyp = packet[3];
    match atyp {
        S_ATP_I4 => {
            if packet.len() < 10 {
                return None;
            }
            let ip = Ipv4Addr::new(packet[4], packet[5], packet[6], packet[7]);
            let port = u16::from_be_bytes([packet[8], packet[9]]);
            Some((SocketAddr::new(IpAddr::V4(ip), port), &packet[10..]))
        }
        S_ATP_I6 => {
            if packet.len() < 22 || !config.ipv6 {
                return None;
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&packet[4..20]);
            let port = u16::from_be_bytes([packet[20], packet[21]]);
            Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), &packet[22..]))
        }
        0x03 => {
            let len = *packet.get(4)? as usize;
            let offset = 5 + len;
            if packet.len() < offset + 2 || !config.resolve {
                return None;
            }
            let host = std::str::from_utf8(&packet[5..offset]).ok()?;
            let port = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
            let resolved = handshake::resolve_name(host, SocketType::Datagram, config)?;
            Some((SocketAddr::new(resolved.ip(), port), &packet[offset + 2..]))
        }
        _ => None,
    }
}

fn encode_socks5_udp_packet(sender: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut packet = vec![0, 0, 0];
    match sender {
        SocketAddr::V4(addr) => {
            packet.push(S_ATP_I4);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            packet.push(S_ATP_I6);
            packet.extend_from_slice(&addr.ip().octets());
            packet.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    packet.extend_from_slice(payload);
    packet
}

fn execute_udp_actions(relay: &UdpSocket, target: SocketAddr, actions: &[DesyncAction]) -> io::Result<()> {
    for action in actions {
        match action {
            DesyncAction::Write(bytes) => {
                relay.send_to(bytes, target)?;
            }
            DesyncAction::SetTtl(ttl) => {
                set_udp_ttl(relay, target, *ttl)?;
            }
            DesyncAction::RestoreDefaultTtl => {}
            DesyncAction::WriteUrgent { .. }
            | DesyncAction::SetMd5Sig { .. }
            | DesyncAction::AttachDropSack
            | DesyncAction::DetachDropSack
            | DesyncAction::AwaitWritable => {}
        }
    }
    Ok(())
}

fn set_udp_ttl(relay: &UdpSocket, target: SocketAddr, ttl: u8) -> io::Result<()> {
    match target {
        SocketAddr::V4(_) => relay.set_ttl(ttl as u32),
        SocketAddr::V6(_) => Ok(()),
    }
}

fn should_cache_udp_host(config: &RuntimeConfig, host: Option<&crate::runtime_policy::ExtractedHost>) -> bool {
    match host.map(|value| value.source) {
        Some(HostSource::Quic) => matches!(config.quic_initial_mode, QuicInitialMode::RouteAndCache),
        Some(HostSource::Http | HostSource::Tls) => true,
        None => false,
    }
}

fn connect_socket(target: SocketAddr, bind_ip: IpAddr, protect_path: Option<&str>, tfo: bool) -> io::Result<TcpStream> {
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

fn relay(
    mut client: TcpStream,
    mut upstream: TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    mut route: ConnectionRoute,
    seed_request: Option<Vec<u8>>,
) -> io::Result<()> {
    let mut session_state = SessionState::default();
    let mut success_recorded = false;
    let mut success_host = seed_request.as_ref().and_then(|payload| extract_host(&state.config, payload));
    let mut success_payload = seed_request.clone();

    if seed_request.is_some() || needs_first_exchange(state)? {
        let request_timeout = client.read_timeout()?;
        let first_request = if let Some(seed) = seed_request {
            Some(seed)
        } else {
            read_optional_first_request(&mut client, request_timeout)?
        };
        if let Some(first_request) = first_request {
            let original_request = first_request;
            let host = extract_host(&state.config, &original_request);
            success_host = host.clone();
            success_payload = Some(original_request.clone());

            loop {
                session_state = SessionState::default();
                let progress = session_state.observe_outbound(&original_request);
                let group = state.config.groups[route.group_index].clone();
                if let Err(err) = send_with_group(
                    &mut upstream,
                    state,
                    route.group_index,
                    &group,
                    &original_request,
                    progress,
                    host.as_deref(),
                    target,
                ) {
                    let failure = classify_transport_error(FailureStage::FirstWrite, &err);
                    emit_failure_classified(state, target, &failure, host.as_deref());
                    let next = advance_route_for_failure(
                        state,
                        target,
                        &route,
                        host.clone(),
                        Some(&original_request),
                        &failure,
                    )?;
                    let Some(next) = next else {
                        return Err(err);
                    };
                    route = next;
                    upstream = reconnect_target(target, state, route.clone(), host.clone(), Some(&original_request))?.0;
                    continue;
                }

                match read_first_response(
                    state,
                    target,
                    host.as_deref(),
                    &mut upstream,
                    &state.config,
                    &original_request,
                )? {
                    FirstResponse::Forward(bytes, server_ttl) => {
                        session_state.observe_inbound(&bytes);
                        client.write_all(&bytes)?;
                        if session_state.recv_count > 0 {
                            if let Some(ttl) = server_ttl {
                                note_server_ttl_for_route(state, target, route.group_index, host.as_deref(), ttl)?;
                            }
                            note_adaptive_tcp_success(
                                state,
                                target,
                                route.group_index,
                                host.as_deref(),
                                &original_request,
                            )?;
                            note_retry_success(
                                state,
                                target,
                                route.group_index,
                                host.as_deref(),
                                Some(&original_request),
                                TransportProtocol::Tcp,
                            )?;
                            note_adaptive_fake_ttl_success(state, target, route.group_index, host.as_deref())?;
                            note_route_success(state, target, &route, host.as_deref())?;
                            success_recorded = true;
                        }
                        break;
                    }
                    FirstResponse::NoData => break,
                    FirstResponse::Failure { failure, response_bytes } => {
                        emit_failure_classified(state, target, &failure, host.as_deref());
                        let next = advance_route_for_failure(
                            state,
                            target,
                            &route,
                            host.clone(),
                            Some(&original_request),
                            &failure,
                        )?;
                        if let Some(next) = next {
                            route = next;
                            upstream =
                                reconnect_target(target, state, route.clone(), host.clone(), Some(&original_request))?
                                    .0;
                            continue;
                        }
                        if failure.action == FailureAction::ResolverOverrideRecommended {
                            return Err(io::Error::new(io::ErrorKind::ConnectionReset, failure.evidence.summary));
                        }
                        if let Some(bytes) = response_bytes {
                            session_state.observe_inbound(&bytes);
                            client.write_all(&bytes)?;
                            break;
                        }
                        if failure.class == FailureClass::SilentDrop {
                            break;
                        }
                        return Err(io::Error::new(io::ErrorKind::ConnectionReset, failure.evidence.summary));
                    }
                }
            }
        }
    }

    let final_state = relay_streams(client, upstream, state, route.group_index, session_state)?;
    if !success_recorded && final_state.recv_count > 0 {
        if let Some(ref request) = success_payload {
            note_adaptive_tcp_success(state, target, route.group_index, success_host.as_deref(), request)?;
            note_retry_success(
                state,
                target,
                route.group_index,
                success_host.as_deref(),
                Some(request),
                TransportProtocol::Tcp,
            )?;
        }
        note_adaptive_fake_ttl_success(state, target, route.group_index, success_host.as_deref())?;
        note_route_success(state, target, &route, success_host.as_deref())?;
    }
    Ok(())
}

fn relay_streams(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session_seed: SessionState,
) -> io::Result<SessionState> {
    client.set_read_timeout(None)?;
    client.set_write_timeout(None)?;
    upstream.set_read_timeout(None)?;
    upstream.set_write_timeout(None)?;

    let client_reader = client.try_clone()?;
    let client_writer = client.try_clone()?;
    let upstream_reader = upstream.try_clone()?;
    let upstream_writer = upstream.try_clone()?;
    let session_state = Arc::new(Mutex::new(session_seed));
    let outbound_session = session_state.clone();
    let inbound_session = session_state.clone();
    let outbound_state = state.clone();
    let group = state
        .config
        .groups
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let drop_sack = group.drop_sack;

    let down = thread::Builder::new()
        .name("ripdpi-dn".into())
        .spawn(move || copy_inbound_half(upstream_reader, client_writer, inbound_session))
        .expect("failed to spawn inbound relay thread");
    let up = thread::Builder::new()
        .name("ripdpi-up".into())
        .spawn(move || {
            copy_outbound_half(client_reader, upstream_writer, outbound_state, group_index, outbound_session)
        })
        .expect("failed to spawn outbound relay thread");

    let up_result = up.join().map_err(|_| io::Error::other("upstream thread panicked"))?;
    let down_result = down.join().map_err(|_| io::Error::other("downstream thread panicked"))?;

    if drop_sack {
        let _ = platform::detach_drop_sack(&upstream);
    }

    up_result?;
    down_result?;
    session_state.lock().map_err(|_| io::Error::other("session mutex poisoned")).map(|state| state.clone())
}

fn activation_context_from_progress(
    progress: OutboundProgress,
    transport: ActivationTransport,
    tcp_segment_hint: Option<ciadpi_desync::TcpSegmentHint>,
    resolved_fake_ttl: Option<u8>,
    adaptive: AdaptivePlannerHints,
) -> ActivationContext {
    ActivationContext {
        round: progress.round as i64,
        payload_size: progress.payload_size as i64,
        stream_start: progress.stream_start as i64,
        stream_end: progress.stream_end as i64,
        transport,
        tcp_segment_hint,
        resolved_fake_ttl,
        adaptive,
    }
}

fn needs_first_exchange(state: &RuntimeState) -> io::Result<bool> {
    Ok(runtime_supports_trigger(state, DETECT_HTTP_LOCAT)?
        || runtime_supports_trigger(state, DETECT_HTTP_BLOCKPAGE)?
        || runtime_supports_trigger(state, DETECT_TLS_HANDSHAKE_FAILURE)?
        || runtime_supports_trigger(state, DETECT_TLS_ALERT)?
        || runtime_supports_trigger(state, DETECT_TCP_RESET)?
        || runtime_supports_trigger(state, DETECT_SILENT_DROP)?
        || runtime_supports_trigger(state, DETECT_DNS_TAMPER)?
        || state.config.host_autolearn_enabled)
}

fn read_optional_first_request(
    client: &mut TcpStream,
    fallback_timeout: Option<Duration>,
) -> io::Result<Option<Vec<u8>>> {
    client.set_read_timeout(Some(Duration::from_millis(250)))?;
    let mut buffer = vec![0u8; 16_384];
    let result = match client.read(&mut buffer) {
        Ok(0) => Ok(None),
        Ok(n) => {
            buffer.truncate(n);
            Ok(Some(buffer))
        }
        Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => Ok(None),
        Err(err) => Err(err),
    };
    client.set_read_timeout(fallback_timeout)?;
    result
}

fn read_first_response(
    state: &RuntimeState,
    target: SocketAddr,
    host: Option<&str>,
    upstream: &mut TcpStream,
    config: &RuntimeConfig,
    request: &[u8],
) -> io::Result<FirstResponse> {
    let _ = platform::enable_recv_ttl(upstream);
    let mut collected = Vec::new();
    let mut chunk = vec![0u8; config.buffer_size.max(16_384)];
    let mut tls_partial = TlsRecordTracker::new(request, config);
    let mut timeout_count = 0i32;
    let mut observed_server_ttl: Option<u8> = None;

    loop {
        upstream.set_read_timeout(first_response_timeout(config, &tls_partial))?;
        let read_result = if collected.is_empty() {
            platform::read_chunk_with_ttl(upstream, &mut chunk).map(|(n, ttl)| {
                if ttl.is_some() {
                    observed_server_ttl = ttl;
                }
                n
            })
        } else {
            upstream.read(&mut chunk)
        };
        let result = match read_result {
            Ok(0) => Ok(FirstResponse::Failure {
                failure: ClassifiedFailure::new(
                    FailureClass::SilentDrop,
                    FailureStage::FirstResponse,
                    FailureAction::RetryWithMatchingGroup,
                    "upstream closed before first response",
                ),
                response_bytes: None,
            }),
            Ok(n) => {
                collected.extend_from_slice(&chunk[..n]);
                tls_partial.observe(&chunk[..n]);

                if tls_partial.waiting_for_tls_record() {
                    continue;
                }

                if let Some(failure) = classify_response_failure(state, target, request, &collected, host) {
                    Ok(FirstResponse::Failure { failure, response_bytes: Some(collected) })
                } else {
                    Ok(FirstResponse::Forward(collected, observed_server_ttl))
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if tls_partial.waiting_for_tls_record() {
                    timeout_count += 1;
                    if timeout_count >= timeout_count_limit(config) {
                        Ok(FirstResponse::Failure {
                            failure: ClassifiedFailure::new(
                                FailureClass::SilentDrop,
                                FailureStage::FirstResponse,
                                FailureAction::RetryWithMatchingGroup,
                                "partial TLS response timed out",
                            ),
                            response_bytes: None,
                        })
                    } else {
                        continue;
                    }
                } else if config.timeout_ms != 0 {
                    Ok(FirstResponse::Failure {
                        failure: classify_transport_error(FailureStage::FirstResponse, &err),
                        response_bytes: None,
                    })
                } else {
                    Ok(FirstResponse::NoData)
                }
            }
            Err(err)
                if matches!(
                    err.kind(),
                    io::ErrorKind::ConnectionReset
                        | io::ErrorKind::ConnectionAborted
                        | io::ErrorKind::BrokenPipe
                        | io::ErrorKind::ConnectionRefused
                        | io::ErrorKind::InvalidInput
                        | io::ErrorKind::TimedOut
                        | io::ErrorKind::HostUnreachable
                ) =>
            {
                Ok(FirstResponse::Failure {
                    failure: classify_transport_error(FailureStage::FirstResponse, &err),
                    response_bytes: None,
                })
            }
            Err(err) => Err(err),
        };
        let _ = upstream.set_read_timeout(None);
        return result;
    }
}

fn first_response_timeout(config: &RuntimeConfig, tls_partial: &TlsRecordTracker) -> Option<Duration> {
    if tls_partial.active() {
        Some(Duration::from_millis(config.partial_timeout_ms as u64))
    } else if config.timeout_ms != 0 {
        Some(Duration::from_millis(config.timeout_ms as u64))
    } else if config.groups.iter().any(|group| {
        group.detect
            & (DETECT_HTTP_LOCAT
                | DETECT_HTTP_BLOCKPAGE
                | DETECT_TLS_HANDSHAKE_FAILURE
                | DETECT_TLS_ALERT
                | DETECT_TORST)
            != 0
    }) {
        Some(Duration::from_millis(250))
    } else {
        None
    }
}

fn timeout_count_limit(config: &RuntimeConfig) -> i32 {
    config.timeout_count_limit.max(1)
}

#[cfg(test)]
fn response_trigger_supported(config: &RuntimeConfig, trigger: TriggerEvent) -> bool {
    let flag = match trigger {
        TriggerEvent::Redirect => DETECT_HTTP_LOCAT,
        TriggerEvent::SslErr => DETECT_TLS_HANDSHAKE_FAILURE,
        TriggerEvent::Connect => DETECT_CONNECT,
        TriggerEvent::Torst => DETECT_TORST,
    };
    config.groups.iter().any(|group| group.detect & flag != 0)
}

#[derive(Default)]
struct TlsRecordTracker {
    enabled: bool,
    disabled: bool,
    record_pos: usize,
    record_size: usize,
    header: [u8; 5],
    total_bytes: usize,
    bytes_limit: usize,
}

impl TlsRecordTracker {
    fn new(request: &[u8], config: &RuntimeConfig) -> Self {
        Self {
            enabled: ciadpi_packets::is_tls_client_hello(request) && config.partial_timeout_ms != 0,
            disabled: false,
            record_pos: 0,
            record_size: 0,
            header: [0; 5],
            total_bytes: 0,
            bytes_limit: config.timeout_bytes_limit.max(0) as usize,
        }
    }

    fn active(&self) -> bool {
        self.enabled && !self.disabled
    }

    fn waiting_for_tls_record(&self) -> bool {
        self.active() && self.record_pos != 0 && self.record_pos != self.record_size
    }

    fn observe(&mut self, bytes: &[u8]) {
        if !self.active() {
            return;
        }

        self.total_bytes += bytes.len();
        if self.bytes_limit != 0 && self.total_bytes > self.bytes_limit {
            self.disabled = true;
            return;
        }

        let mut pos = 0usize;
        while pos < bytes.len() {
            if self.record_pos < 5 {
                self.header[self.record_pos] = bytes[pos];
                self.record_pos += 1;
                pos += 1;
                if self.record_pos < 5 {
                    continue;
                }
                self.record_size = usize::from(u16::from_be_bytes([self.header[3], self.header[4]])) + 5;
                let rec_type = self.header[0];
                if !(0x14..=0x18).contains(&rec_type) {
                    self.disabled = true;
                    return;
                }
            }

            if self.record_pos == self.record_size {
                self.record_pos = 0;
                self.record_size = 0;
                continue;
            }

            let remaining = self.record_size.saturating_sub(self.record_pos);
            if remaining == 0 {
                self.disabled = true;
                return;
            }
            let take = remaining.min(bytes.len() - pos);
            self.record_pos += take;
            pos += take;
        }
    }
}

fn reconnect_target(
    target: SocketAddr,
    state: &RuntimeState,
    mut route: ConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    loop {
        apply_retry_pacing_before_connect(state, target, &route, host.as_deref(), payload)?;
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

fn runtime_supports_trigger(state: &RuntimeState, trigger: u32) -> io::Result<bool> {
    let cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
    Ok(cache.supports_trigger(trigger))
}

#[cfg(test)]
fn trigger_flag(trigger: TriggerEvent) -> u32 {
    match trigger {
        TriggerEvent::Redirect => DETECT_HTTP_LOCAT,
        TriggerEvent::SslErr => DETECT_TLS_HANDSHAKE_FAILURE,
        TriggerEvent::Connect => DETECT_CONNECT,
        TriggerEvent::Torst => DETECT_TORST,
    }
}

enum FirstResponse {
    Forward(Vec<u8>, Option<u8>),
    Failure { failure: ClassifiedFailure, response_bytes: Option<Vec<u8>> },
    NoData,
}

fn copy_inbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    session: Arc<Mutex<SessionState>>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    loop {
        let n = reader.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        if let Ok(mut state) = session.lock() {
            state.observe_inbound(&buffer[..n]);
        }
        writer.write_all(&buffer[..n])?;
    }
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}

fn copy_outbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    state: RuntimeState,
    group_index: usize,
    session: Arc<Mutex<SessionState>>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    let mut remembered_host = None::<String>;
    loop {
        let n = reader.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        let payload = &buffer[..n];
        let progress = {
            let mut state = session.lock().map_err(|_| io::Error::other("session mutex poisoned"))?;
            state.observe_outbound(payload)
        };
        let parsed_host = extract_host(&state.config, payload);
        if parsed_host.is_some() {
            remembered_host = parsed_host.clone();
        }
        let group = state
            .config
            .groups
            .get(group_index)
            .cloned()
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
        let peer_addr = writer.peer_addr()?;
        send_with_group(
            &mut writer,
            &state,
            group_index,
            &group,
            payload,
            progress,
            parsed_host.as_deref().or(remembered_host.as_deref()),
            peer_addr,
        )?;
    }
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}

fn send_with_group(
    writer: &mut TcpStream,
    state: &RuntimeState,
    group_index: usize,
    group: &DesyncGroup,
    payload: &[u8],
    progress: OutboundProgress,
    host: Option<&str>,
    target: SocketAddr,
) -> io::Result<()> {
    let resolved_fake_ttl = resolve_adaptive_fake_ttl(state, target, group_index, group, host)?;
    let adaptive_hints = resolve_adaptive_tcp_hints(state, target, group_index, group, host, payload)?;
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        platform::tcp_segment_hint(writer).ok().flatten(),
        resolved_fake_ttl,
        adaptive_hints,
    );
    if should_desync_tcp(group, context) {
        let seed = DESYNC_SEED_BASE + progress.round.saturating_sub(1);
        match plan_tcp(group, payload, seed, state.config.default_ttl, context) {
            Ok(plan) if requires_special_tcp_execution(group) => {
                execute_tcp_plan(writer, &state.config, group, &plan, seed, resolved_fake_ttl)?;
            }
            Ok(plan) => execute_tcp_actions(
                writer,
                &plan.actions,
                state.config.default_ttl,
                state.config.wait_send,
                Duration::from_millis(state.config.await_interval.max(1) as u64),
            )?,
            Err(_) => writer.write_all(payload)?,
        }
    } else {
        writer.write_all(payload)?;
    }
    Ok(())
}

fn should_desync_tcp(group: &DesyncGroup, context: ActivationContext) -> bool {
    has_tcp_actions(group) && activation_filter_matches(group.activation_filter(), context)
}

fn has_tcp_actions(group: &DesyncGroup) -> bool {
    !group.effective_tcp_chain().is_empty() || group.mod_http != 0 || group.tlsminor.is_some()
}

fn requires_special_tcp_execution(group: &DesyncGroup) -> bool {
    let supports_fake_retransmit = platform::supports_fake_retransmit();
    group.effective_tcp_chain().iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::Fake)
            || (supports_fake_retransmit
                && matches!(step.kind, TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder))
    })
}

fn execute_tcp_actions(
    writer: &mut TcpStream,
    actions: &[DesyncAction],
    default_ttl: u8,
    wait_send: bool,
    await_interval: Duration,
) -> io::Result<()> {
    for action in actions {
        match action {
            DesyncAction::Write(bytes) => writer.write_all(bytes)?,
            DesyncAction::WriteUrgent { prefix, urgent_byte } => send_out_of_band(writer, prefix, *urgent_byte)?,
            DesyncAction::SetTtl(ttl) => set_stream_ttl(writer, *ttl)?,
            DesyncAction::RestoreDefaultTtl => {
                if default_ttl != 0 {
                    set_stream_ttl(writer, default_ttl)?;
                }
            }
            DesyncAction::SetMd5Sig { key_len } => platform::set_tcp_md5sig(writer, *key_len)?,
            DesyncAction::AttachDropSack => {}
            DesyncAction::DetachDropSack => {}
            DesyncAction::AwaitWritable => platform::wait_tcp_stage(writer, wait_send, await_interval)?,
        }
    }
    Ok(())
}

fn execute_tcp_plan(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    group: &DesyncGroup,
    plan: &DesyncPlan,
    seed: u32,
    resolved_fake_ttl: Option<u8>,
) -> io::Result<()> {
    let fake =
        if plan.steps.iter().any(|step| {
            matches!(step.kind, TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
        }) {
            Some(build_fake_packet(group, &plan.tampered, seed).map_err(|_| {
                io::Error::new(io::ErrorKind::InvalidData, "failed to build fake packet for tcp desync")
            })?)
        } else {
            None
        };
    let send_steps =
        group.effective_tcp_chain().into_iter().filter(|step| !step.kind.is_tls_prelude()).collect::<Vec<_>>();
    if send_steps.len() < plan.steps.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "tcp plan steps exceed configured send steps"));
    }

    let mut cursor = 0usize;
    for (index, step) in plan.steps.iter().enumerate() {
        let start = usize::try_from(step.start)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan start"))?;
        let end = usize::try_from(step.end)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan end"))?;
        if start < cursor || end < start || end > plan.tampered.len() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid tcp desync step bounds"));
        }
        let chunk = &plan.tampered[start..end];
        let configured_step = &send_steps[index];

        match step.kind {
            TcpChainStepKind::Split => {
                writer.write_all(chunk)?;
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
            }
            TcpChainStepKind::Oob => {
                send_out_of_band(writer, chunk, group.oob_data.unwrap_or(b'a'))?;
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
            }
            TcpChainStepKind::Disorder => {
                set_stream_ttl(writer, 1)?;
                writer.write_all(chunk)?;
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
                if config.default_ttl != 0 {
                    set_stream_ttl(writer, config.default_ttl)?;
                }
            }
            TcpChainStepKind::Disoob => {
                set_stream_ttl(writer, 1)?;
                send_out_of_band(writer, chunk, group.oob_data.unwrap_or(b'a'))?;
                platform::wait_tcp_stage(
                    writer,
                    config.wait_send,
                    Duration::from_millis(config.await_interval.max(1) as u64),
                )?;
                if config.default_ttl != 0 {
                    set_stream_ttl(writer, config.default_ttl)?;
                }
            }
            TcpChainStepKind::Fake => {
                let fake =
                    fake.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let span = chunk.len();
                let fake_end = fake.fake_offset.saturating_add(span).min(fake.bytes.len());
                let fake_chunk = &fake.bytes[fake.fake_offset..fake_end];
                if fake_chunk.len() != span {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "fake packet prefix length does not match original split span",
                    ));
                }
                platform::send_fake_tcp(
                    writer,
                    chunk,
                    fake_chunk,
                    resolved_fake_ttl.or(group.ttl).unwrap_or(8),
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;
            }
            TcpChainStepKind::FakeSplit => {
                let second = &plan.tampered[end..];
                if second.is_empty() {
                    writer.write_all(chunk)?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                    cursor = end;
                    continue;
                }
                let fake =
                    fake.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let first_fake = build_fake_region_bytes(fake, start, chunk.len());
                let second_fake = build_fake_region_bytes(fake, end, second.len());
                let fake_ttl = resolved_fake_ttl.or(group.ttl).unwrap_or(8);
                platform::send_fake_tcp(
                    writer,
                    chunk,
                    &first_fake,
                    fake_ttl,
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;
                platform::send_fake_tcp(
                    writer,
                    second,
                    &second_fake,
                    fake_ttl,
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;
                cursor = plan.tampered.len();
                break;
            }
            TcpChainStepKind::FakeDisorder => {
                let second = &plan.tampered[end..];
                if second.is_empty() {
                    set_stream_ttl(writer, 1)?;
                    writer.write_all(chunk)?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                    if config.default_ttl != 0 {
                        set_stream_ttl(writer, config.default_ttl)?;
                    }
                    cursor = end;
                    continue;
                }
                let fake =
                    fake.as_ref().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
                let first_fake = build_fake_region_bytes(fake, start, chunk.len());
                let second_fake = build_fake_region_bytes(fake, end, second.len());
                let fake_ttl = resolved_fake_ttl.or(group.ttl).unwrap_or(8);
                platform::send_fake_tcp(
                    writer,
                    chunk,
                    &first_fake,
                    1,
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;
                platform::send_fake_tcp(
                    writer,
                    second,
                    &second_fake,
                    fake_ttl,
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;
                cursor = plan.tampered.len();
                break;
            }
            TcpChainStepKind::HostFake => {
                let Some(span) = resolve_hostfake_span(configured_step, &plan.tampered, start, end, seed) else {
                    writer.write_all(chunk)?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                    cursor = end;
                    continue;
                };

                if start < span.host_start {
                    writer.write_all(&plan.tampered[start..span.host_start])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                }

                let real_host = &plan.tampered[span.host_start..span.host_end];
                let fake_host = build_hostfake_bytes(real_host, configured_step.fake_host_template.as_deref(), seed);
                platform::send_fake_tcp(
                    writer,
                    real_host,
                    &fake_host,
                    resolved_fake_ttl.or(group.ttl).unwrap_or(8),
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;

                if let Some(midhost) = span.midhost {
                    writer.write_all(&plan.tampered[span.host_start..midhost])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                    writer.write_all(&plan.tampered[midhost..span.host_end])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                } else {
                    writer.write_all(real_host)?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                }

                platform::send_fake_tcp(
                    writer,
                    real_host,
                    &fake_host,
                    resolved_fake_ttl.or(group.ttl).unwrap_or(8),
                    group.md5sig,
                    config.default_ttl,
                    (config.wait_send, Duration::from_millis(config.await_interval.max(1) as u64)),
                )?;

                if span.host_end < end {
                    writer.write_all(&plan.tampered[span.host_end..end])?;
                    platform::wait_tcp_stage(
                        writer,
                        config.wait_send,
                        Duration::from_millis(config.await_interval.max(1) as u64),
                    )?;
                }
            }
            TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "tls prelude step must not appear in tcp send plan",
                ));
            }
        }
        cursor = end;
    }

    if cursor < plan.tampered.len() {
        writer.write_all(&plan.tampered[cursor..])?;
    }
    Ok(())
}

fn send_out_of_band(writer: &TcpStream, prefix: &[u8], urgent_byte: u8) -> io::Result<()> {
    let mut packet = Vec::with_capacity(prefix.len() + 1);
    packet.extend_from_slice(prefix);
    packet.push(urgent_byte);
    let sent = SockRef::from(writer).send_out_of_band(&packet)?;
    if sent != packet.len() {
        return Err(io::Error::new(io::ErrorKind::WriteZero, "partial MSG_OOB send"));
    }
    Ok(())
}

fn set_stream_ttl(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    let socket = SockRef::from(stream);
    let ipv4 = socket.set_ttl(ttl as u32);
    let ipv6 = socket.set_unicast_hops_v6(ttl as u32);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
    }
}

#[cfg(test)]
mod tests {
    use super::state::ClientSlotGuard;
    use super::*;
    use ciadpi_config::{OffsetExpr, TcpChainStep, TcpChainStepKind};
    use ciadpi_packets::DEFAULT_FAKE_TLS;
    use ciadpi_session::{encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply, S_ER_CONN};
    use std::sync::atomic::AtomicUsize;

    fn test_group() -> DesyncGroup {
        DesyncGroup::new(0)
    }

    fn test_offset() -> OffsetExpr {
        OffsetExpr::absolute(0)
    }

    #[test]
    fn client_slot_guard_enforces_limit_and_releases_slot() {
        let active = Arc::new(AtomicUsize::new(0));

        let guard = ClientSlotGuard::acquire(active.clone(), 1).expect("first slot");
        assert_eq!(active.load(Ordering::Relaxed), 1);
        assert!(ClientSlotGuard::acquire(active.clone(), 1).is_none());

        drop(guard);
        assert_eq!(active.load(Ordering::Relaxed), 0);
        assert!(ClientSlotGuard::acquire(active, 1).is_some());
    }

    #[test]
    fn udp_packet_round_trip_preserves_sender_and_payload() {
        let config = RuntimeConfig::default();
        let sender = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 7)), 5353);
        let payload = b"dns-payload";
        let packet = encode_socks5_udp_packet(sender, payload);

        let (decoded_sender, decoded_payload) =
            parse_socks5_udp_packet(&packet, &config).expect("parse udp relay packet");
        assert_eq!(decoded_sender, sender);
        assert_eq!(decoded_payload, payload);
    }

    #[test]
    fn should_cache_udp_host_only_caches_quic_in_cache_mode() {
        let mut config = RuntimeConfig::default();
        let quic =
            crate::runtime_policy::ExtractedHost { host: "docs.example.test".to_string(), source: HostSource::Quic };
        let tls =
            crate::runtime_policy::ExtractedHost { host: "docs.example.test".to_string(), source: HostSource::Tls };

        config.quic_initial_mode = QuicInitialMode::Route;
        assert!(!should_cache_udp_host(&config, Some(&quic)));
        assert!(should_cache_udp_host(&config, Some(&tls)));

        config.quic_initial_mode = QuicInitialMode::RouteAndCache;
        assert!(should_cache_udp_host(&config, Some(&quic)));
    }

    #[test]
    fn encode_upstream_socks_connect_encodes_ipv6_targets() {
        let target = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 8080);
        let encoded = encode_upstream_socks_connect(target);

        assert_eq!(encoded[..4], [S_VER5, S_CMD_CONN, 0, S_ATP_I6]);
        assert_eq!(&encoded[4..20], &Ipv6Addr::LOCALHOST.octets());
        assert_eq!(&encoded[20..22], &8080u16.to_be_bytes());
    }

    #[test]
    fn timeout_and_trigger_helpers_follow_runtime_configuration() {
        let mut config = RuntimeConfig { partial_timeout_ms: 75, timeout_ms: 900, ..RuntimeConfig::default() };
        let tls_tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert_eq!(first_response_timeout(&config, &tls_tracker), Some(Duration::from_millis(75)));

        config.partial_timeout_ms = 0;
        let inactive_tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert_eq!(first_response_timeout(&config, &inactive_tracker), Some(Duration::from_millis(900)));

        config.timeout_ms = 0;
        config.groups[0].detect = DETECT_HTTP_LOCAT | DETECT_CONNECT;
        assert_eq!(first_response_timeout(&config, &inactive_tracker), Some(Duration::from_millis(250)));
        assert_eq!(timeout_count_limit(&config), 1);
        assert!(response_trigger_supported(&config, TriggerEvent::Redirect));
        assert!(response_trigger_supported(&config, TriggerEvent::Connect));
        assert!(!response_trigger_supported(&config, TriggerEvent::Torst));
        assert_eq!(trigger_flag(TriggerEvent::SslErr), DETECT_TLS_HANDSHAKE_FAILURE);
        assert_eq!(trigger_flag(TriggerEvent::Torst), DETECT_TORST);

        config.groups[0].detect = 0;
        assert_eq!(first_response_timeout(&config, &inactive_tracker), None);
    }

    #[test]
    fn tls_record_tracker_handles_partial_records_and_limits() {
        let config = RuntimeConfig { partial_timeout_ms: 50, ..RuntimeConfig::default() };

        let mut tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert!(tracker.active());
        tracker.observe(&[0x16, 0x03, 0x03, 0x00, 0x05, 0xaa]);
        assert!(tracker.waiting_for_tls_record());
        tracker.observe(&[0xbb, 0xcc, 0xdd, 0xee]);
        assert!(!tracker.waiting_for_tls_record());

        let limited_config =
            RuntimeConfig { partial_timeout_ms: 50, timeout_bytes_limit: 3, ..RuntimeConfig::default() };
        let mut limited = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &limited_config);
        limited.observe(&[0x16, 0x03, 0x03, 0x00]);
        assert!(!limited.active());

        let mut invalid = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        invalid.observe(&[0x13, 0x03, 0x03, 0x00, 0x01, 0x00]);
        assert!(!invalid.active());
    }

    #[test]
    fn tcp_desync_helpers_require_actionable_groups_and_matching_rounds() {
        let mut group = test_group();
        group.set_round_activation(Some(ciadpi_config::NumericRange::new(2, 4)));
        let in_range = ActivationContext {
            round: 3,
            payload_size: 16,
            stream_start: 0,
            stream_end: 15,
            transport: ActivationTransport::Tcp,
            tcp_segment_hint: None,
            resolved_fake_ttl: None,
            adaptive: AdaptivePlannerHints::default(),
        };
        let out_of_range = ActivationContext { round: 5, ..in_range };

        assert!(!has_tcp_actions(&group));
        assert!(!should_desync_tcp(&group, in_range));
        assert!(activation_filter_matches(group.activation_filter(), in_range));
        assert!(!activation_filter_matches(group.activation_filter(), out_of_range));

        group.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));
        assert!(has_tcp_actions(&group));
        assert!(should_desync_tcp(&group, in_range));
        assert!(!should_desync_tcp(&group, out_of_range));
    }

    #[test]
    fn special_tcp_execution_includes_fake_approximation_steps() {
        let mut group = test_group();
        group.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeSplit, test_offset()));
        assert_eq!(requires_special_tcp_execution(&group), platform::supports_fake_retransmit());

        group.tcp_chain.clear();
        group.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeDisorder, test_offset()));
        assert_eq!(requires_special_tcp_execution(&group), platform::supports_fake_retransmit());

        group.tcp_chain.clear();
        group.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Fake, test_offset()));
        assert!(requires_special_tcp_execution(&group));
    }

    // ── Characterization: UDP codec round-trip across address families ──

    #[test]
    fn udp_packet_round_trip_preserves_ipv6_sender_and_payload() {
        let mut config = RuntimeConfig { ipv6: true, ..RuntimeConfig::default() };
        let sender = SocketAddr::new(IpAddr::V6(Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1)), 8443);
        let payload = b"quic-initial-stub";
        let packet = encode_socks5_udp_packet(sender, payload);

        let (decoded_sender, decoded_payload) =
            parse_socks5_udp_packet(&packet, &config).expect("parse ipv6 udp packet");
        assert_eq!(decoded_sender, sender);
        assert_eq!(decoded_payload, payload);

        // IPv6 rejected when ipv6 disabled
        config.ipv6 = false;
        assert!(parse_socks5_udp_packet(&packet, &config).is_none());
    }

    #[test]
    fn udp_packet_round_trip_empty_payload() {
        let config = RuntimeConfig::default();
        let sender = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)), 443);
        let packet = encode_socks5_udp_packet(sender, b"");

        let (decoded_sender, decoded_payload) = parse_socks5_udp_packet(&packet, &config).expect("parse empty payload");
        assert_eq!(decoded_sender, sender);
        assert!(decoded_payload.is_empty());
    }

    #[test]
    fn udp_packet_parse_rejects_malformed_packets() {
        let config = RuntimeConfig::default();

        // Too short
        assert!(parse_socks5_udp_packet(&[0, 0, 0], &config).is_none());

        // Non-zero fragment byte (index 2)
        assert!(parse_socks5_udp_packet(&[0, 0, 1, S_ATP_I4, 127, 0, 0, 1, 0, 80], &config).is_none());

        // IPv4 truncated (missing port)
        assert!(parse_socks5_udp_packet(&[0, 0, 0, S_ATP_I4, 127, 0, 0, 1], &config).is_none());

        // Unknown address type
        assert!(parse_socks5_udp_packet(&[0, 0, 0, 0x05, 0, 0, 0, 0, 0, 0], &config).is_none());
    }

    // ── Characterization: TLS record tracker state transitions ──

    #[test]
    fn tls_record_tracker_inactive_without_partial_timeout() {
        let config = RuntimeConfig { partial_timeout_ms: 0, ..RuntimeConfig::default() };
        let tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert!(!tracker.active());
        assert!(!tracker.waiting_for_tls_record());
    }

    #[test]
    fn tls_record_tracker_inactive_for_non_tls_request() {
        let config = RuntimeConfig { partial_timeout_ms: 50, ..RuntimeConfig::default() };
        let non_tls = b"GET / HTTP/1.1\r\n";
        let tracker = TlsRecordTracker::new(non_tls, &config);
        assert!(!tracker.active());
    }

    #[test]
    fn tls_record_tracker_multi_record_observation() {
        let config = RuntimeConfig { partial_timeout_ms: 50, ..RuntimeConfig::default() };
        let mut tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert!(tracker.active());

        // First record: content type 0x16 (handshake), size 3
        tracker.observe(&[0x16, 0x03, 0x03, 0x00, 0x03]);
        assert!(tracker.waiting_for_tls_record());
        tracker.observe(&[0xaa, 0xbb, 0xcc]);
        assert!(!tracker.waiting_for_tls_record());

        // Second record: content type 0x14 (change cipher spec), size 1
        tracker.observe(&[0x14, 0x03, 0x03, 0x00, 0x01, 0xff]);
        assert!(!tracker.waiting_for_tls_record());
        assert!(tracker.active());
    }

    // ── Characterization: protocol reply byte sequences ──

    #[test]
    fn socks4_success_reply_byte_sequence() {
        let reply = encode_socks4_reply(true);
        let bytes = reply.as_bytes();
        assert_eq!(bytes[0], 0x00, "VN must be 0");
        assert_eq!(bytes[1], 0x5a, "CD must be 0x5a (granted)");
        assert_eq!(bytes.len(), 8, "SOCKS4 reply is always 8 bytes");
    }

    #[test]
    fn socks4_failure_reply_byte_sequence() {
        let reply = encode_socks4_reply(false);
        let bytes = reply.as_bytes();
        assert_eq!(bytes[0], 0x00);
        assert_eq!(bytes[1], 0x5b, "CD must be 0x5b (rejected)");
    }

    #[test]
    fn socks5_success_reply_preserves_bind_address() {
        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 100)), 8080);
        let reply = encode_socks5_reply(0, addr);
        let bytes = reply.as_bytes();
        assert_eq!(bytes[0], S_VER5);
        assert_eq!(bytes[1], 0x00, "REP success");
        assert_eq!(bytes[3], S_ATP_I4);
        assert_eq!(&bytes[4..8], &[192, 168, 1, 100]);
        assert_eq!(&bytes[8..10], &8080u16.to_be_bytes());
    }

    #[test]
    fn socks5_error_reply_carries_error_code() {
        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
        let reply = encode_socks5_reply(S_ER_CONN, addr);
        let bytes = reply.as_bytes();
        assert_eq!(bytes[1], S_ER_CONN);
    }

    #[test]
    fn http_connect_success_reply_is_200_ok() {
        let reply = encode_http_connect_reply(true);
        let text = std::str::from_utf8(reply.as_bytes()).expect("utf8");
        assert!(text.starts_with("HTTP/1.1 200 OK\r\n"));
        assert!(text.ends_with("\r\n\r\n"));
    }

    #[test]
    fn http_connect_failure_reply_is_503() {
        let reply = encode_http_connect_reply(false);
        let text = std::str::from_utf8(reply.as_bytes()).expect("utf8");
        assert!(text.starts_with("HTTP/1.1 503 Fail\r\n"));
    }

    // ── Characterization: failure classification trigger mapping ──

    #[test]
    fn failure_trigger_mask_covers_all_detection_classes() {
        let cases = [
            (FailureClass::TcpReset, DETECT_TCP_RESET),
            (FailureClass::SilentDrop, DETECT_SILENT_DROP),
            (FailureClass::TlsAlert, DETECT_TLS_ALERT),
            (FailureClass::HttpBlockpage, DETECT_HTTP_BLOCKPAGE),
            (FailureClass::Redirect, DETECT_HTTP_LOCAT),
            (FailureClass::TlsHandshakeFailure, DETECT_TLS_HANDSHAKE_FAILURE),
            (FailureClass::DnsTampering, DETECT_DNS_TAMPER),
            (FailureClass::ConnectFailure, DETECT_CONNECT),
        ];

        for (class, expected_mask) in cases {
            let failure =
                ClassifiedFailure::new(class, FailureStage::FirstResponse, FailureAction::RetryWithMatchingGroup, "");
            assert_eq!(failure_trigger_mask(&failure), expected_mask, "trigger mask mismatch for {class:?}");
        }

        // Classes with zero trigger mask
        for class in [FailureClass::QuicBreakage, FailureClass::Unknown] {
            let failure =
                ClassifiedFailure::new(class, FailureStage::FirstResponse, FailureAction::RetryWithMatchingGroup, "");
            assert_eq!(failure_trigger_mask(&failure), 0, "{class:?} should have zero mask");
        }
    }

    #[test]
    fn failure_penalizes_strategy_for_expected_classes() {
        let penalizing = [
            FailureClass::TcpReset,
            FailureClass::SilentDrop,
            FailureClass::TlsAlert,
            FailureClass::HttpBlockpage,
            FailureClass::Redirect,
            FailureClass::TlsHandshakeFailure,
        ];
        let non_penalizing = [
            FailureClass::DnsTampering,
            FailureClass::ConnectFailure,
            FailureClass::QuicBreakage,
            FailureClass::Unknown,
        ];

        for class in penalizing {
            let failure =
                ClassifiedFailure::new(class, FailureStage::FirstResponse, FailureAction::RetryWithMatchingGroup, "");
            assert!(failure_penalizes_strategy(&failure), "{class:?} should penalize");
        }
        for class in non_penalizing {
            let failure =
                ClassifiedFailure::new(class, FailureStage::FirstResponse, FailureAction::RetryWithMatchingGroup, "");
            assert!(!failure_penalizes_strategy(&failure), "{class:?} should not penalize");
        }
    }
}
