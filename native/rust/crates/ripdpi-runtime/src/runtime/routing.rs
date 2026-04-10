use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use ripdpi_config::{
    DETECT_CONNECT, DETECT_CONNECTION_FREEZE, DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT,
    DETECT_SILENT_DROP, DETECT_TCP_RESET, DETECT_TLS_ALERT, DETECT_TLS_HANDSHAKE_FAILURE,
};
use ripdpi_dns_resolver::extract_ip_answers;
use ripdpi_failure_classifier::{
    block_signal_from_failure, classify_http_response_block, classify_tls_alert, classify_tls_handshake_failure,
    classify_transport_error, confirm_dns_tampering, ClassifiedFailure, FailureAction, FailureClass, FailureStage,
};
use ripdpi_session::{
    detect_response_trigger, TriggerEvent, S_ATP_I4, S_ATP_I6, S_AUTH_NONE, S_CMD_CONN, S_ER_GEN, S_VER5,
};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::platform;
use crate::runtime_policy::{ConnectionRoute, RouteAdvance, TransportProtocol};
use crate::ws_bootstrap::{build_encrypted_dns_resolver, encrypted_dns_label, runtime_encrypted_dns_context};

use super::adaptive::{note_adaptive_fake_ttl_failure, note_adaptive_tcp_failure, note_evolver_failure};
use super::retry::{build_retry_selection_penalties, maybe_emit_candidate_diversification, note_retry_failure};
use super::state::{flush_autolearn_updates, RuntimeState};

const MAX_PREFERRED_EDGE_TARGETS: usize = 2;

#[derive(Debug)]
pub(super) struct ConnectAttemptError {
    source: io::Error,
    tcp_total_retransmissions: Option<u32>,
}

impl ConnectAttemptError {
    fn into_io_error(self) -> io::Error {
        self.source
    }
}

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
    cache
        .select_initial(target, payload, host, allow_unknown_payload, transport, &state.config)
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

pub(super) fn preferred_targets_for_transport(
    state: &RuntimeState,
    original_target: SocketAddr,
    host: Option<&str>,
    transport: TransportProtocol,
) -> Vec<SocketAddr> {
    let mut targets = Vec::new();
    if let Some(host) = host.map(str::trim).filter(|host| !host.is_empty()) {
        if let Some(runtime_context) = state.runtime_context.as_ref() {
            let normalized = host.to_ascii_lowercase();
            if let Some(edges) =
                runtime_context.preferred_edges.get(host).or_else(|| runtime_context.preferred_edges.get(&normalized))
            {
                for edge in
                    edges.iter().filter(|edge| preferred_edge_matches_transport(edge.transport_kind.trim(), transport))
                {
                    let Ok(ip) = edge.ip.trim().parse::<IpAddr>() else {
                        continue;
                    };
                    let candidate = SocketAddr::new(ip, original_target.port());
                    if !targets.contains(&candidate) {
                        targets.push(candidate);
                    }
                    if targets.len() >= MAX_PREFERRED_EDGE_TARGETS {
                        break;
                    }
                }
            }
        }
    }
    if !targets.contains(&original_target) {
        targets.push(original_target);
    }
    targets
}

fn preferred_edge_matches_transport(transport_kind: &str, transport: TransportProtocol) -> bool {
    match transport {
        TransportProtocol::Tcp => {
            transport_kind.eq_ignore_ascii_case("tcp") || transport_kind.eq_ignore_ascii_case("throughput")
        }
        TransportProtocol::Udp => transport_kind.eq_ignore_ascii_case("quic"),
    }
}

fn connect_target_candidates_via_group(
    targets: &[SocketAddr],
    state: &RuntimeState,
    group_index: usize,
    tfo_enabled: bool,
) -> Result<TcpStream, ConnectAttemptError> {
    let mut last_error = None;
    for &candidate in targets {
        match connect_target_via_group_with_tfo(candidate, state, group_index, tfo_enabled) {
            Ok(stream) => return Ok(stream),
            Err(err) => last_error = Some(err),
        }
    }
    Err(last_error.unwrap_or_else(|| ConnectAttemptError {
        source: io::Error::new(io::ErrorKind::AddrNotAvailable, "no target candidates available"),
        tcp_total_retransmissions: None,
    }))
}

