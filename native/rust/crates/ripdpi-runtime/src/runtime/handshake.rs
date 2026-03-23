use crate::sync::{Arc, AtomicBool, Ordering};
use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream, ToSocketAddrs};
use std::thread;
use std::time::Duration;

use crate::platform;
use crate::runtime_policy::{
    extract_host, group_requires_payload, route_matches_payload, ConnectionRoute, TransportProtocol,
};
use crate::ws_bootstrap;
use ripdpi_config::RuntimeConfig;
use ripdpi_session::{
    encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply, parse_http_connect_request,
    parse_socks4_request, parse_socks5_request, ClientRequest, SessionConfig, SessionError, SocketType, S_ATP_I4,
    S_ATP_I6, S_AUTH_BAD, S_AUTH_NONE, S_ER_CMD, S_ER_GEN, S_VER5,
};
use socket2::SockRef;

use ripdpi_ws_tunnel::WsTunnelConfig;

use super::state::{RuntimeState, HANDSHAKE_TIMEOUT};

/// Detect Telegram DC number from target IP, independent of WS tunnel config.
fn detect_telegram_dc(target: SocketAddr) -> Option<u8> {
    match target.ip() {
        IpAddr::V4(v4) => ripdpi_ws_tunnel::dc_from_ip(v4),
        IpAddr::V6(_) => None,
    }
}

/// Format a virtual hostname for a Telegram DC, used as autolearn key.
fn telegram_dc_host(dc: u8) -> String {
    format!("telegram-dc{dc}")
}

#[derive(Clone, Copy)]
enum HandshakeKind {
    Socks4,
    Socks5,
    HttpConnect,
}

enum DelayConnect {
    Immediate,
    Delayed { route: ConnectionRoute, payload: Vec<u8> },
    Closed,
}

/// Classify a target as a Telegram DC, returning the DC number if matched.
fn classify_telegram_target(target: SocketAddr) -> Option<u8> {
    match ripdpi_ws_tunnel::classify_target(target.ip()) {
        ripdpi_ws_tunnel::WsTunnelDecision::Tunnel(dc) => Some(dc),
        ripdpi_ws_tunnel::WsTunnelDecision::Passthrough => None,
    }
}

/// Check if WS tunnel should be tried first (Always mode).
fn should_ws_tunnel_first(target: SocketAddr, state: &RuntimeState) -> Option<u8> {
    if state.config.ws_tunnel_mode != ripdpi_config::WsTunnelMode::Always {
        return None;
    }
    let dc = classify_telegram_target(target)?;
    log::info!("WS tunnel: routing to DC{dc} via wss://kws{dc}.web.telegram.org/apiws");
    Some(dc)
}

/// Check if WS tunnel should be tried as a last resort (Fallback mode).
fn should_ws_tunnel_fallback(target: SocketAddr, state: &RuntimeState) -> Option<u8> {
    if state.config.ws_tunnel_mode != ripdpi_config::WsTunnelMode::Fallback {
        return None;
    }
    classify_telegram_target(target)
}

/// Try WS tunnel as a last resort after desync exhaustion.
/// Returns `Some(Ok(()))` on success, `None` if WS also failed or couldn't be attempted.
fn try_ws_tunnel_fallback(
    client: &TcpStream,
    target: SocketAddr,
    dc: u8,
    state: &RuntimeState,
) -> Option<io::Result<()>> {
    log::info!("WS tunnel fallback: desync exhausted for DC{dc}, escalating to wss://kws{dc}.web.telegram.org/apiws");
    let cloned = client.try_clone().ok()?;
    match run_ws_tunnel(cloned, dc, target, state) {
        WsTunnelResult::Ok => {
            if let Some(telemetry) = &state.telemetry {
                telemetry.on_ws_tunnel_escalation(target, dc, true);
            }
            Some(Ok(()))
        }
        _ => {
            if let Some(telemetry) = &state.telemetry {
                telemetry.on_ws_tunnel_escalation(target, dc, false);
            }
            None
        }
    }
}

/// Result of a WS tunnel attempt.
enum WsTunnelResult {
    /// Tunnel completed successfully.
    Ok,
    /// Tunnel failed; init packet is available for desync fallback.
    Fallback { init_packet: Vec<u8> },
    /// Tunnel failed before reading init; no bytes consumed from client.
    FallbackNoInit,
}

/// Execute the WebSocket tunnel relay for a classified Telegram connection.
/// On failure, returns `Fallback` with the consumed init packet for desync retry.
fn run_ws_tunnel(client: TcpStream, dc: u8, target: SocketAddr, state: &RuntimeState) -> WsTunnelResult {
    let resolved_addr = match ws_bootstrap::resolve_ws_tunnel_addr(dc, state.runtime_context.as_ref()) {
        Ok(addr) => Some(addr),
        Err(err) => {
            log::warn!("WS tunnel encrypted DNS bootstrap failed for DC{dc}: {err}");
            None
        }
    };
    let config = WsTunnelConfig { protect_path: state.config.protect_path.clone(), resolved_addr };
    match ripdpi_ws_tunnel::relay_ws_tunnel(client, dc, target, &config) {
        Ok(()) => WsTunnelResult::Ok,
        Err(err) => {
            log::warn!("WS tunnel failed for DC{dc}, falling back to desync: {}", err.error);
            match err.init_packet {
                Some(init) => WsTunnelResult::Fallback { init_packet: init.to_vec() },
                None => WsTunnelResult::FallbackNoInit,
            }
        }
    }
}

pub(super) fn handle_client(mut client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    client.set_read_timeout(Some(HANDSHAKE_TIMEOUT))?;
    client.set_write_timeout(Some(HANDSHAKE_TIMEOUT))?;
    if state.config.transparent {
        return handle_transparent(client, state);
    }
    if state.config.http_connect {
        return handle_http_connect(client, state);
    }

    let mut first = [0u8; 1];
    client.read_exact(&mut first)?;
    if state.config.shadowsocks {
        return handle_shadowsocks(client, state, first[0]);
    }
    match first[0] {
        0x04 => handle_socks4(client, state, first[0]),
        0x05 => handle_socks5(client, state, first[0]),
        _ => Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported proxy protocol")),
    }
}

fn handle_transparent(client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    let target = platform::original_dst(&client)?;
    let local = client.local_addr()?;
    if local == target {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "transparent proxy target resolves to the local listener",
        ));
    }

    let dc_host = detect_telegram_dc(target).map(|dc| {
        if let Some(telemetry) = &state.telemetry {
            telemetry.on_telegram_dc_detected(target, dc);
        }
        telegram_dc_host(dc)
    });

    // Always mode: try WS tunnel first, fall back to desync on failure
    if let Some(dc) = should_ws_tunnel_first(target, state) {
        match run_ws_tunnel(client.try_clone()?, dc, target, state) {
            WsTunnelResult::Ok => return Ok(()),
            WsTunnelResult::Fallback { init_packet } => {
                let (upstream, route) =
                    super::routing::connect_target(target, state, Some(&init_packet), true, dc_host.clone())?;
                return super::relay::relay(client, upstream, state, target, route, Some(init_packet));
            }
            WsTunnelResult::FallbackNoInit => {
                // Init read failed; fall through to normal path
            }
        }
    }

    match super::routing::connect_target(target, state, None, false, dc_host.clone()) {
        Ok((upstream, route)) => super::relay::relay(client, upstream, state, target, route, None),
        Err(err) => {
            // Fallback mode: try WS tunnel after desync exhaustion
            if let Some(dc) = should_ws_tunnel_fallback(target, state) {
                if let Some(result) = try_ws_tunnel_fallback(&client, target, dc, state) {
                    return result;
                }
            }
            if matches!(err.kind(), io::ErrorKind::ConnectionRefused | io::ErrorKind::TimedOut) {
                let _ = SockRef::from(&client).set_linger(Some(Duration::ZERO));
            }
            Err(err)
        }
    }
}