pub(super) fn connect_target_with_route(
    target: SocketAddr,
    state: &RuntimeState,
    mut route: ConnectionRoute,
    payload: Option<&[u8]>,
    host: Option<String>,
) -> io::Result<(TcpStream, ConnectionRoute)> {
    let max_retries = state.config.max_route_retries;
    let mut retries: usize = 0;
    loop {
        let attempt_targets = preferred_targets_for_transport(state, target, host.as_deref(), TransportProtocol::Tcp);
        match connect_target_candidates_via_group(&attempt_targets, state, route.group_index, state.config.network.tfo)
        {
            Ok(stream) => return Ok((stream, route)),
            Err(mut err) => {
                retries += 1;
                let mut failure = classify_transport_error(FailureStage::Connect, &err.source);
                if should_retry_without_tfo(state, &failure) {
                    tracing::debug!(group_index = route.group_index, target = %target, "retrying connect without TCP Fast Open");
                    match connect_target_candidates_via_group(&attempt_targets, state, route.group_index, false) {
                        Ok(stream) => return Ok((stream, route)),
                        Err(fallback_err) => {
                            err = fallback_err;
                            failure = classify_transport_error(FailureStage::Connect, &err.source);
                        }
                    }
                }
                note_block_signal_for_failure(state, host.as_deref(), &failure, err.tcp_total_retransmissions);
                if retries > max_retries {
                    return Err(err.into_io_error());
                }
                emit_failure_classified(state, target, &failure, host.as_deref());
                let next = advance_route_for_failure(state, target, &route, host.clone(), payload, &failure)?;
                let Some(next) = next else {
                    return Err(err.into_io_error());
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
        FailureClass::StrategyExecutionFailure => DETECT_CONNECT,
        FailureClass::ConnectionFreeze => DETECT_CONNECTION_FREEZE,
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
            | FailureClass::ConnectionFreeze
    )
}

fn is_tunnel_infrastructure_dns_target(target: SocketAddr) -> bool {
    if target.port() != 853 {
        return false;
    }
    match target.ip() {
        IpAddr::V4(ipv4) => {
            let [a, b, ..] = ipv4.octets();
            a == 198 && matches!(b, 18 | 19)
        }
        IpAddr::V6(_) => false,
    }
}

pub(super) fn should_track_strategy_target(target: SocketAddr) -> bool {
    !is_tunnel_infrastructure_dns_target(target)
}

pub(super) fn emit_failure_classified(
    state: &RuntimeState,
    target: SocketAddr,
    failure: &ClassifiedFailure,
    host: Option<&str>,
) {
    if !should_track_strategy_target(target) {
        return;
    }
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
    if !should_track_strategy_target(target) {
        return Ok(None);
    }
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
        note_evolver_failure(state, failure.class);
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

pub(super) fn note_block_signal_for_failure(
    state: &RuntimeState,
    host: Option<&str>,
    failure: &ClassifiedFailure,
    tcp_total_retransmissions: Option<u32>,
) {
    let Some(host) = host else {
        return;
    };
    let Some(signal) = block_signal_from_failure(failure, tcp_total_retransmissions) else {
        return;
    };
    let confirmation_allowed = state
        .control
        .as_ref()
        .and_then(|control| control.current_network_snapshot())
        .is_none_or(|snapshot| snapshot.validated && !snapshot.captive_portal);
    if let Ok(mut cache) = state.cache.lock() {
        cache.note_block_signal(&state.config, host, signal.signal, signal.provider.as_deref(), confirmation_allowed);
        flush_autolearn_updates(state, &mut cache);
    }
}

pub(super) fn classify_response_failure(
    state: &RuntimeState,
    target: SocketAddr,
    request: &[u8],
    response: &[u8],
    host: Option<&str>,
) -> Option<ClassifiedFailure> {
    if response.starts_with(b"HTTP/1.") && ripdpi_packets::is_tls_client_hello(request) {
        if let Some(host) = host {
            if let Some(dns_tampering) = confirm_dns_tampering_for_host(state, host, target.ip()) {
                return Some(dns_tampering);
            }
        }
    }

    if let Some(alert) = classify_tls_alert(response) {
        return Some(alert);
    }
    if let Some(http_block) = classify_http_response_block(response) {
        return Some(http_block);
    }
    if matches!(detect_response_trigger(request, response), Some(TriggerEvent::SslErr)) {
        return Some(classify_tls_handshake_failure("TLS handshake failed before ServerHello"));
    }
    None
}

fn confirm_dns_tampering_for_host(state: &RuntimeState, host: &str, target_ip: IpAddr) -> Option<ClassifiedFailure> {
    let resolver_context = runtime_encrypted_dns_context(state.runtime_context.as_ref());
    let resolver =
        build_encrypted_dns_resolver(state.runtime_context.as_ref(), state.config.process.protect_path.as_deref())
            .ok()?;
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

fn connect_target_via_group_with_tfo(
    target: SocketAddr,
    state: &RuntimeState,
    group_index: usize,
    tfo_enabled: bool,
) -> Result<TcpStream, ConnectAttemptError> {
    let started = std::time::Instant::now();
    let group = state.config.groups.get(group_index).ok_or_else(|| ConnectAttemptError {
        source: io::Error::new(io::ErrorKind::NotFound, "missing desync group"),
        tcp_total_retransmissions: None,
    })?;
    let connect_timeout = if state.config.timeouts.connect_timeout_ms > 0 {
        Some(Duration::from_millis(state.config.timeouts.connect_timeout_ms as u64))
    } else {
        None
    };
    let pre_connect_rcvbuf = group.actions.wsize.map(|w| match w.scale {
        Some(scale) if (scale as u32) < 32 => w.window.checked_shl(scale as u32).unwrap_or(u32::MAX),
        Some(_) => u32::MAX,
        None => w.window,
    });
    let stream = if let Some(upstream) = group.policy.ext_socks {
        connect_via_socks(
            target,
            upstream.addr,
            unspecified_ip_for(upstream.addr),
            state.config.process.protect_path.as_deref(),
            tfo_enabled,
            connect_timeout,
        )
        .map_err(|source| ConnectAttemptError { source, tcp_total_retransmissions: None })
    } else {
        connect_socket_detailed(
            target,
            unspecified_ip_for(target),
            state.config.process.protect_path.as_deref(),
            tfo_enabled,
            connect_timeout,
            pre_connect_rcvbuf,
        )
    }?;

    if group.actions.drop_sack {
        platform::attach_drop_sack(&stream)
            .map_err(|source| ConnectAttemptError { source, tcp_total_retransmissions: None })?;
    }
    // wsize supersedes window_clamp when both are set.
    let effective_clamp = group.actions.wsize.map(|w| w.window).or(group.actions.window_clamp);
    if let Some(clamp) = effective_clamp {
        let _ = platform::set_tcp_window_clamp(&stream, clamp);
    }
    if group.actions.strip_timestamps {
        let _ = platform::attach_strip_timestamps(&stream);
    }
    let elapsed = started.elapsed().as_secs_f64();
    let group_label = format!("{group_index}");
    metrics::histogram!("ripdpi_connection_setup_duration_seconds", "group" => group_label).record(elapsed);
    if let Some(telemetry) = &state.telemetry {
        let upstream_addr = stream.peer_addr().unwrap_or(target);
        let upstream_rtt_ms = platform::tcp_round_trip_time_ms(&stream)
            .ok()
            .flatten()
            .or_else(|| Some(started.elapsed().as_millis() as u64));
        telemetry.on_upstream_connected(upstream_addr, upstream_rtt_ms);
    }
    Ok(stream)
}

fn unspecified_ip_for(addr: SocketAddr) -> IpAddr {
    match addr {
        SocketAddr::V4(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED),
        SocketAddr::V6(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED),
    }
}

fn connect_via_socks(
    target: SocketAddr,
    upstream: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
    connect_timeout: Option<Duration>,
) -> io::Result<TcpStream> {
    let mut stream = connect_socket(upstream, bind_ip, protect_path, tfo, connect_timeout)?;
    stream.set_read_timeout(connect_timeout)?;
    stream.set_write_timeout(connect_timeout)?;

    let handshake_result = (|| {
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
        Ok(())
    })();

    handshake_result?;
    stream.set_read_timeout(None)?;
    stream.set_write_timeout(None)?;
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
    connect_timeout: Option<Duration>,
) -> io::Result<TcpStream> {
    connect_socket_detailed(target, bind_ip, protect_path, tfo, connect_timeout, None)
        .map_err(ConnectAttemptError::into_io_error)
}

fn connect_socket_detailed(
    target: SocketAddr,
    bind_ip: IpAddr,
    protect_path: Option<&str>,
    tfo: bool,
    connect_timeout: Option<Duration>,
    pre_connect_rcvbuf: Option<u32>,
) -> Result<TcpStream, ConnectAttemptError> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))
        .map_err(|source| ConnectAttemptError { source, tcp_total_retransmissions: None })?;
    if let Some(path) = protect_path {
        platform::protect_socket(&socket, Some(path))
            .map_err(|source| ConnectAttemptError { source, tcp_total_retransmissions: None })?;
    }
    if tfo {
        enable_tcp_fastopen_if_supported(&socket)
            .map_err(|source| ConnectAttemptError { source, tcp_total_retransmissions: None })?;
    }
    bind_socket(&socket, bind_ip, target)
        .map_err(|source| ConnectAttemptError { source, tcp_total_retransmissions: None })?;
    if let Some(rcvbuf) = pre_connect_rcvbuf {
        let _ = platform::set_rcvbuf(&socket, rcvbuf);
    }
    let connect_started = std::time::Instant::now();
    tracing::debug!(
        target = %target,
        bind_ip = %bind_ip,
        tcp_fast_open = tfo,
        protected = protect_path.is_some(),
        "ripdpi upstream connect start"
    );
    let connect_result = if let Some(timeout) = connect_timeout {
        socket.connect_timeout(&SockAddr::from(target), timeout)
    } else {
        socket.connect(&SockAddr::from(target))
    };
    if let Err(err) = connect_result {
        let tcp_total_retransmissions = platform::tcp_total_retransmissions(&socket).ok().flatten();
        tracing::warn!(
            target = %target,
            bind_ip = %bind_ip,
            tcp_fast_open = tfo,
            protected = protect_path.is_some(),
            elapsed_ms = connect_started.elapsed().as_millis() as u64,
            "ripdpi upstream connect failed: {err}"
        );
        return Err(ConnectAttemptError { source: err, tcp_total_retransmissions });
    }
    tracing::debug!(
        target = %target,
        bind_ip = %bind_ip,
        tcp_fast_open = tfo,
        protected = protect_path.is_some(),
        elapsed_ms = connect_started.elapsed().as_millis() as u64,
        "ripdpi upstream connect established"
    );
    let stream: TcpStream = socket.into();
    if let Err(err) = stream.set_nodelay(true) {
        tracing::debug!("set_nodelay on upstream socket failed (non-fatal): {err}");
    }
    Ok(stream)
}

fn enable_tcp_fastopen_if_supported(socket: &Socket) -> io::Result<()> {
    match platform::enable_tcp_fastopen_connect(socket) {
        Ok(()) => Ok(()),
        #[cfg(target_os = "android")]
        Err(err) if should_ignore_android_tfo_error(&err) => {
            tracing::debug!("TCP Fast Open unavailable on this Android build: {err}");
            Ok(())
        }
        Err(err) => Err(err),
    }
}

#[cfg(any(test, target_os = "android"))]
fn should_ignore_android_tfo_error(err: &io::Error) -> bool {
    matches!(err.raw_os_error(), Some(libc::ENOPROTOOPT | libc::EOPNOTSUPP | libc::EPERM | libc::EACCES | libc::EINVAL))
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

fn should_retry_without_tfo(state: &RuntimeState, failure: &ClassifiedFailure) -> bool {
    state.config.network.tfo && matches!(failure.class, FailureClass::ConnectFailure | FailureClass::TcpReset)
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
    let max_retries = state.config.max_route_retries;
    let mut retries: usize = 0;
    loop {
        super::retry::apply_retry_pacing_before_connect(state, target, &route, host.as_deref(), payload)?;
        let attempt_targets = preferred_targets_for_transport(state, target, host.as_deref(), TransportProtocol::Tcp);
        match connect_target_candidates_via_group(&attempt_targets, state, route.group_index, state.config.network.tfo)
        {
            Ok(stream) => return Ok((stream, route)),
            Err(mut err) => {
                retries += 1;
                if retries > max_retries {
                    return Err(err.into_io_error());
                }
                let mut failure = classify_transport_error(FailureStage::Connect, &err.source);
                if should_retry_without_tfo(state, &failure) {
                    tracing::debug!(group_index = route.group_index, target = %target, "retrying reconnect without TCP Fast Open");
                    match connect_target_candidates_via_group(&attempt_targets, state, route.group_index, false) {
                        Ok(stream) => return Ok((stream, route)),
                        Err(fallback_err) => {
                            err = fallback_err;
                            failure = classify_transport_error(FailureStage::Connect, &err.source);
                        }
                    }
                }
                emit_failure_classified(state, target, &failure, host.as_deref());
                let next = advance_route_for_failure(state, target, &route, host.clone(), payload, &failure)?;
                let Some(next) = next else {
                    return Err(err.into_io_error());
                };
                route = next;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;
    use std::time::Instant;

    #[test]
    fn outbound_connects_do_not_reuse_listener_bind_ip() {
        assert_eq!(unspecified_ip_for(SocketAddr::from(([203, 0, 113, 7], 443))), IpAddr::V4(Ipv4Addr::UNSPECIFIED),);
        assert_eq!(
            unspecified_ip_for(SocketAddr::from(([0u16, 0, 0, 0, 0, 0, 0, 1], 443))),
            IpAddr::V6(Ipv6Addr::UNSPECIFIED),
        );
    }

    #[test]
    fn android_tfo_capability_errors_are_ignored() {
        for errno in [libc::ENOPROTOOPT, libc::EOPNOTSUPP, libc::EPERM, libc::EACCES, libc::EINVAL] {
            assert!(
                should_ignore_android_tfo_error(&io::Error::from_raw_os_error(errno)),
                "expected errno {errno} to be ignored on Android",
            );
        }
    }

    #[test]
    fn android_tfo_runtime_failures_are_not_ignored() {
        for errno in [libc::ECONNRESET, libc::ETIMEDOUT, libc::EHOSTUNREACH] {
            assert!(
                !should_ignore_android_tfo_error(&io::Error::from_raw_os_error(errno)),
                "expected errno {errno} to remain fatal on Android",
            );
        }
    }

    #[test]
    fn max_route_retries_default_is_eight() {
        let config = ripdpi_config::RuntimeConfig::default();
        assert_eq!(config.max_route_retries, 8);
    }

    #[test]
    fn max_route_retries_is_customizable() {
        let config = ripdpi_config::RuntimeConfig { max_route_retries: 3, ..Default::default() };
        assert_eq!(config.max_route_retries, 3);
    }

    #[test]
    fn synthetic_tunnel_dns_targets_do_not_participate_in_strategy_tracking() {
        assert!(!should_track_strategy_target(SocketAddr::from(([198, 18, 0, 53], 853))));
        assert!(!should_track_strategy_target(SocketAddr::from(([198, 19, 42, 7], 853))));
        assert!(should_track_strategy_target(SocketAddr::from(([198, 18, 0, 53], 443))));
        assert!(should_track_strategy_target(SocketAddr::from(([142, 251, 127, 84], 443))));
    }

    #[test]
    fn upstream_socks_auth_timeout_uses_connect_timeout() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks listener");
        let upstream = listener.local_addr().expect("listener addr");
        let target = SocketAddr::from(([203, 0, 113, 7], 443));
        let server = thread::spawn(move || {
            let (_stream, _) = listener.accept().expect("accept upstream socks client");
            thread::sleep(Duration::from_millis(250));
        });

        let started = Instant::now();
        let err = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
        )
        .expect_err("auth stall should time out");

        assert!(matches!(err.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock));
        assert!(started.elapsed() < Duration::from_millis(200));
        server.join().expect("join upstream socks server");
    }

    #[test]
    fn upstream_socks_connect_reply_timeout_uses_connect_timeout() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks listener");
        let upstream = listener.local_addr().expect("listener addr");
        let target = SocketAddr::from(([203, 0, 113, 7], 443));
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept upstream socks client");
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).expect("read auth request");
            stream.write_all(&[S_VER5, S_AUTH_NONE]).expect("write auth response");
            let mut connect = [0u8; 10];
            stream.read_exact(&mut connect).expect("read connect request");
            thread::sleep(Duration::from_millis(250));
        });

        let started = Instant::now();
        let err = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
        )
        .expect_err("connect reply stall should time out");

        assert!(matches!(err.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock));
        assert!(started.elapsed() < Duration::from_millis(200));
        server.join().expect("join upstream socks server");
    }

    #[test]
    fn upstream_socks_connect_clears_temporary_timeouts_after_success() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream socks listener");
        let upstream = listener.local_addr().expect("listener addr");
        let target = SocketAddr::from(([203, 0, 113, 7], 443));
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept upstream socks client");
            let mut auth = [0u8; 3];
            stream.read_exact(&mut auth).expect("read auth request");
            stream.write_all(&[S_VER5, S_AUTH_NONE]).expect("write auth response");
            let mut connect = [0u8; 10];
            stream.read_exact(&mut connect).expect("read connect request");
            stream.write_all(&[S_VER5, 0, 0, S_ATP_I4, 127, 0, 0, 1, 0x1f, 0x90]).expect("write connect success");
        });

        let stream = connect_via_socks(
            target,
            upstream,
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            None,
            false,
            Some(Duration::from_millis(75)),
        )
        .expect("connect via upstream socks");

        assert_eq!(stream.read_timeout().expect("read timeout"), None);
        assert_eq!(stream.write_timeout().expect("write timeout"), None);
        server.join().expect("join upstream socks server");
    }
}

#[cfg(test)]
#[allow(clippy::items_after_test_module)]
pub(super) fn trigger_flag(trigger: TriggerEvent) -> u32 {
    match trigger {
        TriggerEvent::Redirect => DETECT_HTTP_LOCAT,
        TriggerEvent::SslErr => DETECT_TLS_HANDSHAKE_FAILURE,
        TriggerEvent::Connect => DETECT_CONNECT,
        TriggerEvent::Torst => ripdpi_config::DETECT_TORST,
    }
}