fn handle_socks4(mut client: TcpStream, state: &RuntimeState, version: u8) -> io::Result<()> {
    let request = read_socks4_request(&mut client, version)?;
    let session = SessionConfig { resolve: state.config.resolve, ipv6: state.config.ipv6 };
    let resolver = |host: &str, socket_type: SocketType| resolve_name(host, socket_type, &state.config);
    let parsed = parse_socks4_request(&request, session, &resolver);
    match parsed {
        Ok(ClientRequest::Socks4Connect(target)) => {
            let dc_host = detect_telegram_dc(target.addr).map(|dc| {
                if let Some(telemetry) = &state.telemetry {
                    telemetry.on_telegram_dc_detected(target.addr, dc);
                }
                telegram_dc_host(dc)
            });

            // Always mode: try WS tunnel first
            if let Some(dc) = should_ws_tunnel_first(target.addr, state) {
                client.write_all(encode_socks4_reply(true).as_bytes())?;
                match run_ws_tunnel(client.try_clone()?, dc, target.addr, state) {
                    WsTunnelResult::Ok => return Ok(()),
                    WsTunnelResult::Fallback { init_packet } => {
                        let (upstream, route) = super::routing::connect_target(
                            target.addr,
                            state,
                            Some(&init_packet),
                            true,
                            dc_host.clone(),
                        )?;
                        return super::relay::relay(client, upstream, state, target.addr, route, Some(init_packet));
                    }
                    WsTunnelResult::FallbackNoInit => {
                        // Fall through to normal desync path
                    }
                }
            }
            let ws_fallback_client =
                should_ws_tunnel_fallback(target.addr, state).and_then(|_| client.try_clone().ok());
            let desync_result = match maybe_delay_connect(&mut client, state, target.addr, HandshakeKind::Socks4)? {
                DelayConnect::Immediate => {
                    match super::routing::connect_target(target.addr, state, None, false, dc_host.clone()) {
                        Ok((upstream, route)) => {
                            client.write_all(encode_socks4_reply(true).as_bytes())?;
                            super::relay::relay(client, upstream, state, target.addr, route, None)
                        }
                        Err(err) => Err(err),
                    }
                }
                DelayConnect::Delayed { route, payload } => {
                    let host = extract_host(&state.config, &payload).or(dc_host.clone());
                    match super::routing::connect_target_with_route(
                        target.addr,
                        state,
                        route,
                        Some(&payload),
                        host.clone(),
                    ) {
                        Ok((upstream, route)) => {
                            super::relay::relay(client, upstream, state, target.addr, route, Some(payload))
                        }
                        Err(err) => Err(err),
                    }
                }
                DelayConnect::Closed => Ok(()),
            };
            // Fallback mode: try WS tunnel after desync failure
            match desync_result {
                Ok(()) => Ok(()),
                Err(_err) => {
                    if let Some(dc) = should_ws_tunnel_fallback(target.addr, state) {
                        if let Some(fallback_client) = ws_fallback_client {
                            if let Some(result) = try_ws_tunnel_fallback(&fallback_client, target.addr, dc, state) {
                                return result;
                            }
                        }
                    }
                    Err(_err)
                }
            }
        }
        Ok(_) => {
            client.write_all(encode_socks4_reply(false).as_bytes())?;
            Ok(())
        }
        Err(_) => {
            client.write_all(encode_socks4_reply(false).as_bytes())?;
            Ok(())
        }
    }
}

fn handle_socks5(mut client: TcpStream, state: &RuntimeState, version: u8) -> io::Result<()> {
    if version != S_VER5 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid socks version"));
    }
    negotiate_socks5(&mut client)?;
    let request = read_socks5_request(&mut client)?;
    let session = SessionConfig { resolve: state.config.resolve, ipv6: state.config.ipv6 };
    let resolver = |host: &str, socket_type: SocketType| resolve_name(host, socket_type, &state.config);

    match parse_socks5_request(&request, SocketType::Stream, session, &resolver) {
        Ok(ClientRequest::Socks5Connect(target)) => {
            let dc_host = detect_telegram_dc(target.addr).map(|dc| {
                if let Some(telemetry) = &state.telemetry {
                    telemetry.on_telegram_dc_detected(target.addr, dc);
                }
                telegram_dc_host(dc)
            });

            // Always mode: try WS tunnel first
            if let Some(dc) = should_ws_tunnel_first(target.addr, state) {
                let reply_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0);
                client.write_all(encode_socks5_reply(0, reply_addr).as_bytes())?;
                match run_ws_tunnel(client.try_clone()?, dc, target.addr, state) {
                    WsTunnelResult::Ok => return Ok(()),
                    WsTunnelResult::Fallback { init_packet } => {
                        let (upstream, route) = super::routing::connect_target(
                            target.addr,
                            state,
                            Some(&init_packet),
                            true,
                            dc_host.clone(),
                        )?;
                        return super::relay::relay(client, upstream, state, target.addr, route, Some(init_packet));
                    }
                    WsTunnelResult::FallbackNoInit => {
                        // Fall through to normal desync path
                    }
                }
            }
            let ws_fallback_client =
                should_ws_tunnel_fallback(target.addr, state).and_then(|_| client.try_clone().ok());
            let desync_result = match maybe_delay_connect(&mut client, state, target.addr, HandshakeKind::Socks5)? {
                DelayConnect::Immediate => {
                    match super::routing::connect_target(target.addr, state, None, false, dc_host.clone()) {
                        Ok((upstream, route)) => {
                            let reply_addr = upstream
                                .local_addr()
                                .unwrap_or_else(|_| SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0));
                            client.write_all(encode_socks5_reply(0, reply_addr).as_bytes())?;
                            super::relay::relay(client, upstream, state, target.addr, route, None)
                        }
                        Err(err) => Err(err),
                    }
                }
                DelayConnect::Delayed { route, payload } => {
                    let host = extract_host(&state.config, &payload).or(dc_host.clone());
                    match super::routing::connect_target_with_route(
                        target.addr,
                        state,
                        route,
                        Some(&payload),
                        host.clone(),
                    ) {
                        Ok((upstream, route)) => {
                            super::relay::relay(client, upstream, state, target.addr, route, Some(payload))
                        }
                        Err(err) => Err(err),
                    }
                }
                DelayConnect::Closed => Ok(()),
            };
            // Fallback mode: try WS tunnel after desync failure
            match desync_result {
                Ok(()) => Ok(()),
                Err(_err) => {
                    if let Some(dc) = should_ws_tunnel_fallback(target.addr, state) {
                        if let Some(fallback_client) = ws_fallback_client {
                            if let Some(result) = try_ws_tunnel_fallback(&fallback_client, target.addr, dc, state) {
                                return result;
                            }
                        }
                    }
                    let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
                    // Can't write to original client (consumed by relay); ignore error reply
                    let _ = fail;
                    Ok(())
                }
            }
        }
        Ok(ClientRequest::Socks5UdpAssociate(_target)) => {
            if !state.config.udp {
                let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
                client.write_all(encode_socks5_reply(S_ER_CMD, fail).as_bytes())?;
                return Ok(());
            }
            handle_socks5_udp_associate(client, state)
        }
        Ok(_) => {
            let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
            client.write_all(encode_socks5_reply(S_ER_GEN, fail).as_bytes())?;
            Ok(())
        }
        Err(SessionError { code }) => {
            let fail = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
            client.write_all(encode_socks5_reply(code, fail).as_bytes())?;
            Ok(())
        }
    }
}

fn handle_http_connect(mut client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    let request = read_http_connect_request(&mut client)?;
    let resolver = |host: &str, socket_type: SocketType| resolve_name(host, socket_type, &state.config);
    match parse_http_connect_request(&request, &resolver) {
        Ok(ClientRequest::HttpConnect(target)) => {
            let dc_host = detect_telegram_dc(target.addr).map(|dc| {
                if let Some(telemetry) = &state.telemetry {
                    telemetry.on_telegram_dc_detected(target.addr, dc);
                }
                telegram_dc_host(dc)
            });

            // Always mode: try WS tunnel first
            if let Some(dc) = should_ws_tunnel_first(target.addr, state) {
                client.write_all(encode_http_connect_reply(true).as_bytes())?;
                match run_ws_tunnel(client.try_clone()?, dc, target.addr, state) {
                    WsTunnelResult::Ok => return Ok(()),
                    WsTunnelResult::Fallback { init_packet } => {
                        let (upstream, route) = super::routing::connect_target(
                            target.addr,
                            state,
                            Some(&init_packet),
                            true,
                            dc_host.clone(),
                        )?;
                        return super::relay::relay(client, upstream, state, target.addr, route, Some(init_packet));
                    }
                    WsTunnelResult::FallbackNoInit => {
                        // Fall through to normal desync path
                    }
                }
            }
            let ws_fallback_client =
                should_ws_tunnel_fallback(target.addr, state).and_then(|_| client.try_clone().ok());
            let desync_result = match maybe_delay_connect(&mut client, state, target.addr, HandshakeKind::HttpConnect)?
            {
                DelayConnect::Immediate => {
                    match super::routing::connect_target(target.addr, state, None, false, dc_host.clone()) {
                        Ok((upstream, route)) => {
                            client.write_all(encode_http_connect_reply(true).as_bytes())?;
                            super::relay::relay(client, upstream, state, target.addr, route, None)
                        }
                        Err(err) => Err(err),
                    }
                }
                DelayConnect::Delayed { route, payload } => {
                    let host = extract_host(&state.config, &payload).or(dc_host.clone());
                    match super::routing::connect_target_with_route(
                        target.addr,
                        state,
                        route,
                        Some(&payload),
                        host.clone(),
                    ) {
                        Ok((upstream, route)) => {
                            super::relay::relay(client, upstream, state, target.addr, route, Some(payload))
                        }
                        Err(err) => Err(err),
                    }
                }
                DelayConnect::Closed => Ok(()),
            };
            // Fallback mode: try WS tunnel after desync failure
            match desync_result {
                Ok(()) => Ok(()),
                Err(_err) => {
                    if let Some(dc) = should_ws_tunnel_fallback(target.addr, state) {
                        if let Some(fallback_client) = ws_fallback_client {
                            if let Some(result) = try_ws_tunnel_fallback(&fallback_client, target.addr, dc, state) {
                                return result;
                            }
                        }
                    }
                    // Can't write error reply (client consumed by relay)
                    Ok(())
                }
            }
        }
        _ => {
            client.write_all(encode_http_connect_reply(false).as_bytes())?;
            Ok(())
        }
    }
}

fn handle_shadowsocks(mut client: TcpStream, state: &RuntimeState, first_byte: u8) -> io::Result<()> {
    let (target, first_request) = read_shadowsocks_request(&mut client, first_byte, &state.config)?;
    let host = extract_host(&state.config, &first_request);
    let payload = if first_request.is_empty() { None } else { Some(first_request.as_slice()) };
    let (upstream, route) = super::routing::connect_target(target, state, payload, false, host)?;
    super::relay::relay(
        client,
        upstream,
        state,
        target,
        route,
        if first_request.is_empty() { None } else { Some(first_request) },
    )
}

fn handle_socks5_udp_associate(mut client: TcpStream, state: &RuntimeState) -> io::Result<()> {
    let local_ip = client.local_addr()?.ip();
    let relay = super::udp::build_udp_relay_sockets(local_ip, state.config.protect_path.as_deref())?;
    let reply_addr = relay.client.local_addr()?;
    client.write_all(encode_socks5_reply(0, reply_addr).as_bytes())?;

    let running = Arc::new(AtomicBool::new(true));
    let worker_running = running.clone();
    let worker_state = state.clone();
    let worker_protect_path = state.config.protect_path.clone();
    let worker = thread::Builder::new()
        .name("ripdpi-udp".into())
        .spawn(move || super::udp::udp_associate_loop(relay.client, worker_protect_path, worker_state, worker_running))
        .map_err(|err| io::Error::other(format!("failed to spawn UDP relay thread: {err}")))?;

    client.set_read_timeout(Some(Duration::from_millis(250)))?;
    let mut buffer = [0u8; 64];
    loop {
        match client.read(&mut buffer) {
            Ok(0) => break,
            Ok(_) => {}
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(_) => break,
        }
        if !running.load(Ordering::Relaxed) {
            break;
        }
    }

    running.store(false, Ordering::Relaxed);
    worker.join().map_err(|_| io::Error::other("udp relay thread panicked"))?
}

fn negotiate_socks5(client: &mut TcpStream) -> io::Result<()> {
    let mut count = [0u8; 1];
    client.read_exact(&mut count)?;
    let mut methods = vec![0u8; count[0] as usize];
    client.read_exact(&mut methods)?;
    let method = if methods.contains(&S_AUTH_NONE) { S_AUTH_NONE } else { S_AUTH_BAD };
    client.write_all(&[S_VER5, method])?;
    if method == S_AUTH_BAD {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "no supported socks auth method"));
    }
    Ok(())
}

fn read_socks5_request(client: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    client.read_exact(&mut header)?;
    let mut out = header.to_vec();
    match header[3] {
        S_ATP_I4 => {
            let mut tail = [0u8; 6];
            client.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        S_ATP_I6 => {
            let mut tail = [0u8; 18];
            client.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        0x03 => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len)?;
            out.extend_from_slice(&len);
            let mut tail = vec![0u8; len[0] as usize + 2];
            client.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        _ => {}
    }
    Ok(out)
}

fn read_socks4_request(client: &mut TcpStream, version: u8) -> io::Result<Vec<u8>> {
    let mut out = vec![version];
    let mut fixed = [0u8; 7];
    client.read_exact(&mut fixed)?;
    out.extend_from_slice(&fixed);

    read_until_nul(client, &mut out)?;
    let is_domain = out[4] == 0 && out[5] == 0 && out[6] == 0 && out[7] != 0;
    if is_domain {
        read_until_nul(client, &mut out)?;
    }
    Ok(out)
}

fn read_until_nul(client: &mut TcpStream, out: &mut Vec<u8>) -> io::Result<()> {
    loop {
        let mut byte = [0u8; 1];
        client.read_exact(&mut byte)?;
        out.push(byte[0]);
        if byte[0] == 0 {
            return Ok(());
        }
        if out.len() > 4096 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "request too large"));
        }
    }
}

fn read_http_connect_request(client: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut chunk = [0u8; 512];
    loop {
        let n = client.read(&mut chunk)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected eof during http connect request"));
        }
        out.extend_from_slice(&chunk[..n]);
        if out.windows(4).any(|window| window == b"\r\n\r\n") {
            return Ok(out);
        }
        if out.len() > 64 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "http connect request too large"));
        }
    }
}

pub(super) fn resolve_name(host: &str, _socket_type: SocketType, config: &RuntimeConfig) -> Option<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Some(SocketAddr::new(ip, 0));
    }
    if !config.resolve {
        return None;
    }
    (host, 0).to_socket_addrs().ok()?.find(|addr| config.ipv6 || addr.is_ipv4())
}

fn maybe_delay_connect(
    client: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    handshake: HandshakeKind,
) -> io::Result<DelayConnect> {
    if !state.config.delay_conn {
        return Ok(DelayConnect::Immediate);
    }
    let route = super::routing::select_route(state, target, None, None, true)?;
    let group = state
        .config
        .groups
        .get(route.group_index)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    if !group_requires_payload(group) {
        return Ok(DelayConnect::Immediate);
    }

    send_success_reply(client, handshake)?;
    let Some(payload) = read_blocking_first_request(client, state.config.buffer_size)? else {
        return Ok(DelayConnect::Closed);
    };

    let route = if route_matches_payload(&state.config, route.group_index, target, &payload, TransportProtocol::Tcp) {
        route
    } else {
        let cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
        let host = extract_host(&state.config, &payload);
        cache
            .select_next(
                &state.config,
                &route,
                target,
                Some(&payload),
                host.as_deref(),
                TransportProtocol::Tcp,
                0,
                true,
                None,
            )
            .ok_or_else(|| io::Error::new(io::ErrorKind::PermissionDenied, "no matching desync group"))?
    };

    Ok(DelayConnect::Delayed { route, payload })
}

fn send_success_reply(client: &mut TcpStream, handshake: HandshakeKind) -> io::Result<()> {
    match handshake {
        HandshakeKind::Socks4 => client.write_all(encode_socks4_reply(true).as_bytes()),
        HandshakeKind::Socks5 => {
            let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
            client.write_all(encode_socks5_reply(0, addr).as_bytes())
        }
        HandshakeKind::HttpConnect => client.write_all(encode_http_connect_reply(true).as_bytes()),
    }
}

/// Maximum time to wait for the first request in delay_conn mode.
/// Prevents a slow or malicious client from holding a thread indefinitely.
const DELAY_CONN_READ_TIMEOUT: Duration = Duration::from_secs(60);

fn read_blocking_first_request(client: &mut TcpStream, buffer_size: usize) -> io::Result<Option<Vec<u8>>> {
    let original_timeout = client.read_timeout()?;
    client.set_read_timeout(Some(DELAY_CONN_READ_TIMEOUT))?;
    let mut buffer = vec![0u8; buffer_size.max(16_384)];
    let result = match client.read(&mut buffer) {
        Ok(0) => Ok(None),
        Ok(n) => {
            buffer.truncate(n);
            Ok(Some(buffer))
        }
        Err(err) => Err(err),
    };
    client.set_read_timeout(original_timeout)?;
    result
}

fn read_shadowsocks_request(
    client: &mut TcpStream,
    first_byte: u8,
    config: &RuntimeConfig,
) -> io::Result<(SocketAddr, Vec<u8>)> {
    let mut request = vec![first_byte];
    let mut chunk = [0u8; 4096];
    loop {
        if let Some((target, header_len)) = parse_shadowsocks_target(&request, config) {
            return Ok((target, request[header_len..].to_vec()));
        }
        let n = client.read(&mut chunk)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected eof during shadowsocks request"));
        }
        request.extend_from_slice(&chunk[..n]);
        if request.len() > 64 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "shadowsocks request too large"));
        }
    }
}

fn parse_shadowsocks_target(packet: &[u8], config: &RuntimeConfig) -> Option<(SocketAddr, usize)> {
    let atyp = *packet.first()?;
    match atyp {
        S_ATP_I4 => {
            if packet.len() < 7 {
                return None;
            }
            let ip = Ipv4Addr::new(packet[1], packet[2], packet[3], packet[4]);
            let port = u16::from_be_bytes([packet[5], packet[6]]);
            Some((SocketAddr::new(IpAddr::V4(ip), port), 7))
        }
        S_ATP_I6 => {
            if packet.len() < 19 || !config.ipv6 {
                return None;
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&packet[1..17]);
            let port = u16::from_be_bytes([packet[17], packet[18]]);
            Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), 19))
        }
        0x03 => {
            let len = *packet.get(1)? as usize;
            if packet.len() < 2 + len + 2 || !config.resolve {
                return None;
            }
            let host = std::str::from_utf8(&packet[2..2 + len]).ok()?;
            let port = u16::from_be_bytes([packet[2 + len], packet[3 + len]]);
            let resolved = resolve_name(host, SocketType::Stream, config)?;
            Some((SocketAddr::new(resolved.ip(), port), 2 + len + 2))
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_config::RuntimeConfig;
    use ripdpi_session::{S_CMD_CONN, S_VER5};
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener, TcpStream};
    use std::time::Duration;

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    #[test]
    fn send_success_reply_emits_protocol_specific_payloads() {
        let cases = [
            (HandshakeKind::Socks4, encode_socks4_reply(true).as_bytes().to_vec()),
            (
                HandshakeKind::Socks5,
                encode_socks5_reply(0, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).as_bytes().to_vec(),
            ),
            (HandshakeKind::HttpConnect, encode_http_connect_reply(true).as_bytes().to_vec()),
        ];

        for (handshake, expected) in cases {
            let (mut writer, mut reader) = connected_pair();
            reader.set_read_timeout(Some(Duration::from_secs(1))).expect("set read timeout");

            send_success_reply(&mut writer, handshake).expect("send success reply");

            let mut actual = vec![0u8; expected.len()];
            reader.read_exact(&mut actual).expect("read success reply");
            assert_eq!(actual, expected);
        }
    }

    #[test]
    fn read_socks5_request_reads_domain_target() {
        let (mut reader, mut writer) = connected_pair();
        let request = [
            S_VER5, S_CMD_CONN, 0, 0x03, 11, b'e', b'x', b'a', b'm', b'p', b'l', b'e', b'.', b'c', b'o', b'm', 0x01,
            0xbb,
        ];
        writer.write_all(&request).expect("write socks5 request");

        assert_eq!(read_socks5_request(&mut reader).expect("read socks5 request"), request);
    }

    #[test]
    fn parse_shadowsocks_target_handles_ipv4_and_resolved_domain_targets() {
        let config = RuntimeConfig::default();
        let ipv4_packet = [S_ATP_I4, 127, 0, 0, 1, 0x01, 0xbb];
        let (ipv4_target, ipv4_header_len) =
            parse_shadowsocks_target(&ipv4_packet, &config).expect("parse ipv4 target");
        assert_eq!(ipv4_target, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443));
        assert_eq!(ipv4_header_len, ipv4_packet.len());

        let domain_packet = [0x03, 9, b'1', b'2', b'7', b'.', b'0', b'.', b'0', b'.', b'1', 0x00, 0x50];
        let (domain_target, domain_header_len) =
            parse_shadowsocks_target(&domain_packet, &config).expect("parse domain target");
        assert_eq!(domain_target, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 80));
        assert_eq!(domain_header_len, domain_packet.len());
    }

    #[test]
    fn parse_shadowsocks_target_respects_ipv6_and_resolve_flags() {
        let config = RuntimeConfig { ipv6: false, resolve: false, ..RuntimeConfig::default() };
        let ipv6_packet = [S_ATP_I6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 53];
        let domain_packet = [0x03, 9, b'1', b'2', b'7', b'.', b'0', b'.', b'0', b'.', b'1', 0, 80];

        assert!(parse_shadowsocks_target(&ipv6_packet, &config).is_none());
        assert!(parse_shadowsocks_target(&domain_packet, &config).is_none());
    }
}
